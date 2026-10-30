package com.happwner

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class SubscriptionOutputTest {
    @get:Rule val temporary = TemporaryFolder()
    private val vless = "vless://00000000-0000-4000-8000-000000000001@node.example.invalid:443?encryption=none&security=tls&type=grpc&serviceName=a%2Fb%2Bc&sni=example.invalid&fp=chrome#Test"
    private fun process(file: File, format: SubscriptionFormat = SubscriptionFormat.JSON) =
        SubscriptionOutput.process(file, format, true, { temporary.newFile() })
    private fun digest(file: File): List<Byte> {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16384)
            while (true) { val n = input.read(buffer); if(n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().toList()
    }
    private fun config(index: Int, padding: String): String {
        val user = JSONObject().put("id", "00000000-0000-4000-8000-000000000001")
        val server = JSONObject().put("address", "node.example.invalid").put("port", 443).put("users", JSONArray().put(user))
        val outbound = JSONObject().put("protocol", "vless").put("tag", "proxy")
            .put("settings", JSONObject().put("vnext", JSONArray().put(server)))
        return JSONObject().put("remarks", "server-$index").put("provider_unknown", padding)
            .put("outbounds", JSONArray().put(outbound)).toString()
    }

    @Test fun jsonModeReturnsOriginalServerJsonByteForByteIncludingDnsAndRouting() {
        val file = temporary.newFile()
        val source = JSONObject(config(0, "custom")).put("dns", JSONObject().put("hosts", JSONObject().put("test.invalid", "127.0.0.1")))
            .put("routing", JSONObject().put("domainStrategy", "IPIfNonMatch")).toString(2)
        file.writeText(source)
        val result = process(file)
        assertFalse(result.fromLinks)
        assertEquals(source, result.file.readText())
        assertTrue(JSONObject(result.file.readText()).has("routing"))
    }

    @Test fun linksProduceRealXrayJsonAndRetainTlsAndGrpcSettings() {
        val file = temporary.newFile().apply { writeText(vless) }
        val result = process(file)
        assertTrue(result.fromLinks)
        val config = JSONArray(result.file.readText()).getJSONObject(0)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", outbound.getString("protocol"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("a/b+c", stream.getJSONObject("grpcSettings").getString("serviceName"))
        assertEquals("example.invalid", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals(vless, config.getString("happwner_source_uri"))
        val singBox = process(file, SubscriptionFormat.SING_BOX).file.readText()
        val singBoxConfig = JSONObject(singBox)
        val proxies = singBoxConfig.getJSONArray("outbounds")
        val proxy = (0 until proxies.length()).map { proxies.getJSONObject(it) }.first { it.optString("type") == "vless" }
        assertEquals("a/b+c", proxy.getJSONObject("transport").getString("service_name"))
        File("build/verification").apply { mkdirs() }.resolve("sing-box-grpc.json").writeText(singBox)
    }

    @Test fun fiveHundredBase64LinksProduceFiveHundredJsonConfigs() {
        val file = temporary.newFile()
        file.writeText(Base64.getMimeEncoder().encodeToString((0 until 500).joinToString("\n") { vless.replace("#Test", "#Test-$it") }.toByteArray()))
        assertEquals(500, JSONArray(process(file).file.readText()).length())
    }

    @Test fun bigBase64JsonIsDecodedOnDiskWithoutUriConversion() {
        val raw = temporary.newFile()
        val encoded = temporary.newFile()
        val padding = "a".repeat(136 * 1024)
        raw.bufferedWriter().use { writer ->
            writer.write("[")
            repeat(500) { if(it > 0) writer.write(","); writer.write(config(it, padding)) }
            writer.write("]")
        }
        assertTrue(raw.length() > 64L * 1024 * 1024)
        Base64.getEncoder().wrap(encoded.outputStream()).use { output -> raw.inputStream().use { it.copyTo(output) } }
        val result = process(encoded)
        assertEquals(raw.length(), result.file.length())
        assertEquals(digest(raw), digest(result.file))
        assertFalse(result.fromLinks)
    }

    @Test fun singBoxModeHandles500LargeSourceJsonObjectsAsJson() {
        val raw = temporary.newFile()
        val padding = "a".repeat(136 * 1024)
        raw.bufferedWriter().use { writer ->
            writer.write("[")
            repeat(500) { if(it > 0) writer.write(","); writer.write(config(it, padding)) }
            writer.write("]")
        }
        val result = process(raw, SubscriptionFormat.SING_BOX)
        val json = JSONObject(result.file.readText())
        val outbounds = json.getJSONArray("outbounds")
        assertEquals(500, (0 until outbounds.length()).count { outbounds.getJSONObject(it).optString("type") == "vless" })
        assertTrue(json.has("dns")); assertTrue(json.has("route"))
        File("build/verification").apply { mkdirs() }.resolve("sing-box-500.json").writeText(result.file.readText())
    }

    @Test fun realityLinkProducesXrayRealityFieldsAndIpv6Server() {
        val config = UriToXray.convert("vless://00000000-0000-4000-8000-000000000001@[2001:db8::1]:443?security=reality&type=tcp&flow=xtls-rprx-vision&pbk=public&sid=short&sni=test.invalid&fp=edge#Reality", 0)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        val server = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals("2001:db8::1", server.getString("address"))
        assertEquals("xtls-rprx-vision", server.getJSONArray("users").getJSONObject(0).getString("flow"))
        assertEquals("public", outbound.getJSONObject("streamSettings").getJSONObject("realitySettings").getString("publicKey"))
    }

    @Test fun vmessNumericPortTrojanAndShadowsocksRemainRealOutbounds() {
        val vmess = JSONObject().put("add", "test.invalid").put("port", 443)
            .put("id", "00000000-0000-4000-8000-000000000001").put("ps", "VMess name")
        val vmessConfig = UriToXray.convert("vmess://" + Base64.getEncoder().encodeToString(vmess.toString().toByteArray()), 0)
        assertEquals("VMess name", vmessConfig.getString("remarks"))
        val vmessServer = vmessConfig.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals(443, vmessServer.getInt("port"))
        val trojan = UriToXray.convert("trojan://pass%2Bword@test.invalid:443#Trojan", 0).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("pass+word", trojan.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("password"))
        assertEquals("tls", trojan.getJSONObject("streamSettings").getString("security"))
        val user = Base64.getEncoder().withoutPadding().encodeToString("aes-128-gcm:password".toByteArray())
        val shadowsocks = UriToXray.convert("ss://$user@test.invalid:443#SS", 0).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("aes-128-gcm", shadowsocks.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("method"))
    }
}
