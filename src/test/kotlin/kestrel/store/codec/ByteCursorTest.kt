package kestrel.store.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ByteCursorTest {
    @Test
    fun writesAndReadsVarIntsAcrossByteBoundaries() {
        val samples = listOf(0L, 1L, 127L, 128L, 300L, 16384L, Int.MAX_VALUE.toLong(), Long.MAX_VALUE)
        val writer = ByteWriter()
        for (sample in samples) writer.writeVarInt(sample)
        val reader = ByteReader(writer.toByteArray())
        for (sample in samples) assertEquals(sample, reader.readVarInt())
        assertFalse(reader.hasRemaining())
    }

    @Test
    fun zigZagRoundTripsNegativeAndPositiveValues() {
        val samples = listOf(0L, -1L, 1L, -64L, 64L, Long.MIN_VALUE, Long.MAX_VALUE)
        val writer = ByteWriter()
        for (sample in samples) writer.writeZigZag(sample)
        val reader = ByteReader(writer.toByteArray())
        for (sample in samples) assertEquals(sample, reader.readZigZag())
    }

    @Test
    fun negativeNumbersUseFewerBytesThanUnsignedVarIntWouldViaZigZag() {
        val writer = ByteWriter()
        writer.writeZigZag(-1L)
        assertEquals(1, writer.length)
    }

    @Test
    fun fixedWidthLongAndDoubleRoundTrip() {
        val writer = ByteWriter()
        writer.writeFixedLong(-42L)
        writer.writeFixedDouble(3.14159)
        val reader = ByteReader(writer.toByteArray())
        assertEquals(-42L, reader.readFixedLong())
        assertEquals(3.14159, reader.readFixedDouble())
    }

    @Test
    fun lengthPrefixedBytesRoundTrip() {
        val writer = ByteWriter()
        writer.writeLengthPrefixed(byteArrayOf(1, 2, 3))
        writer.writeLengthPrefixed(ByteArray(0))
        writer.writeByte(9)
        val reader = ByteReader(writer.toByteArray())
        assertTrue(reader.readLengthPrefixed().contentEquals(byteArrayOf(1, 2, 3)))
        assertTrue(reader.readLengthPrefixed().contentEquals(ByteArray(0)))
        assertEquals(9, reader.readByte())
    }

    @Test
    fun readingPastEndThrows() {
        val reader = ByteReader(byteArrayOf(1))
        reader.readByte()
        assertFailsWith<CodecException> { reader.readByte() }
    }

    @Test
    fun growsBufferBeyondInitialCapacity() {
        val writer = ByteWriter(2)
        repeat(500) { writer.writeByte(it and 0xFF) }
        assertEquals(500, writer.length)
        val reader = ByteReader(writer.toByteArray())
        repeat(500) { assertEquals(it and 0xFF, reader.readByte()) }
    }
}
