package com.happwner

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.SecureRandom

object PrefsManager {
    private const val PREFS_NAME = "happ_prefs"
    private const val TAG = "Happwner:Prefs"

    const val KEY_SUBSCRIPTION_DEVICE_ENABLED = "subscription_device_enabled"
    const val KEY_SUBSCRIPTION_DEVICE_OS = "subscription_device_os"
    const val KEY_SUBSCRIPTION_DEVICE_OS_VERSION = "subscription_device_os_version"
    const val KEY_SUBSCRIPTION_DEVICE_MODEL = "subscription_device_model"
    const val KEY_SUBSCRIPTION_DEVICE_NAME = "subscription_device_name"
    const val KEY_SUBSCRIPTION_IDENTITY_HOSTS = "subscription_identity_hosts"
    const val KEY_CUSTOM_USER_AGENT = "custom_user_agent"
    const val KEY_TRUSTED_TARGET_APPS = "trusted_target_apps"

    const val HAPP_PKG_PRIMARY = "com.happproxy"
    const val HAPP_PKG_SECONDARY = "su.happ.proxyutility"
    const val V2RAYTUN_PKG = "com.v2raytun.android"
    const val INCY_PKG = "llc.itdev.incy"

    // All packages whose subscriptions the unlock hook can target
    private val UNLOCK_PKGS = arrayOf(HAPP_PKG_PRIMARY, HAPP_PKG_SECONDARY, V2RAYTUN_PKG)

    fun getSafePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** User-approved targets only. Broadcast registration alone cannot grant access. */
    fun isTrustedTargetPackage(context: Context, pkg: String): Boolean {
        val prefs = getSafePrefs(context)
        if (pkg !in prefs.getStringSet(KEY_TRUSTED_TARGET_APPS, emptySet()).orEmpty()) return false
        val expected = try {
            org.json.JSONObject(prefs.getString("trusted_target_signatures", "{}") ?: "{}").optString(pkg, "")
        } catch (_: Throwable) { "" }
        if (expected.isBlank()) return false
        val actual = getSignatureSha256(context, pkg) ?: return false
        return actual == expected
    }

    /** A stable per-install bearer token required by the loopback bridge. */
    @Synchronized
    fun bridgeAccessToken(context: Context): String {
        val prefs = getSafePrefs(context)
        val existing = prefs.getString("bridge_access_token", null)
        if (existing != null && existing.matches(Regex("[0-9a-f]{64}"))) return existing
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        prefs.edit().putString("bridge_access_token", token).apply()
        return token
    }

    fun subscriptionDeviceOs(prefs: SharedPreferences): String =
        prefs.getString(KEY_SUBSCRIPTION_DEVICE_OS, "Android") ?: "Android"

    fun subscriptionDeviceOsVersion(prefs: SharedPreferences): String =
        prefs.getString(KEY_SUBSCRIPTION_DEVICE_OS_VERSION, Build.VERSION.RELEASE.ifBlank { Build.VERSION.SDK_INT.toString() })
            ?: Build.VERSION.RELEASE.ifBlank { Build.VERSION.SDK_INT.toString() }

    fun subscriptionDeviceModel(prefs: SharedPreferences): String =
        prefs.getString(KEY_SUBSCRIPTION_DEVICE_MODEL, Build.MODEL) ?: Build.MODEL

    fun subscriptionDeviceName(prefs: SharedPreferences): String =
        prefs.getString(KEY_SUBSCRIPTION_DEVICE_NAME, Build.DEVICE) ?: Build.DEVICE

    fun subscriptionIdentityHosts(prefs: SharedPreferences): String =
        prefs.getString(KEY_SUBSCRIPTION_IDENTITY_HOSTS, "") ?: ""

    // Module is active if the Xposed hook is live or LSPatch mode is on
    fun isXposedActive(context: Context): Boolean {
        return ModuleStatus.isModuleActive() ||
            getSafePrefs(context).getBoolean("lspatch_mode", false)
    }

