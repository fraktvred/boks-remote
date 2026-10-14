package dev.boksremote

import android.app.Application
import android.content.Intent
import android.util.Log
import dev.boksremote.ble.Scanner
import dev.boksremote.dmx.DmxBridge
import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxConfigStore
import dev.boksremote.protocol.Advertisement
import dev.boksremote.speaker.EqPresetStore
import dev.boksremote.speaker.PaletteCycle
import dev.boksremote.speaker.PaletteStore
import dev.boksremote.speaker.PincodeStore
import dev.boksremote.speaker.SleepFade
import dev.boksremote.speaker.SpeakerController
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
 * Process-wide owner of the connection, the DMX bridge, Cycle and Sleep fade. They live here rather than in a
 * ViewModel so they keep running when the activity is closed; [LightSessionService] keeps the
 * process in the foreground meanwhile.
 */
class BoksApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val scanner by lazy { Scanner(this) }
    private val pincodes by lazy { PincodeStore(this) }
    val palettes by lazy { PaletteStore(this) }
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
    }

    fun open(ad: Advertisement) {
        scanner.stop()
        close()
        val controller = SpeakerController(this, scanner.device(ad.address), ad, scope, pincodes)
        _speaker.value = controller
        controller.connect()
    }

    fun close() {
        stopBridge()
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

    fun startBridge() {
        val light = _speaker.value ?: return
        // The bridge needs the light to itself; Cycle waits for Resume, and the fade would fight its brightness.
        cycle.pause()
        sleep.stop()
        bridge.start(_dmxConfig.value, light)
    }

    fun stopBridge() {
        if (!bridge.status.value.running) return
        bridge.stop()
    }
}
