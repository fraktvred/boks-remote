package dev.boksremote

import android.app.Application
import android.content.Intent
import android.util.Log
import dev.boksremote.ble.Scanner
import dev.boksremote.dmx.DmxBridge
import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxConfigStore
import dev.boksremote.protocol.Advertisement
import dev.boksremote.speaker.DemoDevice
import dev.boksremote.speaker.KeepAwake
import dev.boksremote.speaker.MemoryKeyValue
import dev.boksremote.speaker.PincodeStore
import dev.boksremote.speaker.PrefsKeyValue
import dev.boksremote.speaker.SpeakerController
import dev.boksremote.speaker.WakeLockKeepAwake
import dev.boksremote.speaker.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
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
    val bridge by lazy { DmxBridge(this, scope) }
    private val dmxConfigStore by lazy { DmxConfigStore(this) }
    /** The user's own scenes, palettes, presets, Cycle and Sleep fade, for the real devices. */
    private val own by lazy { Workspace(scope, { PrefsKeyValue(this, it) }, { WakeLockKeepAwake(this, it) }) }

    private val _speaker = MutableStateFlow<SpeakerController?>(null)
    val speaker: StateFlow<SpeakerController?> = _speaker

    private val _dmxConfig by lazy { MutableStateFlow(dmxConfigStore.load()) }
    // A getter: SharedPreferences are not readable yet while the Application is being constructed.
    val dmxConfig: StateFlow<DmxConfig> get() = _dmxConfig

    private val _demoMode = MutableStateFlow(false)
    /** Demo mode lists a dummy of each device. Not kept across restarts, so neither is anything done in it. */
    val demoMode: StateFlow<Boolean> = _demoMode
    /** Demo mode's dummies and their own set, made when a dummy is first opened. */
    private var demo: Demo? = null

    /**
     * The dummies opened so far and the scenes, palettes and presets they use, which start from the
     * defaults; kept with their changes until demo mode is switched off, and all dropped together then.
     */
    private class Demo(parent: CoroutineScope) {
        val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))
        val workspace = Workspace(scope, { MemoryKeyValue() }, { KeepAwake.NONE })
        val devices = mutableMapOf<DemoDevice, SpeakerController>()
    }

    /** The set the open device uses: demo mode's for a dummy, otherwise the user's own. */
    private val workspace get() = _speaker.value?.takeIf { it.demo }?.let { demo?.workspace } ?: own
    val palettes get() = workspace.palettes
    val scenes get() = workspace.scenes
    val eqPresets get() = workspace.eqPresets
    val cycle get() = workspace.cycle
    val sleep get() = workspace.sleep
    /** The scene in use, kept true to the light. */
    val session get() = workspace.session

    override fun onCreate() {
        super.onCreate()
        // One foreground service covers them all; it runs while any of them does. A dummy's run only while it is open.
        scope.launch {
            combine(bridge.status, own.cycle.status, own.sleep.status) { b, c, s -> b.running || c.running || s.running }
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
        own // Starts keeping the scene in use, and puts it back on the light at the next connection.
    }

    fun open(ad: Advertisement) {
        scanner.stop()
        close()
        show(SpeakerController(this, scanner.device(ad.address), ad, scope, pincodes), own)
    }

    /** Opens [device]'s dummy as it was left, with demo mode's own scenes, palettes and presets. */
    fun openDemo(device: DemoDevice) {
        scanner.stop()
        close()
        val demo = demo ?: Demo(scope).also { demo = it }
        show(demo.devices.getOrPut(device) { SpeakerController.demo(device, demo.scope, pincodes) }, demo.workspace)
    }

    private fun show(controller: SpeakerController, workspace: Workspace) {
        workspace.session.connecting()
        workspace.light.value = controller
        _speaker.value = controller
        controller.connect()
    }

    /** Switching demo mode off resets the dummies and everything done with them: the next time, they start afresh. */
    fun setDemoMode(on: Boolean) {
        if (!on) {
            if (_speaker.value?.demo == true) close()
            demo?.scope?.cancel()
            demo = null
        }
        _demoMode.value = on
    }

    fun close() {
        // Keeps the scene in use as the light was left, before anything below changes it.
        session.leave()
        stopLightSession(restoreScene = false)
        _speaker.value?.disconnect()
        workspace.light.value = null
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
