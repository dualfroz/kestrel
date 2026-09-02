package kestrel.store.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Crc32CTest {
    @Test
    fun sameBytesProduceSameChecksum() {
        val a = "hello kestrel".toByteArray()
        val b = "hello kestrel".toByteArray()
        assertEquals(Crc32C.of(a), Crc32C.of(b))
    }

    @Test
    fun differentBytesProduceDifferentChecksum() {
        assertNotEquals(Crc32C.of("hello".toByteArray()), Crc32C.of("hellp".toByteArray()))
    }

    @Test
    fun detectsSingleBitFlip() {
        val original = ByteArray(4096) { (it % 251).toByte() }
        val corrupted = original.copyOf()
        corrupted[2000] = (corrupted[2000].toInt() xor 0x01).toByte()
        assertNotEquals(Crc32C.of(original), Crc32C.of(corrupted))
    }

    @Test
    fun offsetAndLengthCoverOnlyTheRequestedSlice() {
        val buffer = "xxhelloxx".toByteArray()
        assertEquals(Crc32C.of("hello".toByteArray()), Crc32C.of(buffer, 2, 5))
    }
}
