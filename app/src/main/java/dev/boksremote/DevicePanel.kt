package dev.boksremote

import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxStatus
import dev.boksremote.speaker.SpeakerController
import dev.boksremote.speaker.Workspace
import kotlinx.coroutines.flow.StateFlow

/** The DMX bridge as one device's screen sees it: [start] starts it on that device. */
interface BridgeControl {
    val status: StateFlow<DmxStatus>
    val config: StateFlow<DmxConfig>
    /** The light the bridge drives; null while it doesn't run. */
    val light: StateFlow<SpeakerController?>
    fun start()
    fun stop()
    /** A new patch, applied live if the bridge runs. */
    fun update(config: DmxConfig)
}

/**
 * Everything one device's screen and quick settings work with: the device, the Workspace it uses
 * (demo mode's for a dummy, otherwise the user's own), its controls through that Workspace's Stage,
 * and the DMX bridge. See [BoksApp.panelOf].
 */
class DevicePanel(val device: SpeakerController, val workspace: Workspace, val bridge: BridgeControl) {
    val stage get() = workspace.stage
    /** Through the Stage when [device] is its scene light, otherwise straight to it. */
    val control = workspace.stage.control(device)
}
