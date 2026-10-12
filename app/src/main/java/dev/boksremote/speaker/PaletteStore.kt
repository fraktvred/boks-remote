package dev.boksremote.speaker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's own palettes ("Your palettes"), kept on this phone in creation order. Names are
 * unique across saved palettes and presets because the UI matches the light's active palette
 * by name: the HSV floats read back from the light don't round-trip exactly.
 */
class PaletteStore(context: Context) {
    private val prefs = context.getSharedPreferences("palettes", Context.MODE_PRIVATE)
    private val _palettes = MutableStateFlow(PaletteCodec.decode(prefs.getString(KEY, null).orEmpty()))
    val palettes: StateFlow<List<LightPalette>> = _palettes

    /** Saves [colors] under [name] (made unique) and returns the stored palette. */
    fun add(name: String, colors: List<HsvColor>): LightPalette {
        val palette = LightPalette(uniqueName(name), colors)
        write(_palettes.value + palette)
        return palette
    }

    fun rename(palette: LightPalette, name: String): LightPalette {
        val renamed = palette.copy(name = uniqueName(name, except = palette.name))
        write(_palettes.value.map { if (it.name == palette.name) renamed else it })
        return renamed
    }

    fun delete(palette: LightPalette) = write(_palettes.value.filterNot { it.name == palette.name })

    private fun uniqueName(wanted: String, except: String? = null): String {
        val base = PaletteCodec.sanitize(wanted).ifBlank { "My palette" }
        val taken = (_palettes.value.map { it.name } + LightPalette.PRESETS.map { it.name } + "Custom").toSet() - setOfNotNull(except)
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in taken }
    }

    private fun write(list: List<LightPalette>) {
        _palettes.value = list
        prefs.edit().putString(KEY, PaletteCodec.encode(list)).apply()
    }

    private companion object {
        const val KEY = "saved"
    }
}

/**
 * One palette per line: `name|h,s,v|h,s,v|h,s,v|h,s,v`. Plain text rather than JSON so it stays
 * testable on the JVM, where Android's org.json is only a stub.
 */
object PaletteCodec {
    const val MAX_NAME = 32

    fun sanitize(name: String) = name.replace(Regex("[|\\n\\r]"), " ").trim().take(MAX_NAME)

    fun encode(list: List<LightPalette>): String = list.joinToString("\n") { p ->
        (listOf(sanitize(p.name)) + p.colors.map { "${it.hue},${it.saturation},${it.value}" }).joinToString("|")
    }

    /** Skips malformed lines instead of failing, so one bad entry never loses the rest. */
    fun decode(text: String): List<LightPalette> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size != LightPalette.SIZE + 1 || parts[0].isBlank()) return@mapNotNull null
        val colors = parts.drop(1).map { c ->
            val f = c.split(',').mapNotNull(String::toFloatOrNull)
            if (f.size != 3) return@mapNotNull null
            HsvColor(f[0], f[1], f[2])
        }
        LightPalette(parts[0], colors)
    }.toList()
}
