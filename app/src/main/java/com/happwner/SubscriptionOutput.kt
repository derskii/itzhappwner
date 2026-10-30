package com.happwner

import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.util.Base64

/** Same optional transformations as the original app, with payloads held on disk. */
object SubscriptionOutput {
    data class Result(val file: File, val skipped: Int = 0)

    private fun head(file: File) = SubscriptionFiles.preview(file).trimStart().removePrefix("\uFEFF").trimStart()
    private fun isJson(text: String) = text.startsWith('{') || text.startsWith('[')
    private fun isPayload(text: String) = isJson(text) || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(text)

    // Probe only a bounded prefix. Ordinary text, errors and binary data are not Base64 subscriptions.
    private fun looksEncoded(text: String): Boolean {
        val compact = text.filterNot { it in " \t\r\n" }.take(4096)
        if (compact.length < 4) return false
        return try {
            val prefix = compact.take(compact.length / 4 * 4).replace('-', '+').replace('_', '/')
            isPayload(String(Base64.getDecoder().decode(prefix), Charsets.UTF_8).trimStart().removePrefix("\uFEFF").trimStart())
        } catch (_: IllegalArgumentException) { false }
    }

    private fun decode(raw: File, output: File, checkActive: () -> Unit) {
        val normalized = object : FilterInputStream(raw.inputStream().buffered(16 * 1024)) {
            override fun read(): Int {
                var next: Int
                do { next = super.read() } while (next == 9 || next == 10 || next == 13 || next == 32)
                return when(next) { '-'.code -> '+'.code; '_'.code -> '/'.code; else -> next }
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                var count = 0
                while (count < length) {
                    val value = read()
                    if (value < 0) break
                    buffer[offset + count++] = value.toByte()
                }
                return if (count == 0) -1 else count
            }
        }
        Base64.getDecoder().wrap(normalized).use { source ->
            output.outputStream().use { SubscriptionFiles.copyCapped(source, it, checkActive = checkActive) }
        }
    }

    /** Retain the original Base64 envelope when only Xray conversion was enabled. */
    private fun encodeLike(input: File, output: File, raw: File, checkActive: () -> Unit) {
        val sample = SubscriptionFiles.preview(raw)
        val tail = java.io.RandomAccessFile(raw, "r").use { file ->
            file.seek((file.length() - 8).coerceAtLeast(0))
            ByteArray((file.length() - file.filePointer).toInt()).also(file::readFully).toString(Charsets.US_ASCII)
        }
        val newline = if (sample.contains("\r\n")) "\r\n" else "\n"
        val wrap = sample.trimEnd().contains('\n')
        var encoder = if (sample.contains('-') || sample.contains('_')) Base64.getUrlEncoder() else Base64.getEncoder()
        if (!tail.trimEnd().endsWith('=')) encoder = encoder.withoutPadding()
        val destination = object : FilterOutputStream(output.outputStream().buffered()) {
            var column = 0
            override fun write(value: Int) {
                if (wrap && column == 76) { out.write(newline.toByteArray()); column = 0 }
                out.write(value); column++
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                for (i in offset until offset + length) write(buffer[i].toInt())
            }
            override fun close() {
                if (tail.endsWith('\n')) out.write(newline.toByteArray())
                super.close()
            }
        }
        encoder.wrap(destination).use { encoded ->
            input.inputStream().use { SubscriptionFiles.copyCapped(it, encoded, checkActive = checkActive) }
        }
    }

    fun process(raw: File, processing: SubscriptionProcessing, createOutput: () -> File,
                checkActive: () -> Unit = {}): Result {
        val temporary = mutableListOf<File>()
        var target: File? = null
        fun temp() = File.createTempFile("processing-", ".txt", raw.parentFile).also(temporary::add)
        try {
            checkActive()
            var input = raw
            val prefix = head(raw)
            val encoded = (processing.decodeBase64 || processing.xrayToSingBox) && looksEncoded(prefix)
            if (encoded) input = temp().also { decode(raw, it, checkActive) }
            var result = input
            var skipped = 0
            // Proxy links, including unknown protocols, are never converted to invented JSON.
            if (isJson(head(input)) && (processing.jsonToUri || processing.xrayToSingBox)) {
                result = temp()
                if (processing.jsonToUri) {
                    skipped = SubscriptionFiles.convertJsonFile(input, result, processing.xrayToSingBox, checkActive)
                } else {
                    val configs = mutableListOf<JSONObject>()
                    var total = 0L
                    input.reader().use { reader ->
                        JsonSubscriptionStream.forEachObject(reader, checkActive) { config ->
                            val converted = when (val r = SingBoxConverter.convert(config)) {
                                is SingBoxConverter.Result.Ok -> { skipped += r.skippedOutbounds; r.config }
                                SingBoxConverter.Result.NotXray -> JSONObject(config)
                                SingBoxConverter.Result.Unsupported -> { skipped++; null }
                            }
                            if (converted != null) {
                                total += converted.toString().length * 2L
                                if (total > SubscriptionFiles.MAX_PROCESS_BYTES) throw IOException("Converted configuration is too large; disable Xray to sing-box to retain the source")
                                configs += converted
                            }
                        }
                    }
                    checkActive()
                    val merged = SingBoxConverter.mergeUnified(configs)
                    if (merged != null) result.writeText(merged.toString(2)) else result = input
                }
            }
            // Nothing changed: keep the exact original bytes, including the Base64 envelope.
            if (result == raw || (encoded && !processing.decodeBase64 && result == input)) return Result(raw, skipped)
            val output = createOutput().also { target = it }
            if (encoded && !processing.decodeBase64) encodeLike(result, output, raw, checkActive)
            else result.inputStream().use { source ->
                output.outputStream().use { SubscriptionFiles.copyCapped(source, it, checkActive = checkActive) }
            }
            return Result(output, skipped)
        } catch (e: Exception) {
            target?.delete()
            throw e
        } finally { temporary.forEach { it.delete() } }
    }
}
