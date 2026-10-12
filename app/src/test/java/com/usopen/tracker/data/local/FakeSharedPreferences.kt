package com.usopen.tracker.data.local

import android.content.SharedPreferences

class FakeSharedPreferences : SharedPreferences {

    private val data = mutableMapOf<String, Any?>()

    @Synchronized
    override fun getAll(): MutableMap<String, *> = HashMap(data)

    @Synchronized
    override fun getString(key: String?, defValue: String?): String? {
        return (data[key] as? String) ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val set = data[key] as? Set<String>
        return set?.toMutableSet() ?: defValues
    }

    @Synchronized
    override fun getInt(key: String?, defValue: Int): Int {
        return (data[key] as? Int) ?: defValue
    }

    @Synchronized
    override fun getLong(key: String?, defValue: Long): Long {
        return (data[key] as? Long) ?: defValue
    }

    @Synchronized
    override fun getFloat(key: String?, defValue: Float): Float {
        return (data[key] as? Float) ?: defValue
    }

    @Synchronized
    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return (data[key] as? Boolean) ?: defValue
    }

    @Synchronized
    override fun contains(key: String?): Boolean {
        return data.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class FakeEditor : SharedPreferences.Editor {
        private val modifications = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) modifications[key] = values?.toSet()
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) modifications[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            synchronized(this@FakeSharedPreferences) {
                if (clearAll) {
                    data.clear()
                }
                for (key in removals) {
                    data.remove(key)
                }
                data.putAll(modifications)
                removals.clear()
                modifications.clear()
                clearAll = false
            }
        }
    }
}
