package dev.boksremote.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** pw_hdlc-style UI framing used by the speaker RPC channel. */
object Hdlc {
    const val FLAG = 0x7E
    const val ESCAPE = 0x7D
    private const val ESCAPE_XOR = 0x20
    private const val ADDRESS_ZERO = 0x01 // address 0 as a one-byte varint with the end bit set
    private const val UI_CONTROL = 0x03

    fun crc32(data: ByteArray): Int = CRC32().apply { update(data) }.value.toInt()

    fun frame(payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(ADDRESS_ZERO); write(UI_CONTROL); write(payload)
        }.toByteArray()
        val fcs = crc32(body)
        val out = ByteArrayOutputStream()
        out.write(FLAG)
        fun put(b: Int) {
            if (b == FLAG || b == ESCAPE) { out.write(ESCAPE); out.write(b xor ESCAPE_XOR) } else out.write(b)
        }
        body.forEach { put(it.toInt() and 0xFF) }
        for (i in 0 until 4) put((fcs ushr (8 * i)) and 0xFF)
        out.write(FLAG)
        return out.toByteArray()
    }

    /**
     * Streaming decoder. A frame may span several BLE notifications and one notification may
     * hold several frames, so state persists between [feed] calls.
     */
    class Decoder {
        private val buf = ByteArrayOutputStream()
        private var inFrame = false
        private var escaped = false

        /** Feeds bytes; returns the payload of each complete frame whose FCS checks out. */
        fun feed(chunk: ByteArray): List<Result<ByteArray>> {
            val frames = ArrayList<Result<ByteArray>>()
            for (raw in chunk) {
                val b = raw.toInt() and 0xFF
                when {
                    b == FLAG -> {
                        if (inFrame && buf.size() > 0) frames += finish(buf.toByteArray())
                        buf.reset(); escaped = false; inFrame = true // a closing flag also opens the next frame
                    }
                    !inFrame -> Unit
                    escaped -> {
                        escaped = false
                        if (b == (FLAG xor ESCAPE_XOR) || b == (ESCAPE xor ESCAPE_XOR)) buf.write(b xor ESCAPE_XOR)
                        else { frames += Result.failure(IllegalStateException("bad escape")); buf.reset(); inFrame = false }
                    }
                    b == ESCAPE -> escaped = true
                    else -> buf.write(b)
                }
            }
            return frames
        }

        private fun finish(decoded: ByteArray): Result<ByteArray> {
            if (decoded.size < 6) return Result.failure(IllegalStateException("frame too short"))
            val body = decoded.copyOfRange(0, decoded.size - 4)
            var fcs = 0
            for (i in 0 until 4) fcs = fcs or ((decoded[decoded.size - 4 + i].toInt() and 0xFF) shl (8 * i))
            if (fcs != crc32(body)) return Result.failure(IllegalStateException("FCS mismatch"))
            // Skip the address varint (bytes until one with bit 0 set) and the control byte.
            var i = 0
            while (i < body.size && body[i].toInt() and 1 == 0) i++
            i += 2
            if (i > body.size) return Result.failure(IllegalStateException("truncated header"))
            return Result.success(body.copyOfRange(i, body.size))
        }
    }
}
