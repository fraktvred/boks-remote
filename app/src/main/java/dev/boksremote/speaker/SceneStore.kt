package dev.boksremote.speaker

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

/**
 * The user's scenes, on this phone, in the order they were saved. A palette renamed or deleted in the
 * [PaletteLibrary] is followed in every scene's Cycle, and in the palette a scene shows.
 */
class SceneStore(private val kv: KeyValue) : PaletteLibrary.User {
    private val _scenes = MutableStateFlow(SceneCodec.decode(kv[KEY].orEmpty()))
    val scenes: StateFlow<List<Scene>> = _scenes

    fun add(scene: Scene): Scene {
        val named = scene.copy(name = uniqueName(scene.name))
        write(_scenes.value + named)
        seeded = true
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

    /**
     * True once there has been a scene here, made by the user or as the first one on connecting; from
     * then on, deleting every scene leaves none rather than making a new first one.
     */
    var seeded: Boolean
        get() = kv[KEY_SEEDED] != null || _scenes.value.isNotEmpty()
        set(value) { kv[KEY_SEEDED] = if (value) "1" else null }

    /** The scene in use, by name, kept across app restarts. */
    var active: String?
        get() = kv[KEY_ACTIVE]
        set(value) { kv[KEY_ACTIVE] = value }

    override fun renamed(old: String, new: String) = write(
        _scenes.value.map { s ->
            fun renamed(name: String?) = if (name == old) new else name
            s.copy(
                palette = s.palette?.let { it.copy(name = renamed(it.name)!!) },
                cycle = s.cycle?.let { c -> c.copy(config = c.config.copy(names = c.config.names.map { renamed(it)!! }), current = renamed(c.current)) },
            )
        },
    )

    /** Drops [name] from every scene's Cycle; the undo puts it back in each at its old place. */
    override fun removed(name: String): (String) -> Unit {
        val places = _scenes.value.associate { it.name to (it.cycle?.config?.names?.indexOf(name) ?: -1) }.filterValues { it >= 0 }
        if (places.isEmpty()) return {}
        write(_scenes.value.map { s -> s.withCycleNames { it - name } })
        return { back ->
            write(_scenes.value.map { s ->
                val index = places[s.name] ?: return@map s
                s.withCycleNames { names -> if (back in names) names else names.toMutableList().apply { add(index.coerceIn(0, size), back) } }
            })
        }
    }

    private fun Scene.withCycleNames(change: (List<String>) -> List<String>) =
        copy(cycle = cycle?.let { c -> c.copy(config = c.config.copy(names = change(c.config.names))) })

    private fun uniqueName(wanted: String, except: String? = null): String {
        val base = PaletteCodec.sanitize(wanted).ifBlank { "Scene" }
        return PaletteCodec.unique(base, _scenes.value.map { it.name }.toSet() - setOfNotNull(except))
    }

    private fun write(list: List<Scene>) {
        _scenes.value = list
        kv[KEY] = SceneCodec.encode(list)
    }

    private companion object {
        const val KEY = "saved"
        const val KEY_ACTIVE = "active"
        const val KEY_SEEDED = "seeded"
    }
}

/**
 * One scene per line, in fields split by `|`:
 * `name|brightness|level|palette name|h,s,v|h,s,v|h,s,v|h,s,v|interval|shuffle|cycle names|running|current|ms left|transition`.
 * The palette fields are empty for the default colours and the cycle fields when there is no cycle, as
 * are current and ms left when unknown. The cycle's settings are kept as [CycleConfigCodec] keeps them,
 * in the same order with the transition moved to the end. Earlier lines still read: 3 or 8 fields without a cycle, 11
 * with one saved only while it ran, 12 without where it was, and 14 without the transition, which
 * takes the default.
 */
object SceneCodec {
    private const val FIELDS = 15

    fun encode(list: List<Scene>): String = list.joinToString("\n") { s ->
        val palette = s.palette?.let { PaletteCodec.encode(listOf(it)) } ?: "|".repeat(LightPalette.SIZE)
        val cycle = s.cycle?.let { c ->
            val (interval, shuffle, names, transition) = CycleConfigCodec.fields(c.config)
            "$interval|$shuffle|$names|${c.running}|${c.current.orEmpty()}|${c.remainingMs ?: ""}|$transition"
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
            val config = CycleConfigCodec.parse(parts[8], parts[9], parts[10], parts.getOrNull(14)) ?: return@mapNotNull null
            // 11-field lines only kept a cycle that was running.
            val running = parts.size == 11 || parts[11].toBoolean()
            val current = parts.getOrNull(12)?.ifEmpty { null }
            val remaining = parts.getOrNull(13)?.toLongOrNull()?.coerceAtLeast(0)
            CycleSnapshot(config, running, current, remaining)
        } else null
        Scene(parts[0], brightness, level, palette, cycle)
    }.toList()
}
