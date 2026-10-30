package com.happwner

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** All profile mutations commit the saved record, active snapshot and legacy UI mirrors together. */
object DeviceProfiles {
    data class Preset(val title: Int = 0, val os: String = "", val version: String = "", val model: String = "",
        val userAgent: String = "", val id: String = "", val name: String = "", val hwid: String = "",
        val client: String = "", val clientVersion: String = "", val evidence: String = "manual",
        val source: String = "", val hwidFormat: String = "uuid", val sendHwid: Boolean = false,
        val deviceHeaders: Boolean = false, val hwidHeader: String = "x-hwid",
        val headers: Map<String, String> = emptyMap(), val hosts: String = "", val nativeHwid: Boolean = sendHwid) {
        fun identity() = RequestIdentity(true, os, version, model, name, userAgent,
            if (sendHwid) hwid else "", deviceHeaders, hwidHeader, headers, hosts, spoofNativeHwid=sendHwid && nativeHwid,
            client=client,clientVersion=clientVersion,profileId=id)
    }

    // Happ's device-header schema and native UA are unverified: these presets explicitly use
    // the editable compatibility schema, never a claimed native Happ User-Agent.
    val presets = listOf(
        Preset(os="Windows", version="11", model="PC x64", id="windows11", name="Windows 11 · Happ Desktop 4.5.4", client="Happ Desktop", clientVersion="4.5.4", evidence="compatibility", source="https://github.com/Happ-proxy/happ-desktop/releases/tag/4.5.4", sendHwid=true, deviceHeaders=true),
        Preset(os="Linux", version="Ubuntu 24.04", model="Linux x86_64", id="linux", name="Ubuntu 24.04 · Happ Desktop 4.5.4", client="Happ Desktop", clientVersion="4.5.4", evidence="compatibility", source="https://github.com/Happ-proxy/happ-desktop/releases/tag/4.5.4", sendHwid=true, deviceHeaders=true),
        Preset(os="Android", version="16", model="Pixel 9", id="android", name="Android 16 · Pixel 9 · Happ 4.7.1", client="Happ", clientVersion="4.7.1", evidence="compatibility", source="https://github.com/Happ-proxy/happ-android/releases/tag/4.7.1", hwidFormat="hex16", sendHwid=true, deviceHeaders=true),
        Preset(os="iOS", version="18", model="iPhone 15 Pro", id="ios", name="iOS 18 · iPhone 15 Pro · Happ 6.1.0", client="Happ", clientVersion="6.1.0", evidence="compatibility", source="https://apps.apple.com/app/id6504287215", sendHwid=true, deviceHeaders=true),
        Preset(os="macOS", version="15", model="MacBook Air", id="macos", name="macOS 15 · Happ Desktop 4.5.4", client="Happ Desktop", clientVersion="4.5.4", evidence="compatibility", source="https://github.com/Happ-proxy/happ-desktop/releases/tag/4.5.4", sendHwid=true, deviceHeaders=true),
        Preset(os="Android", version="16", model="Pixel 9", userAgent="v2rayNG/2.3.11", id="v2rayng", name="Android 16 · v2rayNG 2.3.11", client="v2rayNG", clientVersion="2.3.11", evidence="source", source="https://github.com/2dust/v2rayNG/releases/tag/2.3.11")
    ) + listOf("Windows", "Linux", "macOS").flatMap { os ->
        val token = os.lowercase(java.util.Locale.ROOT)
        listOf(
            Preset(os=os, userAgent="HiddifyNext/4.1.1 ($token) like ClashMeta v2ray sing-box", id="hiddify_$token", name="$os · Hiddify 4.1.1", client="Hiddify", clientVersion="4.1.1", evidence="source", source="https://github.com/hiddify/hiddify-app/tree/v4.1.1"),
            Preset(os=os, userAgent="HiddifyNextX/4.1.1 ($token) like ClashMeta v2ray sing-box", id="hiddify_x_$token", name="$os · Hiddify 4.1.1 (Xray)", client="Hiddify Xray", clientVersion="4.1.1", evidence="source", source="https://github.com/hiddify/hiddify-app/tree/v4.1.1"),
            Preset(os=os, userAgent="clash-verge/v2.5.8", id="clash_$token", name="$os · Clash Verge Rev 2.5.8", client="Clash Verge Rev", clientVersion="2.5.8", evidence="source", source="https://github.com/clash-verge-rev/clash-verge-rev/tree/v2.5.8")
        )
    }
    private const val SAVED = "saved_device_profiles_v2"
    private const val STATE = "active_device_profile_v2"
    private const val MIGRATED = "device_profiles_migrated_v2"
    private const val BACKUP = "before_device_profile_v2"
    private const val ACTIVE = "active_device_profile_id"
    private const val DAMAGED_ACTIVE = "damaged_device_profile_v2"
    private const val DAMAGED_BACKUP = "damaged_device_profile_backup_v2"
    private const val DAMAGED_SAVED = "damaged_saved_device_profiles_v2"
    private val backupKeys = listOf("custom_hwid", "custom_user_agent", "use_custom_hwid_input", "use_custom_hwid_substitution", PrefsManager.KEY_SUBSCRIPTION_IDENTITY_HOSTS)

