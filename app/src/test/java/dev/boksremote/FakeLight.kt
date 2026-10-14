package dev.boksremote

import dev.boksremote.speaker.ConnectionState
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.Light
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.SpeakerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * A Lightboks that takes every write at once and records it in [writes]: a palette by name,
 * "default" for the light's own colours, "fade:name" for a fade's start and "off". While [refusing],
 * light mode and palette writes fail the way Android refuses them when busy: recorded as "refused",
 * and the light stays as it was.
 */
class FakeLight(
    connected: Boolean = true,
    brightness: Int = 200,
    level: EnergyLevel = EnergyLevel.MEDIUM,
) : Light {
    override val state = MutableStateFlow(
        SpeakerState(
            connection = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
            brightness = brightness,
            energyLevel = level,
            paletteIsDefault = true,
        ),
    )
    override var dropping = false
    var refusing = false
    val writes = mutableListOf<String>()

    fun connect(connected: Boolean = true) =
        state.update { it.copy(connection = if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED) }

    override fun setPalette(palette: LightPalette) {
        if (refusing) return run { writes += "refused" }
        writes += palette.name
        state.update { it.copy(palette = palette, paletteIsDefault = false) }
    }

    override fun resetPalette() {
        if (refusing) return run { writes += "refused" }
        writes += "default"
        state.update { it.copy(palette = null, paletteIsDefault = true) }
    }

    override suspend fun fadePalette(from: LightPalette, to: LightPalette, durationMs: Long) {
        writes += "fade:${to.name}"
        state.update { it.copy(palette = to, paletteIsDefault = false) }
        delay(durationMs)
    }

    override fun setBrightness(value: Int) = state.update { it.copy(brightness = value) }

    override fun setEnergyLevel(level: EnergyLevel) {
        if (refusing) return run { writes += "refused" }
        state.update { it.copy(energyLevel = level) }
    }

    override fun powerOff() {
        writes += "off"
    }
}
