package dev.boksremote.speaker

import android.content.Context

/** Remembers each speaker's owner pincode so reconnecting doesn't prompt again. Kept on this phone only. */
class PincodeStore(context: Context) {
    private val prefs = context.getSharedPreferences("pincodes", Context.MODE_PRIVATE)

    fun get(address: String): String? = prefs.getString(address, null)
    fun put(address: String, pin: String) = prefs.edit().putString(address, pin).apply()
    fun remove(address: String) = prefs.edit().remove(address).apply()
}
