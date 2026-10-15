package dev.boksremote

import android.app.Application
import android.content.Intent
import android.util.Log
import dev.boksremote.ble.GattConnection
import dev.boksremote.ble.Scanner
import dev.boksremote.dmx.DmxBridge
import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxConfigStore
import dev.boksremote.protocol.Advertisement
import dev.boksremote.protocol.Family
import dev.boksremote.speaker.DemoDevice
import dev.boksremote.speaker.KeepAwake
import dev.boksremote.speaker.MemoryKeyValue
import dev.boksremote.speaker.PincodeStore
import dev.boksremote.speaker.PrefsKeyValue
import dev.boksremote.speaker.SpeakerController
import dev.boksremote.speaker.Stage
import dev.boksremote.speaker.WakeLockKeepAwake
import dev.boksremote.speaker.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the device list, the scene in use, the DMX bridge, Cycle and Sleep fade. They live here rather than in a
 * ViewModel so they keep running when the activity is closed; [LightSessionService] keeps the
 * process in the foreground meanwhile.
 */
class BoksApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val scanner by lazy { Scanner(this) }
    private val pincodes by lazy { PincodeStore(PrefsKeyValue(this, "pincodes")) }
    val bridge by lazy { DmxBridge(this, scope) }
    private val dmxConfigStore by lazy { DmxConfigStore(this) }
    /** The user's own scenes, palettes, presets, Cycle and Sleep fade, for the real devices. */
    private val own by lazy { Workspace(scope, { PrefsKeyValue(this, it) }, { WakeLockKeepAwake(this, it) }) }

    private val _bridged = MutableStateFlow<SpeakerController?>(null)
    /** The Lightboks the DMX bridge drives; it stays connected, open or not, until the bridge stops. */
    private val bridged: SpeakerController? get() = _bridged.value
    /** The bridged light, lent by its Stage. */
    private var lease: Stage.Lease? = null

    /** The bridge's light keeps trying for as long as it runs; Cycle's, a while, then Cycle stops. */
    private val held: StateFlow<Map<SpeakerController, Hold>> by lazy {
        combine(_bridged, own.stage.light, own.stage.cycle.status) { bridged, light, cycle ->
            buildMap {
                if (cycle.running && light is SpeakerController) put(light, CYCLE_HOLD)
                if (bridged != null) put(bridged, BRIDGE_HOLD)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    /** The devices nearby and the open one; its scene light is the user's own Stage's. */
    val list by lazy {
        DeviceList(
            scope,
            scanner,
            make = { ad -> SpeakerController(GattConnection(this, scanner.device(ad.address)), ad, scope, pincodes) },
            held = held,
            onSceneLight = own.stage::attach,
        )
    }

    /** The open device. */
    val speaker: StateFlow<SpeakerController?> get() = list.open
    val nearby: StateFlow<Map<String, NearbyDevice<SpeakerController>>> get() = list.nearby

    private val _dmxConfig by lazy { MutableStateFlow(dmxConfigStore.load()) }
    // A getter: SharedPreferences are not readable yet while the Application is being constructed.
    val dmxConfig: StateFlow<DmxConfig> get() = _dmxConfig

    private val _demoMode = MutableStateFlow(BuildConfig.DEBUG)
    /**
     * Demo mode lists a dummy of each device. Not kept across restarts, so neither is anything done in
     * it; a debug build starts with it on.
     */
    val demoMode: StateFlow<Boolean> = _demoMode
    /** Demo mode's dummies and their own set, made when the list first shows a dummy. */
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

    /**
     * The user's own Stage, whatever is open: the one the light-session service, its notification and the
     * Quick Settings tile follow, since a dummy's Cycle and Sleep fade run in the app alone.
     */
    val ownStage get() = own.stage

    override fun onCreate() {
        super.onCreate()
        // One foreground service covers them all; it runs while any of them does. A dummy's run in the app alone.
        scope.launch {
            combine(bridge.status, own.stage.cycle.status, own.stage.sleep) { b, c, s -> b.running || c.running || s.running }
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
        list // Starts following the scan and keeping the list connected.
    }

    /** The app comes to the foreground, or leaves it; see [DeviceList]. */
    fun setForeground(shown: Boolean) = list.setForeground(shown)

    /** The scenes, palettes and presets [device] uses: demo mode's for a dummy, otherwise the user's own. */
    fun workspaceOf(device: SpeakerController): Workspace = if (device.demo) demo?.workspace ?: own else own

    /** What [device]'s screen and quick settings work with. */
    fun panelOf(device: SpeakerController) = DevicePanel(device, workspaceOf(device), object : BridgeControl {
        override val status get() = bridge.status
        override val config get() = dmxConfig
        override val light: StateFlow<SpeakerController?> get() = _bridged
        override fun start() = startBridge(device)
        override fun stop() = stopBridge()
        override fun update(config: DmxConfig) = updateDmxConfig(config)
    })

    fun open(ad: Advertisement) {
        close()
        list.open(ad)
    }

    /** [device]'s dummy as it was left, with demo mode's own scenes, palettes and presets; made the first time. */
    fun demoDevice(device: DemoDevice): SpeakerController {
        val demo = demo ?: Demo(scope).also { demo = it }
        return demo.devices.getOrPut(device) {
            SpeakerController.demo(device, demo.scope, pincodes).also { controller ->
                controller.connect()
                if (device.advert.family == Family.LIGHT) demo.workspace.stage.attach(controller)
            }
        }
    }

    /** A dummy Lightboks becomes the light demo mode's scenes keep true; a speaker leaves that to the one already there. */
    fun openDemo(device: DemoDevice) {
        close()
        val controller = demoDevice(device)
        if (controller.state.value.family == Family.LIGHT) workspaceOf(controller).stage.attach(controller)
        list.open(controller)
    }

    /** Switching demo mode off resets the dummies and everything done with them: the next time, they start afresh. */
    fun setDemoMode(on: Boolean) {
        if (!on) {
            if (list.open.value?.demo == true) close()
            demo?.scope?.cancel()
            demo = null
        }
        _demoMode.value = on
    }

    /**
     * Back to the list, which keeps the device connected for its quick settings while the app is in the
     * foreground. A running DMX bridge or Cycle keeps going, and its light connected; Sleep fade stops.
     */
    fun close() {
        val closing = list.open.value ?: return
        if (bridged !== closing) stopBridge(restoreScene = false)
        workspaceOf(closing).stage.stopSleep()
        list.close()
    }

    private fun updateDmxConfig(config: DmxConfig) {
        _dmxConfig.value = config
        dmxConfigStore.save(config)
        // Live re-patch: the bridge keeps the light link and only re-opens its socket if it must.
        if (bridge.status.value.running) bridge.reconfigure(config)
    }

    /** Starts the bridge on [light], which must be its Stage's scene light, as the open Lightboks is. */
    private fun startBridge(light: SpeakerController) {
        stopBridge()
        // The bridge drives the scene light; it stops if the light stops being it.
        val lease = workspaceOf(light).stage.takeIf { it.light.value === light }?.lend(onEnd = { stopBridge(restoreScene = false) }) ?: return
        this.lease = lease
        _bridged.value = light
        bridge.start(_dmxConfig.value, lease)
    }

    /**
     * [restoreScene]: the scene in use goes back on the light the bridge only borrowed; false when the
     * light is being left. A light the bridge alone held is let go by the list.
     */
    fun stopBridge(restoreScene: Boolean = true) {
        val lease = lease
        this.lease = null
        _bridged.value = null
        if (bridge.status.value.running) bridge.stop()
        // Already ended if the light stopped being the scene light meanwhile.
        lease?.end(restore = restoreScene)
    }

    /** Stops whatever drives the light from the background: the bridge, Cycle and Sleep fade. */
    fun stopLightSession(restoreScene: Boolean = true) {
        stopBridge(restoreScene)
        own.stage.stopCycle()
        own.stage.stopSleep()
    }

    private companion object {
        /** The bridge's light stays the scene light, since its Stage lent it. */
        val BRIDGE_HOLD = Hold(retryForMs = null, sceneLight = true)
        /** Long enough to ride out a moment out of range; a light switched off doesn't keep the phone busy all night. */
        val CYCLE_HOLD = Hold(retryForMs = 5 * 60_000L)
    }
}
