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

    @Test fun defaultIsJsonAndFormatChoiceIsTheSameForUiAndBridge() {
        val context = TestPreferencesContext()
        context.preferences.edit().putBoolean("process_manual", true).commit()
        val prefs = PrefsManager.getSafePrefs(context)
        assertEquals(SubscriptionFormat.JSON, SubscriptionFormat.read(prefs))
        assertEquals(SubscriptionFormat.JSON, SubscriptionFormat.read(prefs, true))
        for (bridge in listOf(false, true)) for (format in SubscriptionFormat.entries) {
            SubscriptionFormat.save(prefs, format, bridge)
            assertEquals(format, SubscriptionFormat.read(PrefsManager.getSafePrefs(context), bridge))
        }
    }
}
