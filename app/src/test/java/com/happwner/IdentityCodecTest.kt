package com.happwner

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.net.URLDecoder

class IdentityCodecTest {
    @Test fun fullSnapshotRoundTripsWithoutLosingPolicyOrMetadata() {
        val p=RequestIdentity(true,"Linux","24.04","PC","Personal","UA/1","abc",true,"X-Identity",
            mapOf("X-Device-Info" to "manual"),"one.example",false,"Client","1","custom_test")
        assertEquals(p,IdentityCodec.decode(IdentityCodec.encode(p)))
        assertEquals(p.subscriptionHeaders(),IdentityCodec.decode(IdentityCodec.encode(p)).subscriptionHeaders())
    }

    @Test fun bridgeEncodingPreservesLiteralPlusAndSnapshotAfterLaterSelection() {
        val url="https://example.invalid/sub?token=a+b&value=%20"
        val headers=mutableMapOf("X-Test" to "before")
        val p=RequestIdentity(enabled=true,userAgent="before",extraHeaders=headers,hosts="one.example")
        val token = "a".repeat(64)
        val link=IdentityCodec.bridgeUrl(url,p,token)
        headers["X-Test"]="after"
        val pairs=link.substringAfter('?').split('&').associate {
            val part=it.split('=',limit=2); part[0] to URLDecoder.decode(part[1],"UTF-8")
        }
        assertEquals(url,pairs["url"])
        val snapshot=IdentityCodec.decode(pairs.getValue("identity"))
        assertEquals("before",snapshot.extraHeaders["X-Test"])
        assertEquals("one.example",snapshot.hosts)
        assertEquals(token,pairs["key"])
    }

    @Test fun malformedUnsupportedAndUnsafePayloadsAreRejected() {
        assertTrue(runCatching { IdentityCodec.decode("{}") }.isFailure)
        val valid=IdentityCodec.encode(RequestIdentity(enabled=true))
        assertTrue(runCatching { IdentityCodec.decode(valid.replace("\"v\":2","\"v\":900")) }.isFailure)
        val injected=JSONObject(valid).put("headers", JSONObject().put("X-Test", "value\r\nInjected: 1")).toString()
        assertTrue(runCatching { IdentityCodec.decode(injected) }.isFailure)
        assertTrue(runCatching { IdentityCodec.decode("x".repeat(IdentityCodec.MAX_PAYLOAD+1)) }.isFailure)
    }

    @Test fun presetsOnlyClaimSourceVerifiedUserAgentsAndKeepHwidPoliciesExplicit() {
        for(p in DeviceProfiles.presets) {
            assertTrue(p.copy(hwid="local-test").identity().invalidHeaderFields().isEmpty())
            if(p.evidence=="compatibility") assertEquals("",p.userAgent)
            if(p.evidence=="source") { assertTrue(p.userAgent.isNotEmpty()); assertFalse(p.sendHwid); assertFalse(p.deviceHeaders) }
        }
        assertTrue(DeviceProfiles.generateHwid("uuid").matches(Regex("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        assertTrue(DeviceProfiles.generateHwid("hex32").matches(Regex("[0-9a-f]{32}")))
        assertTrue(DeviceProfiles.generateHwid("hex16").matches(Regex("[0-9a-f]{16}")))
    }
}
