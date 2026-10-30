package com.happwner

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

class SettingsProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.happwner.settings"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/settings")

        const val METHOD_GET_SETTINGS = "getSettings"
        const val METHOD_SAVE_URL = "saveUrl"
        const val METHOD_SAVE_ID = "saveCapturedId"
        const val KEY_HWID = "custom_hwid"
        const val KEY_ENABLED = "use_custom_hwid_substitution"

        const val KEY_DEVICE_IDENTITY_ENABLED = PrefsManager.KEY_SUBSCRIPTION_DEVICE_ENABLED
        const val KEY_DEVICE_IDENTITY_OS = PrefsManager.KEY_SUBSCRIPTION_DEVICE_OS
        const val KEY_DEVICE_IDENTITY_OS_VERSION = PrefsManager.KEY_SUBSCRIPTION_DEVICE_OS_VERSION
        const val KEY_DEVICE_IDENTITY_MODEL = PrefsManager.KEY_SUBSCRIPTION_DEVICE_MODEL
        const val KEY_DEVICE_IDENTITY_NAME = PrefsManager.KEY_SUBSCRIPTION_DEVICE_NAME
        const val KEY_DEVICE_IDENTITY_HOSTS = PrefsManager.KEY_SUBSCRIPTION_IDENTITY_HOSTS
        const val KEY_USER_AGENT = PrefsManager.KEY_CUSTOM_USER_AGENT

    }

    override fun onCreate(): Boolean = true

    // IPC entry point: get-settings or save-url, called by the hooked/patched app
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (!isApprovedCaller(context)) return null
        val prefs = PrefsManager.getSafePrefs(context)

        return when (method) {
            // Return the current HWID / intercept / unlock state
            METHOD_GET_SETTINGS -> Bundle().apply {
                putString(IdentityCodec.KEY, currentIdentityPayload(context))
                val isActive = PrefsManager.isHwidSpoofActive(context)
                val custom = prefs.getString(KEY_HWID, "") ?: ""
                val hwidToSend = if (isActive) custom else ""
                putString(KEY_HWID, hwidToSend)
                putBoolean(KEY_ENABLED, isActive)
                putBoolean("intercept_enabled", prefs.getBoolean("intercept_enabled", false))
                putBoolean("hook_happ_unlock_settings", PrefsManager.isUnlockHookEnabled(context))
                putBoolean(KEY_DEVICE_IDENTITY_ENABLED, prefs.getBoolean(KEY_DEVICE_IDENTITY_ENABLED, false))
                putString(KEY_DEVICE_IDENTITY_OS, PrefsManager.subscriptionDeviceOs(prefs))
                putString(KEY_DEVICE_IDENTITY_OS_VERSION, PrefsManager.subscriptionDeviceOsVersion(prefs))
                putString(KEY_DEVICE_IDENTITY_MODEL, PrefsManager.subscriptionDeviceModel(prefs))
                putString(KEY_DEVICE_IDENTITY_NAME, PrefsManager.subscriptionDeviceName(prefs))
                putString(KEY_DEVICE_IDENTITY_HOSTS, PrefsManager.subscriptionIdentityHosts(prefs))
                putString(KEY_USER_AGENT, prefs.getString(KEY_USER_AGENT, "") ?: "")
            }
            // Persist a captured subscription URL into history and notify the UI
            METHOD_SAVE_URL -> {
                val url = arg?.takeIf { it.length <= 8192 } ?: return null
                val callerUid = android.os.Binder.getCallingUid()
                val callerPackages = try { context.packageManager.getPackagesForUid(callerUid)?.toSet().orEmpty() }
                    catch (_: Throwable) { emptySet() }
                val claimedPackage = extras?.getString("pkg")
                if (claimedPackage.isNullOrBlank() || claimedPackage !in callerPackages ||
                    !PrefsManager.isTrustedTargetPackage(context, claimedPackage)) return null
                try { SubscriptionHttp.validateUrl(url) } catch (_: Exception) { return null }

                val history = prefs.getString("url_history_list", "") ?: ""
                val list = if (history.isEmpty()) mutableListOf() else history.split("|||").toMutableList()
                list.remove(url)
                list.add(0, url)
                prefs.edit().putString("url_history_list", list.take(100).joinToString("|||")).apply()
                context.sendBroadcast(android.content.Intent("${context.packageName}.URL_CAPTURED").apply {
                    setPackage(context.packageName)
                    putExtra("url", url)
                })
                null
            }
            METHOD_SAVE_ID -> {
                val id = arg?.takeIf { IdReceiver.isValidCapturedId(it) } ?: return null
                val callerUid = android.os.Binder.getCallingUid()
                val callerPackages = try { context.packageManager.getPackagesForUid(callerUid)?.toSet().orEmpty() }
                    catch (_: Throwable) { emptySet() }
                val claimedPackage = extras?.getString("pkg")
                if (claimedPackage.isNullOrBlank() || claimedPackage !in callerPackages ||
                    !PrefsManager.isTrustedTargetPackage(context, claimedPackage)) return null
                prefs.edit().putString("captured_id", id).apply()
                context.sendBroadcast(android.content.Intent("${context.packageName}.ID_CAPTURED").apply {
                    setPackage(context.packageName)
                })
                null
            }
            else -> null
        }
    }

    // For apps that query settings directly (fallback)
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val context = context ?: return null
        if (!isApprovedCaller(context)) return null
        val prefs = PrefsManager.getSafePrefs(context)
        val cursor = MatrixCursor(arrayOf("key", "value"))
        cursor.addRow(arrayOf(IdentityCodec.KEY, currentIdentityPayload(context)))
        val isActive = PrefsManager.isHwidSpoofActive(context)
        val custom = prefs.getString(KEY_HWID, "") ?: ""
        val hwidToSend = if (isActive) custom else ""
        cursor.addRow(arrayOf(KEY_HWID, hwidToSend))
        cursor.addRow(arrayOf(KEY_ENABLED, if (isActive) 1 else 0))
        cursor.addRow(arrayOf("hook_happ_unlock_settings", if (PrefsManager.isUnlockHookEnabled(context)) 1 else 0))
        cursor.addRow(arrayOf(KEY_DEVICE_IDENTITY_ENABLED, if (prefs.getBoolean(KEY_DEVICE_IDENTITY_ENABLED, false)) 1 else 0))
        cursor.addRow(arrayOf(KEY_DEVICE_IDENTITY_OS, PrefsManager.subscriptionDeviceOs(prefs)))
        cursor.addRow(arrayOf(KEY_DEVICE_IDENTITY_OS_VERSION, PrefsManager.subscriptionDeviceOsVersion(prefs)))
        cursor.addRow(arrayOf(KEY_DEVICE_IDENTITY_MODEL, PrefsManager.subscriptionDeviceModel(prefs)))
        cursor.addRow(arrayOf(KEY_DEVICE_IDENTITY_NAME, PrefsManager.subscriptionDeviceName(prefs)))
        cursor.addRow(arrayOf(KEY_DEVICE_IDENTITY_HOSTS, PrefsManager.subscriptionIdentityHosts(prefs)))
        cursor.addRow(arrayOf(KEY_USER_AGENT, prefs.getString(KEY_USER_AGENT, "") ?: ""))
        return cursor
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    private fun isApprovedCaller(context: Context): Boolean {
        val uid = android.os.Binder.getCallingUid()
        if (uid == android.os.Process.myUid()) return true
        val packages = try { context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty() }
            catch (_: Throwable) { return false }
        return packages.any { PrefsManager.isTrustedTargetPackage(context, it) }
    }

    private fun currentIdentityPayload(context: Context): String = try {
        IdentityCodec.encode(DeviceProfiles.currentIdentity(context))
    } catch (_: Throwable) {
        IdentityCodec.encode(RequestIdentity())
    }
}
