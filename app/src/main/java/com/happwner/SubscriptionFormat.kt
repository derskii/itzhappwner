package com.happwner

import android.content.SharedPreferences

enum class SubscriptionFormat { JSON, SING_BOX, URI;
    companion object {
        fun ensureDefaults(prefs: SharedPreferences) {
            if (prefs.getBoolean("json_default_v134", false)) return
            prefs.edit().putBoolean("json_default_v134", true)
                .putBoolean("process_manual", false).putBoolean("process_server", false)
                .putBoolean("process_xray_manual", false).putBoolean("process_xray_server", false)
                .putBoolean("process_b64_manual", true).putBoolean("process_b64_server", true).apply()
        }
        fun read(prefs: SharedPreferences, bridge: Boolean = false): SubscriptionFormat {
            val suffix = if (bridge) "server" else "manual"
            return when {
                prefs.getBoolean("process_xray_$suffix", false) -> SING_BOX
                prefs.getBoolean("process_$suffix", false) -> URI
                else -> JSON
            }
        }
        fun save(prefs: SharedPreferences, value: SubscriptionFormat, bridge: Boolean = false) {
            val suffix = if (bridge) "server" else "manual"
            prefs.edit().putBoolean("process_$suffix", value == URI)
                .putBoolean("process_xray_$suffix", value == SING_BOX).apply()
        }
    }
}
