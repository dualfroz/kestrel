package kestrel.store.heap

import kestrel.store.page.Page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeapPageTest {
    private fun freshPage(): Page = Page(0).also { HeapPage.init(it) }

    @Test
    fun freshPageHasNoSlotsAndFullFreeSpace() {
        val page = freshPage()
        assertEquals(0, HeapPage.slotCount(page))
        assertEquals(HeapPage.NO_PAGE, HeapPage.nextPageId(page))
        assertTrue(HeapPage.freeBytes(page) > HeapPage.MAX_RECORD_SIZE)
    }

    @Test
    fun insertAndGetRoundTrips() {
        val page = freshPage()
        val slot = HeapPage.insert(page, "hello".toByteArray())
        assertEquals(0, slot)
        assertEquals("hello", String(HeapPage.get(page, slot!!)))
    }

    @Test
    fun multipleSlotsPreserveIndependentContent() {
        val page = freshPage()
        val a = HeapPage.insert(page, "alpha".toByteArray())!!
        val b = HeapPage.insert(page, "beta".toByteArray())!!
        val c = HeapPage.insert(page, "gamma".toByteArray())!!
        assertEquals("alpha", String(HeapPage.get(page, a)))
        assertEquals("beta", String(HeapPage.get(page, b)))
        assertEquals("gamma", String(HeapPage.get(page, c)))
    }

    @Test
    fun freeBytesShrinksAsRecordsAreInserted() {
        val page = freshPage()
        val before = HeapPage.freeBytes(page)
        HeapPage.insert(page, ByteArray(100))
        val after = HeapPage.freeBytes(page)
        assertEquals(100 + 4, before - after)
    }

    @Test
    fun insertReturnsNullWhenPageIsFull() {
        val page = freshPage()
        var last: Int? = 0
        while (last != null) {
            last = HeapPage.insert(page, ByteArray(200))
        }
        assertNull(HeapPage.insert(page, ByteArray(200)))
    }

    @Test
    fun deleteTombstonesSlotAndGetThrows() {
        val page = freshPage()
        val slot = HeapPage.insert(page, "doomed".toByteArray())!!
        HeapPage.delete(page, slot)
        assertTrue(HeapPage.isDeleted(page, slot))
        assertFailsWith<HeapPageException> { HeapPage.get(page, slot) }
    }

    @Test
    fun deletingAlreadyDeletedSlotFails() {
        val page = freshPage()
        val slot = HeapPage.insert(page, "x".toByteArray())!!
        HeapPage.delete(page, slot)
        assertFailsWith<HeapPageException> { HeapPage.delete(page, slot) }
    }

    @Test
    fun compactReclaimsSpaceFromDeletedSlots() {
        val page = freshPage()
        val slots = ArrayList<Int>()
        while (true) {
            val slot = HeapPage.insert(page, ByteArray(250)) ?: break
            slots.add(slot)
        }
        for (slot in slots.take(slots.size / 2)) HeapPage.delete(page, slot)
        // Freed space is fragmented (tombstoned), not contiguous, until compact runs from within insert.
        assertTrue(HeapPage.insert(page, ByteArray(250)) != null)
    }

    @Test
    fun compactPreservesLiveSlotIdsAndContent() {
        val page = freshPage()
        val a = HeapPage.insert(page, "keep-a".toByteArray())!!
        val b = HeapPage.insert(page, "gone".toByteArray())!!
        val c = HeapPage.insert(page, "keep-c".toByteArray())!!
        HeapPage.delete(page, b)
        HeapPage.compact(page)
        assertEquals("keep-a", String(HeapPage.get(page, a)))
        assertEquals("keep-c", String(HeapPage.get(page, c)))
        assertTrue(HeapPage.isDeleted(page, b))
    }

    @Test
    fun overwriteInPlaceShrinksLength() {
        val page = freshPage()
        val slot = HeapPage.insert(page, "original-value".toByteArray())!!
        HeapPage.overwrite(page, slot, "short".toByteArray())
        assertEquals("short", String(HeapPage.get(page, slot)))
        assertEquals(5, HeapPage.lengthOf(page, slot))
    }

    @Test
    fun overwriteGrowingBeyondCapacityFails() {
        val page = freshPage()
        val slot = HeapPage.insert(page, "short".toByteArray())!!
        assertFailsWith<IllegalArgumentException> { HeapPage.overwrite(page, slot, "much-longer-value".toByteArray()) }
    }

    @Test
    fun accessingOutOfRangeSlotFails() {
        val page = freshPage()
        assertFailsWith<HeapPageException> { HeapPage.get(page, 0) }
        assertFailsWith<HeapPageException> { HeapPage.get(page, -1) }
    }

    @Test
    fun insertRejectsRecordLargerThanPage() {
        val page = freshPage()
        assertFailsWith<IllegalArgumentException> { HeapPage.insert(page, ByteArray(HeapPage.MAX_RECORD_SIZE + 1)) }
    }

    @Test
    fun setNextPageIdRoundTripsAndMarksPageDirty() {
        val page = freshPage()
        page.isDirty = false
        HeapPage.setNextPageId(page, 42L)
        assertEquals(42L, HeapPage.nextPageId(page))
        assertTrue(page.isDirty)
    }
}
