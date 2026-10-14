package dev.boksremote.speaker

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Builds a whole palette from one colour by the usual colour-wheel rules. The three mains come
 * first and the Effect colour last; the accent is kept bright because it flashes over the rest.
 */
enum class Harmony(val label: String) {
    ANALOGOUS("Analogous"),
    COMPLEMENTARY("Complement"),
    SPLIT("Split"),
    TRIADIC("Triadic"),
    SQUARE("Square"),
    MONO("Mono");

    fun from(base: HsvColor): List<HsvColor> {
        val h = base.hue
        val s = base.saturation
        val v = base.value
        fun turn(deg: Float, sat: Float = s, value: Float = v) = HsvColor(wrapHue(h + deg), sat.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        return when (this) {
            ANALOGOUS -> listOf(base, turn(30f), turn(-30f), turn(180f, value = 1f))
            COMPLEMENTARY -> listOf(base, turn(180f), turn(20f, sat = s * 0.7f), turn(0f, sat = 0.15f, value = 1f))
            SPLIT -> listOf(base, turn(150f), turn(210f), turn(0f, sat = 0.15f, value = 1f))
            TRIADIC -> listOf(base, turn(120f), turn(240f), turn(60f, value = 1f))
            SQUARE -> listOf(base, turn(90f), turn(180f), turn(270f, value = 1f))
            MONO -> listOf(base, turn(0f, sat = s * 0.55f), turn(0f, value = v * 0.55f), turn(0f, sat = 0.12f, value = 1f))
        }
    }
}

private fun wrapHue(h: Float) = ((h % 360f) + 360f) % 360f

/** The angle between two hues, 0..180. */
internal fun hueDistance(a: Float, b: Float): Float = abs(wrapHue(a - b)).let { min(it, 360f - it) }

/**
 * Picks a palette from an image's pixels: the most prominent vivid hues become the mains, and the
 * next one the Effect colour. Pixels are grouped by hue and weighted by how colourful they are, so
 * a large grey sky doesn't outvote a small bright subject. Greys and near-blacks can't be shown by
 * the light's LEDs, so the colours are lifted to at least [MIN_VALUE] brightness.
 */
object PhotoPalette {
    private const val BINS = 24
    /** Peaks closer than this count as one colour. */
    private const val MIN_HUE_GAP = 25f
    private const val MIN_SATURATION = 0.2f
    private const val MIN_PIXEL_VALUE = 0.15f
    private const val MIN_VALUE = 0.7f

    private class Bin {
        var weight = 0.0
        var x = 0.0; var y = 0.0 // hue as a weighted unit vector, so 359° and 1° average to 0°
        var s = 0.0; var v = 0.0
    }

    /** [argb] pixels, already scaled down. Null when the image has no colour to speak of. */
    fun extract(argb: IntArray): List<HsvColor>? {
        val bins = Array(BINS) { Bin() }
        for (p in argb) {
            val c = HsvColor.fromRgb((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            if (c.saturation < MIN_SATURATION || c.value < MIN_PIXEL_VALUE) continue
            val w = (c.saturation * c.value).toDouble().let { it * it }
            val bin = bins[(c.hue / (360f / BINS)).toInt().coerceIn(0, BINS - 1)]
            val rad = Math.toRadians(c.hue.toDouble())
            bin.weight += w
            bin.x += cos(rad) * w; bin.y += sin(rad) * w
            bin.s += c.saturation * w; bin.v += c.value * w
        }
        val total = bins.sumOf { it.weight }
        if (total == 0.0) return null
        val candidates = bins.filter { it.weight > total * 0.01 }.sortedByDescending { it.weight }.map { b ->
            val hue = wrapHue(Math.toDegrees(atan2(b.y, b.x)).toFloat())
            HsvColor(hue, (b.s / b.weight).toFloat(), maxOf((b.v / b.weight).toFloat(), MIN_VALUE))
        }
        val picked = mutableListOf<HsvColor>()
        for (c in candidates) {
            if (picked.none { hueDistance(it.hue, c.hue) < MIN_HUE_GAP }) picked += c
            if (picked.size == LightPalette.SIZE) break
        }
        // A photo with one or two colours: the rest are shades of the strongest, and a near-white accent.
        val base = picked.first()
        val fill = listOf(base.copy(saturation = base.saturation * 0.55f), base.copy(value = base.value * 0.6f))
        while (picked.size < LightPalette.SIZE - 1) picked += fill[picked.size - 1]
        if (picked.size < LightPalette.SIZE) picked += base.copy(saturation = 0.12f, value = 1f)
        // The Effect colour flashes over the others, so it is the brightest of what was found.
        val effect = picked.last().copy(value = 1f)
        return picked.dropLast(1) + effect
    }
}
