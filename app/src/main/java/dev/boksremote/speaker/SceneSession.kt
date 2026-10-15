package dev.boksremote.speaker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The scene in use and everything that keeps it true to the light. It is a live save state: what the
 * user changes on the light goes into it once it has held for a moment, so there is nothing to update by
 * hand. What isn't the user's never goes in: an applied scene's own writes, a Sleep fade's brightness,
 * the Drop pad, the light while the DMX bridge has it, and a scene's writes that failed: until the light
 * shows an applied scene, nothing goes into it. The scene comes back with the light on the next
 * connection, and is saved exactly as the light was left when it is switched away from.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SceneSession(
    private val scope: CoroutineScope,
    private val light: StateFlow<Light?>,
    private val scenes: SceneStore,
    private val cycle: PaletteCycle,
    private val sleep: SleepFade,
    private val clock: Clock = Clock.ELAPSED,
) : SceneView {
    private val _active = MutableStateFlow(scenes.active)
    /** The scene in use, by name: applied or saved last. Kept across restarts. */
    override val active: StateFlow<String?> = _active

    private val lightState = light.flatMapLatest { it?.state ?: flowOf(null) }

    /**
     * The scene in use as the light shows it now, which is what it is saved as a moment later; shown in
     * place of the saved scene, a change or a Cycle step appears at once rather than once it is saved.
     * Null while the light isn't the user's to read (see [syncable]) and while an applied scene's own
     * writes are still landing; the saved scene stands in then.
     */
    override val live: StateFlow<Scene?> = combine(
        combine(lightState, cycle.config, cycle.status) { _, _, _ -> },
        combine(cycle.lent, sleep.status.map { it.running }) { _, _ -> },
        scenes.scenes,
        _active,
    ) { _, _, _, _ -> if (settling()) null else syncable()?.second }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** [Clock] time of the last scene applied; its writes are still landing for a moment. */
    private var appliedAt = Long.MIN_VALUE / 2
    /** True from connecting until the scene in use has been put back on the light; nothing is synced meanwhile. */
    private var restorePending = false
    /**
     * True once a Sleep fade has set the brightness, until the user sets it or a scene is applied. The
     * scene in use keeps its own brightness meanwhile, so a fade, or the dark light it ends on, never goes into it.
     */
    private var fadedBrightness = false
    /**
     * The scene last applied, until the light reports its light mode and palette. Writes Android refuses
     * leave the light as it was, and that look must never be saved as the new scene.
     */
    private var applying: Applying? = null
    private var retry: Job? = null
    /** True from connecting until the light has reported its state, while there has never been a scene; see [SceneStore.seeded]. */
    private var seedPending = false

    /** [cycling]: Cycle shows its own palette, so the scene's isn't waited for. */
    private class Applying(val scene: Scene, val cycling: Boolean)

    init {
        // The scene in use follows what the user sets, once it has held for a moment.
        scope.launch {
            combine(lightState, cycle.config, cycle.status, cycle.lent, sleep.status) { s, _, _, _, _ -> s }
                .debounce(SYNC_MS)
                .collect {
                    // A change made just after applying a scene waits out that scene's own writes rather than being lost.
                    val wait = appliedAt + SETTLE_MS - clock.now()
                    if (wait > 0) delay(wait)
                    sync()
                }
        }
        // From the moment a fade starts, the brightness on the light is the fade's, not the user's.
        scope.launch {
            sleep.status.map { it.running }.distinctUntilChanged().collect { if (it) fadedBrightness = true }
        }
        // The scene in use comes back with the light: once it has reported its state, the scene is applied.
        scope.launch {
            lightState.collect { s ->
                if (s != null) applying?.let { if (s.shows(it)) applying = null }
                if (s?.connection != ConnectionState.CONNECTED || s.brightness == null || s.energyLevel == null) return@collect
                // The first Lightboks connected starts out with a scene in use, made from how it is.
                if (seedPending && Feature.LIGHT_CONTENT in s.features) {
                    seedPending = false
                    if (!scenes.seeded) save(FIRST_SCENE)
                }
                if (!restorePending) return@collect
                restorePending = false
                activeScene()?.let(::apply)
            }
        }
    }

    /** A light is being connected; the scene in use goes back on it once it has reported its state. */
    fun connecting() {
        restorePending = _active.value != null
        seedPending = !scenes.seeded
    }

    /** The light is being left: the scene in use keeps it as it was, and stays in use for the next connection. */
    fun leave() {
        sync(exact = true)
        restorePending = false
        applying = null
        retry?.cancel()
    }

    /** The brightness slider: the user's own level, which stops a fade and goes into the scene in use. */
    fun setBrightness(level: Int) {
        val light = light.value ?: return
        sleep.stop()
        fadedBrightness = false
        light.setBrightness(level)
    }

    /** What the light shows now as a scene called [name]; null while there is no connected light. */
    fun capture(name: String): Scene? {
        val s = light.value?.state?.value ?: return null
        if (s.connection != ConnectionState.CONNECTED) return null
        val brightness = s.brightness ?: return null
        val level = s.energyLevel ?: return null
        return Scene(name, brightness, level, s.ownPalette, cycle.snapshot())
    }

    /** Saves what the light shows as a new scene, which becomes the active one. */
    override fun save(name: String) {
        val scene = capture(name) ?: return
        setActive(scenes.add(scene).name)
    }

    override fun rename(scene: Scene, name: String) {
        val renamed = scenes.replace(scene, scene.copy(name = name))
        if (_active.value == scene.name) setActive(renamed.name)
    }

    override fun delete(scene: Scene) {
        scenes.delete(scene)
        if (_active.value == scene.name) setActive(null)
    }

    /**
     * A scene is picked by hand, like a palette, so the fade makes way for it. The scene being left is
     * saved first, Cycle's exact moment included. Cycle goes back to how the new scene froze it,
     * running or not; while the DMX bridge has the light, Cycle keeps it paused either way.
     */
    fun apply(scene: Scene) {
        val light = light.value ?: return
        val leaving = _active.value
        if (leaving != null && leaving != scene.name) sync(exact = true)
        setActive(scene.name)
        appliedAt = clock.now()
        sleep.stop()
        light.setEnergyLevel(scene.level)
        val snapshot = scene.cycle
        // A running Cycle shows its own palette; otherwise the scene's does.
        val cycling = when {
            snapshot == null -> false.also { cycle.showByHand(light, scene.palette) }
            cycle.restore(light, snapshot, showing = scene.palette != null && scene.palette.name == snapshot.current) -> true
            else -> false.also { light.show(scene.palette) }
        }
        light.setBrightness(scene.brightness)
        fadedBrightness = false
        val pending = Applying(scene, cycling).takeUnless { light.state.value.shows(it) }
        applying = pending
        retry?.cancel()
        if (pending != null) retry = scope.launch {
            // Sent once more if the light hasn't taken it by the time it should have, say after a refused write.
            delay(SETTLE_MS)
            if (applying !== pending) return@launch
            light.setEnergyLevel(scene.level)
            if (!cycling) light.show(scene.palette)
            light.setBrightness(scene.brightness)
        }
    }

    private fun SpeakerState.shows(a: Applying) =
        energyLevel == a.scene.level && (a.cycling || ownPalette?.colors == a.scene.palette?.colors)

    /** The DMX bridge takes the light: Cycle waits for Resume, and the fade would fight its brightness. */
    fun lend() {
        cycle.lend()
        sleep.stop()
    }

    /**
     * The bridge only borrowed the light, so the scene in use goes back on it, Cycle included; what
     * DMX left on the light isn't the user's and never goes into the scene. [reapply] false when the
     * light is being left anyway.
     */
    fun giveBack(reapply: Boolean) {
        cycle.giveBack()
        if (reapply) activeScene()?.let(::apply)
    }

    private fun activeScene() = scenes.scenes.value.firstOrNull { it.name == _active.value }

    private fun setActive(name: String?) {
        _active.value = name
        scenes.active = name
    }

    private fun settling() = clock.now() - appliedAt < SETTLE_MS

    /**
     * Writes the light's state into the active scene. Skipped while something other than the user
     * drives the light and while an applied scene's own writes are still landing, so neither is mistaken
     * for a change the user made; after a Sleep fade, only the brightness is left out (see
     * [fadedBrightness]). [exact] also writes when only the time left on Cycle's palette moved, for the
     * moment a scene is left.
     */
    private fun sync(exact: Boolean = false) {
        if (settling()) return
        val (saved, now) = syncable() ?: return
        if (exact || now.steady() != saved.steady()) scenes.replace(saved, now)
    }

    /** The scene in use as saved and as the light is now, unless something other than the user drives the light. */
    private fun syncable(): Pair<Scene, Scene>? {
        val name = _active.value ?: return null
        if (restorePending || applying != null || cycle.lent.value || light.value?.dropping == true) return null
        val saved = scenes.scenes.value.firstOrNull { it.name == name } ?: return null
        val now = capture(name) ?: return null
        return saved to if (fadedBrightness) now.copy(brightness = saved.brightness) else now
    }

    // The time left on Cycle's palette always moves; it alone isn't a change worth a write.
    private fun Scene.steady() = copy(cycle = cycle?.copy(remainingMs = null))

    companion object {
        /** How long the light has to hold still before the active scene takes the change. */
        const val SYNC_MS = 2_000L
        /** After a scene is applied, its own writes land within this; they are not user changes. */
        const val SETTLE_MS = 3_000L
        /** The scene made on first connecting a Lightboks. */
        const val FIRST_SCENE = "Default Scene"
    }
}
