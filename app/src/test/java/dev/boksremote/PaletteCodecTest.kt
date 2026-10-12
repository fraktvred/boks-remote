package dev.boksremote

import dev.boksremote.speaker.HsvColor
import dev.boksremote.speaker.LightPalette
import dev.boksremote.speaker.PaletteCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class PaletteCodecTest {
    @Test
    fun roundTripsPalettes() {
        val list = listOf(LightPalette.PRESETS[0], LightPalette("Mine", List(4) { HsvColor(it * 90f, 0.5f, 1f) }))
        assertEquals(list, PaletteCodec.decode(PaletteCodec.encode(list)))
    }

    @Test
    fun namesCannotBreakTheFormat() {
        val p = LightPalette("a|b\nc", LightPalette.PRESETS[1].colors)
        assertEquals(listOf(p.copy(name = "a b c")), PaletteCodec.decode(PaletteCodec.encode(listOf(p))))
    }

    @Test
    fun skipsMalformedLines() {
        val good = PaletteCodec.encode(listOf(LightPalette.PRESETS[2]))
        assertEquals(listOf(LightPalette.PRESETS[2]), PaletteCodec.decode("junk|1,2\n\n$good\nx|1,1,1"))
    }
}
