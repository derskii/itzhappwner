package com.happwner

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetSocketAddress

class SubscriptionHttpTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun streamsBeyondInMemoryLimitAndPreservesAllBytes() = runBlocking {
        val size = SubscriptionFiles.MAX_PROCESS_BYTES + 12345
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/subscription") { exchange ->
            exchange.sendResponseHeaders(200, size)
            exchange.responseBody.use { output ->
                val buffer = ByteArray(8192) { (it % 127).toByte() }
                var remaining = size
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size.toLong()).toInt()
                    output.write(buffer, 0, count)
                    remaining -= count
                }
            }
        }
        server.start()
        try {
            val target = temporary.newFile()
            val response = SubscriptionHttp.getToFile("http://127.0.0.1:${server.address.port}/subscription", RequestIdentity(), target)
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
        } finally { server.stop(0) }
    }

    @Test fun rejectsOversizedDownloadBeforeAllocatingBody() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/huge") { exchange ->
            exchange.sendResponseHeaders(200, SubscriptionFiles.MAX_DOWNLOAD_BYTES + 1)
            exchange.close()
        }
        server.start()
        try {
            val target = temporary.newFile()
            val response = SubscriptionHttp.getToFile("http://127.0.0.1:${server.address.port}/huge", RequestIdentity(), target)
            assertFalse(response.isSuccessful)
            assertTrue(response.error.orEmpty().contains("256 MiB"))
            assertEquals(0, target.length())
        } finally { server.stop(0) }
    }
}
