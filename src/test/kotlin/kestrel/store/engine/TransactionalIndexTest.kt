package kestrel.store.engine

import kestrel.store.page.BufferPool
import kestrel.store.page.DiskManager
import kestrel.store.txn.TransactionManager
import kestrel.store.wal.WriteAheadLog
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TransactionalIndexTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun key(i: Int): ByteArray = "key-%05d".format(i).toByteArray()

    private inner class Fixture {
        val diskManager = DiskManager(tempFile("kestrel-txindex-data"))
        val bufferPool = BufferPool(diskManager, 64)
        val wal = WriteAheadLog(tempFile("kestrel-txindex-wal"))
        val txnManager = TransactionManager(wal, diskManager)
        val tracker = TouchedPageTracker()
        val index = TransactionalIndex.create(bufferPool, txnManager, tracker, lockResourceId = -1L)

        fun abort(txnId: Long) {
            txnManager.abort(txnId)
            index.rollback(txnId)
            for (pageId in tracker.forget(txnId)) bufferPool.invalidate(pageId)
        }

        fun commit(txnId: Long) {
            txnManager.commit(txnId)
            index.forgetTransaction(txnId)
            tracker.forget(txnId)
        }
    }

    @Test
    fun aCommittedEntryIsVisibleToLaterTransactions() {
        val fx = Fixture()
        val writer = fx.txnManager.begin()
        fx.index.insert(writer, key(1), 100L)
        fx.commit(writer)

        val reader = fx.txnManager.begin()
        assertEquals(100L, fx.index.search(reader, key(1)))
    }

    @Test
    fun deleteThenCommitRemovesTheEntry() {
        val fx = Fixture()
        val setup = fx.txnManager.begin()
        fx.index.insert(setup, key(1), 1L)
        fx.commit(setup)

        val writer = fx.txnManager.begin()
        fx.index.delete(writer, key(1))
        fx.commit(writer)

        val reader = fx.txnManager.begin()
        assertNull(fx.index.search(reader, key(1)))
    }

    @Test
    fun enoughInsertsForceARootSplitAndEveryKeyStaysSearchable() {
        val fx = Fixture()
        val originalRoot = fx.index.rootPageId
        val writer = fx.txnManager.begin()
        for (i in 0 until 400) fx.index.insert(writer, key(i), i.toLong())
        fx.commit(writer)

        assertNotEquals(originalRoot, fx.index.rootPageId, "400 entries must have split the original single-leaf root")
        val reader = fx.txnManager.begin()
        for (i in 0 until 400) assertEquals(i.toLong(), fx.index.search(reader, key(i)))
    }

    @Test
    fun abortingAWriteThatSplitTheRootRestoresTheOldRootAndKeepsPriorDataIntact() {
        val fx = Fixture()
        val setup = fx.txnManager.begin()
        fx.index.insert(setup, key(0), 0L)
        fx.commit(setup)
        val rootBeforeWriter = fx.index.rootPageId

        val writer = fx.txnManager.begin()
        for (i in 1 until 400) fx.index.insert(writer, key(i), i.toLong())
        assertNotEquals(rootBeforeWriter, fx.index.rootPageId, "the writer's own inserts must have split the root")

        fx.abort(writer)

        assertEquals(rootBeforeWriter, fx.index.rootPageId, "abort must restore the root pointer as it was before the writer began")
        val reader = fx.txnManager.begin()
        assertEquals(0L, fx.index.search(reader, key(0)), "data committed before the aborted writer must survive")
        assertNull(fx.index.search(reader, key(1)), "data inserted by the aborted writer must not be visible")
    }

    @Test
    fun rangeScanReturnsKeysInOrderWithinBounds() {
        val fx = Fixture()
        val writer = fx.txnManager.begin()
        for (i in 0 until 50) fx.index.insert(writer, key(i), i.toLong())
        fx.commit(writer)

        val reader = fx.txnManager.begin()
        val scanned = fx.index.rangeScan(reader, from = key(10), to = key(15), toInclusive = true)
        assertEquals((10..15).map { it.toLong() }, scanned.map { it.second })
        assertTrue(scanned.zipWithNext().all { (a, b) -> kestrel.store.codec.KeyCodec.compare(a.first, b.first) < 0 })
    }

    @Test
    fun destroyFreesEveryPageSoALaterIndexReusesThemInsteadOfGrowingTheFile() {
        val fx = Fixture()
        val writer = fx.txnManager.begin()
        for (i in 0 until 400) fx.index.insert(writer, key(i), i.toLong())
        fx.commit(writer)
        val peak = fx.diskManager.allocatedPageCount()

        val destroyer = fx.txnManager.begin()
        fx.index.destroy(destroyer)
        fx.txnManager.commit(destroyer)

        val fresh = TransactionalIndex.create(fx.bufferPool, fx.txnManager, fx.tracker, lockResourceId = -2L)
        val filler = fx.txnManager.begin()
        for (i in 0 until 400) fresh.insert(filler, key(i), i.toLong())
        fx.txnManager.commit(filler)

        assertTrue(
            fx.diskManager.allocatedPageCount() <= peak,
            "expected the destroyed index's pages to be reused by the new index instead of growing the file " +
                "further (peak=$peak, now=${fx.diskManager.allocatedPageCount()})"
        )
    }
}
