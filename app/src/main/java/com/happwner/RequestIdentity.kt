package com.happwner

import java.util.Locale

/**
 * A pure request profile shared by direct subscription fetches, the local bridge,
 * and the hooked-app adapter. Values are copied from preferences by Android code.
 */
data class RequestIdentity(
    val enabled: Boolean = false,
    val os: String = "",
    val osVersion: String = "",
    val model: String = "",
    val deviceName: String = "",
    val userAgent: String = "",
    val hwid: String = "",
    val sendDeviceHeaders: Boolean = true,
    val hwidHeader: String = "x-hwid",
    val extraHeaders: Map<String, String> = emptyMap(),
    val hosts: String = "",
    val spoofNativeHwid: Boolean = false,
    val client: String = "",
    val clientVersion: String = "",
    val profileId: String = ""
) {
    /** Device identity headers supported by the subscription request profile. */
    fun identityHeaders(): Map<String, String> = buildMap {
        if (!enabled || !sendDeviceHeaders) return@buildMap
        putIfValid("x-device-os", os)
        putIfValid("x-ver-os", osVersion)
        putIfValid("x-device-model", model)
    }

    /**
     * Headers used by explicit subscription fetches. Empty HWID is omitted by
     * default so hooked clients can retain their own request; legacy direct and
     * bridge fetches can opt in to the historical empty x-hwid header.
     */
    fun subscriptionHeaders(includeEmptyHwid: Boolean = false): Map<String, String> = buildMap {
        if ((includeEmptyHwid || hwid.isNotBlank()) && isValidHeaderValue(hwid) && isValidHeaderName(hwidHeader)) put(hwidHeader, hwid)

        if (userAgent.isNotBlank() && isValidHeaderValue(userAgent)) put("User-Agent", userAgent)
        putAll(identityHeaders())
        if (enabled) for ((name, value) in extraHeaders) {
            if (isValidHeaderName(name) && isValidHeaderValue(value)) {
                keys.firstOrNull { it.equals(name, true) }?.let { remove(it) }
                put(name, value)
            }
        }
    }

    /** Names of configured header values that must be rejected before sending. */
    fun invalidHeaderFields(): List<String> = buildList {
        if (hwid.isNotBlank() && !isValidHeaderValue(hwid)) add("x-hwid")
        if (!isValidHeaderName(hwidHeader)) add("HWID header")
        if (userAgent.isNotBlank() && !isValidHeaderValue(userAgent)) add("User-Agent")
        if (enabled && sendDeviceHeaders) {
            if (os.isNotBlank() && !isValidHeaderValue(os)) add("x-device-os")
            if (osVersion.isNotBlank() && !isValidHeaderValue(osVersion)) add("x-ver-os")
            if (model.isNotBlank() && !isValidHeaderValue(model)) add("x-device-model")
        }
        if (enabled) {
            if (extraHeaders.size > 32) add("Too many headers")
            val seen = mutableSetOf<String>()
            for ((name, value) in extraHeaders) {
                if (!isValidHeaderName(name) || !isValidHeaderValue(value) || !seen.add(name.lowercase(Locale.ROOT))) add(name)
            }
        }
    }

    private fun MutableMap<String, String>.putIfValid(name: String, value: String) {
        if (value.isNotBlank() && isValidHeaderValue(value)) put(name, value)
    }

    companion object {
        const val MAX_HEADER_VALUE_LENGTH = 4096

        fun isValidHeaderName(name: String): Boolean = name.isNotEmpty() && name.length <= 128 &&
            name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~" } &&
            name.lowercase(Locale.ROOT) !in setOf("host", "connection", "content-length", "transfer-encoding", "upgrade", "proxy-connection", "trailer", "te")

        /** Accept only printable ASCII safe for both Java URLConnection and OkHttp headers. */
        fun isValidHeaderValue(value: String): Boolean {
            if (value.length > MAX_HEADER_VALUE_LENGTH) return false
            return value.all { it.code in 0x20..0x7e }
        }

        /**
         * Match a hostname against a comma-separated exact-host allowlist.
         * Matching is case-insensitive per DNS rules; wildcards and suffix
         * matches are deliberately unsupported.
         */
        fun isHostAllowed(host: String?, configuredHosts: String?): Boolean {
            val candidate = normalizeHost(host) ?: return false
            val allowedHosts = configuredHosts.orEmpty()
                .split(',')
                .mapNotNull(::normalizeHost)
                .toSet()
            return candidate in allowedHosts
        }

        private fun normalizeHost(host: String?): String? {
            val value = host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (value.any { it.isWhitespace() || it.code < 0x21 || it.code == 0x7f }) return null
            if (value.contains('*') || value.contains('/') || value.contains('@')) return null
            return value.lowercase(Locale.ROOT)
        }
    }
}
