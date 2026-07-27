package kestrel.store.heap

import kestrel.KestrelException
import kestrel.store.page.Page

class HeapPageException(message: String) : KestrelException(message)

/**
 * Slotted-page layout for heap-organized storage.
 *
 * ```
 * [ header (12B) | slot directory, grows down | free space | tuple bytes, grow up ]
 * ```
 *
 * The header holds the id of the next page in the heap's page chain, the
 * slot count, and the offset where tuple bytes currently begin. Each slot
 * directory entry is `[offset:u16][length:u16]`; a length of
 * [TOMBSTONE_LENGTH] marks a deleted slot. Deleting a record only tombstones
 * its slot so the [RecordId] stays stable; [compact] later reclaims the
 * space by repacking live tuples against the end of the page.
 */
object HeapPage {
    private const val HEADER_SIZE = 12
    private const val SLOT_SIZE = 4
    private const val TOMBSTONE_LENGTH = 0xFFFF
    const val NO_PAGE = -1L

    val MAX_RECORD_SIZE: Int = Page.PAGE_SIZE - HEADER_SIZE - SLOT_SIZE

    fun init(page: Page, nextPageId: Long = NO_PAGE) {
        page.data.fill(0)
        writeLong(page, 0, nextPageId)
        writeShort(page, 8, 0)
        writeShort(page, 10, Page.PAGE_SIZE)
        page.isDirty = true
    }

    fun nextPageId(page: Page): Long = readLong(page, 0)

    fun setNextPageId(page: Page, pageId: Long) {
        writeLong(page, 0, pageId)
        page.isDirty = true
    }

    fun slotCount(page: Page): Int = readShort(page, 8)

    fun freeSpaceOffset(page: Page): Int = readShort(page, 10)

    fun freeBytes(page: Page): Int = freeSpaceOffset(page) - directoryEnd(page)

    fun isDeleted(page: Page, slotId: Int): Boolean = lengthAt(page, slotId) == TOMBSTONE_LENGTH

    fun lengthOf(page: Page, slotId: Int): Int {
        val length = lengthAt(page, slotId)
        if (length == TOMBSTONE_LENGTH) throw HeapPageException("slot $slotId is deleted")
        return length
    }

    fun get(page: Page, slotId: Int): ByteArray {
        val entry = slotEntryOffset(page, slotId)
        val length = readShort(page, entry + 2)
        if (length == TOMBSTONE_LENGTH) throw HeapPageException("slot $slotId is deleted")
        val offset = readShort(page, entry)
        return page.data.copyOfRange(offset, offset + length)
    }

    /** Returns the new slot id, or null if [bytes] does not fit even after [compact]. */
    fun insert(page: Page, bytes: ByteArray): Int? {
        require(bytes.size <= MAX_RECORD_SIZE) { "record of ${bytes.size} bytes exceeds page capacity" }
        if (!fits(page, bytes.size)) {
            compact(page)
            if (!fits(page, bytes.size)) return null
        }
        val slotId = slotCount(page)
        val newOffset = freeSpaceOffset(page) - bytes.size
        System.arraycopy(bytes, 0, page.data, newOffset, bytes.size)
        writeSlotEntry(page, slotId, newOffset, bytes.size)
        writeShort(page, 8, slotId + 1)
        writeShort(page, 10, newOffset)
        page.isDirty = true
        return slotId
    }

    fun delete(page: Page, slotId: Int) {
        val entry = slotEntryOffset(page, slotId)
        if (readShort(page, entry + 2) == TOMBSTONE_LENGTH) throw HeapPageException("slot $slotId is already deleted")
        writeShort(page, entry, 0)
        writeShort(page, entry + 2, TOMBSTONE_LENGTH)
        page.isDirty = true
    }

    /** Overwrites a slot in place. [bytes] must be no larger than the slot's current length. */
    fun overwrite(page: Page, slotId: Int, bytes: ByteArray) {
        val entry = slotEntryOffset(page, slotId)
        val length = readShort(page, entry + 2)
        if (length == TOMBSTONE_LENGTH) throw HeapPageException("slot $slotId is deleted")
        require(bytes.size <= length) { "overwrite of ${bytes.size} bytes must not grow slot $slotId (capacity $length)" }
        val offset = readShort(page, entry)
        System.arraycopy(bytes, 0, page.data, offset, bytes.size)
        writeShort(page, entry + 2, bytes.size)
        page.isDirty = true
    }

    /** Repacks live tuples against the end of the page, discarding tombstoned bytes. */
    fun compact(page: Page) {
        val count = slotCount(page)
        val live = ArrayList<Pair<Int, ByteArray>>(count)
        for (slotId in 0 until count) {
            val entry = slotEntryOffset(page, slotId)
            val length = readShort(page, entry + 2)
            if (length == TOMBSTONE_LENGTH) continue
            val offset = readShort(page, entry)
            live.add(slotId to page.data.copyOfRange(offset, offset + length))
        }
        var writeOffset = Page.PAGE_SIZE
        for ((slotId, bytes) in live) {
            writeOffset -= bytes.size
            System.arraycopy(bytes, 0, page.data, writeOffset, bytes.size)
            writeSlotEntry(page, slotId, writeOffset, bytes.size)
        }
        writeShort(page, 10, writeOffset)
        page.isDirty = true
    }

    private fun fits(page: Page, size: Int): Boolean = directoryEnd(page) + SLOT_SIZE + size <= freeSpaceOffset(page)

    private fun directoryEnd(page: Page): Int = HEADER_SIZE + slotCount(page) * SLOT_SIZE

    private fun lengthAt(page: Page, slotId: Int): Int = readShort(page, slotEntryOffset(page, slotId) + 2)

    private fun slotEntryOffset(page: Page, slotId: Int): Int {
        if (slotId !in 0 until slotCount(page)) throw HeapPageException("slot $slotId out of range")
        return HEADER_SIZE + slotId * SLOT_SIZE
    }

    private fun writeSlotEntry(page: Page, slotId: Int, offset: Int, length: Int) {
        val entry = HEADER_SIZE + slotId * SLOT_SIZE
        writeShort(page, entry, offset)
        writeShort(page, entry + 2, length)
    }

    private fun readShort(page: Page, at: Int): Int =
        ((page.data[at].toInt() and 0xFF) shl 8) or (page.data[at + 1].toInt() and 0xFF)

    private fun writeShort(page: Page, at: Int, value: Int) {
        page.data[at] = (value ushr 8).toByte()
        page.data[at + 1] = value.toByte()
    }

    private fun readLong(page: Page, at: Int): Long {
        var result = 0L
        for (i in 0 until 8) result = (result shl 8) or (page.data[at + i].toLong() and 0xFF)
        return result
    }

    private fun writeLong(page: Page, at: Int, value: Long) {
        for (i in 0 until 8) page.data[at + i] = (value ushr (8 * (7 - i))).toByte()
    }
}
