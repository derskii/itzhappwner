package com.happwner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestIdentityTest {
    @Test
    fun disabledProfileAddsNoDeviceIdentityHeaders() {
        val identity = RequestIdentity(
            os = "Android",
            osVersion = "15",
            model = "Pixel",
            deviceName = "phone"
        )

        assertTrue(identity.identityHeaders().isEmpty())
        assertFalse(identity.subscriptionHeaders().keys.any { it.startsWith("x-device-") || it == "x-ver-os" })
    }

    @Test
    fun enabledProfileUsesOptionalVerifiedIdentityHeadersAndOmitsDeviceName() {
        val identity = RequestIdentity(
            enabled = true,
            os = "Android",
            osVersion = "15",
            model = "Pixel 9",
            deviceName = "Personal phone"
        )

        assertEquals(
            linkedMapOf(
                "x-device-os" to "Android",
                "x-ver-os" to "15",
                "x-device-model" to "Pixel 9"
            ),
            identity.identityHeaders()
        )
    }

    @Test
    fun blankIdentityValuesAreOmitted() {
        val identity = RequestIdentity(enabled = true, os = "Android", osVersion = " ", model = "")

        assertEquals(mapOf("x-device-os" to "Android"), identity.identityHeaders())
    }

    @Test
    fun legacySubscriptionHeadersPreserveValuesAndCanIncludeEmptyHwidExplicitly() {
        val ua = " Custom Client/1.0 (Android) "
        val identity = RequestIdentity(userAgent = ua, hwid = "abc-123")

        assertEquals(
            linkedMapOf("x-hwid" to "abc-123", "User-Agent" to ua),
            identity.subscriptionHeaders()
        )
        assertEquals(
            mapOf("x-hwid" to ""),
            RequestIdentity().subscriptionHeaders(includeEmptyHwid = true)
        )
        assertTrue(RequestIdentity().subscriptionHeaders().isEmpty())
    }

    @Test
    fun invalidValuesAreReportedAndNeverReturnedAsHeaders() {
        val identity = RequestIdentity(
            enabled = true,
            os = "Android\r\nInjected: yes",
            osVersion = "15",
            model = "Pixel\n9",
            userAgent = "UA\r\nBad: header",
            hwid = ""
        )

        assertEquals(listOf("User-Agent", "x-device-os", "x-device-model"), identity.invalidHeaderFields())
        assertEquals(mapOf("x-ver-os" to "15"), identity.identityHeaders())
        assertTrue("User-Agent" !in identity.subscriptionHeaders())
    }

    @Test
    fun headerValueValidationRejectsControlsUnpairedSurrogatesAndOversizeValues() {
        assertTrue(RequestIdentity.isValidHeaderValue("Pixel 9 phone"))
        assertFalse(RequestIdentity.isValidHeaderValue("hello\tworld"))
        assertFalse(RequestIdentity.isValidHeaderValue("hello\u0000world"))
        assertFalse(RequestIdentity.isValidHeaderValue("hello\u0085world"))
        assertFalse(RequestIdentity.isValidHeaderValue("emoji \uD83D\uDE00"))
        assertFalse(RequestIdentity.isValidHeaderValue("cyrillic \u0410"))
        assertFalse(RequestIdentity.isValidHeaderValue("x".repeat(RequestIdentity.MAX_HEADER_VALUE_LENGTH + 1)))
    }

    @Test
    fun hostAllowlistMatchesExactHostnamesCaseInsensitively() {
        val allowlist = " example.com,API.example.net "

        assertTrue(RequestIdentity.isHostAllowed("EXAMPLE.COM", allowlist))
        assertTrue(RequestIdentity.isHostAllowed("api.example.net", allowlist))
        assertFalse(RequestIdentity.isHostAllowed("sub.example.com", allowlist))
        assertFalse(RequestIdentity.isHostAllowed("example.com.evil", allowlist))
        assertFalse(RequestIdentity.isHostAllowed("example.com", "*.example.com"))
        assertFalse(RequestIdentity.isHostAllowed("example.com", ""))
    }
    @Test
    fun metadataOnlyProfileDoesNotInventDeviceHeaders() {
        val p=RequestIdentity(enabled=true,os="Windows",osVersion="11",model="PC",userAgent="clash-verge/v2.5.8",sendDeviceHeaders=false)
        assertEquals(mapOf("User-Agent" to "clash-verge/v2.5.8"),p.subscriptionHeaders())
    }

    @Test
    fun customHeaderNamesAreCaseInsensitiveOverrides() {
        val p=RequestIdentity(enabled=true,userAgent="old",extraHeaders=mapOf("user-agent" to "new", "X-Device-Info" to "custom"))
        assertEquals(mapOf("user-agent" to "new", "X-Device-Info" to "custom"),p.subscriptionHeaders())
        assertTrue(p.invalidHeaderFields().isEmpty())
    }

    @Test
    fun rejectsTransportHeadersAndCaseDuplicateNames() {
        val p=RequestIdentity(enabled=true,extraHeaders=linkedMapOf("Host" to "evil", "X-Test" to "a", "x-test" to "b", "bad:name" to "v"))
        assertTrue(p.invalidHeaderFields().containsAll(listOf("Host","x-test","bad:name")))
        assertFalse(p.subscriptionHeaders().containsKey("Host"))
        assertFalse(RequestIdentity.isValidHeaderName("Content-Length"))
        assertFalse(RequestIdentity.isValidHeaderName("X\r\nBad"))
    }

    @Test
    fun snapshotsCarryTheirOwnHostPolicy() {
        val a=RequestIdentity(enabled=true,hosts="one.example",userAgent="one")
        val b=a.copy(hosts="two.example",userAgent="two")
        assertTrue(RequestIdentity.isHostAllowed("one.example",a.hosts))
        assertFalse(RequestIdentity.isHostAllowed("one.example",b.hosts))
        assertEquals("one",a.subscriptionHeaders()["User-Agent"])
    }

    @Test
    fun hwidHeaderCanBeConfiguredAndEmptyValueIsOmitted() {
        assertEquals(mapOf("X-Custom-Id" to "id"),RequestIdentity(hwid="id",hwidHeader="X-Custom-Id").subscriptionHeaders())
        assertTrue(RequestIdentity(hwid="").subscriptionHeaders().isEmpty())
        assertFalse(RequestIdentity(hwid="id",hwidHeader="Connection").invalidHeaderFields().isEmpty())
    }
}
