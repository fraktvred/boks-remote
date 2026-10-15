package dev.boksremote.speaker

/** Remembers each speaker's owner pincode so reconnecting doesn't prompt again. Kept on this phone only. */
class PincodeStore(private val store: KeyValue) {
    fun get(address: String): String? = store[address]
    fun put(address: String, pin: String) { store[address] = pin }
    fun remove(address: String) { store[address] = null }
}
