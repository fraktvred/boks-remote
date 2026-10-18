package dev.boksremote

import dev.boksremote.protocol.Family
import dev.boksremote.speaker.CycleStatus
import dev.boksremote.ui.DeviceView
import dev.boksremote.ui.StandIn
import dev.boksremote.ui.paletteName
import dev.boksremote.ui.title
import kotlin.math.roundToInt

/** Which device a row of the remote stands for: the open one, or the user's scene light beside it. */
enum class RemoteTarget { OPEN, SCENE_LIGHT }

/** A button of the remote: a step down, a toggle in the middle, a step up. */
enum class RemoteButton(val icon: Int, val label: String) {
    VOLUME_DOWN(R.drawable.ic_remote_down, "Volume down"),
    MUTE(R.drawable.ic_remote_mute, "Mute"),
    UNMUTE(R.drawable.ic_remote_unmute, "Unmute"),
    VOLUME_UP(R.drawable.ic_remote_up, "Volume up"),
    DIM(R.drawable.ic_remote_down, "Dimmer"),
    PAUSE_CYCLE(R.drawable.ic_remote_pause, "Pause Cycle"),
    PLAY_CYCLE(R.drawable.ic_remote_play, "Play Cycle"),
    BRIGHTEN(R.drawable.ic_remote_up, "Brighter"),
}

/** One device in the remote notification: what it is doing, and its buttons, none while it can't take them. */
data class RemoteRow(val target: RemoteTarget, val name: String, val status: String, val buttons: List<RemoteButton>)

/**
 * [view]'s device as a row of the remote. [bridge] says what the DMX bridge is doing while it has the
 * light; Cycle is offered on the scene light only, where it can play or already does.
 */
internal fun remoteRow(target: RemoteTarget, view: DeviceView, cycle: CycleStatus, sceneLight: Boolean, bridge: String): RemoteRow {
    val s = view.state
    val name = s.title
    fun row(status: String, buttons: List<RemoteButton> = emptyList()) = RemoteRow(target, name, status, buttons)
    when (view.standIn) {
        StandIn.CONNECTING -> return row("Connecting…")
        StandIn.FAILED -> return row("Couldn't connect")
        StandIn.DISCONNECTED -> return row("Disconnected")
        StandIn.PINCODE -> return row("Locked with a pincode")
        StandIn.LOCKED -> return row("Anti-theft locked")
        null -> {}
    }
    if (view.leased) return row(bridge)
    if (s.family == Family.LIGHT) {
        val brightness = s.brightness ?: return row(s.palette?.let { s.paletteName } ?: "Connected")
        val status = listOfNotNull(s.palette?.let { s.paletteName }, percent(brightness)).joinToString(" · ")
        val cycleButton = when {
            !sceneLight -> null
            cycle.running -> RemoteButton.PAUSE_CYCLE
            view.cycle == null -> RemoteButton.PLAY_CYCLE
            else -> null
        }
        return row(status, listOfNotNull(RemoteButton.DIM, cycleButton, RemoteButton.BRIGHTEN))
    }
    val volume = s.volume ?: return row("Connected")
    val mute = if (volume == 0) RemoteButton.UNMUTE else RemoteButton.MUTE
    return row(if (volume == 0) "Muted" else "Volume ${percent(volume)}", listOf(RemoteButton.VOLUME_DOWN, mute, RemoteButton.VOLUME_UP))
}

private fun percent(level: Int) = "${(level * 100 / 255f).roundToInt()}%"

/**
 * A 0..255 [level] one [stepPercent] step up or down, landing on a multiple of the step so the shown
 * percentage moves in round numbers.
 */
fun stepLevel(level: Int, up: Boolean, stepPercent: Int): Int {
    val percent = (level * 100 / 255f).roundToInt()
    val steps = if (up) percent / stepPercent + 1 else (percent + stepPercent - 1) / stepPercent - 1
    return ((steps * stepPercent).coerceIn(0, 100) * 255 / 100f).roundToInt()
}
