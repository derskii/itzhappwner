package com.happwner

import android.content.SharedPreferences

/** Independent original processing switches, captured together for one request. */
data class SubscriptionProcessing(
    val jsonToUri: Boolean = false,
    val decodeBase64: Boolean = true,
    val xrayToSingBox: Boolean = false
) {
    fun encode(): Int = (if (jsonToUri) 1 else 0) or (if (decodeBase64) 2 else 0) or (if (xrayToSingBox) 4 else 0)

    companion object {
        fun restore(bits: Int) = SubscriptionProcessing(bits and 1 != 0, bits and 2 != 0, bits and 4 != 0)
        fun read(prefs: SharedPreferences, bridge: Boolean = false): SubscriptionProcessing {
            val suffix = if (bridge) "server" else "manual"
            return SubscriptionProcessing(
                prefs.getBoolean("process_$suffix", false),
                prefs.getBoolean("process_b64_$suffix", true),
                prefs.getBoolean("process_xray_$suffix", false)
            )
        }
    }
}
