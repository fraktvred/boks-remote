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

    @Test
    fun savedNamesTakenByPresetsGetASuffix() {
        val colors = LightPalette.PRESETS[0].colors
        val list = listOf(LightPalette("Galaxy", colors), LightPalette("Galaxy 2", colors), LightPalette("Mine", colors))
        val names = PaletteCodec.withUniqueNames(list, setOf("Galaxy")).map { it.name }
        assertEquals(listOf("Galaxy 3", "Galaxy 2", "Mine"), names)
    }

    @Test
    fun presetNamesAreUnique() {
        assertEquals(LightPalette.PRESETS.size, LightPalette.PRESETS.map { it.name }.toSet().size)
    }

    @Test
    fun parsesHexColours() {
        assertEquals(HsvColor(0f, 1f, 1f), HsvColor.fromHex("#FF0000"))
        assertEquals(HsvColor(240f, 1f, 1f), HsvColor.fromHex("0000ff"))
        assertEquals(null, HsvColor.fromHex("#F00"))
        assertEquals(null, HsvColor.fromHex("#GG0000"))
    }

    @Test
    fun recentColoursPutNewFirstWithoutLookalikes() {
        val red = HsvColor(0f, 1f, 1f)
        val blue = HsvColor(240f, 1f, 1f)
        val nearlyRed = HsvColor(0.2f, 0.999f, 1f)
        // The older red looks the same as the new one, so only the new one stays.
        assertEquals(listOf(blue, nearlyRed), PaletteCodec.mergeRecent(listOf(blue, nearlyRed), listOf(red), max = 5))
        assertEquals(1, PaletteCodec.mergeRecent(listOf(blue, red), emptyList(), max = 1).size)
        assertEquals(listOf(red, blue), PaletteCodec.decodeColors(PaletteCodec.encodeColors(listOf(red, blue))))
    }
}
