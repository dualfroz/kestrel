package kestrel.store.codec

import kestrel.KestrelException

class CodecException(message: String) : KestrelException(message)

class ByteWriter(initialCapacity: Int = 32) {
    private var buffer = ByteArray(initialCapacity)
    private var size = 0

    val length: Int get() = size

    private fun ensure(extra: Int) {
        if (size + extra <= buffer.size) return
        var newCapacity = buffer.size * 2
        while (newCapacity < size + extra) newCapacity *= 2
        buffer = buffer.copyOf(newCapacity)
    }

    fun writeByte(value: Int): ByteWriter {
        ensure(1)
        buffer[size++] = value.toByte()
        return this
    }

    fun writeBytes(bytes: ByteArray): ByteWriter {
        ensure(bytes.size)
        System.arraycopy(bytes, 0, buffer, size, bytes.size)
        size += bytes.size
        return this
    }

    fun writeFixedLong(value: Long): ByteWriter {
        ensure(8)
        for (i in 7 downTo 0) {
            buffer[size++] = ((value ushr (i * 8)) and 0xFF).toByte()
        }
        return this
    }

    fun writeFixedDouble(value: Double): ByteWriter = writeFixedLong(java.lang.Double.doubleToLongBits(value))

    fun writeVarInt(value: Long): ByteWriter {
        var remaining = value
        while (true) {
            val byte = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining == 0L) {
                writeByte(byte)
                return this
            }
            writeByte(byte or 0x80)
        }
    }

    fun writeZigZag(value: Long): ByteWriter = writeVarInt((value shl 1) xor (value shr 63))

    fun writeLengthPrefixed(bytes: ByteArray): ByteWriter {
        writeVarInt(bytes.size.toLong())
        writeBytes(bytes)
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}

class ByteReader(private val data: ByteArray, private var position: Int = 0) {
    val remaining: Int get() = data.size - position

    fun hasRemaining(): Boolean = position < data.size

    fun readByte(): Int {
        if (position >= data.size) throw CodecException("unexpected end of buffer")
        return data[position++].toInt() and 0xFF
    }

    fun readBytes(count: Int): ByteArray {
        if (position + count > data.size) throw CodecException("unexpected end of buffer")
        val slice = data.copyOfRange(position, position + count)
        position += count
        return slice
    }

    fun readFixedLong(): Long {
        var result = 0L
        repeat(8) { result = (result shl 8) or readByte().toLong() }
        return result
    }

    fun readFixedDouble(): Double = java.lang.Double.longBitsToDouble(readFixedLong())

    fun readVarInt(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val byte = readByte()
            result = result or ((byte.toLong() and 0x7F) shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw CodecException("varint too long")
        }
    }

    fun readZigZag(): Long {
        val encoded = readVarInt()
        return (encoded ushr 1) xor -(encoded and 1)
    }

    fun readLengthPrefixed(): ByteArray = readBytes(readVarInt().toInt())
}
