package com.happwner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Shared bounded HTTP transport for direct fetches and the loopback bridge. */
object SubscriptionHttp {
    private const val MAX_BODY_BYTES = SubscriptionFiles.MAX_PROCESS_BYTES
    private const val MAX_REDIRECTS = 5
    private const val TIMEOUT_MS = 15_000
    private const val TOTAL_TIMEOUT_MS = 300_000L
    private val deadlineScheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "Happwner-SubscriptionDeadline").apply { isDaemon = true }
    }
    private val redirectCodes = setOf(301, 302, 303, 307, 308)

    data class Result(
        val statusCode: Int,
        val body: String = "",
        val headers: Map<String, List<String>> = emptyMap(),
        val error: String? = null
    ) {
        val isSuccessful: Boolean get() = statusCode in 200..299 && error == null
    }

    fun validateUrl(raw: String): URL {
        require(raw.length <= 8192) { "Subscription URL is too long" }
        val uri = java.net.URI(raw.trim())
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "Subscription URL must use HTTP or HTTPS"
        }
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null) { "Subscription URL is invalid" }
        require(uri.port == -1 || uri.port in 1..65535) { "Subscription URL has an invalid port" }
        return URL(uri.toASCIIString())
    }

    suspend fun get(rawUrl: String, identity: RequestIdentity): Result = request(rawUrl, identity, null)

    /** Stream the original bytes to a private file; no response-sized byte array or String. */
    suspend fun getToFile(rawUrl: String, identity: RequestIdentity, target: File): Result =
        request(rawUrl, identity, target)

    private suspend fun request(rawUrl: String, identity: RequestIdentity, target: File?): Result = withContext(Dispatchers.IO) {
        val invalidHeaders = identity.invalidHeaderFields()
        if (invalidHeaders.isNotEmpty()) {
            return@withContext Result(400, error = "Invalid request headers: ${invalidHeaders.joinToString()}")
        }

        var current = try {
            validateUrl(rawUrl)
        } catch (e: Exception) {
            return@withContext Result(400, error = e.message ?: "Invalid subscription URL")
        }
        var requestHeaders = identity.subscriptionHeaders()
        val deadlineAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TOTAL_TIMEOUT_MS)
        val activeConnection = AtomicReference<HttpURLConnection?>()
        val deadlineTask = deadlineScheduler.schedule(
            { activeConnection.getAndSet(null)?.disconnect() }, TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS
        )

        try {
            for (redirect in 0..MAX_REDIRECTS) {
                if (System.nanoTime() >= deadlineAt) return@withContext Result(504, error = "Upstream request timed out")
                var connection: HttpURLConnection? = null
                try {
                    val conn = current.openConnection() as? HttpURLConnection
                        ?: return@withContext Result(400, error = "Only HTTP and HTTPS are supported")
                    connection = conn
                    activeConnection.set(conn)
                    if (System.nanoTime() >= deadlineAt) return@withContext Result(504, error = "Upstream request timed out")
                    conn.instanceFollowRedirects = false
                    conn.requestMethod = "GET"
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.setRequestProperty("Accept", "application/json, text/plain;q=0.9, */*;q=0.1")
                    requestHeaders.forEach { (name, value) -> conn.setRequestProperty(name, value) }

                    val status = conn.responseCode
                    val headers = conn.headerFields.entries.mapNotNull { (key, values) ->
                        if (key == null || values == null) null else key to values.toList()
                    }.toMap()
                    if (status in redirectCodes) {
                        val location = conn.getHeaderField("Location")
                            ?: return@withContext Result(status, headers = headers, error = "Redirect without a location")
                        if (redirect == MAX_REDIRECTS) {
                            return@withContext Result(502, headers = headers, error = "Too many redirects")
                        }
                        val next = try { validateUrl(URL(current, location).toExternalForm()) }
                        catch (_: Exception) { return@withContext Result(502, headers = headers, error = "Invalid redirect target") }
                        if (!sameOrigin(current, next)) requestHeaders = emptyMap()
                        current = next
                        continue
                    }
                    if (status !in 200..299) {
                        return@withContext Result(status, headers = headers, error = "Upstream returned HTTP $status")
                    }
                    val limit = if (target == null) MAX_BODY_BYTES else SubscriptionFiles.MAX_DOWNLOAD_BYTES
                    val length = conn.getHeaderFieldLong("Content-Length", -1)
                    if (length > limit) throw java.io.IOException("Response size limit exceeded")
                    val context = coroutineContext
                    val body = if (target == null) readBodyCapped(conn.inputStream) else {
                        conn.inputStream.use { input ->
                            target.outputStream().use { output ->
                                val buffer = ByteArray(16 * 1024)
                                var total = 0L
                                while (true) {
                                    context.ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > limit) throw java.io.IOException("Response size limit exceeded")
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        ""
                    }
                    return@withContext Result(status, body, headers)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SocketTimeoutException) {
                    return@withContext Result(504, error = "Upstream request timed out")
                } catch (e: Exception) {
                    if (System.nanoTime() >= deadlineAt) return@withContext Result(504, error = "Upstream request timed out")
                    val reason = if (e is java.io.IOException && e.message?.contains("size limit", true) == true) {
                        if (target == null) "Response exceeds the processing limit; save it to a file in Happwner"
                        else "Upstream response exceeds the 256 MiB file limit"
                    } else "Upstream request failed (${e.javaClass.simpleName})"
                    return@withContext Result(502, error = reason)
                } finally {
                    if (connection != null) activeConnection.compareAndSet(connection, null)
                    connection?.disconnect()
                }
            }
            Result(502, error = "Too many redirects")
        } finally {
            deadlineTask.cancel(false)
            activeConnection.getAndSet(null)?.disconnect()
        }
    }

    private fun sameOrigin(a: URL, b: URL): Boolean =
        a.protocol.equals(b.protocol, true) && a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b)

    private fun effectivePort(url: URL): Int = when {
        url.port >= 0 -> url.port
        url.protocol.equals("https", true) -> 443
        else -> 80
    }

    private fun readBodyCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        input.use {
            while (true) {
                val count = it.read(chunk)
                if (count < 0) break
                total += count
                if (total > MAX_BODY_BYTES) throw java.io.IOException("Response size limit exceeded")
                out.write(chunk, 0, count)
            }
        }
        return out.toString("UTF-8")
    }
}
