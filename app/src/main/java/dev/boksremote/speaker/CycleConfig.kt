package dev.boksremote.speaker

/** How Cycle goes from one palette to the next. */
enum class CycleTransition(val label: String) { INSTANT("Instant"), FADE("Fade") }

/** Which palettes Cycle plays, by name and in the user's order, and how. */
data class CycleConfig(
    val names: List<String> = DEFAULT_NAMES,
    val intervalSeconds: Int = 60,
    val shuffle: Boolean = false,
    val transition: CycleTransition = CycleTransition.FADE,
) {
    companion object {
        /** No shorter than 30 s: each palette the light gets may be written to its flash (see DmxBridge). */
        val INTERVALS = listOf(30, 60, 120, 300, 600, 1800)
        const val MIN_PALETTES = 2
        val DEFAULT_NAMES = listOf("Synthwave", "Sunset", "Tropicana", "Aurora", "Galaxy", "Ultraviolet")

        fun intervalLabel(seconds: Int) = if (seconds < 60) "$seconds s" else "${seconds / 60} min"
    }
}

/**
 * Cycle's settings as text, in the fields `interval|shuffle|names|transition`, the one format both
 * Cycle's own store and a scene keep them in. Names are split by [NAME_SEPARATOR], since palette
 * names may hold anything but `|` and newlines.
 */
object CycleConfigCodec {
    const val NAME_SEPARATOR = '\u001F'

    fun fields(c: CycleConfig): List<String> =
        listOf(c.intervalSeconds.toString(), c.shuffle.toString(), c.names.joinToString(NAME_SEPARATOR.toString()), c.transition.name)

    fun encode(c: CycleConfig): String = fields(c).joinToString("|")

    fun decode(text: String): CycleConfig? = text.split('|').takeIf { it.size == 4 }?.let { parse(it[0], it[1], it[2], it[3]) }

    /**
     * The settings from their fields; null when the interval isn't one Cycle offers. A missing
     * [transition], from before there was a choice, takes the default.
     */
    fun parse(interval: String, shuffle: String, names: String, transition: String?, separator: Char = NAME_SEPARATOR): CycleConfig? {
        val seconds = interval.toIntOrNull()?.takeIf { it in CycleConfig.INTERVALS } ?: return null
        return CycleConfig(
            names.split(separator).filter { it.isNotBlank() },
            seconds,
            shuffle.toBoolean(),
            CycleTransition.entries.firstOrNull { it.name == transition } ?: CycleConfig().transition,
        )
    }
}

/** Cycle's settings across restarts. */
class CycleConfigStore(private val kv: KeyValue) {
    fun load(): CycleConfig = kv[KEY]?.let(CycleConfigCodec::decode) ?: legacy() ?: CycleConfig()

    fun save(config: CycleConfig) {
        kv[KEY] = CycleConfigCodec.encode(config)
    }

    /** Whether the Cycle card shows its palettes as large previews; how it looks, so not part of [CycleConfig] or a scene. */
    var largePreviews: Boolean
        get() = kv[LARGE_PREVIEWS].toBoolean()
        set(value) { kv[LARGE_PREVIEWS] = value.toString() }

    /** Before one key held them all, each setting had its own; names were split by newlines. */
    private fun legacy(): CycleConfig? {
        val names = kv[OLD_NAMES] ?: return null
        val default = CycleConfig()
        return CycleConfigCodec.parse(kv[OLD_INTERVAL] ?: "", kv[OLD_SHUFFLE] ?: "false", names, kv[OLD_TRANSITION], separator = '\n')
            ?: default.copy(names = names.split('\n').filter { it.isNotBlank() }, shuffle = kv[OLD_SHUFFLE].toBoolean())
    }

    private companion object {
        const val KEY = "config"
        const val LARGE_PREVIEWS = "large_previews"
        const val OLD_NAMES = "names"
        const val OLD_INTERVAL = "interval"
        const val OLD_SHUFFLE = "shuffle"
        const val OLD_TRANSITION = "transition"
    }
}
