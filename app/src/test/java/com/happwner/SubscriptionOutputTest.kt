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
    private fun process(file: File, processing: SubscriptionProcessing = SubscriptionProcessing()) =
        SubscriptionOutput.process(file, processing, { temporary.newFile() })
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
        assertEquals(source, result.file.readText())
        assertTrue(JSONObject(result.file.readText()).has("routing"))
    }

    private val mixedLinks = listOf(vless,
        "hysteria2://test-password@hy.example.invalid:443?sni=hy.example.invalid#HY2",
        "tuic://test-id:test-password@tuic.example.invalid:443#TUIC",
        "future-protocol://opaque-data@example.invalid:443#Unknown"
    ).joinToString("\r\n", postfix = "\r\n")

    @Test fun screenshotRegressionMixedBase64LinksAreDecodedWithoutInventingConfigs() {
        val raw = temporary.newFile().apply { writeText(Base64.getMimeEncoder().encodeToString(mixedLinks.toByteArray())) }
        assertEquals(mixedLinks, process(raw).file.readText())
        assertEquals(4, process(raw).file.readLines().size)
    }

    @Test fun everyOriginalSwitchCombinationPreservesAllLinkProtocols() {
        for (bits in 0..7) {
            val options = SubscriptionProcessing.restore(bits)
            val plain = temporary.newFile().apply { writeText(mixedLinks) }
            assertEquals(mixedLinks, process(plain, options).file.readText())
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(mixedLinks.toByteArray())
            val raw = temporary.newFile().apply { writeText(encoded) }
            assertEquals(if (options.decodeBase64) mixedLinks else encoded, process(raw, options).file.readText())
        }
    }

    @Test fun fiveHundredBase64LinksKeepEveryLineAndParameter() {
        val links = (0 until 500).joinToString("\n") { mixedLinks.replace("#HY2", "#HY2-$it") }
        val raw = temporary.newFile().apply { writeText(Base64.getEncoder().encodeToString(links.toByteArray())) }
        assertEquals(links, process(raw).file.readText())
    }

    @Test fun ordinaryTextAndUnrecognizedBase64ArePassedThrough() {
        for (source in listOf("", "Access denied by provider", "Error: subscription expired", "aGVsbG8=", "////")) {
            val raw = temporary.newFile().apply { writeText(source) }
            assertEquals(source, process(raw).file.readText())
        }
    }

    @Test fun base64CheckboxIsRespectedForJsonAsWellAsLinks() {
        val json = config(1, "original-fields")
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        val raw = temporary.newFile().apply { writeText(encoded) }
        assertEquals(encoded, process(raw, SubscriptionProcessing(decodeBase64 = false)).file.readText())
        assertEquals(json, process(raw).file.readText())
    }

    @Test fun optionalJsonConversionsMatchOriginalConverterAndCanBeCombined() {
        val source = config(1, "original-fields")
        val raw = temporary.newFile().apply { writeText(source) }
        for (jsonToUri in listOf(false, true)) for (xray in listOf(false, true)) {
            val options = SubscriptionProcessing(jsonToUri, false, xray)
            val expected = LinkConverter.convert(source, jsonToUri, false, xray)
            val actual = process(raw, options).file.readText()
            if (jsonToUri) assertEquals(expected.trim(), actual.trim())
            else if (xray) assertTrue(JSONObject(expected).similar(JSONObject(actual)))
            else assertEquals(source, actual)
        }
    }

    @Test fun xrayConversionWithBase64DisabledKeepsBase64Envelope() {
        val raw = temporary.newFile().apply { writeText(Base64.getMimeEncoder().encodeToString(config(0, "a").toByteArray()) + "\r\n") }
        val options = SubscriptionProcessing(decodeBase64 = false, xrayToSingBox = true)
        val result = process(raw, options).file.readText()
        assertTrue(result.endsWith("\r\n"))
        val decoded = String(Base64.getMimeDecoder().decode(result), Charsets.UTF_8)
        assertTrue(JSONObject(decoded).has("route"))
    }

    @Test fun cancelledDecodingCleansTemporaryFilesAndKeepsSource() {
        val raw = temporary.newFile().apply { writeText(Base64.getEncoder().encodeToString(mixedLinks.toByteArray())) }
        val before = temporary.root.list()!!.toSet()
        var checks = 0
        try {
            SubscriptionOutput.process(raw, SubscriptionProcessing(), { temporary.newFile() }) {
                if (++checks == 2) throw java.io.InterruptedIOException("cancelled")
            }
            fail("Expected cancellation")
        } catch (_: java.io.InterruptedIOException) {}
        assertEquals(before, temporary.root.list()!!.toSet())
        assertTrue(raw.isFile)
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
        assertEquals(raw, process(raw).file)
        val result = process(encoded)
        assertEquals(raw.length(), result.file.length())
        assertEquals(digest(raw), digest(result.file))
    }

    @Test fun singBoxModeHandles500LargeSourceJsonObjectsAsJson() {
        val raw = temporary.newFile()
        val padding = "a".repeat(136 * 1024)
        raw.bufferedWriter().use { writer ->
            writer.write("[")
            repeat(500) { if(it > 0) writer.write(","); writer.write(config(it, padding)) }
            writer.write("]")
        }
        val result = process(raw, SubscriptionProcessing(xrayToSingBox = true))
        val json = JSONObject(result.file.readText())
        val outbounds = json.getJSONArray("outbounds")
        assertEquals(500, (0 until outbounds.length()).count { outbounds.getJSONObject(it).optString("type") == "vless" })
        assertTrue(json.has("dns")); assertTrue(json.has("route"))
        File("build/verification").apply { mkdirs() }.resolve("sing-box-500.json").writeText(result.file.readText())
    }

}
