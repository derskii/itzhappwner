package com.happwner

import org.json.JSONObject
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.util.Base64

/** One file pipeline for the screen, file import, and HTTP bridge. */
object SubscriptionOutput {
    data class Result(val file: File, val fromLinks: Boolean = false, val skipped: Int = 0)

    private fun forEachLink(file: File, checkActive: () -> Unit, consume: (String) -> Unit) {
        file.bufferedReader().use { reader ->
            val line = StringBuilder()
            while (true) {
                val next = reader.read()
                if (next < 0 || next == '\n'.code) {
                    val text = line.toString().trim()
                    if (text.isNotEmpty() && !text.startsWith('#')) consume(text)
                    line.setLength(0)
                    checkActive()
                    if (next < 0) break
                } else {
                    if (line.length >= 256 * 1024) throw IOException("Proxy link exceeds the 256 KiB limit")
                    line.append(next.toChar())
                }
            }
        }
    }

    fun process(raw: File, format: SubscriptionFormat, decodeBase64: Boolean, createOutput: () -> File,
                checkActive: () -> Unit = {}): Result {
        var decoded: File? = null
        var target: File? = null
        try {
            var input = raw
            var head = SubscriptionFiles.preview(input).trimStart().removePrefix("\uFEFF").trimStart()
            if (head.isEmpty()) throw IOException("Empty subscription")
            if (!head.startsWith('{') && !head.startsWith('[') && !head.contains("://") && decodeBase64) {
                val temp = File.createTempFile("decoded-", ".txt", raw.parentFile)
                decoded = temp
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
                        return if(count == 0) -1 else count
                    }
                }
                Base64.getDecoder().wrap(normalized).use { source ->
                    temp.outputStream().use { destination ->
                        SubscriptionFiles.copyCapped(source, destination, checkActive = checkActive)
                    }
                }
                input = temp
                head = SubscriptionFiles.preview(input).trimStart().removePrefix("\uFEFF").trimStart()
            }
            val json = head.startsWith('{') || head.startsWith('[')
            if (json && format == SubscriptionFormat.JSON) {
                // Preserve every server field, including routing, DNS, and fields unknown to this app.
                if (input == raw) return Result(raw)
                val output = createOutput().also { target = it }
                input.inputStream().use { source -> output.outputStream().use { SubscriptionFiles.copyCapped(source, it, checkActive = checkActive) } }
                return Result(output)
            }
            if (!json && format == SubscriptionFormat.URI) {
                if (input == raw) return Result(raw)
                val output = createOutput().also { target = it }
                input.inputStream().use { source -> output.outputStream().use { SubscriptionFiles.copyCapped(source, it, checkActive = checkActive) } }
                return Result(output)
            }
            val output = createOutput().also { target = it }
            if (format == SubscriptionFormat.URI) {
                SubscriptionFiles.convertJsonFile(input, output, false, checkActive)
                return Result(output)
            }
            if (format == SubscriptionFormat.JSON) {
                output.bufferedWriter().use { writer ->
                    writer.write("[\n")
                    var count = 0
                    forEachLink(input, checkActive) { text ->
                        if (count > 0) writer.write(",\n")
                        writer.write(UriToXray.convert(text, count++).toString())
                    }
                    if (count == 0) throw IOException("No proxy links in subscription")
                    writer.write("\n]")
                }
                return Result(output, fromLinks = true)
            }
            val configs = mutableListOf<JSONObject>()
            var total = 0L
            var skipped = 0
            fun convert(config: String) {
                checkActive()
                val converted = when(val r = SingBoxConverter.convert(config)) {
                    is SingBoxConverter.Result.Ok -> { skipped += r.skippedOutbounds; r.config }
                    SingBoxConverter.Result.NotXray -> JSONObject(config).also {
                        require(it.has("outbounds")) { "Not a sing-box configuration" }
                    }
                    SingBoxConverter.Result.Unsupported -> throw IOException("Unsupported Xray configuration for sing-box")
                }
                total += converted.toString().length * 2L
                if (total > SubscriptionFiles.MAX_PROCESS_BYTES) throw IOException("Merged sing-box configuration is too large; choose JSON to preserve the full subscription")
                configs += converted
            }
            if (json) input.reader().use { JsonSubscriptionStream.forEachObject(it, checkActive, ::convert) }
            else {
                var count = 0
                forEachLink(input, checkActive) { text -> convert(UriToXray.convert(text, count++).toString()) }
            }
            checkActive()
            val merged = SingBoxConverter.mergeUnified(configs) ?: throw IOException("No supported sing-box configurations")
            output.writeText(merged.toString(2))
            return Result(output, fromLinks = !json, skipped = skipped)
        } catch (e: Exception) {
            target?.delete()
            throw e
        } finally { decoded?.delete() }
    }
}
