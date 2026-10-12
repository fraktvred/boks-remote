package dev.boksremote.dmx

/** One DMX universe's channel values (index 0 = channel 1), as received from the network. */
class DmxFrame(val universe: Int, val data: ByteArray) {
    /** 1-based DMX channel value 0..255; channels past the received length read as 0. */
    fun channel(n: Int): Int = if (n in 1..data.size) data[n - 1].toInt() and 0xFF else 0
}

/**
 * Parsers for the two common DMX-over-IP protocols. Universe numbering follows each
 * protocol's own convention: Art-Net counts from 0 (15-bit Port-Address), sACN from 1.
 */
object DmxPackets {
    const val ARTNET_PORT = 6454
    const val SACN_PORT = 5568

    private val ARTNET_ID = "Art-Net\u0000".toByteArray(Charsets.US_ASCII)
    private const val OP_DMX = 0x5000
    private const val OP_POLL = 0x2000
    private const val OP_POLL_REPLY = 0x2100
    private const val ARTPOLLREPLY_SIZE = 239
    private val ACN_ID = "ASC-E1.17\u0000\u0000\u0000".toByteArray(Charsets.US_ASCII)
    private const val SACN_ROOT_VECTOR_DATA = 0x00000004
    private const val SACN_FRAMING_VECTOR_DATA = 0x00000002
    private const val SACN_OPTION_PREVIEW = 0x80
    private const val SACN_OPTION_TERMINATED = 0x40

    /** ArtDmx: "Art-Net\0", opcode 0x5000 (LE), version, sequence, physical, SubUni, Net, length (BE), data. */
    fun parseArtNet(p: ByteArray, len: Int = p.size): DmxFrame? {
        if (len < 18 || !startsWith(p, ARTNET_ID)) return null
        val op = u8(p, 8) or (u8(p, 9) shl 8)
        if (op != OP_DMX) return null
        val universe = (u8(p, 15) shl 8) or u8(p, 14)
        val count = ((u8(p, 16) shl 8) or u8(p, 17)).coerceAtMost(len - 18).coerceAtMost(512)
        if (count <= 0) return null
        return DmxFrame(universe, p.copyOfRange(18, 18 + count))
    }

    /** ArtPoll: a controller asking which nodes are on the network. */
    fun isArtPoll(p: ByteArray, len: Int = p.size): Boolean =
        len >= 12 && startsWith(p, ARTNET_ID) && (u8(p, 8) or (u8(p, 9) shl 8)) == OP_POLL

    /**
     * ArtPollReply announcing one DMX output port on [universe], so desks list the phone as a node
     * and can patch it by name. Offsets follow the Art-Net 4 spec; unused fields stay zero.
     */
    fun artPollReply(ip: ByteArray, universe: Int, shortName: String, longName: String, report: String, outputting: Boolean): ByteArray {
        val r = ByteArray(ARTPOLLREPLY_SIZE)
        ARTNET_ID.copyInto(r, 0)
        r[8] = (OP_POLL_REPLY and 0xFF).toByte(); r[9] = (OP_POLL_REPLY shr 8).toByte()
        ip.copyInto(r, 10, 0, 4)
        r[14] = (ARTNET_PORT and 0xFF).toByte(); r[15] = (ARTNET_PORT shr 8).toByte()
        r[17] = 1 // firmware version
        r[18] = ((universe shr 8) and 0x7F).toByte() // Net
        r[19] = ((universe shr 4) and 0x0F).toByte() // Sub-Net
        r[21] = 0xFF.toByte() // OEM: unknown
        r[23] = 0xD0.toByte() // Status1: indicators normal, Port-Address set locally (in the app)
        r[24] = 0xF0.toByte(); r[25] = 0x7F // ESTA 0x7FF0: the range reserved for prototypes
        ascii(shortName, r, 26, 18)
        ascii(longName, r, 44, 64)
        ascii(report, r, 108, 64)
        r[173] = 1 // one port
        r[174] = 0x80.toByte() // port 0 outputs DMX512 received over Art-Net
        r[182] = if (outputting) 0x80.toByte() else 0 // GoodOutputA: data is being output
        r[190] = (universe and 0x0F).toByte() // SwOut, the port's Universe nibble
        r[211] = 1 // BindIndex: the root (only) device
        r[212] = 0x08 // Status2: supports 15-bit Port-Address
        return r
    }

    /** E1.31 data packet; preview and stream-terminated packets and non-zero start codes are ignored. */
    fun parseSacn(p: ByteArray, len: Int = p.size): DmxFrame? {
        if (len < 126) return null
        if (!regionEquals(p, 4, ACN_ID)) return null
        if (u32(p, 18) != SACN_ROOT_VECTOR_DATA || u32(p, 40) != SACN_FRAMING_VECTOR_DATA) return null
        val options = u8(p, 112)
        if (options and (SACN_OPTION_PREVIEW or SACN_OPTION_TERMINATED) != 0) return null
        val universe = (u8(p, 113) shl 8) or u8(p, 114)
        val propertyCount = (u8(p, 123) shl 8) or u8(p, 124) // includes the start code
        if (u8(p, 125) != 0) return null // only the null start code carries dimmer data
        val count = (propertyCount - 1).coerceAtMost(len - 126).coerceAtMost(512)
        if (count <= 0) return null
        return DmxFrame(universe, p.copyOfRange(126, 126 + count))
    }

    /** sACN multicast group for a universe: 239.255.hi.lo. */
    fun sacnGroup(universe: Int): String = "239.255.${(universe shr 8) and 0xFF}.${universe and 0xFF}"

    /** Null-terminated ASCII into a fixed-size field; anything non-ASCII becomes '?'. */
    private fun ascii(text: String, out: ByteArray, at: Int, size: Int) {
        text.take(size - 1).forEachIndexed { i, c -> out[at + i] = (if (c.code in 0x20..0x7E) c.code else '?'.code).toByte() }
    }

    private fun u8(p: ByteArray, i: Int) = p[i].toInt() and 0xFF
    private fun u32(p: ByteArray, i: Int) = (u8(p, i) shl 24) or (u8(p, i + 1) shl 16) or (u8(p, i + 2) shl 8) or u8(p, i + 3)
    private fun startsWith(p: ByteArray, prefix: ByteArray) = regionEquals(p, 0, prefix)
    private fun regionEquals(p: ByteArray, at: Int, x: ByteArray): Boolean {
        if (p.size < at + x.size) return false
        for (i in x.indices) if (p[at + i] != x[i]) return false
        return true
    }
}
