package com.happwner

import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences

/** Exercises actual profile persistence with private, isolated test storage. */
class TestPreferencesContext : ContextWrapper(null) {
    val preferences = InMemoryPreferences()
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = preferences
    override fun getPackageName(): String = "com.happwner.test"
    override fun sendBroadcast(intent: Intent?) = Unit
}

class InMemoryPreferences : SharedPreferences {
    private val data = mutableMapOf<String, Any>()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    override fun getAll(): MutableMap<String, *> = data.toMutableMap()
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (data[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getBoolean(key: String?, defValue: Boolean) = data[key] as? Boolean ?: defValue
    override fun getInt(key: String?, defValue: Int) = data[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = data[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = data[key] as? Float ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners += listener }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners -= listener }
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false
        private fun put(key: String?, value: Any?): SharedPreferences.Editor = apply { changes[requireNotNull(key)] = value }
        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun remove(key: String?) = put(key, null)
        override fun clear(): SharedPreferences.Editor = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) data.clear()
            changes.forEach { (key, value) -> if(value == null) data.remove(key) else data[key] = value }
            changes.keys.forEach { key -> listeners.toList().forEach { it.onSharedPreferenceChanged(this@InMemoryPreferences, key) } }
        }
    }
}
