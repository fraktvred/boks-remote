package dev.boksremote.speaker

import dev.boksremote.proto.ProtoMessage
import dev.boksremote.proto.ProtoWriter
import dev.boksremote.proto.proto
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.roundToInt

/** One palette colour in HSV: hue 0..360, saturation and value 0..1. */
data class HsvColor(val hue: Float, val saturation: Float, val value: Float) {
    /** ARGB int for drawing. */
    fun toArgb(): Int = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))

    companion object {
        /** Pure-Kotlin RGB→HSV so palette parsing stays testable off-device. */
        fun fromRgb(r: Int, g: Int, b: Int): HsvColor {
            val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
            val max = maxOf(rf, gf, bf); val min = minOf(rf, gf, bf); val d = max - min
            val hue = when {
                d == 0f -> 0f
                max == rf -> 60f * (((gf - bf) / d) % 6f)
                max == gf -> 60f * ((bf - rf) / d + 2f)
                else -> 60f * ((rf - gf) / d + 4f)
            }.let { if (it < 0) it + 360f else it }
            return HsvColor(hue, if (max == 0f) 0f else d / max, max)
        }

        fun fromArgb(argb: Int): HsvColor {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(argb, hsv)
            return HsvColor(hsv[0], hsv[1], hsv[2])
        }
    }
}

/**
 * A Lightboks palette: three main colours followed by the effect colour (strobe/sparkle accents),
 * the order the light expects.
 */
data class LightPalette(val name: String, val colors: List<HsvColor>) {
    init { require(colors.size == SIZE) { "a palette has $SIZE colours" } }

    companion object {
        const val SIZE = 4

        private fun hsv(h: Int, s: Int, v: Int) = HsvColor(h.toFloat(), s / 100f, v / 100f)

        /** Starting points written for Boks Remote; the user can edit any colour. */
        val PRESETS = listOf(
            LightPalette("Sunset", listOf(hsv(18, 95, 100), hsv(335, 85, 100), hsv(275, 80, 90), hsv(48, 90, 100))),
            LightPalette("Ocean", listOf(hsv(195, 100, 100), hsv(220, 90, 95), hsv(170, 85, 90), hsv(0, 0, 100))),
            LightPalette("Forest", listOf(hsv(110, 90, 85), hsv(80, 85, 90), hsv(150, 80, 70), hsv(55, 80, 100))),
            LightPalette("Fire", listOf(hsv(8, 100, 100), hsv(25, 100, 100), hsv(45, 100, 100), hsv(0, 0, 100))),
            LightPalette("Neon", listOf(hsv(300, 100, 100), hsv(180, 100, 100), hsv(90, 100, 100), hsv(60, 100, 100))),
            LightPalette("Ice", listOf(hsv(200, 30, 100), hsv(210, 60, 100), hsv(190, 15, 100), hsv(0, 0, 100))),
            LightPalette("Aurora", listOf(hsv(150, 90, 95), hsv(185, 85, 95), hsv(285, 70, 90), hsv(120, 60, 100))),
            LightPalette("Candy", listOf(hsv(330, 60, 100), hsv(190, 55, 100), hsv(280, 45, 100), hsv(55, 55, 100))),
            LightPalette("Ultraviolet", listOf(hsv(265, 100, 100), hsv(290, 95, 90), hsv(240, 100, 80), hsv(320, 80, 100))),
        )
    }
}

/** Built-in FX-pad animations, by the firmware's animation ids. Held while the pad is pressed. */
enum class LightFx(val label: String, val id: UUID) {
    STROBE("Strobe", UUID.fromString("1eebe87a-8483-4f2f-8809-0670feaec3f5")),
    BLACKOUT("Blackout", UUID.fromString("dfd91ef1-3123-43a8-9ef6-d4ed6e22769c")),
    CHASE("Chase", UUID.fromString("50f273e0-d698-4e7d-9806-9981ef4bf48c")),
    SPARKLE("Sparkle", UUID.fromString("84e60a65-ee1b-433d-bd59-07734d4718d7")),
}

/** Protobuf shapes for LightContentService. */
object LightContentCodec {
    val NO_ID: UUID = UUID(0, 0)
    private const val CONTROL_SPECIFIC = 1

    fun uuidBytes(id: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()

    /** SetAnimationRequest{uuid: UUID{value}}. */
    fun animationRequest(id: UUID): ByteArray = proto { message(1) { bytes(1, uuidBytes(id)) } }

    /** SetCustomProgramRequest{program: CustomProgram{uuid: UUID{value}}}: one level deeper than an animation. */
    fun customProgramRequest(id: UUID): ByteArray = proto { message(1) { message(1) { bytes(1, uuidBytes(id)) } } }

    /**
     * ActiveColorPalette{control_type: SPECIFIC_UUID, palette: {uuid, name, representation: RGB×4, engine_colors: HSI×4}}.
     * Engine colours are HSV with saturation and value scaled to 0..255; the RGB copy is what apps display.
     */
    fun palette(p: LightPalette, id: UUID): ByteArray = proto {
        int32(1, CONTROL_SPECIFIC)
        message(2) {
            message(2) { bytes(1, uuidBytes(id)) }
            string(3, p.name)
            message(4) {
                for (c in p.colors) {
                    val argb = c.toArgb()
                    message(1) { uint32(1, (argb shr 16) and 0xFF); uint32(2, (argb shr 8) and 0xFF); uint32(3, argb and 0xFF) }
                }
            }
            message(5) {
                for (c in p.colors) message(1) { engineColor(c) }
            }
        }
    }

    private fun ProtoWriter.engineColor(c: HsvColor) {
        uint32(1, c.hue.roundToInt().coerceIn(0, 360))
        uint32(2, (c.saturation * 255).roundToInt().coerceIn(0, 255))
        uint32(3, (c.value * 255).roundToInt().coerceIn(0, 255))
    }

    /**
     * Parses GetActivePalette; null when the light runs its default (AUTO) colours or the message is incomplete.
     * Colours come from the RGB representation, which is the displayable copy: the firmware's engine values use
     * a different intensity scale (the official presets store intensity 50 for full colours), so converting them
     * back would misrepresent what the light shows.
     */
    fun parsePalette(m: ProtoMessage): LightPalette? {
        if (m.int(1) != CONTROL_SPECIFIC) return null
        val palette = m.message(2) ?: return null
        val rgb = palette.message(4)?.all(1)?.map { it.message } ?: return null
        if (rgb.size != LightPalette.SIZE) return null
        val colors = rgb.map { HsvColor.fromRgb(it.int(1), it.int(2), it.int(3)) }
        return LightPalette(palette.string(3).ifBlank { "Custom" }, colors)
    }
}