    fun title(context: Context, preset: Preset): String = preset.name.ifBlank {
        if (preset.title != 0) context.getString(preset.title) else "${preset.os} ${preset.version}".trim()
    }
    private fun encode(p: Preset) = JSONObject().put("id",p.id).put("name",p.name)
        .put("os",p.os).put("version",p.version).put("model",p.model).put("ua",p.userAgent).put("hwid",p.hwid)
        .put("client",p.client).put("clientVersion",p.clientVersion).put("evidence",p.evidence).put("source",p.source)
        .put("hwidFormat",p.hwidFormat).put("sendHwid",p.sendHwid).put("deviceHeaders",p.deviceHeaders)
        .put("nativeHwid",p.nativeHwid).put("hwidHeader",p.hwidHeader).put("headers",JSONObject(p.headers)).put("hosts",p.hosts)
    private fun decode(p: JSONObject): Preset {
        val profile = Preset(os=p.optString("os"), version=p.optString("version"), model=p.optString("model"),
            userAgent=p.optString("ua"), id=p.getString("id"), name=p.optString("name"), hwid=p.optString("hwid"),
            client=p.optString("client"), clientVersion=p.optString("clientVersion"), evidence=p.optString("evidence","legacy"),
            source=p.optString("source"), hwidFormat=p.optString("hwidFormat","hex16"), sendHwid=p.optBoolean("sendHwid",true),
            deviceHeaders=p.optBoolean("deviceHeaders",true), hwidHeader=p.optString("hwidHeader","x-hwid"),
            headers=IdentityCodec.headers(p.optJSONObject("headers") ?: JSONObject()), hosts=p.optString("hosts"),
            nativeHwid=p.optBoolean("nativeHwid",p.optBoolean("sendHwid",true)))
        require(profile.id.isNotBlank() && profile.id.length <= 160)
        require(profile.name.length <= 160 && profile.hwidFormat in setOf("uuid", "hex16", "hex32"))
        require(profile.identity().invalidHeaderFields().isEmpty())
        IdentityCodec.encode(profile.identity())
        return profile
    }

