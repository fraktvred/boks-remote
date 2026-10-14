package dev.boksremote

import dev.boksremote.protocol.ProductModel
import dev.boksremote.speaker.EqCurve
import dev.boksremote.speaker.EqMode
import dev.boksremote.speaker.EqPreset
import dev.boksremote.speaker.EqPresetCodec
import dev.boksremote.speaker.SoundProfiles
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EqTest {
    @Test
    fun encodesBandsLikeTheOfficialApp() {
        // Bass +3, flat mids, treble -7: the worked example from the decompiled app.
        val wire = EqCurve.toWire(listOf(3, 0, 0, 0, 0, -7))
        assertArrayEquals(byteArrayOf(0x42, 0x33, 0x33, 0x33, 0x33, 0x10), wire)
    }

    @Test
    fun clampsAndRoundTrips() {
        assertArrayEquals(byteArrayOf(1, 101, 51, 51, 51, 51), EqCurve.toWire(listOf(-30, 30)))
        val bands = listOf(-10, -4, 0, 2, 7, 10)
        assertEquals(bands, EqCurve.fromWire(EqCurve.toWire(bands)))
    }

    @Test
    fun unsetOrShortBandsReadAsFlat() {
        assertEquals(EqCurve.FLAT, EqCurve.fromWire(ByteArray(6)))
        assertEquals(listOf(10, 0, 0, 0, 0, 0), EqCurve.fromWire(byteArrayOf(101)))
    }

    @Test
    fun presetNamesDependOnModel() {
        assertEquals(listOf("Bass+", "Outdoor", "Indoor"), SoundProfiles.forModel(ProductModel.SB_MIX).map { it.title })
        assertEquals(EqMode.QUIET, SoundProfiles.forModel(ProductModel.SB_MIX).first { it.title == "Indoor" }.mode)
        assertEquals(listOf("Stage", "Dancefloor", "Lounge"), SoundProfiles.forModel(ProductModel.SB4).map { it.title })
        assertEquals(listOf("Loud", "Default", "Quiet"), SoundProfiles.forModel(null).map { it.title })
    }

    @Test
    fun presetsRoundTripAndSkipMalformedLines() {
        val list = listOf(EqPreset("Party", listOf(3, 0, 5, 0, 0, -7)), EqPreset("a|b", EqCurve.FLAT))
        val decoded = EqPresetCodec.decode(EqPresetCodec.encode(list))
        assertEquals(listOf(list[0], list[1].copy(name = "a b")), decoded)
        assertEquals(list.take(1), EqPresetCodec.decode("junk\nx|1,2\n${EqPresetCodec.encode(list.take(1))}\ny|1,2,3,4,5,z"))
    }
}
