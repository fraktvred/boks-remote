package dev.boksremote.speaker

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

/** Why Cycle or Sleep fade can't start now. */
enum class Unavailable(val label: String) {
    NO_LIGHT("No Lightboks"),
    UNSUPPORTED("Not supported by this Lightboks"),
    LEASED("DMX bridge on"),
    TOO_FEW_PALETTES("Add at least two palettes"),
}

/** What the Stage lets happen now; null where nothing stands in the way. */
data class StageStatus(
    val leased: Boolean = false,
    val cycle: Unavailable? = Unavailable.NO_LIGHT,
    val sleep: Unavailable? = Unavailable.NO_LIGHT,
)

/** Cycle as the screens see it: where it is, and its settings, which don't write to the light. */
interface CycleView {
    val config: StateFlow<CycleConfig>
    val status: StateFlow<CycleStatus>
    val largePreviews: StateFlow<Boolean>
    fun palettes(): List<LightPalette>
    fun position(): Int
    fun setLargePreviews(on: Boolean)
    fun update(config: CycleConfig)
}

/** The scene in use as the screens see it, and the scenes library edits that don't write to the light. */
interface SceneView {
    val active: StateFlow<String?>
    val live: StateFlow<Scene?>
    fun save(name: String)
    fun rename(scene: Scene, name: String)
    fun delete(scene: Scene)
}

/** A palette editor's session on one light: previews, then cancelling puts back what was there. */
interface PalettePreview {
    fun show(colors: List<HsvColor>)
    fun cancel()
    /** Leaves [palette] on the light as the user's own pick. */
    fun keep(palette: LightPalette)
    /** New colours for the palette the light already shows. */
    fun refresh(palette: LightPalette)
}

