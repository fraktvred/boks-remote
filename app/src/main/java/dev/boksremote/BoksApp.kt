package dev.boksremote

import android.app.Application
import android.content.Intent
import android.util.Log
import dev.boksremote.ble.Scanner
import dev.boksremote.dmx.DmxBridge
import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxConfigStore
import dev.boksremote.protocol.Advertisement
import dev.boksremote.speaker.Clock
import dev.boksremote.speaker.CycleConfigStore
import dev.boksremote.speaker.EqPresetStore
import dev.boksremote.speaker.PaletteCycle
import dev.boksremote.speaker.PaletteLibrary
import dev.boksremote.speaker.PincodeStore
import dev.boksremote.speaker.PrefsKeyValue
import dev.boksremote.speaker.SceneSession
import dev.boksremote.speaker.SceneStore
import dev.boksremote.speaker.SleepFade
import dev.boksremote.speaker.SpeakerController
import dev.boksremote.speaker.WakeLockKeepAwake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the connection, the scene in use, the DMX bridge, Cycle and Sleep fade. They live here rather than in a
 * ViewModel so they keep running when the activity is closed; [LightSessionService] keeps the
 * process in the foreground meanwhile.
 */
class BoksApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val scanner by lazy { Scanner(this) }
    private val pincodes by lazy { PincodeStore(this) }
    val palettes by lazy { PaletteLibrary(PrefsKeyValue(this, "palettes")) }
    val scenes by lazy { SceneStore(PrefsKeyValue(this, "scenes")) }
    val eqPresets by lazy { EqPresetStore(this) }
    val bridge by lazy { DmxBridge(this, scope) }
    val cycle by lazy { PaletteCycle(scope, palettes, CycleConfigStore(PrefsKeyValue(this, "cycle")), Clock.ELAPSED, WakeLockKeepAwake(this, "BoksRemote:cycle")) }
    /** The scene in use, kept true to the light. */
    val session by lazy { SceneSession(scope, speaker, scenes, cycle, sleep) }
    val sleep by lazy { SleepFade(scope, Clock.ELAPSED, WakeLockKeepAwake(this, "BoksRemote:sleep")) }
    private val dmxConfigStore by lazy { DmxConfigStore(this) }

    private val _speaker = MutableStateFlow<SpeakerController?>(null)
    val speaker: StateFlow<SpeakerController?> = _speaker

    private val _dmxConfig by lazy { MutableStateFlow(dmxConfigStore.load()) }
    // A getter: SharedPreferences are not readable yet while the Application is being constructed.
    val dmxConfig: StateFlow<DmxConfig> get() = _dmxConfig

    override fun onCreate() {
        super.onCreate()
        // Scenes name palettes too, so they follow a rename or delete; Cycle signs itself up.
        palettes.register(scenes)
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
        session // Starts keeping the scene in use, and puts it back on the light at the next connection.
    }

    fun open(ad: Advertisement) {
        scanner.stop()
        close()
        val controller = SpeakerController(this, scanner.device(ad.address), ad, scope, pincodes)
        session.connecting()
        _speaker.value = controller
        controller.connect()
    }

    fun close() {
        // Keeps the scene in use as the light was left, before anything below changes it.
        session.leave()
        stopLightSession(restoreScene = false)
        _speaker.value?.disconnect()
        _speaker.value = null
    }

    fun updateDmxConfig(config: DmxConfig) {
        _dmxConfig.value = config
        dmxConfigStore.save(config)
        // Live re-patch: the bridge keeps the light link and only re-opens its socket if it must.
        if (bridge.status.value.running) bridge.reconfigure(config)
    }

    fun startBridge() {
        val light = _speaker.value ?: return
        session.lend()
        bridge.start(_dmxConfig.value, light)
    }

    /** [restoreScene]: the scene in use goes back on the light the bridge only borrowed; false when the light is being left. */
    fun stopBridge(restoreScene: Boolean = true) {
        if (!bridge.status.value.running) return
        bridge.stop()
        session.giveBack(reapply = restoreScene)
    }

    /** Stops whatever drives the light from the background: the bridge, Cycle and Sleep fade. */
    fun stopLightSession(restoreScene: Boolean = true) {
        stopBridge(restoreScene)
        cycle.stop()
        sleep.stop()
    }
}
