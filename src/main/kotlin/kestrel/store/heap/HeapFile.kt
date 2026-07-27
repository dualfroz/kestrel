package kestrel.store.heap

import kestrel.KestrelException
import kestrel.store.page.Page
import kestrel.store.page.PageStore

class HeapFileException(message: String) : KestrelException(message)

/**
 * A heap-organized collection of variable-length records spanning a singly
 * linked chain of [HeapPage]s. The chain head lives in a dedicated header
 * page whose id ([headerPageId]) callers persist to reopen the same heap
 * later, mirroring how [kestrel.store.btree.BPlusTree] exposes its root page
 * id. Insert walks the chain looking for a page with enough free space,
 * newest first, before allocating and linking a fresh page; this reuses
 * space freed by deletes ahead of growing the file.
 */
class HeapFile private constructor(private val bufferPool: PageStore, val headerPageId: Long) {
    companion object {
        fun create(bufferPool: PageStore): HeapFile {
            val header = bufferPool.newPage()
            writeFirstDataPage(header, HeapPage.NO_PAGE)
            bufferPool.unpinPage(header.id, true)
            return HeapFile(bufferPool, header.id)
        }

        fun open(bufferPool: PageStore, headerPageId: Long): HeapFile = HeapFile(bufferPool, headerPageId)

        private fun writeFirstDataPage(header: Page, pageId: Long) {
            for (i in 0 until 8) header.data[i] = (pageId ushr (8 * (7 - i))).toByte()
            header.isDirty = true
        }

        private fun readFirstDataPage(header: Page): Long {
            var result = 0L
            for (i in 0 until 8) result = (result shl 8) or (header.data[i].toLong() and 0xFF)
            return result
        }
    }

    fun insert(bytes: ByteArray): RecordId {
        if (bytes.size > HeapPage.MAX_RECORD_SIZE) {
            throw HeapFileException("record of ${bytes.size} bytes exceeds max ${HeapPage.MAX_RECORD_SIZE}")
        }
        val header = bufferPool.fetchPage(headerPageId)
        val firstPageId = readFirstDataPage(header)
        var scanId = firstPageId
        while (scanId != HeapPage.NO_PAGE) {
            val page = bufferPool.fetchPage(scanId)
            val slotId = HeapPage.insert(page, bytes)
            if (slotId != null) {
                bufferPool.unpinPage(scanId, true)
                bufferPool.unpinPage(headerPageId, false)
                return RecordId(scanId, slotId)
            }
            val next = HeapPage.nextPageId(page)
            bufferPool.unpinPage(scanId, false)
            scanId = next
        }
        val newPage = bufferPool.newPage()
        HeapPage.init(newPage, firstPageId)
        val slotId = HeapPage.insert(newPage, bytes)
            ?: throw HeapFileException("record of ${bytes.size} bytes does not fit in an empty page")
        bufferPool.unpinPage(newPage.id, true)
        writeFirstDataPage(header, newPage.id)
        bufferPool.unpinPage(headerPageId, true)
        return RecordId(newPage.id, slotId)
    }

    fun get(rid: RecordId): ByteArray {
        val page = bufferPool.fetchPage(rid.pageId)
        try {
            return HeapPage.get(page, rid.slotId)
        } finally {
            bufferPool.unpinPage(rid.pageId, false)
        }
    }

    fun delete(rid: RecordId) {
        val page = bufferPool.fetchPage(rid.pageId)
        HeapPage.delete(page, rid.slotId)
        bufferPool.unpinPage(rid.pageId, true)
    }

    /**
     * Replaces the record at [rid]. When [bytes] fits within the slot's
     * current capacity it is overwritten in place and [rid] is returned
     * unchanged; otherwise the old slot is deleted and the record is
     * re-inserted, possibly moving to a different page, and the new
     * [RecordId] is returned. Callers that index this heap must update any
     * stored [RecordId] with the return value.
     */
    fun update(rid: RecordId, bytes: ByteArray): RecordId {
        val page = bufferPool.fetchPage(rid.pageId)
        val capacity = HeapPage.lengthOf(page, rid.slotId)
        if (bytes.size <= capacity) {
            HeapPage.overwrite(page, rid.slotId, bytes)
            bufferPool.unpinPage(rid.pageId, true)
            return rid
        }
        HeapPage.delete(page, rid.slotId)
        bufferPool.unpinPage(rid.pageId, true)
        return insert(bytes)
    }

    /** Every page belonging to this heap: [headerPageId] plus every page in the data-page chain. */
    fun allPageIds(): List<Long> {
        val ids = mutableListOf(headerPageId)
        val header = bufferPool.fetchPage(headerPageId)
        var pageId = readFirstDataPage(header)
        bufferPool.unpinPage(headerPageId, false)
        while (pageId != HeapPage.NO_PAGE) {
            val page = bufferPool.fetchPage(pageId)
            val next = HeapPage.nextPageId(page)
            bufferPool.unpinPage(pageId, false)
            ids.add(pageId)
            pageId = next
        }
        return ids
    }

    /** Iterates every live record in page-chain order. Deleted slots are skipped. */
    fun scan(): Sequence<Pair<RecordId, ByteArray>> = sequence {
        val header = bufferPool.fetchPage(headerPageId)
        var pageId = readFirstDataPage(header)
        bufferPool.unpinPage(headerPageId, false)
        while (pageId != HeapPage.NO_PAGE) {
            val page = bufferPool.fetchPage(pageId)
            val count = HeapPage.slotCount(page)
            val next = HeapPage.nextPageId(page)
            val liveBytes = (0 until count)
                .filterNot { HeapPage.isDeleted(page, it) }
                .map { RecordId(pageId, it) to HeapPage.get(page, it) }
            bufferPool.unpinPage(pageId, false)
            for (entry in liveBytes) yield(entry)
            pageId = next
        }
    }
}
