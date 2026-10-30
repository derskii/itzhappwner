package com.happwner

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import java.io.File
import kotlinx.coroutines.sync.withLock
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class SubscriptionService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var isRunning = false
    private val clientSlots = Semaphore(MAX_CLIENTS)
    private val activeClients = ConcurrentHashMap.newKeySet<Socket>()

    companion object {
        private const val MAX_CLIENTS = 4
        private const val MAX_REQUEST_LINE_BYTES = 256 * 1024
        private const val REQUEST_LINE_TIMEOUT_MS = 30_000L
        private const val MAX_QUERY_CHARS = 240 * 1024
        private var serverSocket: ServerSocket? = null
    }

    // Apply the app language to the service context
    override fun attachBaseContext(newBase: Context) {
        val prefs = PrefsManager.getSafePrefs(newBase)
        val lang = prefs.getString("app_lang", "system") ?: "system"
        val config = newBase.resources.configuration

        val locale = if (lang == "system") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                android.content.res.Resources.getSystem().configuration.locales.get(0)
            } else {
                @Suppress("DEPRECATION")
                android.content.res.Resources.getSystem().configuration.locale
            }
        } else {
            java.util.Locale.forLanguageTag(lang)
        }

        java.util.Locale.setDefault(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Create the notification channel up front
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    // Start/stop the local HTTP bridge on 127.0.0.1:8166
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = PrefsManager.getSafePrefs(this)

        updateNotificationState()

        // "Disable" button from the notification
        if (intent?.action == "ACTION_DISCONNECT") {
            prefs.edit().putBoolean("bridge_enabled", false).apply()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            BridgeController.notifyChanged(this)
            return START_NOT_STICKY
        }

        val bridgeEnabled = prefs.getBoolean("bridge_enabled", false)

        // Bridge turned off: tear down the foreground service and stop
        if (!bridgeEnabled) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Bring the accept loop up if it isn't already running
        if (!isRunning) {
            startServer()
        }

        if (prefs.getBoolean("bridge_watchdog", false)) {
            WatchdogReceiver.scheduleNextWatchdog(this)
        }

        BridgeController.refreshSurfaces(this)
        return START_STICKY
    }

    // Persistent foreground-service notification (shown the whole time the service is alive)
    private fun updateNotificationState() {
        val hideIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, "bridge_channel")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val contentIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(this, 0, contentIntent, flags)

        val hidePendingIntent = PendingIntent.getActivity(this, 200, hideIntent, flags)

        val disconnectIntent = Intent(this, SubscriptionService::class.java).apply {
            action = "ACTION_DISCONNECT"
        }
        val disconnectPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 201, disconnectIntent, flags)
        } else {
            PendingIntent.getService(this, 201, disconnectIntent, flags)
        }

        // Build the ongoing notification with Hide / Disconnect actions
        val notification = NotificationCompat.Builder(this, "bridge_channel")
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_content))
            .setSmallIcon(R.drawable.ic_dns)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(R.drawable.ic_delete, getString(R.string.notif_action_hide), hidePendingIntent)
            .addAction(R.drawable.ic_settings, getString(R.string.notif_action_disconnect), disconnectPendingIntent)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(resources.getInteger(R.integer.id_fgs_notif), notification, fgsType)
        } else {
            startForeground(resources.getInteger(R.integer.id_fgs_notif), notification)
        }
    }

    // Open the socket and run the accept loop on an IO coroutine
    private fun startServer() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                synchronized(SubscriptionService::class.java) {
                    if (serverSocket == null || serverSocket!!.isClosed) {
                        serverSocket = ServerSocket().apply {
                            reuseAddress = true
                            bind(InetSocketAddress("127.0.0.1", 8166)) // fixed bridge port
                        }
                    }
                }

                Log.d("Happwner:Server", "Server started on port 8166")
                while (isRunning) {
                    val socket = try {
                        serverSocket?.accept()
                    } catch (e: Exception) {
                        if (isRunning) Log.e("Happwner:Server", "Accept error: ${e.message}")
                        null
                    } ?: break
                    if (!clientSlots.tryAcquire()) {
                        try {
                            sendError(socket.getOutputStream(), 503, "Bridge is busy")
                        } catch (_: Exception) {
                        } finally {
                            try { socket.close() } catch (_: Exception) {}
                        }
                        continue
                    }
                    activeClients.add(socket)
                    handleClient(socket)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("Happwner:Server", "Server failed (${e.javaClass.simpleName})")
                    PrefsManager.getSafePrefs(this@SubscriptionService).edit().putBoolean("bridge_enabled", false).apply()
                    Handler(Looper.getMainLooper()).post {
                        ServiceCompat.stopForeground(this@SubscriptionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        BridgeController.notifyChanged(this@SubscriptionService)
                        stopSelf()
                    }
                }
            } finally {
                isRunning = false
            }
        }
    }

    // Bridge request: pull the subscription, decrypt, convert, then return it
    private fun handleClient(socket: Socket) {
        scope.launch {
            var rawFile: File? = null
            var convertedFile: File? = null
            var plaintextFile: File? = null
            try {
                val line = readRequestLine(socket)
                val output = socket.getOutputStream()
                if (line == null) {
                    sendError(output, 400, "Invalid request")
                    return@launch
                }
                val parts = line.split(' ')
                if (parts.size != 3 || parts[0] != "GET" || parts[1].isEmpty() || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) {
                    sendError(output, 400, "Invalid request line")
                    return@launch
                }
                val path = parts[1]
                if (!path.startsWith('/') || path.contains('#')) {
                    sendError(output, 400, "Invalid request target")
                    return@launch
                }
                val query = if (path.contains('?')) path.substringAfter('?') else if (path.startsWith("/url=")) path.substring(1) else ""
                if (query.isEmpty() || query.length > MAX_QUERY_CHARS) {
                    sendError(output, if (query.isEmpty()) 400 else 414, "Invalid or oversized request")
                    return@launch
                }
                val params = try { parseParams(query) } catch (_: Exception) {
                    sendError(output, 400, "Invalid query")
                    return@launch
                }
                val expectedKey = PrefsManager.bridgeAccessToken(this@SubscriptionService)
                val suppliedKey = params["key"].orEmpty()
                if (!java.security.MessageDigest.isEqual(expectedKey.toByteArray(Charsets.US_ASCII), suppliedKey.toByteArray(Charsets.US_ASCII))) {
                    sendError(output, 401, "Bridge access key required")
                    return@launch
                }
                val targetUrl = params["url"]
                if (targetUrl.isNullOrBlank()) {
                    sendError(output, 400, "Missing URL")
                    return@launch
                }
                val ua = params["ua"] ?: ""
                val hwid = params["hwid"] ?: ""
                val identity = try {
                    params["identity"]?.let(IdentityCodec::decode) ?: params["device_profile"]?.let {
                        val legacy = org.json.JSONObject(it)
                        RequestIdentity(enabled=legacy.getBoolean("enabled"),os=legacy.getString("os"),
                            osVersion=legacy.getString("version"),model=legacy.getString("model"),userAgent=ua,hwid=hwid)
                    } ?: RequestIdentity(userAgent=ua,hwid=hwid)
                } catch (_: Exception) {
                    sendError(output, 400, "Invalid identity snapshot")
                    return@launch
                }
                val raw = File.createTempFile("bridge-", ".txt", cacheDir)
                rawFile = raw
                val response = SubscriptionHttp.getToFile(targetUrl, identity, raw)
                if (!response.isSuccessful) {
                    sendError(output, response.statusCode.coerceIn(400, 599), response.error ?: "Upstream request failed")
                    return@launch
                }
                val prefs = PrefsManager.getSafePrefs(this@SubscriptionService)
                val format = SubscriptionFormat.read(prefs, bridge = true)
                val tryB64 = prefs.getBoolean("process_b64_server", true)
                val finalFile = SubscriptionFiles.processingMutex.withLock {
                    var input = raw
                    if (response.headers.keys.any { it.equals("Encrypt-Tag", true) }) {
                        if (raw.length() > SubscriptionFiles.MAX_PROCESS_BYTES) {
                            sendError(output, 413, "Encrypted response exceeds the decryption limit")
                            return@launch
                        }
                        when (val r = HappCrypto.process(targetUrl, raw.readText(), response.headers)) {
                            is HappCrypto.Result.Success -> {
                                input = File.createTempFile("bridge-plaintext-", ".txt", cacheDir).also { plaintextFile = it }
                                input.writeText(r.plaintext)
                            }
                            is HappCrypto.Result.Failed -> throw java.io.IOException("Subscription decryption failed")
                            HappCrypto.Result.NotEncrypted -> Unit
                        }
                    }
                    val processingContext = coroutineContext
                    SubscriptionOutput.process(input, format, tryB64, {
                        File.createTempFile("bridge-converted-", ".txt", cacheDir).also { convertedFile = it }
                    }, { processingContext.ensureActive() }).file
                }
                sendResponse(output, response.statusCode, finalFile, response.headers)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Happwner:Server", "Error handling client (${e.javaClass.simpleName})")
                try { sendError(socket.getOutputStream(), 502, "Subscription processing failed; open Happwner to save the original response") }
                catch (_: Exception) {}
            } finally {
                rawFile?.delete()
                convertedFile?.delete()
                plaintextFile?.delete()
                activeClients.remove(socket)
                clientSlots.release()
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun readRequestLine(socket: Socket): String? {
        val input = socket.getInputStream()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(REQUEST_LINE_TIMEOUT_MS)
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= MAX_REQUEST_LINE_BYTES) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0L) throw java.net.SocketTimeoutException("Request line timeout")
            socket.soTimeout = TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val next = input.read()
            if (next < 0) break
            if (next == '\n'.code) break
            if (next != '\r'.code) {
                if (next !in 0x20..0x7e) throw java.io.IOException("Non-ASCII request line")
                bytes.write(next)
            }
        }
        if (bytes.size() > MAX_REQUEST_LINE_BYTES) throw java.io.IOException("Request line exceeds limit")
        return if (bytes.size() == 0) null else bytes.toString("US-ASCII")
    }

    // Parse a small, strictly URL-encoded parameter set.
    private fun parseParams(query: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val pairs = query.split("&")
        require(pairs.size <= 8)
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            require(idx > 0)
            val key = pair.substring(0, idx)
            require(key in setOf("url", "identity", "device_profile", "hwid", "ua", "key"))
            require(!map.containsKey(key))
            map[key] = java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }
        return map
    }

    // Forward only subscription-related headers to the client
    private fun sendResponse(output: OutputStream, status: Int, body: File, headers: Map<String, List<String>>) {
        val headerSb = StringBuilder()
        headerSb.append("HTTP/1.1 $status ${reasonPhrase(status)}\r\n")
        headerSb.append("Content-Type: text/plain; charset=utf-8\r\n")
        headerSb.append("Content-Length: ${body.length()}\r\n")
        headerSb.append("Access-Control-Allow-Origin: *\r\n")
        headerSb.append("Connection: close\r\n")

        for ((key, values) in headers) {
            val k = key.lowercase(Locale.US)
            if (k in setOf("subscription-userinfo", "content-disposition", "profile-update-interval", "profile-title") &&
                RequestIdentity.isValidHeaderName(key)) {
                val safeValues = values.filter { it.all { c -> c.code in 0x20..0x7e } }
                if (safeValues.isNotEmpty()) headerSb.append("$key: ${safeValues.joinToString(", ")}\r\n")
            }
        }

        headerSb.append("\r\n")
        output.write(headerSb.toString().toByteArray(Charsets.UTF_8))
        body.inputStream().use { it.copyTo(output, 16 * 1024) }
        output.flush()
    }

    // Minimal HTTP error response
    private fun sendError(output: OutputStream, code: Int, message: String) {
        val safe = message.filter { it.code in 0x20..0x7e }.take(512)
        val bytes = safe.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $code ${reasonPhrase(code)}\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            safe
        output.write(response.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun reasonPhrase(code: Int): String = when (code) {
        200 -> "OK"
        301 -> "Moved Permanently"
        302 -> "Found"
        303 -> "See Other"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        414 -> "URI Too Long"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Upstream Response"
    }

    // Toast a decryption failure on the main thread
    private fun showDecryptErrorToast(keyName: String, reason: String) {
        val appContext = applicationContext
        val text = appContext.getString(R.string.toast_decrypt_failed, keyName, reason)
        Handler(Looper.getMainLooper()).post {
            try {
                Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                Log.w("Happwner:Server", "Toast failed: ${e.message}")
            }
        }
    }

    // Low-importance channel for the persistent bridge notification
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "bridge_channel",
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    // Stop the loop, close the socket, cancel the coroutines
    override fun onDestroy() {
        isRunning = false
        BridgeController.refreshSurfaces(applicationContext)
        Log.d("Happwner:Server", "Server stopping...")
        synchronized(SubscriptionService::class.java) {
            try {
                serverSocket?.close()
                serverSocket = null
                Log.d("Happwner:Server", "Server socket closed")
            } catch (e: Exception) {}
        }
        activeClients.toList().forEach { try { it.close() } catch (_: Exception) {} }
        scope.cancel()
        super.onDestroy()
    }
}