    private fun custom(context: Context): List<Preset> {
        val prefs = PrefsManager.getSafePrefs(context)
        val raw = prefs.getString(SAVED,"[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (e: Exception) {
            // Preserve corrupt storage before allowing a later save to replace it.
            prefs.edit().putString(DAMAGED_SAVED, raw).apply()
            Log.e("Happwner:Profiles", "Saved profile list is damaged (${e.javaClass.simpleName})")
            return emptyList()
        }
        // A damaged entry must not hide all other records.
        return (0 until array.length()).mapNotNull { runCatching { decode(array.getJSONObject(it)) }.getOrNull() }
    }
    @Synchronized private fun migrate(context: Context) {
        val prefs = PrefsManager.getSafePrefs(context)
        if (prefs.getBoolean(MIGRATED,false)) return
        val old = runCatching { JSONArray(prefs.getString("saved_device_profiles_v1","[]")) }.getOrDefault(JSONArray())
        val migrated = (0 until old.length()).mapNotNull { runCatching { decode(old.getJSONObject(it)) }.getOrNull() }.toMutableList()
        val edit = prefs.edit().putBoolean(MIGRATED,true)
        if (prefs.getBoolean(PrefsManager.KEY_SUBSCRIPTION_DEVICE_ENABLED,false)) {
            val id = prefs.getString(ACTIVE,"").orEmpty()
            val legacy = Preset(id=if(id.startsWith("custom_")) id else "custom_"+UUID.randomUUID(),
                name=prefs.getString(PrefsManager.KEY_SUBSCRIPTION_DEVICE_NAME,"Legacy profile").orEmpty(),
                os=PrefsManager.subscriptionDeviceOs(prefs),version=PrefsManager.subscriptionDeviceOsVersion(prefs),model=PrefsManager.subscriptionDeviceModel(prefs),
                userAgent=prefs.getString(PrefsManager.KEY_CUSTOM_USER_AGENT,"").orEmpty(),hwid=prefs.getString("custom_hwid","").orEmpty(),
                hwidFormat="hex16",sendHwid=prefs.getBoolean("use_custom_hwid_input",false),deviceHeaders=true,evidence="legacy",hosts=PrefsManager.subscriptionIdentityHosts(prefs))
            migrated.removeAll { it.id == legacy.id }; migrated.add(legacy)
            edit.putString(STATE,encode(legacy).toString()).putString(ACTIVE,legacy.id)
        }
        edit.putString(SAVED,JSONArray(migrated.map(::encode)).toString()).apply()
    }
    @Synchronized fun all(context: Context): List<Preset> {
        migrate(context)
        val saved = custom(context).associateBy { it.id }
        return presets.map { saved[it.id] ?: it } + saved.values.filter { p -> presets.none { it.id == p.id } }
    }
    fun isSaved(context: Context, id: String): Boolean = custom(context).any { it.id == id }
    @Synchronized fun active(context: Context): Preset? {
        migrate(context)
        val prefs = PrefsManager.getSafePrefs(context)
        val raw = prefs.getString(STATE,null) ?: return null
        // Do not fall back to another identity when a persisted snapshot is invalid.
        return try {
            decode(JSONObject(raw))
        } catch (e: Exception) {
            prefs.edit().putString(DAMAGED_ACTIVE, raw).remove(STATE).remove(ACTIVE)
                .putBoolean(PrefsManager.KEY_SUBSCRIPTION_DEVICE_ENABLED, false)
                .putBoolean("use_custom_hwid_substitution", false).apply()
            Log.e("Happwner:Profiles", "Active profile is damaged; disabled its overrides (${e.javaClass.simpleName})")
            PrefsManager.fixSharedPrefs(context)
            null
        }
    }
    fun generateHwid(format: String): String = when(format) {
        "hex16" -> UUID.randomUUID().toString().replace("-", "").take(16)
        "hex32" -> UUID.randomUUID().toString().replace("-", "")
        else -> UUID.randomUUID().toString()
    }
    @Synchronized fun hwid(context: Context, preset: Preset): String {
        if (preset.hwid.isNotEmpty()) return preset.hwid
        val prefs=PrefsManager.getSafePrefs(context); val key="device_profile_hwid_"+preset.id
        return prefs.getString(key,null) ?: generateHwid(preset.hwidFormat).also { prefs.edit().putString(key,it).apply() }
    }
    private fun validate(profile: Preset) {
        require(profile.name.isNotBlank() && profile.name.length <= 160) { "Profile name is required (max 160)" }
        require(profile.identity().invalidHeaderFields().isEmpty()) { "Invalid HTTP header" }
        require(profile.hwidFormat in setOf("uuid","hex16","hex32")) { "Invalid HWID format" }
        require(!profile.sendHwid || profile.hwid.isNotBlank()) { "HWID is required when enabled" }
        IdentityCodec.encode(profile.identity())
    }
    @Synchronized fun apply(context: Context, preset: Preset) {
        migrate(context)
        val effective = custom(context).firstOrNull { it.id == preset.id } ?: preset
        val resolved=effective.copy(name=title(context,effective), hwid=if(effective.sendHwid) hwid(context,effective) else effective.hwid)
        validate(resolved)
        persist(context,resolved,custom(context))
    }
    /** ID, never the label, determines which custom record is replaced. */
    @Synchronized fun save(context: Context, profile: Preset): Preset {
        migrate(context)
        val entries=custom(context).toMutableList()
        val saved=profile.copy(id=profile.id.ifBlank { "custom_"+UUID.randomUUID() },evidence="manual",source="")
        validate(saved)
        entries.removeAll { it.id == saved.id }; entries.add(saved)
        persist(context,saved,entries)
        return saved
    }
    private fun persist(context: Context, profile: Preset, entries: List<Preset>) {
        val prefs=PrefsManager.getSafePrefs(context)
        val editor=prefs.edit()
        if (!prefs.contains(STATE) && !prefs.contains(BACKUP)) {
            val backup=JSONObject(); val values=prefs.all
            backupKeys.forEach { key -> if(values.containsKey(key)) backup.put(key,values[key]) }
            editor.putString(BACKUP,backup.toString())
        }
        editor.putString(SAVED,JSONArray(entries.map(::encode)).toString()).putString(STATE,encode(profile).toString())
            .putString(ACTIVE,profile.id).putBoolean(PrefsManager.KEY_SUBSCRIPTION_DEVICE_ENABLED,true)
            .putString(PrefsManager.KEY_SUBSCRIPTION_DEVICE_OS,profile.os).putString(PrefsManager.KEY_SUBSCRIPTION_DEVICE_OS_VERSION,profile.version)
            .putString(PrefsManager.KEY_SUBSCRIPTION_DEVICE_MODEL,profile.model).putString(PrefsManager.KEY_SUBSCRIPTION_DEVICE_NAME,profile.name)
            .putString(PrefsManager.KEY_CUSTOM_USER_AGENT,profile.userAgent).putString("custom_hwid",profile.hwid)
            .putBoolean("use_custom_hwid_input",profile.sendHwid).putBoolean("use_custom_hwid_substitution",profile.sendHwid && profile.nativeHwid)
            .putString(PrefsManager.KEY_SUBSCRIPTION_IDENTITY_HOSTS,profile.hosts).apply()
        PrefsManager.fixSharedPrefs(context)
    }
    @Synchronized fun updateHwid(context: Context, value: String) {
        val hwid = value.trim()
        require(RequestIdentity.isValidHeaderValue(hwid)) { "Invalid HWID" }
        val current = active(context)
        if (current != null) save(context, current.copy(hwid = hwid, sendHwid = hwid.isNotBlank()))
        else {
            PrefsManager.getSafePrefs(context).edit().putString("custom_hwid", hwid)
                .putBoolean("use_custom_hwid_input", hwid.isNotBlank()).apply()
            PrefsManager.fixSharedPrefs(context)
        }
    }
    @Synchronized fun disable(context: Context) {
        migrate(context)
        val prefs=PrefsManager.getSafePrefs(context); val edit=prefs.edit()
        val backupRaw = prefs.getString(BACKUP,"{}") ?: "{}"
        val backup = try { JSONObject(backupRaw) } catch (e: Exception) {
            prefs.edit().putString(DAMAGED_BACKUP, backupRaw).apply()
            Log.e("Happwner:Profiles", "Profile backup is damaged (${e.javaClass.simpleName})")
            JSONObject()
        }
        backupKeys.forEach { key ->
            when(val value=backup.opt(key)) { is Boolean -> edit.putBoolean(key,value); is String -> edit.putString(key,value); else -> edit.remove(key) }
        }
        edit.remove(STATE).remove(ACTIVE).remove(BACKUP).putBoolean(PrefsManager.KEY_SUBSCRIPTION_DEVICE_ENABLED,false).apply()
        PrefsManager.fixSharedPrefs(context)
    }
    @Synchronized fun delete(context: Context, id: String) {
        migrate(context)
        require(isSaved(context, id))
        val entries=custom(context).filterNot { it.id == id }
        if(active(context)?.id == id) disable(context)
        PrefsManager.getSafePrefs(context).edit().putString(SAVED,JSONArray(entries.map(::encode)).toString()).apply()
    }
    @Synchronized fun currentIdentity(context: Context): RequestIdentity {
        active(context)?.let { return it.identity() }
        val prefs=PrefsManager.getSafePrefs(context).all
        val manual=prefs["use_custom_hwid_input"] == true
        val hwid=if(manual) prefs["custom_hwid"] as? String else prefs["captured_id"] as? String
        val native = manual && (prefs["use_custom_hwid_substitution"] as? Boolean ?: PrefsManager.isXposedActive(context))
        return RequestIdentity(userAgent=prefs[PrefsManager.KEY_CUSTOM_USER_AGENT] as? String ?: "",hwid=hwid.orEmpty(),spoofNativeHwid=native)
    }
}
