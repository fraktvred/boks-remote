package dev.boksremote

import dev.boksremote.dmx.DmxBridgeService
import dev.boksremote.dmx.DmxConfig
import dev.boksremote.dmx.DmxFrame
import dev.boksremote.dmx.DmxPackets
import dev.boksremote.dmx.DmxProtocol
import dev.boksremote.dmx.DmxStatus
import dev.boksremote.dmx.LightboksPersonality
import dev.boksremote.dmx.LocalNetwork
import dev.boksremote.dmx.Rgb
import dev.boksremote.speaker.EnergyLevel
import dev.boksremote.speaker.LightFx
import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DmxTest {
    private fun artDmx(net: Int, subUni: Int, data: ByteArray): ByteArray =
        "Art-Net\u0000".toByteArray() + byteArrayOf(0x00, 0x50, 0, 14, 1, 0, subUni.toByte(), net.toByte(), (data.size shr 8).toByte(), data.size.toByte()) + data

    /** Minimal E1.31 data packet with the fields the parser checks. */
    private fun sacn(universe: Int, data: ByteArray, options: Int = 0, startCode: Int = 0): ByteArray {
        val p = ByteArray(126 + data.size)
        p[1] = 0x10
        "ASC-E1.17\u0000\u0000\u0000".toByteArray().copyInto(p, 4)
        p[21] = 4 // root vector: data
        p[43] = 2 // framing vector: DMP data
        p[112] = options.toByte()
        p[113] = (universe shr 8).toByte(); p[114] = universe.toByte()
        val count = data.size + 1
        p[123] = (count shr 8).toByte(); p[124] = count.toByte()
        p[125] = startCode.toByte()
        data.copyInto(p, 126)
        return p
    }

    @Test
    fun artNetParsesPortAddressAndData() {
        val f = DmxPackets.parseArtNet(artDmx(net = 1, subUni = 0x23, data = byteArrayOf(10, 20, 30)))!!
        assertEquals(0x123, f.universe)
        assertEquals(20, f.channel(2))
        assertEquals(0, f.channel(4)) // past the received length
    }

    @Test
    fun artNetIgnoresOtherOpcodes() {
        val poll = "Art-Net\u0000".toByteArray() + byteArrayOf(0x00, 0x20, 0, 14, 0, 0, 0, 0, 0, 0)
        assertNull(DmxPackets.parseArtNet(poll))
    }

    @Test
    fun sacnParsesUniverseAndSkipsPreviewTerminatedAndAltStartCodes() {
        val f = DmxPackets.parseSacn(sacn(7, byteArrayOf(1, 2, 3)))!!
        assertEquals(7, f.universe)
        assertEquals(3, f.channel(3))
        assertNull(DmxPackets.parseSacn(sacn(7, byteArrayOf(1), options = 0x80)))
        assertNull(DmxPackets.parseSacn(sacn(7, byteArrayOf(1), options = 0x40)))
        assertNull(DmxPackets.parseSacn(sacn(7, byteArrayOf(1), startCode = 0xDD)))
        assertEquals("239.255.1.2", DmxPackets.sacnGroup(0x102))
    }

    @Test
    fun fullPersonalityMapsFromStartAddress() {
        val data = ByteArray(40)
        val start = 20
        val values = listOf(200, 130, 255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 255, 150)
        values.forEachIndexed { i, v -> data[start - 1 + i] = v.toByte() }
        val look = LightboksPersonality.FULL.map(DmxFrame(0, data), start)
        assertEquals(200, look.dimmer)
        assertEquals(EnergyLevel.HIGH, look.mode)
        assertEquals(listOf(Rgb(255, 0, 0), Rgb(0, 255, 0), Rgb(0, 0, 255), Rgb(255, 255, 255)), look.colors)
        assertEquals(LightFx.CHASE, look.fx)
    }

    @Test
    fun simplePersonalityUsesOneColourForAllSlots() {
        val look = LightboksPersonality.SIMPLE.map(DmxFrame(0, byteArrayOf(10, -1, 1, 2, 3)), 1)
        assertEquals(EnergyLevel.CUSTOM, look.mode)
        assertEquals(List(4) { Rgb(1, 2, 3) }, look.colors)
        assertNull(look.fx)
    }

    @Test
    fun modeAndFxBandEdges() {
        assertEquals(EnergyLevel.LOW, LightboksPersonality.mode(63))
        assertEquals(EnergyLevel.MEDIUM, LightboksPersonality.mode(64))
        assertEquals(EnergyLevel.CUSTOM, LightboksPersonality.mode(192))
        assertNull(LightboksPersonality.fx(31))
        assertEquals(LightFx.STROBE, LightboksPersonality.fx(32))
        assertEquals(LightFx.SPARKLE, LightboksPersonality.fx(255))
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun v4(s: String) = InetAddress.getByName(s) as Inet4Address

    @Test
    fun artPollIsRecognisedAndDmxIsNot() {
        val poll = "Art-Net\u0000".toByteArray() + byteArrayOf(0x00, 0x20, 0, 14, 0, 0)
        assertTrue(DmxPackets.isArtPoll(poll))
        assertFalse(DmxPackets.isArtPoll(artDmx(0, 0, byteArrayOf(1))))
    }

    @Test
    fun artPollReplyAnnouncesOneOutputOnThePortAddress() {
        val universe = 0x1A7 // Net 1, Sub-Net 0xA, Universe 7
        val r = DmxPackets.artPollReply(byteArrayOf(192.toByte(), 168.toByte(), 1, 23), universe, "Boks Remote", "Boks Remote - #000123", "#0001 [0000] ok", outputting = true)
        assertEquals(239, r.size)
        assertEquals("Art-Net", String(r, 0, 7))
        assertEquals(0x2100, u8(r, 8) or (u8(r, 9) shl 8))
        assertEquals(listOf(192, 168, 1, 23), (10..13).map { u8(r, it) })
        assertEquals(6454, u8(r, 14) or (u8(r, 15) shl 8))
        assertEquals(1, u8(r, 18)) // Net
        assertEquals(0xA, u8(r, 19)) // Sub-Net
        assertEquals(7, u8(r, 190)) // SwOut[0]
        assertEquals("Boks Remote", String(r, 26, 11))
        assertEquals(0, u8(r, 26 + 11)) // null-terminated
        assertEquals(1, u8(r, 173)) // NumPorts
        assertEquals(0x80, u8(r, 174)) // output port
        assertEquals(0x80, u8(r, 182)) // GoodOutputA
    }

    @Test
    fun artPollReplyTruncatesNamesToTheirFields() {
        val r = DmxPackets.artPollReply(ByteArray(4), 0, "x".repeat(40), "Lightbøks", "", outputting = false)
        assertEquals(0, u8(r, 26 + 17)) // short name keeps its terminator
        assertEquals("Lightb?ks", String(r, 44, 9))
        assertEquals(0, u8(r, 182))
    }

    @Test
    fun facingPicksTheAddressOnThePeersSubnet() {
        assertTrue(LocalNetwork.sameSubnet(v4("192.168.1.23"), v4("192.168.1.200"), 24))
        assertFalse(LocalNetwork.sameSubnet(v4("192.168.1.23"), v4("192.168.2.1"), 24))
        assertTrue(LocalNetwork.sameSubnet(v4("10.0.0.1"), v4("10.255.1.1"), 8))
        val wifi = dev.boksremote.dmx.LocalAddress(v4("192.168.1.23"), 24, "wlan0")
        val hotspot = dev.boksremote.dmx.LocalAddress(v4("10.42.0.1"), 24, "ap0")
        assertEquals(hotspot, LocalNetwork.facing(v4("10.42.0.77"), listOf(wifi, hotspot)))
        assertEquals(wifi, LocalNetwork.facing(v4("172.16.0.5"), listOf(wifi, hotspot)))
    }

    @Test
    fun universeRangesFollowEachProtocol() {
        assertEquals(0..32767, DmxProtocol.ARTNET.universes)
        assertEquals(1..63999, DmxProtocol.SACN.universes)
    }

    @Test
    fun notificationSummaryPrioritisesProblems() {
        val c = DmxConfig(DmxProtocol.SACN, universe = 3)
        val ok = DmxStatus(running = true, addresses = listOf("192.168.1.23"))
        assertEquals("Waiting for sACN universe 3 on 192.168.1.23", DmxBridgeService.summary(ok, c))
        assertEquals("Receiving sACN universe 3 from 192.168.1.5", DmxBridgeService.summary(ok.copy(packetsPerSecond = 40, source = "192.168.1.5"), c))
        assertEquals("Light disconnected, reconnecting…", DmxBridgeService.summary(ok.copy(packetsPerSecond = 40, reconnecting = true), c))
        assertTrue(DmxBridgeService.summary(ok.copy(addresses = emptyList()), c).startsWith("No Wi-Fi"))
    }
}
