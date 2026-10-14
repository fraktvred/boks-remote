package dev.boksremote.speaker

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A saved look for the Lightboks: brightness, light mode and palette, applied together. The palette's
 * colours are kept in the scene, so it still works after the palette is edited or deleted; null means
 * the light's own default colours. A scene also freezes Cycle in [cycle]: applying it puts Cycle back
 * as it was, down to the palette it showed and the time that palette had left. Null for scenes saved
 * before scenes kept Cycle; those leave it as it is.
 */
data class Scene(
    val name: String,
    val brightness: Int,
    val level: EnergyLevel,
    val palette: LightPalette?,
    val cycle: CycleSnapshot? = null,
)

/** The user's scenes, on this phone, in the order they were saved. */
class SceneStore(context: Context) {
    private val prefs = context.getSharedPreferences("scenes", Context.MODE_PRIVATE)
    private val _scenes = MutableStateFlow(SceneCodec.decode(prefs.getString(KEY, null).orEmpty()))
    val scenes: StateFlow<List<Scene>> = _scenes

    fun add(scene: Scene): Scene {
        val named = scene.copy(name = uniqueName(scene.name))
        write(_scenes.value + named)
        return named
    }

    /** Replaces [old] in place, keeping its spot, and returns the stored scene. */
    fun replace(old: Scene, new: Scene): Scene {
        val named = new.copy(name = uniqueName(new.name, except = old.name))
        write(_scenes.value.map { if (it.name == old.name) named else it })
        return named
    }

    fun move(from: Int, to: Int) = write(_scenes.value.toMutableList().apply { add(to, removeAt(from)) })

    fun delete(scene: Scene) = write(_scenes.value.filterNot { it.name == scene.name })

    fun suggestName(): String = uniqueName("Scene")

    /** The scene in use, by name, kept across app restarts. */
    var active: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    private fun uniqueName(wanted: String, except: String? = null): String {
        val base = PaletteCodec.sanitize(wanted).ifBlank { "Scene" }
        return PaletteCodec.unique(base, _scenes.value.map { it.name }.toSet() - setOfNotNull(except))
    }

    private fun write(list: List<Scene>) {
        _scenes.value = list
        prefs.edit().putString(KEY, SceneCodec.encode(list)).apply()
    }

    private companion object {
        const val KEY = "saved"
        const val KEY_ACTIVE = "active"
    }
}

/**
 * One scene per line, in fields split by `|`:
 * `name|brightness|level|palette name|h,s,v|h,s,v|h,s,v|h,s,v|interval|shuffle|cycle names|running|current|ms left|transition`.
 * The palette fields are empty for the default colours and the cycle fields when there is no cycle, as
 * are current and ms left when unknown. Cycle names are split by [NAME_SEPARATOR], since palette names
 * may hold anything but `|` and newlines. Earlier lines still read: 3 or 8 fields without a cycle, 11
 * with one saved only while it ran, 12 without where it was, and 14 without the transition, which
 * takes the default.
 */
object SceneCodec {
    private const val NAME_SEPARATOR = '\u001F'
    private const val FIELDS = 15

    fun encode(list: List<Scene>): String = list.joinToString("\n") { s ->
        val palette = s.palette?.let { PaletteCodec.encode(listOf(it)) } ?: "|".repeat(LightPalette.SIZE)
        val cycle = s.cycle?.let { c ->
            val cfg = c.config
            "${cfg.intervalSeconds}|${cfg.shuffle}|${cfg.names.joinToString(NAME_SEPARATOR.toString())}|${c.running}|${c.current.orEmpty()}|${c.remainingMs ?: ""}|${cfg.transition.name}"
        } ?: "||||||"
        "${PaletteCodec.sanitize(s.name)}|${s.brightness}|${s.level.name}|$palette|$cycle"
    }

    /** Skips malformed lines. */
    fun decode(text: String): List<Scene> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size !in setOf(3, 8, 11, 12, 14, FIELDS) || parts[0].isBlank()) return@mapNotNull null
        val brightness = parts[1].toIntOrNull()?.takeIf { it in 0..255 } ?: return@mapNotNull null
        val level = EnergyLevel.entries.firstOrNull { it.name == parts[2] } ?: return@mapNotNull null
        val palette = if (parts.size > 3 && parts[3].isNotEmpty()) {
            PaletteCodec.decode(parts.subList(3, 8).joinToString("|")).singleOrNull() ?: return@mapNotNull null
        } else null
        val cycle = if (parts.size >= 11 && parts[8].isNotEmpty()) {
            val interval = parts[8].toIntOrNull()?.takeIf { it in CycleConfig.INTERVALS } ?: return@mapNotNull null
            val transition = CycleTransition.entries.firstOrNull { it.name == parts.getOrNull(14) } ?: CycleConfig().transition
            val config = CycleConfig(parts[10].split(NAME_SEPARATOR).filter { it.isNotEmpty() }, interval, parts[9].toBoolean(), transition)
            // 11-field lines only kept a cycle that was running.
            val running = parts.size == 11 || parts[11].toBoolean()
            val current = parts.getOrNull(12)?.ifEmpty { null }
            val remaining = parts.getOrNull(13)?.toLongOrNull()?.coerceAtLeast(0)
            CycleSnapshot(config, running, current, remaining)
        } else null
        Scene(parts[0], brightness, level, palette, cycle)
    }.toList()
}
