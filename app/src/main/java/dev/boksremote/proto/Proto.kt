package dev.boksremote.proto

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire-format codec. The speaker messages are small and few, so a
 * hand-written codec keeps the app free of the protobuf toolchain and generated code.
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, WIRE_VARINT); rawVarint(value); return this
    }

    fun int32(field: Int, value: Int) = varint(field, value.toLong()) // negatives sign-extend to 10 bytes, per spec
    fun uint32(field: Int, value: Int) = varint(field, value.toLong() and 0xFFFFFFFFL)
    fun bool(field: Int, value: Boolean) = varint(field, if (value) 1 else 0)

    fun fixed32(field: Int, value: Int): ProtoWriter {
        tag(field, WIRE_FIXED32)
        for (i in 0 until 4) out.write((value ushr (8 * i)) and 0xFF)
        return this
    }

    fun float(field: Int, value: Float) = fixed32(field, java.lang.Float.floatToRawIntBits(value))

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, WIRE_LEN); rawVarint(value.size.toLong()); out.write(value); return this
    }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun message(field: Int, build: ProtoWriter.() -> Unit) = bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    private fun rawVarint(v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
        out.write(x.toInt())
    }

    companion object {
        const val WIRE_VARINT = 0
        const val WIRE_FIXED64 = 1
        const val WIRE_LEN = 2
        const val WIRE_FIXED32 = 5
    }
}

/** One decoded field. [varint] holds varint/fixed values; [bytes] holds length-delimited payloads. */
data class ProtoField(val number: Int, val wire: Int, val varint: Long, val bytes: ByteArray?) {
    val int: Int get() = varint.toInt()
    val bool: Boolean get() = varint != 0L
    val float: Float get() = java.lang.Float.intBitsToFloat(varint.toInt())
    val string: String get() = String(bytes ?: ByteArray(0), Charsets.UTF_8)
    val message: ProtoMessage get() = ProtoMessage.parse(bytes ?: ByteArray(0))
}

/** A parsed message: fields in wire order, repeated fields appear multiple times. */
class ProtoMessage(val fields: List<ProtoField>) {
    fun first(number: Int): ProtoField? = fields.firstOrNull { it.number == number }
    fun all(number: Int): List<ProtoField> = fields.filter { it.number == number }

    fun int(number: Int, default: Int = 0) = first(number)?.int ?: default
    fun bool(number: Int) = first(number)?.bool ?: false
    fun float(number: Int, default: Float = 0f) = first(number)?.float ?: default
    fun string(number: Int) = first(number)?.string ?: ""
    fun bytes(number: Int) = first(number)?.bytes ?: ByteArray(0)
    fun message(number: Int): ProtoMessage? = first(number)?.message

    companion object {
        fun parse(data: ByteArray): ProtoMessage {
            val fields = ArrayList<ProtoField>()
            var pos = 0
            fun readVarint(): Long {
                var result = 0L; var shift = 0
                while (true) {
                    require(pos < data.size) { "truncated varint" }
                    val b = data[pos++].toInt() and 0xFF
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                    require(shift < 64) { "varint too long" }
                }
            }
            fun readFixed(n: Int): Long {
                require(pos + n <= data.size) { "truncated fixed$n" }
                var v = 0L
                for (i in 0 until n) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                pos += n
                return v
            }
            while (pos < data.size) {
                val key = readVarint()
                val number = (key ushr 3).toInt()
                when (val wire = (key and 7).toInt()) {
                    ProtoWriter.WIRE_VARINT -> fields += ProtoField(number, wire, readVarint(), null)
                    ProtoWriter.WIRE_FIXED64 -> fields += ProtoField(number, wire, readFixed(8), null)
                    ProtoWriter.WIRE_FIXED32 -> fields += ProtoField(number, wire, readFixed(4), null)
                    ProtoWriter.WIRE_LEN -> {
                        val len = readVarint().toInt()
                        require(len >= 0 && pos + len <= data.size) { "truncated bytes field $number" }
                        fields += ProtoField(number, wire, len.toLong(), data.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    else -> throw IllegalArgumentException("unsupported wire type $wire")
                }
            }
            return ProtoMessage(fields)
        }
    }
}

fun proto(build: ProtoWriter.() -> Unit): ByteArray = ProtoWriter().apply(build).toByteArray()
