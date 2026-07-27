package kestrel.store.codec

import java.util.zip.CRC32C

/** Castagnoli CRC-32 checksums used to detect corruption of on-disk pages and records. */
object Crc32C {
    fun of(bytes: ByteArray): Int = of(bytes, 0, bytes.size)

    fun of(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32C()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }
}
