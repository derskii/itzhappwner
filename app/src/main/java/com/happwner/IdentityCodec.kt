package com.happwner

import org.json.JSONObject
import java.net.URLEncoder

/** One versioned, immutable payload for provider, broadcasts and bridge links. */
object IdentityCodec {
    const val KEY = "request_identity_v2"
    const val MAX_PAYLOAD = 32768

    fun encode(p: RequestIdentity): String {
        require(p.invalidHeaderFields().isEmpty()) { "Invalid request identity" }
        return JSONObject().put("v", 2)
        .put("enabled", p.enabled).put("os", p.os).put("osVersion", p.osVersion)
        .put("model", p.model).put("name", p.deviceName).put("ua", p.userAgent)
        .put("hwid", p.hwid).put("deviceHeaders", p.sendDeviceHeaders)
        .put("hwidHeader", p.hwidHeader).put("headers", JSONObject(p.extraHeaders))
        .put("hosts", p.hosts).put("nativeHwid", p.spoofNativeHwid)
        .put("client",p.client).put("clientVersion",p.clientVersion).put("profileId",p.profileId)
        .toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD) }
    }

    fun decode(raw: String): RequestIdentity {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD) { "Profile too large" }
        val p = JSONObject(raw)
        require(p.getInt("v") == 2) { "Unsupported profile version" }
        val identity = RequestIdentity(p.getBoolean("enabled"), p.getString("os"),
            p.getString("osVersion"), p.getString("model"), p.getString("name"),
            p.getString("ua"), p.getString("hwid"), p.getBoolean("deviceHeaders"),
            p.getString("hwidHeader"), headers(p.getJSONObject("headers")), p.getString("hosts"), p.getBoolean("nativeHwid"),
            p.getString("client"),p.getString("clientVersion"),p.getString("profileId"))
        require(identity.invalidHeaderFields().isEmpty()) { "Invalid profile headers" }
        return identity
    }

    fun headers(json: JSONObject): Map<String, String> {
        require(json.length() <= 32) { "Too many headers" }
        return buildMap {
            val names = json.keys()
            while (names.hasNext()) {
                val name = names.next()
                val value = json.get(name)
                require(value is String) { "Header values must be strings" }
                put(name, value)
            }
        }
    }

    fun bridgeUrl(url: String, identity: RequestIdentity, accessToken: String): String {
        require(identity.invalidHeaderFields().isEmpty())
        require(accessToken.matches(Regex("[0-9a-f]{64}"))) { "Invalid bridge access token" }
        val target = java.net.URI(url.trim())
        require((target.scheme.equals("http", true) || target.scheme.equals("https", true)) &&
            !target.host.isNullOrBlank() && target.rawUserInfo == null &&
            (target.port == -1 || target.port in 1..65535) && url.length <= 8192) {
            "Subscription URL must be a valid HTTP or HTTPS URL"
        }
        return "http://127.0.0.1:8166/?url=" + URLEncoder.encode(url, "UTF-8") +
            "&identity=" + URLEncoder.encode(encode(identity), "UTF-8") +
            "&key=" + URLEncoder.encode(accessToken, "UTF-8")
    }
}
