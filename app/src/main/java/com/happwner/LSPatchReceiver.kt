package com.happwner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONObject

class LSPatchReceiver : BroadcastReceiver() {
    // Handle MODULE_LOADED: record the patched app and maybe enable LSPatch mode
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("Happwner:LSP", "Receiver triggered with action: $action")

        if (action == "${context.packageName}.MODULE_LOADED") {
            val pkg = intent.getStringExtra("pkg")
            Log.d("Happwner:LSP", "MODULE_LOADED from package: $pkg")

            if (pkg == null) {
                Log.w("Happwner:LSP", "Package name is null, aborting")
                return
            }
            if (!pkg.matches(Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$"))) {
                Log.w("Happwner:LSP", "Invalid package name; ignoring MODULE_LOADED")
                return
            }
            if (pkg == context.packageName) {
                Log.d("Happwner:LSP", "Self-load detected, ignoring")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val senderUid = intent.sentFromUid
                val senderPackages = try { context.packageManager.getPackagesForUid(senderUid)?.toSet().orEmpty() }
                    catch (_: Throwable) { emptySet() }
                if (pkg !in senderPackages) {
                    Log.w("Happwner:LSP", "Broadcast sender does not match the claimed package; ignoring")
                    return
                }
            }

            // A package cannot enroll itself by sending MODULE_LOADED. The user must
            // explicitly approve it in Settings, and its installed signature must match.
            if (!PrefsManager.isTrustedTargetPackage(context, pkg)) {
                Log.w("Happwner:LSP", "Package is not in the user-approved target list; ignoring MODULE_LOADED")
                return
            }

            // Load the current patched-apps set and signature map
            val prefs = PrefsManager.getSafePrefs(context)
            val apps = prefs.getStringSet("lspatch_apps", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            val sigMapJson = prefs.getString("lspatch_signatures", "{}") ?: "{}"
            val sigMap = try { JSONObject(sigMapJson) } catch (_: Throwable) { JSONObject() }

            // Important: keep the signature CRC32, otherwise MainActivity drops the app from the list
            val crc = PrefsManager.getSignatureCrc32(context, pkg)
            Log.d("Happwner:LSP", "Signature CRC for $pkg: $crc")
            if (crc == null) {
                Log.w("Happwner:LSP", "Package is not installed or has no signature; ignoring MODULE_LOADED")
                return
            }
            if (sigMap.optLong(pkg, -1L) != crc) {
                Log.w("Happwner:LSP", "Package signature changed after approval; ignoring MODULE_LOADED")
                return
            }

            val isNew = apps.add(pkg)
            Log.d("Happwner:LSP", "App in set: $pkg (Is new: $isNew). Current apps count: ${apps.size}")

            val editor = prefs.edit()
                .putStringSet("lspatch_apps", apps)
                .putString("lspatch_signatures", sigMap.toString())

            val xposedActive = ModuleStatus.isModuleActive()
            if (!xposedActive) {
                // New app: enable the mode
                if (isNew) {
                    editor.putBoolean("lspatch_mode", true)
                    Log.i("Happwner:LSP", "LSPatch mode auto-enabled due to new app: $pkg")
                // App already known but the mode is off: enable it
                } else if (!prefs.getBoolean("lspatch_mode", false)) {
                    editor.putBoolean("lspatch_mode", true)
                    Log.i("Happwner:LSP", "LSPatch mode restored for existing app: $pkg")
                }
            } else {
                // Xposed is active: record the app in the list, but leave lspatch_mode untouched
                Log.d("Happwner:LSP", "Xposed mode active, accumulating app but not flipping lspatch_mode")
            }

            editor.apply()

            // Tell MainActivity to refresh the UI
            context.sendBroadcast(Intent("${context.packageName}.REFRESH_UI").setPackage(context.packageName))
            Log.d("Happwner:LSP", "Sent REFRESH_UI broadcast")

            // Send the settings back in the reply
            PrefsManager.broadcastSettings(context)
        }
    }
}
