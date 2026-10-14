package dev.boksremote.speaker

import dev.boksremote.proto.ProtoMessage
import dev.boksremote.proto.ProtoWriter
import dev.boksremote.proto.proto
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/** One palette colour in HSV: hue 0..360, saturation and value 0..1. */
data class HsvColor(val hue: Float, val saturation: Float, val value: Float) {
    /** ARGB int for drawing. Pure Kotlin, like [fromRgb], so colour maths stays testable off-device. */
    fun toArgb(): Int {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val s = saturation.coerceIn(0f, 1f); val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - abs(h % 2f - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun byte(f: Float) = ((f + m) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    /**
     * The colour [t] (0..1) of the way to [to], along the shorter way round the hue circle. A grey has
     * no hue of its own, so it takes the other colour's and only its saturation moves.
     */
    fun blend(to: HsvColor, t: Float): HsvColor {
        val f = t.coerceIn(0f, 1f)
        if (f == 1f) return to
        val from = if (saturation < GREY) copy(hue = to.hue) else this
        val target = if (to.saturation < GREY) to.copy(hue = from.hue) else to
        val turn = ((target.hue - from.hue) % 360f + 540f) % 360f - 180f
        return HsvColor(
            ((from.hue + turn * f) % 360f + 360f) % 360f,
            from.saturation + (target.saturation - from.saturation) * f,
            from.value + (target.value - from.value) * f,
        )
    }

    /** "#RRGGBB". */
    fun hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

    companion object {
        private const val GREY = 0.05f

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

        /** "#RRGGBB" or "RRGGBB"; null if it isn't one. */
        fun fromHex(text: String): HsvColor? {
            val hex = text.trim().removePrefix("#")
            if (hex.length != 6) return null
            val rgb = hex.toIntOrNull(16) ?: return null
            return fromRgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
        }

        fun fromArgb(argb: Int): HsvColor = fromRgb((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
    }
}

/**
 * A Lightboks palette: three main colours followed by the effect colour (strobe/sparkle accents),
 * the order the light expects.
 */
data class LightPalette(val name: String, val colors: List<HsvColor>) {
    init { require(colors.size == SIZE) { "a palette has $SIZE colours" } }

    /** Colour by colour, [t] of the way to [to]; it carries [to]'s name, being on its way there. */
    fun blend(to: LightPalette, t: Float) = LightPalette(to.name, colors.zip(to.colors) { a, b -> a.blend(b, t) })

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
            LightPalette("Synthwave", listOf(hsv(320, 100, 100), hsv(275, 90, 95), hsv(190, 100, 100), hsv(28, 100, 100))),
            LightPalette("Vaporwave", listOf(hsv(310, 45, 100), hsv(178, 50, 100), hsv(265, 40, 100), hsv(55, 45, 100))),
            LightPalette("Cyberpunk", listOf(hsv(58, 100, 100), hsv(185, 100, 100), hsv(315, 100, 100), hsv(0, 100, 100))),
            LightPalette("Acid", listOf(hsv(78, 100, 100), hsv(300, 100, 100), hsv(55, 100, 100), hsv(0, 0, 100))),
            LightPalette("Tropicana", listOf(hsv(172, 95, 95), hsv(338, 70, 100), hsv(35, 95, 100), hsv(90, 90, 100))),
            LightPalette("Golden Hour", listOf(hsv(32, 95, 100), hsv(45, 90, 100), hsv(18, 60, 100), hsv(38, 25, 100))),
            LightPalette("Blood Moon", listOf(hsv(355, 100, 90), hsv(18, 100, 85), hsv(285, 90, 60), hsv(35, 100, 100))),
            LightPalette("Abyss", listOf(hsv(228, 100, 80), hsv(195, 100, 55), hsv(255, 95, 70), hsv(178, 90, 100))),
            LightPalette("Galaxy", listOf(hsv(258, 90, 85), hsv(305, 85, 90), hsv(220, 95, 95), hsv(0, 0, 100))),
            LightPalette("Warehouse", listOf(hsv(0, 100, 100), hsv(350, 100, 60), hsv(225, 70, 80), hsv(0, 0, 100))),
            LightPalette("Matrix", listOf(hsv(120, 100, 100), hsv(135, 100, 55), hsv(95, 90, 90), hsv(120, 30, 100))),
            LightPalette("Sakura", listOf(hsv(340, 40, 100), hsv(350, 25, 100), hsv(330, 10, 100), hsv(95, 70, 85))),
            LightPalette("Prism", listOf(hsv(0, 100, 100), hsv(120, 100, 100), hsv(240, 100, 100), hsv(60, 100, 100))),
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
