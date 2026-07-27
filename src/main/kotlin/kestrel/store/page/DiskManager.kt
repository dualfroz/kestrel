package kestrel.store.page

import kestrel.KestrelException
import kestrel.store.codec.ByteReader
import kestrel.store.codec.ByteWriter
import kestrel.store.codec.Crc32C
import java.io.File
import java.io.RandomAccessFile

class CorruptPageException(message: String) : KestrelException(message)

/**
 * Owns a single heap file of fixed-size pages. Every physical slot is stored
 * as `[4-byte CRC32C][PAGE_SIZE payload]` so torn or corrupted writes are
 * detected on read. Slot 0 is reserved for a metadata header (page count and
 * the head of the free list); logical page ids are 0-based and map to
 * physical slot `id + 1`. Freed pages are threaded into a singly linked free
 * list stored inline in the freed page's own bytes, so allocation reuses
 * space instead of only ever growing the file.
 */
class DiskManager(private val file: File) : AutoCloseable {
    companion object {
        private const val MAGIC = 0x4B535452 // "KSTR"
        private const val SLOT_SIZE = 4 + Page.PAGE_SIZE
        private const val HEADER_SLOT = 0L
        private const val NO_PAGE = -1L
    }

    private val raf = RandomAccessFile(file, "rw")
    private var pageCount: Long
    private var freeListHead: Long

    init {
        if (raf.length() == 0L) {
            pageCount = 0
            freeListHead = NO_PAGE
            writeHeader()
        } else {
            val header = readSlot(HEADER_SLOT)
            val reader = ByteReader(header)
            val magic = reader.readFixedLong().toInt()
            if (magic != MAGIC) throw CorruptPageException("bad header magic in ${file.path}")
            pageCount = reader.readFixedLong()
            freeListHead = reader.readFixedLong()
        }
    }

    fun allocatedPageCount(): Long = pageCount

    fun allocatePage(): Long {
        if (freeListHead != NO_PAGE) {
            val pageId = freeListHead
            val payload = readSlot(physicalSlot(pageId))
            freeListHead = ByteReader(payload).readFixedLong()
            writeHeader()
            return pageId
        }
        val pageId = pageCount
        pageCount += 1
        writeHeader()
        return pageId
    }

    fun freePage(pageId: Long) {
        val payload = ByteWriter(Page.PAGE_SIZE)
        payload.writeFixedLong(freeListHead)
        val padded = payload.toByteArray().copyOf(Page.PAGE_SIZE)
        writeSlot(physicalSlot(pageId), padded)
        freeListHead = pageId
        writeHeader()
    }

    fun readPage(pageId: Long): Page {
        require(pageId in 0 until pageCount) { "page $pageId is not allocated" }
        val page = Page(pageId)
        val payload = readSlot(physicalSlot(pageId))
        System.arraycopy(payload, 0, page.data, 0, Page.PAGE_SIZE)
        return page
    }

    fun writePage(page: Page) {
        writeSlot(physicalSlot(page.id), page.data)
    }

    fun fsync() {
        raf.fd.sync()
    }

    override fun close() {
        fsync()
        raf.close()
    }

    private fun physicalSlot(pageId: Long): Long = pageId + 1

    private fun writeHeader() {
        val writer = ByteWriter(Page.PAGE_SIZE)
        writer.writeFixedLong(MAGIC.toLong())
        writer.writeFixedLong(pageCount)
        writer.writeFixedLong(freeListHead)
        val padded = writer.toByteArray().copyOf(Page.PAGE_SIZE)
        writeSlot(HEADER_SLOT, padded)
    }

    private fun writeSlot(slot: Long, payload: ByteArray) {
        require(payload.size == Page.PAGE_SIZE) { "payload must be exactly ${Page.PAGE_SIZE} bytes" }
        val checksum = Crc32C.of(payload)
        raf.seek(slot * SLOT_SIZE)
        raf.write(checksum ushr 24)
        raf.write(checksum ushr 16)
        raf.write(checksum ushr 8)
        raf.write(checksum)
        raf.write(payload)
    }

    private fun readSlot(slot: Long): ByteArray {
        raf.seek(slot * SLOT_SIZE)
        val checksumBytes = ByteArray(4)
        raf.readFully(checksumBytes)
        val expectedChecksum = ((checksumBytes[0].toInt() and 0xFF) shl 24) or
            ((checksumBytes[1].toInt() and 0xFF) shl 16) or
            ((checksumBytes[2].toInt() and 0xFF) shl 8) or
            (checksumBytes[3].toInt() and 0xFF)
        val payload = ByteArray(Page.PAGE_SIZE)
        raf.readFully(payload)
        val actualChecksum = Crc32C.of(payload)
        if (actualChecksum != expectedChecksum) {
            throw CorruptPageException("checksum mismatch at slot $slot in ${file.path}")
        }
        return payload
    }
}
