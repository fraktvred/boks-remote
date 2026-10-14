package dev.boksremote

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.boksremote.ble.Scanner
import dev.boksremote.dmx.DmxBridge
import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxConfigStore
import dev.boksremote.protocol.Advertisement
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.EqPresetStore
import dev.boksremote.speaker.PaletteCycle
import dev.boksremote.speaker.PaletteStore
import dev.boksremote.speaker.PincodeStore
import dev.boksremote.speaker.Scene
import dev.boksremote.speaker.SceneStore
import dev.boksremote.speaker.SleepFade
import dev.boksremote.speaker.SpeakerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the connection, the DMX bridge, Cycle and Sleep fade. They live here rather than in a
 * ViewModel so they keep running when the activity is closed; [LightSessionService] keeps the
 * process in the foreground meanwhile.
 */
class BoksApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val scanner by lazy { Scanner(this) }
    private val pincodes by lazy { PincodeStore(this) }
    val palettes by lazy { PaletteStore(this) }
    val scenes by lazy { SceneStore(this) }
    val eqPresets by lazy { EqPresetStore(this) }
    val bridge by lazy { DmxBridge(this, scope) }
    val cycle by lazy { PaletteCycle(this, scope, palettes) }
    val sleep by lazy { SleepFade(this, scope) }
    private val dmxConfigStore by lazy { DmxConfigStore(this) }

    private val _speaker = MutableStateFlow<SpeakerController?>(null)
    val speaker: StateFlow<SpeakerController?> = _speaker

    private val _dmxConfig by lazy { MutableStateFlow(dmxConfigStore.load()) }
    // A getter: SharedPreferences are not readable yet while the Application is being constructed.
    val dmxConfig: StateFlow<DmxConfig> get() = _dmxConfig

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        // One foreground service covers them all; it runs while any of them does.
        scope.launch {
            combine(bridge.status, cycle.status, sleep.status) { b, c, s -> b.running || c.running || s.running }
                .distinctUntilChanged()
                .drop(1) // the initial "neither runs"
                .collect { active ->
                    val service = Intent(this@BoksApp, LightSessionService::class.java)
                    if (!active) stopService(service)
                    else try {
                        startForegroundService(service)
                    } catch (e: IllegalStateException) {
                        // Started from the background (the Quick Settings tile) where Android may refuse it. It
                        // carries on while the process lives; it just isn't protected with the screen off.
                        Log.w("BoksApp", "light session service not started", e)
                    }
                }
        }
        // The active scene follows what the user sets, once it has held for a moment.
        scope.launch {
            combine(speaker.flatMapLatest { it?.state ?: flowOf(null) }, cycle.config, cycle.status, bridge.status, sleep.status) { s, _, _, _, _ -> s }
                .debounce(SCENE_SYNC_MS)
                .collect {
                    // A change made just after applying a scene waits out that scene's own writes rather than being lost.
                    val wait = appliedAt + SCENE_SETTLE_MS - SystemClock.elapsedRealtime()
                    if (wait > 0) delay(wait)
                    syncActiveScene()
                }
        }
        // From the moment a fade starts, the brightness on the light is the fade's, not the user's.
        scope.launch {
            sleep.status.map { it.running }.distinctUntilChanged().collect { if (it) fadedBrightness = true }
        }
        // The scene in use comes back with the light: once it has reported its state, the scene is applied.
        scope.launch {
            speaker.flatMapLatest { it?.state ?: flowOf(null) }.collect { s ->
                if (!restorePending || s?.connection != ConnectionState.CONNECTED || s.brightness == null || s.energyLevel == null) return@collect
                restorePending = false
                scenes.scenes.value.firstOrNull { it.name == _activeScene.value }?.let(::applyScene)
            }
        }
    }

    fun open(ad: Advertisement) {
        scanner.stop()
        close()
        val controller = SpeakerController(this, scanner.device(ad.address), ad, scope, pincodes)
        restorePending = _activeScene.value != null
        _speaker.value = controller
        controller.connect()
    }

    fun close() {
        // Keeps the scene in use as the light was left, before anything below changes it. It stays in use,
        // and is applied again on the next connection.
        syncActiveScene(exact = true)
        restorePending = false
        stopBridge(restoreScene = false)
        cycle.stop()
        sleep.stop()
        _speaker.value?.disconnect()
        _speaker.value = null
    }

    fun updateDmxConfig(config: DmxConfig) {
        _dmxConfig.value = config
        dmxConfigStore.save(config)
        // Live re-patch: the bridge keeps the light link and only re-opens its socket if it must.
        if (bridge.status.value.running) bridge.reconfigure(config)
    }

    private val _activeScene by lazy { MutableStateFlow(scenes.active) }
    /**
     * The scene in use, by name: applied or saved last. It is a live save state: what the user changes
     * on the light goes into it, so there is nothing to update by hand. Kept across restarts.
     */
    val activeScene: StateFlow<String?> get() = _activeScene
    /**
     * True once a Sleep fade has set the brightness, until the user sets it or a scene is applied. The
     * scene in use keeps its own brightness meanwhile, so a fade, or the dark light it ends on, never goes into it.
     */
    private var fadedBrightness = false

    /** The brightness slider: the user's own level, which stops a fade and goes into the scene in use. */
    fun setBrightness(light: SpeakerController, level: Int) {
        sleep.stop()
        fadedBrightness = false
        light.setBrightness(level)
    }

    /** True from connecting until the scene in use has been put back on the light; nothing is synced meanwhile. */
    private var restorePending = false

    private fun setActiveScene(name: String?) {
        _activeScene.value = name
        scenes.active = name
    }

    /**
     * The scene in use as the light shows it now, which is what it is saved as a moment later; shown in
     * place of the saved scene, a change or a Cycle step appears at once rather than once it is saved.
     * Null while the light isn't the user's to read (see [syncable]) and while an applied scene's own
     * writes are still landing; the saved scene stands in then.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSceneLive: StateFlow<Scene?> by lazy {
        combine(
            combine(speaker.flatMapLatest { it?.state ?: flowOf(null) }, cycle.config, cycle.status) { _, _, _ -> },
            combine(bridge.status.map { it.running }, sleep.status.map { it.running }) { _, _ -> },
            scenes.scenes,
            _activeScene,
        ) { _, _, _, _ -> if (SystemClock.elapsedRealtime() - appliedAt < SCENE_SETTLE_MS) null else syncable()?.second }
            .stateIn(scope, SharingStarted.Eagerly, null)
    }
    /** [SystemClock.elapsedRealtime] of the last scene applied; its writes are still landing for a moment. */
    private var appliedAt = 0L

    /** What the light shows now as a scene called [name]; null while there is no connected Lightboks. */
    fun captureScene(name: String): Scene? {
        val light = _speaker.value ?: return null
        val s = light.state.value
        if (s.connection != ConnectionState.CONNECTED) return null
        val brightness = s.brightness ?: return null
        val level = s.energyLevel ?: return null
        return Scene(name, brightness, level, s.palette?.takeUnless { s.paletteIsDefault }, cycle.snapshot())
    }

    /** Saves what the light shows as a new scene, which becomes the active one. */
    fun saveScene(name: String) {
        val scene = captureScene(name) ?: return
        setActiveScene(scenes.add(scene).name)
    }

    fun renameScene(scene: Scene, name: String) {
        val renamed = scenes.replace(scene, scene.copy(name = name))
        if (_activeScene.value == scene.name) setActiveScene(renamed.name)
    }

    fun deleteScene(scene: Scene) {
        scenes.delete(scene)
        if (_activeScene.value == scene.name) setActiveScene(null)
    }

    /**
     * Writes the light's state into the active scene. Skipped while something other than the user
     * drives the light (the DMX bridge, the Drop pad) and while an applied scene's own writes are still
     * landing, so neither is mistaken for a change the user made; after a Sleep fade, only the
     * brightness is left out (see [fadedBrightness]). [exact] also writes
     * when only the time left on Cycle's palette moved, for the moment a scene is left.
     */
    private fun syncActiveScene(exact: Boolean = false) {
        if (SystemClock.elapsedRealtime() - appliedAt < SCENE_SETTLE_MS) return
        val (saved, now) = syncable() ?: return
        if (exact || now.steady() != saved.steady()) scenes.replace(saved, now)
    }

    /** The scene in use as saved and as the light is now, unless something other than the user drives the light. */
    private fun syncable(): Pair<Scene, Scene>? {
        val name = _activeScene.value ?: return null
        if (restorePending || bridge.status.value.running || _speaker.value?.dropping == true) return null
        val saved = scenes.scenes.value.firstOrNull { it.name == name } ?: return null
        val now = captureScene(name) ?: return null
        return saved to if (fadedBrightness) now.copy(brightness = saved.brightness) else now
    }

    // The time left on Cycle's palette always moves; it alone isn't a change worth a write.
    private fun Scene.steady() = copy(cycle = cycle?.copy(remainingMs = null))

    /**
     * A scene is picked by hand, like a palette, so the fade makes way for it. The scene being left is
     * saved first, Cycle's exact moment included. Cycle goes back to how the new scene froze it,
     * running or not; the DMX bridge keeps it paused either way.
     */
    fun applyScene(scene: Scene) {
        val light = _speaker.value ?: return
        val leaving = _activeScene.value
        if (leaving != null && leaving != scene.name) syncActiveScene(exact = true)
        setActiveScene(scene.name)
        appliedAt = SystemClock.elapsedRealtime()
        sleep.stop()
        light.setEnergyLevel(scene.level)
        val snapshot = scene.cycle?.let { if (bridge.status.value.running) it.copy(running = false) else it }
        val cycling = if (snapshot != null) {
            cycle.restore(light, snapshot, showing = scene.palette != null && scene.palette.name == snapshot.current)
        } else false.also { cycle.pause() }
        // A running Cycle shows its own palette; otherwise the scene's does.
        if (!cycling) {
            if (scene.palette != null) light.setPalette(scene.palette) else light.resetPalette()
        }
        light.setBrightness(scene.brightness)
        fadedBrightness = false
    }

    fun startBridge() {
        val light = _speaker.value ?: return
        // The bridge needs the light to itself; Cycle waits for Resume, and the fade would fight its brightness.
        cycle.pause()
        sleep.stop()
        bridge.start(_dmxConfig.value, light)
    }

    /**
     * The bridge only borrowed the light, so the scene in use goes back on it, Cycle included; what
     * DMX left on the light isn't the user's and never goes into the scene. [restoreScene] false when
     * the light is being left anyway.
     */
    fun stopBridge(restoreScene: Boolean = true) {
        if (!bridge.status.value.running) return
        bridge.stop()
        if (restoreScene) scenes.scenes.value.firstOrNull { it.name == _activeScene.value }?.let(::applyScene)
    }

    private companion object {
        /** How long the light has to hold still before the active scene takes the change. */
        const val SCENE_SYNC_MS = 2_000L
        /** After a scene is applied, its own writes land within this; they are not user changes. */
        const val SCENE_SETTLE_MS = 3_000L
    }
}
