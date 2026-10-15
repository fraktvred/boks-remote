package dev.boksremote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.boksremote.DevicePanel
import dev.boksremote.protocol.Family
import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.CycleStatus
import dev.boksremote.speaker.EqCurve
import dev.boksremote.speaker.EqMode
import dev.boksremote.speaker.EqPreset
import dev.boksremote.speaker.Feature
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.SpeakerState
import dev.boksremote.speaker.StageStatus
import dev.boksremote.speaker.Unavailable

/** What stands in for a device's controls, the most pressing first. */
internal enum class StandIn { CONNECTING, FAILED, DISCONNECTED, PINCODE, LOCKED }

/** How the scene light's look is offered: in full, its mode alone, or not at all. */
internal enum class LookControls { FULL, MODE, NONE }

/**
 * What a device's screen and its quick settings show, decided once from the device, its Stage and
 * whether it is the scene light, so the two never disagree. See [deviceView].
 */
internal data class DeviceView(
    val state: SpeakerState,
    /** What stands in for the controls; null shows them. */
    val standIn: StandIn?,
    /** The DMX bridge has this light: it drives every channel, so the light's own controls would only fight it. */
    val leased: Boolean,
    /** Brightness and mode, in the light's own controls; NONE while leased. */
    val look: LookControls,
    /** Palettes, scenes, Cycle, Sleep fade and effects; hidden while leased. */
    val lightContent: Boolean,
    /** The DMX bridge, which stays reachable while it has the light so it can be stopped. */
    val dmx: Boolean,
    val eq: Boolean,
    /** When Cycle next changes this light's palette; null unless it plays on this light. */
    val cycleNextAt: Long?,
    /** Why Cycle can't start on the Stage now; null when it can. */
    val cycle: Unavailable?,
    /** Why Sleep fade can't start on the Stage now; null when it can. */
    val sleep: Unavailable?,
)

/**
 * [s] as its screens show it, given its Stage's [stage] status and [cycle], and whether it is that
 * Stage's scene light. Only the scene light can be leased, and only on it does Cycle play.
 */
internal fun deviceView(s: SpeakerState, stage: StageStatus, sceneLight: Boolean, cycle: CycleStatus): DeviceView {
    val leased = stage.leased && sceneLight
    val content = Feature.LIGHT_CONTENT in s.features
    return DeviceView(
        state = s,
        standIn = when {
            s.connection == ConnectionState.CONNECTING -> StandIn.CONNECTING
            s.connection == ConnectionState.FAILED -> StandIn.FAILED
            s.connection != ConnectionState.CONNECTED -> StandIn.DISCONNECTED
            s.pincodeRequired -> StandIn.PINCODE
            s.locked -> StandIn.LOCKED
            else -> null
        },
        leased = leased,
        look = when {
            leased -> LookControls.NONE
            s.brightness != null -> LookControls.FULL
            s.energyLevel != null -> LookControls.MODE
            else -> LookControls.NONE
        },
        lightContent = content && !leased,
        dmx = content,
        eq = Feature.EQ in s.features,
        cycleNextAt = (cycle as? CycleStatus.Running)?.nextAt?.takeIf { sceneLight },
        cycle = stage.cycle,
        sleep = stage.sleep,
    )
}

/** [panel]'s device as its screens show it, kept up to date. */
@Composable
internal fun rememberDeviceView(panel: DevicePanel): DeviceView {
    val s by panel.device.state.collectAsState()
    val stage by panel.stage.status.collectAsState()
    val sceneLight by panel.stage.light.collectAsState()
    val cycle by panel.stage.cycle.status.collectAsState()
    return deviceView(s, stage, sceneLight === panel.device, cycle)
}

/** Why [what] can't start now, as a sentence under its controls. */
internal fun Unavailable.hint(what: String): String = when (this) {
    Unavailable.NO_LIGHT -> "Connect a Lightboks to use $what."
    Unavailable.UNSUPPORTED -> "This Lightboks can't run $what."
    Unavailable.LEASED -> "${what.replaceFirstChar(Char::uppercase)} waits while the DMX bridge runs."
    Unavailable.TOO_FEW_PALETTES -> "Add at least two palettes to cycle through them."
}

/** The palette on the light; null while it runs its default colours, which it picks itself. */
internal val SpeakerState.shownPalette: LightPalette? get() = palette?.takeUnless { paletteIsDefault }

/** The name of the palette on the light, its defaults included. */
internal val SpeakerState.paletteName: String get() = shownPalette?.name ?: "Lightboks default"

/** What the connected device is called in sentences: the Lightboks, or a speaker. */
internal val SpeakerState.kind: String get() = if (family == Family.LIGHT) "Lightboks" else "speaker"

/** The device's name, or what it is when it has none. */
internal val SpeakerState.title: String get() = shownName(name) ?: model?.label ?: kind.replaceFirstChar(Char::uppercase)

/** The device as a sentence names it: its name, or "the speaker". */
internal val SpeakerState.inSentence: String get() = shownName(name) ?: model?.label ?: "the $kind"

/** The saved EQ the speaker plays: its custom curve when that matches one of [presets]. */
internal fun activePreset(s: SpeakerState, presets: List<EqPreset>): EqPreset? =
    if (s.eq == EqMode.USER) presets.firstOrNull { it.bands == (s.eqBands ?: EqCurve.FLAT) } else null

/** In place of a leased light's own controls. */
internal const val LEASED_NOTE = "The DMX bridge has the light. Its colours, brightness, scenes, Cycle and Sleep fade come back when the bridge stops."