/**
 * What the scene light shows and who drives each of its channels: palette (the user or Cycle),
 * brightness (the user or Sleep fade) and mode (the user), unless a [Lease] gives them all to the
 * DMX bridge. Everything that writes to the scene light goes through here, so a write by hand on a
 * channel stops whoever drove it, and nothing writes while the light is leased. Other lights are
 * written to directly through [control].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Stage(
    private val scope: CoroutineScope,
    library: PaletteLibrary,
    scenes: SceneStore,
    cycleStore: CycleConfigStore,
    clock: Clock = Clock.ELAPSED,
    cycleAwake: KeepAwake = KeepAwake.NONE,
    sleepAwake: KeepAwake = KeepAwake.NONE,
) {
    private val _light = MutableStateFlow<Light?>(null)
    /** The scene light; null while there is none. */
    val light: StateFlow<Light?> = _light

    private val palettes = PaletteCycle(scope, library, cycleStore, clock, cycleAwake)
    private val fade = SleepFade(scope, clock, sleepAwake)
    private val session = SceneSession(scope, _light, scenes, palettes, fade, clock)
    private val _lease = MutableStateFlow<Lease?>(null)

    val cycle: CycleView = palettes
    val scenes: SceneView = session
    val sleep: StateFlow<SleepStatus> = fade.status

    val status: StateFlow<StageStatus> = combine(
        _light.flatMapLatest { it?.state ?: flowOf(null) },
        _lease,
        palettes.config,
        library.palettes,
    ) { s, lease, _, _ ->
        val connected = s?.connection == ConnectionState.CONNECTED
        val blocked = when {
            lease != null -> Unavailable.LEASED
            !connected -> Unavailable.NO_LIGHT
            else -> null
        }
        StageStatus(
            leased = lease != null,
            cycle = blocked ?: when {
                Feature.LIGHT_CONTENT !in s?.features.orEmpty() -> Unavailable.UNSUPPORTED
                palettes.palettes().size < CycleConfig.MIN_PALETTES -> Unavailable.TOO_FEW_PALETTES
                else -> null
            },
            sleep = blocked,
        )
    }.stateIn(scope, SharingStarted.Eagerly, StageStatus())

    /**
     * Makes [light] the scene light. The scene in use is saved as the old light was left; Cycle and
     * Sleep fade, which drove it, stop, and its lease ends. The scene in use goes back on the new
     * light once it has reported its state.
     */
    fun attach(light: Light?) {
        val old = _light.value
        if (light === old) return
        if (old != null) {
            session.leave()
            palettes.stop()
            fade.stop()
            _lease.value?.let { it.finish(restore = false); it.onEnd() }
        }
        if (light != null) session.connecting()
        _light.value = light
    }

    /** The user's controls for [light]: through the Stage for the scene light, straight to the light for any other. */
    fun control(light: Light): LightControl = LightControl(light)

    inner class LightControl internal constructor(private val light: Light) {
        /** The scene light and not leased: the Stage's to drive. Null when the scene light is leased. */
        private fun scene(): Boolean? = when {
            _light.value !== light -> false
            _lease.value != null -> null
            else -> true
        }

        /** [palette] as the user's own pick, or the light's default colours for null; it wins over Cycle. */
        fun showPalette(palette: LightPalette?) {
            when (scene()) {
                true -> palettes.showByHand(light, palette)
                false -> light.show(palette)
                null -> {}
            }
        }

        /** New colours for the palette the light already shows; Cycle, if running, isn't interrupted. */
        fun updateShown(palette: LightPalette) {
            if (scene() != null) light.setPalette(palette)
        }

        fun preview(): PalettePreview = when (scene()) {
            true -> palettes.preview(light)
            false -> DirectPreview(light)
            null -> NoPreview
        }

        /** The user's own level, which stops a Sleep fade on the scene light. */
        fun setBrightness(value: Int) {
            when (scene()) {
                true -> session.setBrightness(value)
                false -> light.setBrightness(value)
                null -> {}
            }
        }

        fun setMode(level: EnergyLevel) {
            if (scene() != null) light.setEnergyLevel(level)
        }

        /** Not a channel, so it goes through a lease too; on the scene light, Cycle and Sleep fade stop first. */
        fun powerOff() {
            if (_light.value === light) {
                palettes.stop()
                fade.stop()
            }
            light.powerOff()
        }
    }

    /** Start, or Resume from where it paused. */
    fun startCycle() {
        val light = _light.value ?: return
        palettes.start(light)
    }

    fun holdCycle() = palettes.hold()
    fun skipCycle() = palettes.skip()
    fun stopCycle() = palettes.stop()

    fun jumpTo(name: String) {
        val light = _light.value ?: return
        palettes.jumpTo(light, name)
    }

    /** Ignored while the light is leased: the bridge drives every channel. */
    fun applyScene(scene: Scene) {
        if (_lease.value != null) return
        session.apply(scene)
    }

    fun startSleep(minutes: Int) {
        val light = _light.value ?: return
        if (_lease.value != null) return
        fade.start(light, minutes)
    }

    fun stopSleep() = fade.stop()

    /**
     * Lends the scene light to the DMX bridge: Cycle pauses and Sleep fade stops until the [Lease] ends.
     * [onEnd] hears of the Stage ending it, when the light stops being the scene light.
     */
    fun lend(onEnd: () -> Unit = {}): Lease? {
        val light = _light.value ?: return null
        _lease.value?.end(restore = false)
        session.lend()
        return Lease(light, onEnd).also { _lease.value = it }
    }

    inner class Lease internal constructor(val light: Light, internal val onEnd: () -> Unit) {
        /** An effect started through [output] and not yet stopped. */
        private var fx = false

        /** The light's own writes while the lease lasts; once it has ended they go nowhere. */
        val output: LightOutput = object : LightOutput {
            override suspend fun applyFx(id: UUID) = ifLeased {
                light.output.applyFx(id)
                fx = id != LightContentCodec.NO_ID
            }
            override suspend fun applyBrightness(value: Int) = ifLeased { light.output.applyBrightness(value) }
            override suspend fun applyEnergyLevel(level: EnergyLevel) = ifLeased { light.output.applyEnergyLevel(level) }
            override suspend fun applyPalette(palette: LightPalette, id: UUID) = ifLeased { light.output.applyPalette(palette, id) }
        }

        private suspend fun ifLeased(write: suspend () -> Unit) {
            if (_lease.value === this) write()
        }

        /**
         * Gives the light back: [restore] puts the scene in use back on it, Cycle included. An effect the
         * bridge left running stops, since nothing could release it any more. Does nothing once ended,
         * which it also is when the light stops being the scene light.
         */
        fun end(restore: Boolean) = finish(restore)

        internal fun finish(restore: Boolean) {
            if (_lease.value !== this) return
            _lease.value = null
            if (fx) {
                fx = false
                scope.launch {
                    try {
                        light.output.applyFx(LightContentCodec.NO_ID)
                    } catch (e: Exception) {
                        Log.w("Stage", "effect left running", e)
                    }
                }
            }
            session.giveBack(reapply = restore)
        }
    }

    /** A palette editor on a light the Stage doesn't drive: cancelling puts back the palette it found. */
    private class DirectPreview(private val light: Light) : PalettePreview {
        private val before = light.state.value.ownPalette
        private var previewing = false

        override fun show(colors: List<HsvColor>) {
            previewing = true
            light.setPalette(LightPalette("Custom", colors))
        }

        override fun cancel() {
            if (!previewing) return
            previewing = false
            light.show(before)
        }

        override fun keep(palette: LightPalette) {
            previewing = false
            light.show(palette)
        }

        override fun refresh(palette: LightPalette) {
            previewing = false
            light.setPalette(palette)
        }
    }

    /** While the light is leased, an editor changes nothing on it. */
    private object NoPreview : PalettePreview {
        override fun show(colors: List<HsvColor>) {}
        override fun cancel() {}
        override fun keep(palette: LightPalette) {}
        override fun refresh(palette: LightPalette) {}
    }
}
