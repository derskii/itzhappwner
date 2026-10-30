package com.happwner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket

class SubscriptionHttpTest {
    @get:Rule val temporary = TemporaryFolder()

    private class LocalServer(size: Long, body: Boolean = true) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/subscription"
        private val worker = Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().use { output ->
                        output.write("HTTP/1.1 200 OK\r\nContent-Length: $size\r\nConnection: close\r\n\r\n".toByteArray())
                        if (body) {
                            val buffer = ByteArray(8192) { (it % 127).toByte() }
                            var remaining = size
                            while (remaining > 0) {
                                val count = minOf(remaining, buffer.size.toLong()).toInt()
                                output.write(buffer, 0, count)
                                remaining -= count
                            }
                        }
                    }
                }
            } catch (_: java.io.IOException) { /* The client may reject a response before reading it. */ }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); worker.join(1000) }
    }

    @Test fun streamsBeyondInMemoryLimitAndPreservesAllBytes() = runBlocking {
        val size = SubscriptionFiles.MAX_PROCESS_BYTES + 12345
        LocalServer(size).use { server ->
            val target = temporary.newFile()
            val response = SubscriptionHttp.getToFile(server.url, RequestIdentity(), target)
            assertTrue(response.error, response.isSuccessful)
            assertEquals("", response.body)
            assertEquals(size, target.length())
            target.inputStream().use { input ->
                var index = 0L
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (i in 0 until count) assertEquals(((index++ % 8192) % 127).toByte(), buffer[i])
                }
            }
        }
    }

    @Test fun rejectsOversizedDownloadBeforeAllocatingBody() = runBlocking {
        LocalServer(SubscriptionFiles.MAX_DOWNLOAD_BYTES + 1, body = false).use { server ->
            val target = temporary.newFile()
            val response = SubscriptionHttp.getToFile(server.url, RequestIdentity(), target)
            assertFalse(response.isSuccessful)
            assertTrue(response.error.orEmpty().contains("256 MiB"))
            assertEquals(0L, target.length())
        }
    }
}
