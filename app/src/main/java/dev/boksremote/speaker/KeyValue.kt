package dev.boksremote.speaker

import android.content.Context

/** Where the stores keep their text: SharedPreferences on the phone, a map in tests. */
interface KeyValue {
    operator fun get(key: String): String?
    /** Null removes [key]. */
    operator fun set(key: String, value: String?)
}

class PrefsKeyValue(context: Context, name: String) : KeyValue {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    // As text whatever its type, so values an earlier version saved as an int or a boolean still read.
    override fun get(key: String): String? = prefs.all[key]?.toString()

    override fun set(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

class MemoryKeyValue(initial: Map<String, String> = emptyMap()) : KeyValue {
    private val map = initial.toMutableMap()
    override fun get(key: String): String? = map[key]
    override fun set(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
