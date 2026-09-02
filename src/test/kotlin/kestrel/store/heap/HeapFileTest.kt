package kestrel.store.heap

import kestrel.store.page.BufferPool
import kestrel.store.page.DiskManager
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HeapFileTest {
    private fun tempFile(): File = Files.createTempFile("kestrel-heap", ".db").toFile().also { it.deleteOnExit() }

    private fun newPool(file: File = tempFile(), capacity: Int = 64): BufferPool = BufferPool(DiskManager(file), capacity)

    @Test
    fun insertAndGetRoundTrips() {
        val heap = HeapFile.create(newPool())
        val rid = heap.insert("hello world".toByteArray())
        assertEquals("hello world", String(heap.get(rid)))
    }

    @Test
    fun insertManySmallRecordsAllReadableIndividually() {
        val heap = HeapFile.create(newPool())
        val ids = (0 until 50).map { heap.insert("row-$it".toByteArray()) }
        for ((index, rid) in ids.withIndex()) {
            assertEquals("row-$index", String(heap.get(rid)))
        }
    }

    @Test
    fun insertSpillsToANewPageWhenCurrentPageIsFull() {
        val heap = HeapFile.create(newPool())
        val ids = (0 until 40).map { heap.insert(ByteArray(200) { it.toByte() }) }
        val distinctPages = ids.map { it.pageId }.toSet()
        assertTrue(distinctPages.size > 1, "expected records to spread across multiple pages")
        for (rid in ids) assertEquals(200, heap.get(rid).size)
    }

    @Test
    fun scanReturnsEveryLiveRecordAcrossAllPages() {
        val heap = HeapFile.create(newPool())
        val inserted = (0 until 30).map { heap.insert(ByteArray(300) { i -> i.toByte() }) }.toSet()
        val scanned = heap.scan().map { it.first }.toSet()
        assertEquals(inserted, scanned)
    }

    @Test
    fun deleteRemovesRecordFromScanAndGetFails() {
        val heap = HeapFile.create(newPool())
        val keep = heap.insert("keep".toByteArray())
        val drop = heap.insert("drop".toByteArray())
        heap.delete(drop)
        assertEquals(setOf(keep), heap.scan().map { it.first }.toSet())
        assertFailsWith<HeapPageException> { heap.get(drop) }
    }

    @Test
    fun updateThatFitsInPlaceKeepsSameRecordId() {
        val heap = HeapFile.create(newPool())
        val rid = heap.insert("original text".toByteArray())
        val updated = heap.update(rid, "short".toByteArray())
        assertEquals(rid, updated)
        assertEquals("short", String(heap.get(rid)))
    }

    @Test
    fun updateThatOutgrowsSlotReturnsNewRecordIdAndDropsOld() {
        val heap = HeapFile.create(newPool())
        val rid = heap.insert("tiny".toByteArray())
        val updated = heap.update(rid, "a much longer replacement value".toByteArray())
        assertTrue(updated != rid)
        assertEquals("a much longer replacement value", String(heap.get(updated)))
        assertFailsWith<HeapPageException> { heap.get(rid) }
    }

    @Test
    fun deletedSpaceIsReusedByLaterInsertsBeforeGrowingTheFile() {
        val heap = HeapFile.create(newPool())
        val filler = (0 until 15).map { heap.insert(ByteArray(250)) }
        val pagesAfterFill = filler.map { it.pageId }.toSet()
        for (rid in filler.take(10)) heap.delete(rid)
        val reused = heap.insert(ByteArray(250))
        assertTrue(reused.pageId in pagesAfterFill, "expected reclaimed space to be reused instead of allocating a new page")
    }

    @Test
    fun insertRejectsRecordLargerThanAnEmptyPage() {
        val heap = HeapFile.create(newPool())
        assertFailsWith<HeapFileException> { heap.insert(ByteArray(HeapPage.MAX_RECORD_SIZE + 1)) }
    }

    @Test
    fun allPageIdsOnAFreshHeapIsJustTheHeader() {
        val heap = HeapFile.create(newPool())
        assertEquals(listOf(heap.headerPageId), heap.allPageIds())
    }

    @Test
    fun allPageIdsCoversTheHeaderAndEveryDataPage() {
        val heap = HeapFile.create(newPool())
        val ids = (0 until 40).map { heap.insert(ByteArray(200) { it.toByte() }) }
        val dataPages = ids.map { it.pageId }.toSet()

        val allPageIds = heap.allPageIds()
        assertEquals(allPageIds.size, allPageIds.toSet().size, "expected no page id to be visited twice")
        assertTrue(allPageIds.contains(heap.headerPageId))
        assertEquals(dataPages + heap.headerPageId, allPageIds.toSet())
    }

    @Test
    fun reopeningWithSameHeaderPageSeesPersistedData() {
        val file = tempFile()
        val diskManager = DiskManager(file)
        val pool = BufferPool(diskManager, 64)
        val heap = HeapFile.create(pool)
        val rid = heap.insert("durable".toByteArray())
        pool.flushAll()

        val reopened = HeapFile.open(pool, heap.headerPageId)
        assertEquals("durable", String(reopened.get(rid)))
    }
}
