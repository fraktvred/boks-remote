package dev.boksremote.speaker

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** What a palette on the light is to the user, as the palette card shows it. */
enum class PaletteKind(val label: String) {
    DEFAULT("Chosen by the light"),
    PRESET("Preset"),
    YOURS("Your palette"),
    /** Sent by another app, or kept with "Use without saving": the app doesn't know it by name. */
    UNSAVED("Not saved"),
}

/**
 * Every palette the app knows by name: the presets, and the user's own ("Your palettes"), kept on
 * this phone in creation order. Names are unique across both because palettes are matched by name:
 * the light's active one (the HSV floats read back from it don't round-trip exactly), Cycle's and
 * the scenes'. So a rename or delete here reaches everything that names the palette, through
 * [register]ed [User]s, rather than each caller having to remember them.
 */
class PaletteLibrary(private val kv: KeyValue) {
    /** Something that refers to palettes by name, and follows them when they change. */
    interface User {
        fun renamed(old: String, new: String)
        /** [name] is gone; returns how to put it back, under the name it is restored with. */
        fun removed(name: String): (restored: String) -> Unit
    }

    private val users = mutableListOf<User>()
    private val _palettes = MutableStateFlow(PaletteCodec.decode(kv[KEY].orEmpty()))
    /** The user's own palettes. */
    val palettes: StateFlow<List<LightPalette>> = _palettes
    private val _recentColors = MutableStateFlow(PaletteCodec.decodeColors(kv[KEY_RECENT].orEmpty()))
    /** Colours from the palettes saved lately, newest first, for reuse in the editor. */
    val recentColors: StateFlow<List<HsvColor>> = _recentColors

    init {
        // A preset added in an update may take a name the user already gave a palette; theirs gets a suffix.
        val unique = PaletteCodec.withUniqueNames(_palettes.value, RESERVED)
        if (unique != _palettes.value) write(unique)
    }

    fun register(user: User) {
        users += user
    }

    /** Presets, then the user's own. */
    fun all(): List<LightPalette> = LightPalette.PRESETS + _palettes.value

    fun resolve(name: String): LightPalette? = all().firstOrNull { it.name == name }

    /** The user's own palette called like [palette], if there is one. */
    fun own(palette: LightPalette?): LightPalette? = palette?.let { p -> _palettes.value.firstOrNull { it.name == p.name } }

    /** What [palette], as on the light, is; null means the light's default colours. */
    fun kind(palette: LightPalette?): PaletteKind = when {
        palette == null -> PaletteKind.DEFAULT
        own(palette) != null -> PaletteKind.YOURS
        LightPalette.PRESETS.any { it.name == palette.name } -> PaletteKind.PRESET
        else -> PaletteKind.UNSAVED
    }

    /** Saves [colors] under [name] (made unique) and returns the stored palette. */
    fun add(name: String, colors: List<HsvColor>): LightPalette {
        val palette = LightPalette(uniqueName(name), colors)
        write(_palettes.value + palette)
        rememberColors(colors)
        return palette
    }

    /** Replaces [original] in place, keeping its spot in the list; a new name reaches every [User]. */
    fun update(original: LightPalette, name: String, colors: List<HsvColor>): LightPalette {
        val updated = LightPalette(uniqueName(name, except = original.name), colors)
        write(_palettes.value.map { if (it.name == original.name) updated else it })
        if (colors != original.colors) rememberColors(colors)
        if (updated.name != original.name) users.forEach { it.renamed(original.name, updated.name) }
        return updated
    }

    fun rename(palette: LightPalette, name: String): LightPalette = update(palette, name, palette.colors)

    /**
     * Deletes [palette], and drops it wherever it is named, so a later one saved under its name joins
     * nothing by accident. Returns the undo, which puts it all back in place.
     */
    fun delete(palette: LightPalette): () -> Unit {
        val index = _palettes.value.indexOfFirst { it.name == palette.name }
        write(_palettes.value.filterNot { it.name == palette.name })
        val undos = users.map { it.removed(palette.name) }
        return {
            // Renamed if its name was taken meanwhile.
            val back = palette.copy(name = uniqueName(palette.name))
            write(_palettes.value.toMutableList().apply { add(index.coerceIn(0, size), back) })
            undos.forEach { it(back.name) }
        }
    }

