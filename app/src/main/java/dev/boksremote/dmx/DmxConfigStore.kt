package dev.boksremote.dmx

import android.content.Context

/** Remembers the bridge's patch (protocol, universe, address, personality) between launches. */
class DmxConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("dmx", Context.MODE_PRIVATE)

    fun load(): DmxConfig {
        val defaults = DmxConfig()
        val protocol = enumOrNull<DmxProtocol>(prefs.getString("protocol", null)) ?: defaults.protocol
        val personality = enumOrNull<LightboksPersonality>(prefs.getString("personality", null)) ?: defaults.personality
        return DmxConfig(
            protocol = protocol,
            universe = prefs.getInt("universe", protocol.firstUniverse).coerceIn(protocol.universes),
            startAddress = prefs.getInt("startAddress", defaults.startAddress).coerceIn(1, 513 - personality.footprint),
            personality = personality,
        )
    }

    fun save(c: DmxConfig) = prefs.edit()
        .putString("protocol", c.protocol.name)
        .putInt("universe", c.universe)
        .putInt("startAddress", c.startAddress)
        .putString("personality", c.personality.name)
        .apply()

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? = enumValues<T>().firstOrNull { it.name == name }
}
