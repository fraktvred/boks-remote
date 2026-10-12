package dev.boksremote.dmx

import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.HsvColor
import dev.boksremote.speaker.LightFx
import dev.boksremote.speaker.LightPalette

/** What the DMX channels ask the light to do. Equal values mean "nothing to send". */
data class LightLook(
    val dimmer: Int,
    val mode: EnergyLevel,
    val colors: List<Rgb>,
    val fx: LightFx?,
) {
    fun palette() = LightPalette("DMX", colors.map { HsvColor.fromRgb(it.r, it.g, it.b) })
}

data class Rgb(val r: Int, val g: Int, val b: Int)

/**
 * DMX channel layouts ("personalities") for the Lightboks, relative to the start address.
 * Ranges are written out in the UI and README so they can be patched on a desk.
 */
enum class LightboksPersonality(val label: String, val footprint: Int) {
    /** Dimmer, mode, one colour used for all four palette slots. */
    SIMPLE("Simple · 5 ch", 5),

    /** Dimmer, mode, four RGB palette colours, FX. */
    FULL("Full · 15 ch", 15);

    fun channels(): List<String> = when (this) {
        SIMPLE -> listOf("Dimmer", "Mode", "Red", "Green", "Blue")
        FULL -> listOf("Dimmer", "Mode") +
            listOf("Main 1", "Main 2", "Main 3", "Effect").flatMap { listOf("$it R", "$it G", "$it B") } +
            "FX"
    }

    fun map(frame: DmxFrame, start: Int): LightLook {
        fun ch(offset: Int) = frame.channel(start + offset)
        fun rgb(at: Int) = Rgb(ch(at), ch(at + 1), ch(at + 2))
        return when (this) {
            SIMPLE -> LightLook(ch(0), mode(ch(1)), List(LightPalette.SIZE) { rgb(2) }, null)
            FULL -> LightLook(ch(0), mode(ch(1)), listOf(rgb(2), rgb(5), rgb(8), rgb(11)), fx(ch(14)))
        }
    }

    companion object {
        /** 0–63 Chill, 64–127 Dance, 128–191 Rave, 192–255 Uplight (static colour). */
        fun mode(v: Int): EnergyLevel = when (v) {
            in 0..63 -> EnergyLevel.LOW
            in 64..127 -> EnergyLevel.MEDIUM
            in 128..191 -> EnergyLevel.HIGH
            else -> EnergyLevel.CUSTOM
        }

        /** 0–31 off, 32–87 Strobe, 88–143 Blackout, 144–199 Chase, 200–255 Sparkle. */
        fun fx(v: Int): LightFx? = when (v) {
            in 0..31 -> null
            in 32..87 -> LightFx.STROBE
            in 88..143 -> LightFx.BLACKOUT
            in 144..199 -> LightFx.CHASE
            else -> LightFx.SPARKLE
        }
    }
}
