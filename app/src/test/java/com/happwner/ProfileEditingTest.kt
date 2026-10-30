package com.happwner

import org.junit.Assert.*
import org.junit.Test

class ProfileEditingTest {
    @Test fun editingBuiltInHwidKeepsOneProfileAndSurvivesSwitchingBack() {
        val context = TestPreferencesContext()
        val builtin = DeviceProfiles.presets.first()
        DeviceProfiles.apply(context, builtin)
        DeviceProfiles.updateHwid(context, "changed-hwid-1")
        DeviceProfiles.updateHwid(context, "changed-hwid-2")
        assertEquals(builtin.id, DeviceProfiles.active(context)?.id)
        assertEquals(1, DeviceProfiles.all(context).count { it.id == builtin.id })
        DeviceProfiles.apply(context, DeviceProfiles.presets.last())
        DeviceProfiles.apply(context, builtin)
        assertEquals("changed-hwid-2", DeviceProfiles.currentIdentity(context).subscriptionHeaders()["x-hwid"])
    }

    @Test fun editingCustomHwidDoesNotCreateAnotherRecordOrRequireRenaming() {
        val context = TestPreferencesContext()
        val profile = DeviceProfiles.save(context, DeviceProfiles.Preset(name = "Same name", hwid = "before", sendHwid = true))
        repeat(3) { DeviceProfiles.updateHwid(context, "updated-$it") }
        val active = DeviceProfiles.active(context)!!
        assertEquals(profile.id, active.id)
        assertEquals(profile.name, active.name)
        assertEquals("updated-2", DeviceProfiles.currentIdentity(context).hwid)
        assertEquals(1, DeviceProfiles.all(context).count { it.id == profile.id })
    }

    @Test fun duplicateNamesDoNotOverwriteOtherProfiles() {
        val context = TestPreferencesContext()
        val first = DeviceProfiles.save(context, DeviceProfiles.Preset(name = "Phone", hwid = "one", sendHwid = true))
        val second = DeviceProfiles.save(context, DeviceProfiles.Preset(name = "Phone", hwid = "two", sendHwid = true))
        assertNotEquals(first.id, second.id)
        DeviceProfiles.updateHwid(context, "changed-two")
        val saved = DeviceProfiles.all(context)
        assertEquals("one", saved.first { it.id == first.id }.hwid)
        assertEquals("changed-two", saved.first { it.id == second.id }.hwid)
    }

    @Test fun clearingHwidKeepsProfileButOmitsHeader() {
        val context = TestPreferencesContext()
        val profile = DeviceProfiles.save(context, DeviceProfiles.Preset(name = "Test", hwid = "one", sendHwid = true))
        DeviceProfiles.updateHwid(context, "")
        assertEquals(profile.id, DeviceProfiles.active(context)?.id)
        assertFalse(DeviceProfiles.currentIdentity(context).subscriptionHeaders().containsKey("x-hwid"))
    }

    @Test fun editingHwidAlsoRemovesAStaleExtraHeaderOverride() {
        val context = TestPreferencesContext()
        DeviceProfiles.save(context, DeviceProfiles.Preset(name = "Test", hwid = "one", sendHwid = true,
            headers = mapOf("X-Hwid" to "stale", "X-Test" to "keep")))
        DeviceProfiles.updateHwid(context, "new-hwid")
        val headers = DeviceProfiles.currentIdentity(context).subscriptionHeaders()
        assertEquals("new-hwid", headers["x-hwid"])
        assertEquals("keep", headers["X-Test"])
    }

    @Test fun openingPreferencesDoesNotResetExistingProcessingChoices() {
        val context = TestPreferencesContext()
        context.preferences.edit().putBoolean("process_manual", true)
            .putBoolean("process_xray_manual", true).putBoolean("process_b64_manual", false)
            .putBoolean("process_server", false).putBoolean("process_xray_server", false).commit()
        val prefs = PrefsManager.getSafePrefs(context)
        assertEquals(SubscriptionProcessing(true, false, true), SubscriptionProcessing.read(prefs))
        assertEquals(SubscriptionProcessing(), SubscriptionProcessing.read(prefs, bridge = true))
        assertFalse(prefs.contains("json_default_v134"))
    }

    @Test fun processingSnapshotIncludesBase64AndIndependentConversionFlags() {
        for (bits in 0..7) assertEquals(bits, SubscriptionProcessing.restore(bits).encode())
        assertNotEquals(SubscriptionProcessing(), SubscriptionProcessing(decodeBase64 = false))
    }
}
