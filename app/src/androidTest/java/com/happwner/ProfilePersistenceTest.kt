package com.happwner

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Uses the TEST application's private preferences, never the user's target-app settings. */
class ProfilePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    @Before fun prepare() { PrefsManager.getSafePrefs(context).edit().clear().commit() }
    @After fun clean() { PrefsManager.getSafePrefs(context).edit().clear().commit() }

    @Test fun renameUpdatesByIdAndDuplicateNamesKeepSeparateRecords() {
        val a=DeviceProfiles.save(context,DeviceProfiles.Preset(name="A"))
        val b=DeviceProfiles.save(context,DeviceProfiles.Preset(name="B"))
        val renamed=DeviceProfiles.save(context,a.copy(name="C"))
        assertEquals(a.id,renamed.id)
        DeviceProfiles.save(context,renamed.copy(name="B"))
        val saved=DeviceProfiles.all(context).filter { it.id.startsWith("custom_") }
        assertEquals(setOf(a.id,b.id),saved.map { it.id }.toSet())
        assertEquals("B",saved.first { it.id==b.id }.name)
    }

    @Test fun builtInHwidSurvivesSwitchesAndRepeatedRequests() {
        val p=DeviceProfiles.presets.first()
        DeviceProfiles.apply(context,p)
        val first=DeviceProfiles.currentIdentity(context).hwid
        DeviceProfiles.apply(context,DeviceProfiles.presets.last())
        DeviceProfiles.apply(context,p)
        assertEquals(first,DeviceProfiles.currentIdentity(context).hwid)
        assertTrue(first.isNotBlank())
    }

    @Test fun migrationKeepsLegacyIdsAndHwid() {
        val p=JSONObject().put("id","custom_old").put("name","Legacy").put("os","Android")
            .put("version","16").put("model","old").put("ua","old-ua").put("hwid","old-hwid")
        PrefsManager.getSafePrefs(context).edit().putString("saved_device_profiles_v1",JSONArray().put(p).toString())
            .putBoolean("subscription_device_enabled",true).putString("active_device_profile_id","custom_old")
            .putString("custom_hwid","old-hwid").putString("custom_user_agent","old-ua")
            .putBoolean("use_custom_hwid_input",true).commit()
        assertEquals("custom_old",DeviceProfiles.active(context)?.id)
        assertEquals("old-hwid",DeviceProfiles.currentIdentity(context).hwid)
        assertEquals("old-hwid",DeviceProfiles.all(context).first { it.id=="custom_old" }.hwid)
    }

    @Test fun disablingOrDeletingActiveProfileKeepsCapturedIdAndOtherProfiles() {
        PrefsManager.getSafePrefs(context).edit().putString("captured_id","real-device").putString("custom_user_agent","before").commit()
        val a=DeviceProfiles.save(context,DeviceProfiles.Preset(name="A",userAgent="profile"))
        DeviceProfiles.save(context,DeviceProfiles.Preset(name="B"))
        DeviceProfiles.apply(context,a)
        DeviceProfiles.delete(context,a.id)
        assertNull(DeviceProfiles.active(context))
        assertEquals("real-device",PrefsManager.getSafePrefs(context).getString("captured_id",null))
        assertEquals("before",DeviceProfiles.currentIdentity(context).userAgent)
        assertTrue(DeviceProfiles.all(context).any { it.name=="B" })
    }

    @Test fun bridgeSnapshotRetainsEveryFieldAfterProfileSwitch() {
        val a=DeviceProfiles.save(context,DeviceProfiles.Preset(name="A",os="Linux",version="24.04",client="C",clientVersion="1",userAgent="agent-a",hwid="hwid-a",sendHwid=true,nativeHwid=false,deviceHeaders=true,headers=mapOf("X-Device-Info" to "info"),hosts="one.example"))
        val before=DeviceProfiles.currentIdentity(context)
        val bridge=IdentityCodec.bridgeUrl("https://example.invalid/sub?x=a+b",before,"a".repeat(64))
        DeviceProfiles.save(context,a.copy(name="B",userAgent="agent-b",hwid="hwid-b",hosts="two.example"))
        val query = bridge.substringAfter('?').split('&').associate { part ->
            val pair = part.split('=', limit=2)
            pair[0] to java.net.URLDecoder.decode(pair[1], "UTF-8")
        }
        val decoded=IdentityCodec.decode(query.getValue("identity"))
        assertEquals(before,decoded)
        assertEquals(before.subscriptionHeaders(),decoded.subscriptionHeaders())
        assertFalse(decoded.spoofNativeHwid)
        assertEquals("agent-b",DeviceProfiles.currentIdentity(context).userAgent)
    }

    @Test fun malformedSnapshotsDoNotFallBackToCurrentSettings() {
        assertTrue(runCatching { IdentityCodec.decode("{}") }.isFailure)
        val good=IdentityCodec.encode(RequestIdentity(enabled=true))
        assertTrue(runCatching { IdentityCodec.decode(good.replace("\"v\":2","\"v\":999")) }.isFailure)
        assertTrue(runCatching { IdentityCodec.decode("x".repeat(IdentityCodec.MAX_PAYLOAD+1)) }.isFailure)
    }
}