    // Robust install check: getPackageInfo, then launch intent, then applicationInfo
    fun isPackageInstalled(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        try {
            pm.getPackageInfo(pkg, 0)
            return true
        } catch (_: PackageManager.NameNotFoundException) {
            return false
        } catch (t: Throwable) {
            Log.w(TAG, "getPackageInfo($pkg) threw ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            if (pm.getLaunchIntentForPackage(pkg) != null) return true
        } catch (_: Throwable) {}
        try {
            pm.getApplicationInfo(pkg, 0)
            return true
        } catch (_: PackageManager.NameNotFoundException) {
        } catch (_: Throwable) {}
        return false
    }

    // Which unlock-target packages (Happ variants + v2RayTun) are currently installed
    fun installedUnlockPackages(context: Context): List<String> {
        val result = mutableListOf<String>()
        for (pkg in UNLOCK_PKGS) {
            if (isPackageInstalled(context, pkg)) result.add(pkg)
        }
        return result
    }

    // An unlock target is active under Xposed (installed) or LSPatch (present in the patched set)
    fun isUnlockTargetActiveForModule(context: Context): Boolean {
        if (ModuleStatus.isModuleActive() && installedUnlockPackages(context).isNotEmpty()) {
            return true
        }
        val lspatchApps = getSafePrefs(context).getStringSet("lspatch_apps", null) ?: return false
        return UNLOCK_PKGS.any { lspatchApps.contains(it) }
    }

    // HWID spoof: explicit user choice if set, otherwise default to Xposed-active
    fun isHwidSpoofEnabled(context: Context): Boolean {
        DeviceProfiles.active(context)?.let { return it.sendHwid && it.nativeHwid }
        val p = getSafePrefs(context)
        if (p.contains("use_custom_hwid_substitution")) {
            return p.getBoolean("use_custom_hwid_substitution", false)
        }
        return isXposedActive(context)
    }

    // Spoof is live only when enabled AND a manual HWID was entered
    fun isHwidSpoofActive(context: Context): Boolean {
        DeviceProfiles.active(context)?.let { return it.sendHwid && it.nativeHwid && it.hwid.isNotBlank() }
        if (!isHwidSpoofEnabled(context)) return false
        return getSafePrefs(context).getBoolean("use_custom_hwid_input", false)
    }

    // Unlock-settings hook: explicit choice if set, else default when module + an unlock target are active
    fun isUnlockHookEnabled(context: Context): Boolean {
        val p = getSafePrefs(context)
        if (p.contains("hook_happ_unlock_settings")) {
            return p.getBoolean("hook_happ_unlock_settings", false)
        }
        return isXposedActive(context) && isUnlockTargetActiveForModule(context)
    }

    // Send a package-scoped reload signal; profile values are fetched through the caller-checked provider.
    fun broadcastSettings(context: Context) {
        val prefs = getSafePrefs(context)
        val targets = prefs.getStringSet(KEY_TRUSTED_TARGET_APPS, emptySet()).orEmpty()
        for (target in targets) {
            if (target.isBlank() || target == context.packageName) continue
            val intent = Intent("${context.packageName}.SETTINGS_UPDATE").setPackage(target)
                .addFlags(0x01000000) // FLAG_RECEIVER_INCLUDE_BACKGROUND
            context.sendBroadcast(intent)
        }
    }

    fun fixSharedPrefs(context: Context) {
        broadcastSettings(context)
    }

    // CRC32 of the first signature: cheap and enough to detect a reinstall
    fun getSignatureCrc32(context: Context, pkgName: String): Long? {
        val pm = context.packageManager
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            }

            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures != null && signatures.isNotEmpty()) {
                val crc = java.util.zip.CRC32()
                crc.update(signatures[0].toByteArray())
                crc.value
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Current signing-certificate fingerprint used to bind explicit target approval. */
    fun getSignatureSha256(context: Context, pkgName: String): String? {
        val pm = context.packageManager
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
            }
            val signatures = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }) ?: return null
            if (signatures.isEmpty()) return null
            signatures.map { signature ->
                java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(java.util.Locale.ROOT, byte.toInt() and 0xff) }
            }.sorted().joinToString(":")
        } catch (_: Exception) {
            null
        }
    }
}
