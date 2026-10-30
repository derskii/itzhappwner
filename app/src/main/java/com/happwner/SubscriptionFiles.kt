package com.happwner

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Disk-backed payloads. Never put a subscription in an Intent, Bundle or clipboard transaction. */
object SubscriptionFiles {
    val processingMutex = kotlinx.coroutines.sync.Mutex()
    const val MAX_DOWNLOAD_BYTES = 256L * 1024 * 1024
    const val MAX_PROCESS_BYTES = 4L * 1024 * 1024
    const val MAX_CLIPBOARD_BYTES = 128L * 1024
    const val PREVIEW_CHARS = 15_000

    /** JSON-to-URI can process hundreds of large configs with memory bounded by one config. */
    fun convertJsonFile(input: File, output: File, xrayToSb: Boolean): Int {
        var skipped = 0
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            input.reader(Charsets.UTF_8).use { reader ->
                JsonSubscriptionStream.forEachObject(reader) { json ->
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                    val result = LinkConverter.convertWithStats(json, jsonToUri = true, tryBase64 = false, xrayToSb = xrayToSb)
                    if (result.text.isNotEmpty()) {
                        writer.write(result.text)
                        writer.newLine()
                    }
                    skipped += result.xraySkipped
                }
            }
        }
        return skipped
    }

    fun copyCapped(input: InputStream, output: OutputStream, limit: Long = MAX_DOWNLOAD_BYTES): Long {
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            if (total > limit) throw IOException("Response size limit exceeded ($limit bytes)")
            output.write(buffer, 0, count)
        }
    }

    fun preview(file: File): String = file.reader(Charsets.UTF_8).use { reader ->
        val buffer = CharArray(PREVIEW_CHARS)
        var count = 0
        while (count < buffer.size) {
            val n = reader.read(buffer, count, buffer.size - count)
            if (n < 0) break
            count += n
        }
        // Do not split a supplementary Unicode character at the preview boundary.
        if (count > 0 && Character.isHighSurrogate(buffer[count - 1])) count--
        String(buffer, 0, count)
    }
}
