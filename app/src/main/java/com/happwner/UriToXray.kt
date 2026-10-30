package com.happwner

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Explicit link import. Original DNS/routing cannot be reconstructed from a share link. */
object UriToXray {
    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    private fun base64(value: String): String = String(Base64.getDecoder().decode(value.replace('-', '+').replace('_', '/')), Charsets.UTF_8)

    fun convert(link: String, index: Int): JSONObject {
        val uri = URI(link.trim())
        val scheme = uri.scheme?.lowercase() ?: error("Missing proxy protocol")
        val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        var name = uri.rawFragment?.let(::decode)?.takeIf { it.isNotBlank() } ?: "proxy-${index + 1}"
        val outbound: JSONObject
        if (scheme == "vmess") {
            val value = JSONObject(base64(link.substringAfter("://").substringBefore('#')))
            name = value.optString("ps", name).ifBlank { name }
            outbound = JSONObject().put("protocol", "vmess").put("settings", JSONObject().put("vnext", JSONArray().put(
                JSONObject().put("address", value.getString("add")).put("port", value.get("port").toString().toInt()).put("users", JSONArray().put(
                    JSONObject().put("id", value.getString("id")).put("alterId", value.optString("aid", "0").toInt())
                        .put("security", value.optString("scy", "auto"))
                )))))
            val p = mutableMapOf("type" to value.optString("net", "tcp"), "security" to if(value.optString("tls") == "tls") "tls" else "none")
            for (key in listOf("host", "path", "sni", "alpn", "fp")) if(value.has(key)) p[key] = value.optString(key)
            outbound.put("streamSettings", stream(p))
        } else {
            val host = uri.host?.removeSurrounding("[", "]") ?: error("Proxy link has no server address")
            val port = uri.port.takeIf { it in 1..65535 } ?: error("Proxy link has no valid server port")
            val credentials = decode(uri.rawUserInfo ?: error("Proxy link has no credentials"))
            outbound = when (scheme) {
                "vless" -> JSONObject().put("protocol", "vless").put("settings", JSONObject().put("vnext", JSONArray().put(
                    JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(
                        JSONObject().put("id", credentials).put("encryption", params["encryption"] ?: "none")
                            .apply { params["flow"]?.let { put("flow", it) } }
                    )))))
                "trojan" -> JSONObject().put("protocol", "trojan").put("settings", JSONObject().put("servers", JSONArray().put(
                    JSONObject().put("address", host).put("port", port).put("password", credentials)
                )))
                "ss" -> {
                    val user = if (credentials.contains(':')) credentials else base64(credentials)
                    require(user.contains(':')) { "Invalid Shadowsocks credentials" }
                    JSONObject().put("protocol", "shadowsocks").put("settings", JSONObject().put("servers", JSONArray().put(
                        JSONObject().put("address", host).put("port", port).put("method", user.substringBefore(':'))
                            .put("password", user.substringAfter(':'))
                    )))
                }
                else -> error("Unsupported link protocol: $scheme")
            }
            if (scheme != "ss") outbound.put("streamSettings", stream(if(scheme == "trojan" && !params.containsKey("security")) params + ("security" to "tls") else params))
        }
        outbound.put("tag", name)
        // Keep the source so provider-specific URI extensions remain recoverable.
        return JSONObject().put("remarks", name).put("happwner_source_uri", link)
            .put("outbounds", JSONArray().put(outbound))
    }

    private fun stream(p: Map<String, String>): JSONObject {
        val type = p["type"] ?: "tcp"
        val security = p["security"] ?: "none"
        val result = JSONObject().put("network", type).put("security", security)
        if (security == "reality" || security == "tls") {
            val tls = JSONObject()
            p["sni"]?.let { tls.put("serverName", it) }
            p["fp"]?.let { tls.put("fingerprint", it) }
            p["alpn"]?.let { tls.put("alpn", JSONArray(it.split(','))) }
            if (security == "reality") {
                p["pbk"]?.let { tls.put("publicKey", it) }
                p["sid"]?.let { tls.put("shortId", it) }
                p["spx"]?.let { tls.put("spiderX", it) }
            } else p["allowInsecure"]?.let { tls.put("allowInsecure", it == "1" || it.equals("true", true)) }
            result.put(if(security == "reality") "realitySettings" else "tlsSettings", tls)
        }
        when(type) {
            "tcp", "raw" -> p["headerType"]?.let { result.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", it))) }
            "ws" -> result.put("wsSettings", JSONObject().put("path", p["path"] ?: "/").apply {
                p["host"]?.let { put("headers", JSONObject().put("Host", it)) }
            })
            "grpc" -> result.put("grpcSettings", JSONObject().put("serviceName", p["serviceName"] ?: "")
                .put("multiMode", p["mode"] == "multi").apply { p["authority"]?.let { put("authority", it) } })
            "http", "h2" -> result.put("httpSettings", JSONObject().put("path", p["path"] ?: "/").apply {
                p["host"]?.let { put("host", JSONArray(it.split(','))) }
            })
            "httpupgrade", "xhttp", "splithttp" -> result.put("${type}Settings", JSONObject().put("path", p["path"] ?: "/").apply {
                p["host"]?.let { put("host", it) }; p["mode"]?.let { put("mode", it) }
            })
            else -> error("Unsupported link transport: $type")
        }
        return result
    }
}
