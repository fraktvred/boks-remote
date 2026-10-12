package dev.boksremote

import dev.boksremote.proto.ProtoMessage
import dev.boksremote.proto.proto
import dev.boksremote.protocol.Advertisement
import dev.boksremote.protocol.Family
import dev.boksremote.protocol.Hdlc
import dev.boksremote.protocol.ProductModel
import dev.boksremote.protocol.RpcClient
import dev.boksremote.protocol.Uuids
import dev.boksremote.speaker.LightContentCodec
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun hex(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun serviceAndMethodIdsAreCrc32OfBareNames() {
        assertEquals(0x41BCB464, Hdlc.crc32("AudioControlService".toByteArray()))
        assertEquals(0xA06156D3.toInt(), Hdlc.crc32("SetEqMode".toByteArray()))
    }

    @Test
    fun emptyRequestMatchesReferenceFrame() {
        val packet = RpcClient.encodeRequest("AudioControlService", "GetEqMode", ByteArray(0), callId = 1)
        assertArrayEquals(hex("1D 64 B4 BC 41 25 AE 45 4D 51 38 01 2A 00"), packet)
        assertArrayEquals(hex("7E 01 03 1D 64 B4 BC 41 25 AE 45 4D 51 38 01 2A 00 17 4F BC 93 7E"), Hdlc.frame(packet))
    }

    @Test
    fun requestWithPayloadPutsPayloadBeforeCallId() {
        val packet = RpcClient.encodeRequest("AudioControlService", "SetEqMode", proto { int32(1, 1) }, callId = 2)
        val m = ProtoMessage.parse(packet)
        assertEquals(listOf(3, 4, 5, 7), m.fields.map { it.number })
        assertArrayEquals(hex("08 01"), m.bytes(5))
        assertEquals(2, m.int(7))
    }

    @Test
    fun decoderHandlesEscapesSplitsAndBackToBackFrames() {
        val payloadA = byteArrayOf(0x7E, 0x7D, 0x10)
        val payloadB = byteArrayOf(0x30, 0x05)
        val stream = Hdlc.frame(payloadA) + Hdlc.frame(payloadB)
        val decoder = Hdlc.Decoder()
        val out = (decoder.feed(stream.copyOfRange(0, 5)) + decoder.feed(stream.copyOfRange(5, stream.size))).map { it.getOrThrow() }
        assertEquals(2, out.size)
        assertArrayEquals(payloadA, out[0])
        assertArrayEquals(payloadB, out[1])
    }

    @Test
    fun decoderRejectsCorruptFcs() {
        val frame = Hdlc.frame(byteArrayOf(1, 2, 3))
        frame[4] = (frame[4] + 1).toByte()
        assertTrue(Hdlc.Decoder().feed(frame).single().isFailure)
    }

    @Test
    fun protoRoundTripsScalarsStringsAndNegativeInts() {
        val bytes = proto { int32(1, -1); string(2, "Boks"); float(3, 1.5f); bool(4, true) }
        val m = ProtoMessage.parse(bytes)
        assertEquals(-1, m.int(1))
        assertEquals("Boks", m.string(2))
        assertEquals(1.5f, m.float(3))
        assertTrue(m.bool(4))
    }

    @Test
    fun advertisementParsesModelFirmwareAndSecurity() {
        val ad = Advertisement.parse("AA", "Boks", -50, listOf(Uuids.SPEAKER_CONTROL_SERVICE), hex("01 02 04 07 04 00 00"))!!
        assertEquals(Family.BIFROST, ad.family)
        assertEquals(ProductModel.SB_MIX, ad.model)
        assertEquals("2.4.7", ad.firmware)
        assertTrue(ad.security!!.pincodeEnabled)
    }

    @Test
    fun lightRequestsNestUuidBytesInStringOrder() {
        val id = UUID.fromString("303c4ee1-e178-4f2f-bc18-6b3f4f682825")
        val uuid = "30 3C 4E E1 E1 78 4F 2F BC 18 6B 3F 4F 68 28 25"
        assertArrayEquals(hex("0A 12 0A 10 $uuid"), LightContentCodec.animationRequest(id))
        assertArrayEquals(hex("0A 14 0A 12 0A 10 $uuid"), LightContentCodec.customProgramRequest(id))
    }

    @Test
    fun parsePaletteReadsRgbRepresentationAndIgnoresAuto() {
        // Captured from a Lightboks running the official app's "Gunfingers out" preset.
        val captured = hex(
            "08 01 12 68 12 12 0a 10 18 7f 51 67 d2 e7 4d d7 9d 1f e3 c5 52 15 04 30 1a 0e 47 75 6e 66 69 6e 67 65 72 73 " +
                "20 6f 75 74 22 1c 0a 03 08 ff 01 0a 05 08 37 18 ff 01 0a 06 08 95 01 18 ff 01 0a 06 08 ff 01 10 ff 01 2a 24 " +
                "0a 05 10 ff 01 18 32 0a 08 08 fd 01 10 ff 01 18 32 0a 08 08 93 02 10 ff 01 18 32 0a 07 08 3c 10 ff 01 18 32",
        )
        val p = LightContentCodec.parsePalette(ProtoMessage.parse(captured))!!
        assertEquals("Gunfingers out", p.name)
        assertEquals(0f, p.colors[0].hue)
        assertEquals(1f, p.colors[0].value)
        assertEquals(60f, p.colors[3].hue)
        assertEquals(null, LightContentCodec.parsePalette(ProtoMessage.parse(proto { int32(1, 0) })))
    }
}
