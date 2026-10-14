package dev.boksremote.speaker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A named custom EQ curve; [bands] are dB per band, bass to treble (see [EqCurve]). */
data class EqPreset(val name: String, val bands: List<Int>)

/**
 * The user's custom EQ presets, kept on this phone in creation order. The speaker only holds the
 * active curve, so the active preset is recognised by matching its bands. The curve is the same
 * six bands on every model, so presets are shared across speakers.
 */
class EqPresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("eq_presets", Context.MODE_PRIVATE)
    private val _presets = MutableStateFlow(EqPresetCodec.decode(prefs.getString(KEY, null).orEmpty()))
    val presets: StateFlow<List<EqPreset>> = _presets

    /** Saves [bands] under [name] (made unique) and returns the stored preset. */
    fun add(name: String, bands: List<Int>): EqPreset {
        val preset = EqPreset(uniqueName(name), bands)
        write(_presets.value + preset)
        return preset
    }

    /** Replaces the curve of [preset], keeping its name and position. */
    fun update(preset: EqPreset, bands: List<Int>): EqPreset {
        val updated = preset.copy(bands = bands)
        write(_presets.value.map { if (it.name == preset.name) updated else it })
        return updated
    }

    fun rename(preset: EqPreset, name: String): EqPreset {
        val renamed = preset.copy(name = uniqueName(name, except = preset.name))
        write(_presets.value.map { if (it.name == preset.name) renamed else it })
        return renamed
    }

    fun delete(preset: EqPreset) = write(_presets.value.filterNot { it.name == preset.name })

    private fun uniqueName(wanted: String, except: String? = null): String {
        val base = EqPresetCodec.sanitize(wanted).ifBlank { "My EQ" }
        val taken = (_presets.value.map { it.name } + "Custom").toSet() - setOfNotNull(except)
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base $it" }.first { it !in taken }
    }

    private fun write(list: List<EqPreset>) {
        _presets.value = list
        prefs.edit().putString(KEY, EqPresetCodec.encode(list)).apply()
    }

    private companion object {
        const val KEY = "saved"
    }
}

/** One preset per line: `name|b0,b1,b2,b3,b4,b5` in dB. Same plain-text approach as [PaletteCodec]. */
object EqPresetCodec {
    const val MAX_NAME = 32

    fun sanitize(name: String) = name.replace(Regex("[|\\n\\r]"), " ").trim().take(MAX_NAME)

    fun encode(list: List<EqPreset>): String = list.joinToString("\n") { "${sanitize(it.name)}|${it.bands.joinToString(",")}" }

    /** Skips malformed lines instead of failing, so one bad entry never loses the rest. */
    fun decode(text: String): List<EqPreset> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
        val bands = parts[1].split(',').map { it.toIntOrNull() ?: return@mapNotNull null }
        if (bands.size != EqCurve.BANDS) return@mapNotNull null
        EqPreset(parts[0], bands.map { it.coerceIn(EqCurve.MIN_DB, EqCurve.MAX_DB) })
    }.toList()
}
