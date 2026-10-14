package dev.boksremote

import android.app.Application
import android.content.Intent
import dev.boksremote.ble.Scanner
import dev.boksremote.dmx.DmxBridge
import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxConfigStore
import dev.boksremote.protocol.Advertisement
import dev.boksremote.speaker.EqPresetStore
import dev.boksremote.speaker.PaletteCycle
import dev.boksremote.speaker.PaletteStore
import dev.boksremote.speaker.PincodeStore
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
 * Process-wide owner of the connection, the DMX bridge and Cycle. They live here rather than in a
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
    private val dmxConfigStore by lazy { DmxConfigStore(this) }

    private val _speaker = MutableStateFlow<SpeakerController?>(null)
    val speaker: StateFlow<SpeakerController?> = _speaker

    private val _dmxConfig by lazy { MutableStateFlow(dmxConfigStore.load()) }
    // A getter: SharedPreferences are not readable yet while the Application is being constructed.
    val dmxConfig: StateFlow<DmxConfig> get() = _dmxConfig

    override fun onCreate() {
        super.onCreate()
        // One foreground service covers both; it runs while either does.
        scope.launch {
            combine(bridge.status, cycle.status) { b, c -> b.running || c.running }
                .distinctUntilChanged()
                .drop(1) // the initial "neither runs"
                .collect { active ->
                    val service = Intent(this@BoksApp, LightSessionService::class.java)
                    if (active) startForegroundService(service) else stopService(service)
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
        // The bridge needs the light to itself; Cycle waits for Resume.
        cycle.pause()
        bridge.start(_dmxConfig.value, light)
    }

    fun stopBridge() {
        if (!bridge.status.value.running) return
        bridge.stop()
    }
}