    /** The name a new palette based on [wanted] would get, e.g. "Synthwave 2" for a copy of the preset. */
    fun suggestName(wanted: String): String = uniqueName(wanted)

    private fun uniqueName(wanted: String, except: String? = null): String {
        val base = PaletteCodec.sanitize(wanted).ifBlank { "My palette" }
        val taken = (_palettes.value.map { it.name } + RESERVED).toSet() - setOfNotNull(except)
        return PaletteCodec.unique(base, taken)
    }

    private fun write(list: List<LightPalette>) {
        _palettes.value = list
        kv[KEY] = PaletteCodec.encode(list)
    }

    private fun rememberColors(colors: List<HsvColor>) {
        val recent = PaletteCodec.mergeRecent(colors, _recentColors.value, PaletteCodec.MAX_RECENT)
        _recentColors.value = recent
        kv[KEY_RECENT] = PaletteCodec.encodeColors(recent)
    }

    private companion object {
        const val KEY = "saved"
        const val KEY_RECENT = "recent"
        val RESERVED = LightPalette.PRESETS.map { it.name }.toSet() + "Custom"
    }
}

/**
 * One palette per line: `name|h,s,v|h,s,v|h,s,v|h,s,v`. Plain text rather than JSON so it stays
 * testable on the JVM, where Android's org.json is only a stub.
 */
object PaletteCodec {
    const val MAX_NAME = 32
    /** Recent colours kept for the editor. */
    const val MAX_RECENT = 8

    fun sanitize(name: String) = name.replace(Regex("[|\\n\\r]"), " ").trim().take(MAX_NAME)

    /** [base], or "[base] 2", "[base] 3"… if that is [taken]. */
    fun unique(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in taken }
    }

    /** Renames palettes whose name is [reserved] or used by an earlier one in [list]. */
    fun withUniqueNames(list: List<LightPalette>, reserved: Set<String>): List<LightPalette> {
        val all = list.map { it.name }.toSet()
        val taken = reserved.toMutableSet()
        return list.map { p ->
            // A suffixed name must not take one that a later palette already has.
            val name = if (p.name in taken) unique(p.name, taken + all) else p.name
            taken += name
            p.copy(name = name)
        }
    }

    fun encode(list: List<LightPalette>): String = list.joinToString("\n") { p -> sanitize(p.name) + "|" + encodeColors(p.colors) }

    fun encodeColors(colors: List<HsvColor>): String = colors.joinToString("|") { "${it.hue},${it.saturation},${it.value}" }

    /** Skips malformed entries. */
    fun decodeColors(text: String): List<HsvColor> = text.split('|').mapNotNull(::decodeColor)

    private fun decodeColor(text: String): HsvColor? {
        val f = text.split(',').mapNotNull(String::toFloatOrNull)
        return if (f.size == 3) HsvColor(f[0], f[1], f[2]) else null
    }

    /** [new] ahead of [old], without colours that look the same, at most [max]. */
    fun mergeRecent(new: List<HsvColor>, old: List<HsvColor>, max: Int): List<HsvColor> =
        (new + old).distinctBy { Triple(it.hue.roundToInt() % 360, (it.saturation * 100).roundToInt(), (it.value * 100).roundToInt()) }.take(max)

    /** Skips malformed lines instead of failing, so one bad entry never loses the rest. */
    fun decode(text: String): List<LightPalette> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size != LightPalette.SIZE + 1 || parts[0].isBlank()) return@mapNotNull null
        val colors = parts.drop(1).map { decodeColor(it) ?: return@mapNotNull null }
        LightPalette(parts[0], colors)
    }.toList()
}
