package dev.boksremote

import dev.boksremote.speaker.Harmony
import dev.boksremote.speaker.HsvColor
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.PhotoPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class PaletteToolsTest {
    private fun dist(a: Float, b: Float) = abs(a - b).let { min(it, 360 - it) }

    @Test
    fun hexRoundTrips() {
        for (hex in listOf("#FF2A6D", "#000000", "#FFFFFF", "#00FF7F", "#123456")) {
            assertEquals(hex, HsvColor.fromHex(hex)!!.hex())
        }
    }

    @Test
    fun harmoniesKeepTheBaseAndWrapHues() {
        val base = HsvColor(350f, 0.9f, 0.8f)
        for (h in Harmony.entries) {
            val colors = h.from(base)
            assertEquals(LightPalette.SIZE, colors.size)
            assertEquals(base, colors[0])
            colors.forEach { assertTrue(it.hue in 0f..360f && it.saturation in 0f..1f && it.value in 0f..1f) }
        }
        val triad = Harmony.TRIADIC.from(base)
        assertEquals(110f, triad[1].hue, 0.01f)
        assertEquals(230f, triad[2].hue, 0.01f)
    }

    @Test
    fun photoPicksTheVividHuesOverGrey() {
        val grey = 0xFF808080.toInt()
        val red = 0xFFFF1010.toInt()
        val blue = 0xFF1030FF.toInt()
        val pixels = IntArray(1000) { i -> when { i < 700 -> grey; i < 850 -> red; else -> blue } }
        val colors = PhotoPalette.extract(pixels)!!
        assertEquals(LightPalette.SIZE, colors.size)
        assertTrue(dist(colors[0].hue, 0f) < 10f || dist(colors[0].hue, 230f) < 10f)
        assertTrue(colors.take(3).any { dist(it.hue, 0f) < 10f })
        assertTrue(colors.take(3).any { dist(it.hue, 230f) < 10f })
        assertEquals(1f, colors[3].value, 0.001f)
    }

    @Test
    fun redHuesEitherSideOfZeroAverageToRed() {
        val pixels = IntArray(100) { if (it % 2 == 0) 0xFFFF0008.toInt() else 0xFFFF0800.toInt() }
        assertTrue(dist(PhotoPalette.extract(pixels)!![0].hue, 0f) < 5f)
    }

    @Test
    fun greyPhotoHasNoPalette() {
        assertNull(PhotoPalette.extract(IntArray(100) { 0xFF777777.toInt() }))
    }
}
