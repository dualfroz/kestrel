package kestrel.store.page

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BufferPoolTest {
    private fun newManager() = DiskManager(Files.createTempFile("kestrel-pool", ".db").toFile().also { it.deleteOnExit() })

    @Test
    fun fetchingTheSamePageTwiceReturnsTheSameCachedInstance() {
        val pool = BufferPool(newManager(), capacity = 4)
        val page = pool.newPage()
        page.data[0] = 1
        pool.unpinPage(page.id, true)
        val first = pool.fetchPage(page.id)
        val second = pool.fetchPage(page.id)
        assertTrue(first === second)
        pool.unpinPage(page.id, false)
        pool.unpinPage(page.id, false)
    }

    @Test
    fun dirtyPageIsPersistedOnEviction() {
        val manager = newManager()
        val pool = BufferPool(manager, capacity = 1)
        val page = pool.newPage()
        page.data[0] = 99
        pool.unpinPage(page.id, true)

        // Force eviction of the only frame by fetching a second page.
        val other = pool.newPage()
        pool.unpinPage(other.id, false)

        val reloaded = manager.readPage(page.id)
        assertEquals(99, reloaded.data[0])
    }

    @Test
    fun cleanPageIsNotRewrittenOnEviction() {
        val manager = newManager()
        val pool = BufferPool(manager, capacity = 1)
        val page = pool.newPage()
        pool.unpinPage(page.id, true)
        pool.flushAll()

        val fetched = pool.fetchPage(page.id)
        fetched.data[5] = 55 // mutate without marking dirty
        pool.unpinPage(page.id, markDirty = false)

        pool.newPage() // evicts the clean page without writeback
        val reloaded = manager.readPage(page.id)
        assertEquals(0, reloaded.data[5])
    }

    @Test
    fun pinnedPagesAreNeverEvicted() {
        val pool = BufferPool(newManager(), capacity = 1)
        val pinned = pool.newPage() // pinCount == 1, never unpinned
        assertFailsWith<BufferPoolExhaustedException> { pool.newPage() }
        pool.unpinPage(pinned.id, false)
    }

    @Test
    fun clockGivesRecentlyTouchedPagesASecondChanceOverStalePages() {
        val pool = BufferPool(newManager(), capacity = 3)
        val a = pool.newPage(); pool.unpinPage(a.id, true)
        val b = pool.newPage(); pool.unpinPage(b.id, true)
        val c = pool.newPage(); pool.unpinPage(c.id, true)

        // One full clock sweep: evicts `a` (first frame revisited) and clears b/c's reference bits.
        val d = pool.newPage(); pool.unpinPage(d.id, true)

        // Refresh `c`'s reference bit by touching it again; `b` is left stale.
        val cTouch = pool.fetchPage(c.id)
        pool.unpinPage(c.id, false)

        // Next eviction must take the stale page (`b`), sparing the freshly touched `c`.
        val e = pool.newPage(); pool.unpinPage(e.id, true)

        assertTrue(pool.fetchPage(c.id) === cTouch, "c should still be cached, not re-read from disk")
        pool.unpinPage(c.id, false)
        assertTrue(pool.fetchPage(b.id) !== b, "b should have been evicted and re-read as a fresh instance")
        pool.unpinPage(b.id, false)
    }

    @Test
    fun deletingAPageFreesItInTheDiskManager() {
        val manager = newManager()
        val pool = BufferPool(manager, capacity = 4)
        val page = pool.newPage()
        val id = page.id
        pool.unpinPage(id, true)
        pool.deletePage(id)
        val recycled = pool.newPage()
        assertEquals(id, recycled.id)
        pool.unpinPage(recycled.id, true)
    }

    @Test
    fun deletingAPinnedPageIsRejected() {
        val pool = BufferPool(newManager(), capacity = 4)
        val page = pool.newPage()
        assertFailsWith<IllegalStateException> { pool.deletePage(page.id) }
        pool.unpinPage(page.id, false)
    }

    @Test
    fun flushPageWritesBackImmediatelyWithoutEviction() {
        val manager = newManager()
        val pool = BufferPool(manager, capacity = 4)
        val page = pool.newPage()
        page.data[0] = 77
        pool.unpinPage(page.id, true)
        pool.flushPage(page.id)
        assertEquals(77, manager.readPage(page.id).data[0])
    }

    @Test
    fun invalidateDropsADirtyPageWithoutWritingItBack() {
        val manager = newManager()
        val pool = BufferPool(manager, capacity = 4)
        val page = pool.newPage()
        pool.unpinPage(page.id, true)
        pool.flushAll() // establishes a known, all-zero baseline on disk

        val fetched = pool.fetchPage(page.id)
        fetched.data[0] = 42
        pool.unpinPage(page.id, true)

        pool.invalidate(page.id)

        assertEquals(0, manager.readPage(page.id).data[0], "invalidate must not flush the dirty page it drops")
        val refetched = pool.fetchPage(page.id)
        assertTrue(refetched !== fetched, "a page dropped by invalidate must be re-read fresh from disk")
        pool.unpinPage(page.id, false)
    }

    @Test
    fun invalidatingAPinnedPageIsRejected() {
        val pool = BufferPool(newManager(), capacity = 4)
        val page = pool.newPage()
        assertFailsWith<IllegalStateException> { pool.invalidate(page.id) }
        pool.unpinPage(page.id, false)
    }

    @Test
    fun invalidatingAnUncachedPageIsANoOp() {
        val pool = BufferPool(newManager(), capacity = 4)
        pool.invalidate(999L)
    }
}
