package kestrel.store.engine

import kestrel.store.heap.HeapFileException
import kestrel.store.page.BufferPool
import kestrel.store.page.DiskManager
import kestrel.store.txn.TransactionManager
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransactionalHeapTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private inner class Fixture {
        val diskManager = DiskManager(tempFile("kestrel-txheap-data"))
        val bufferPool = BufferPool(diskManager, 32)
        val wal = kestrel.store.wal.WriteAheadLog(tempFile("kestrel-txheap-wal"))
        val txnManager = TransactionManager(wal, diskManager)
        val tracker = TouchedPageTracker()
        val heap = TransactionalHeap.create(bufferPool, txnManager, tracker)

        fun abort(txnId: Long) {
            txnManager.abort(txnId)
            for (pageId in tracker.forget(txnId)) bufferPool.invalidate(pageId)
        }
    }

    @Test
    fun aCommittedInsertIsVisibleToLaterTransactions() {
        val fx = Fixture()
        val writer = fx.txnManager.begin()
        val rid = fx.heap.insert(writer, "durable row".toByteArray())
        fx.txnManager.commit(writer)

        val reader = fx.txnManager.begin()
        assertEquals("durable row", String(fx.heap.get(reader, rid)))
        assertEquals(listOf(rid), fx.heap.scan(reader).map { it.first })
    }

    @Test
    fun anAbortedInsertDisappearsImmediatelyEvenThoughItsPageIsStillCached() {
        val fx = Fixture()
        val writer = fx.txnManager.begin()
        val rid = fx.heap.insert(writer, "should vanish".toByteArray())
        fx.abort(writer)

        val reader = fx.txnManager.begin()
        assertTrue(fx.heap.scan(reader).isEmpty(), "an aborted insert must not be visible, cache or no cache")
        assertFailsWith<Exception> { fx.heap.get(reader, rid) }
    }

    @Test
    fun updateThenAbortRestoresThePriorValue() {
        val fx = Fixture()
        val setup = fx.txnManager.begin()
        val rid = fx.heap.insert(setup, "original".toByteArray())
        fx.txnManager.commit(setup)

        val writer = fx.txnManager.begin()
        fx.heap.update(writer, rid, "changed".toByteArray())
        fx.abort(writer)

        val reader = fx.txnManager.begin()
        assertEquals("original", String(fx.heap.get(reader, rid)))
    }

    @Test
    fun deleteThenAbortRestoresTheRecord() {
        val fx = Fixture()
        val setup = fx.txnManager.begin()
        val rid = fx.heap.insert(setup, "keep me".toByteArray())
        fx.txnManager.commit(setup)

        val writer = fx.txnManager.begin()
        fx.heap.delete(writer, rid)
        fx.abort(writer)

        val reader = fx.txnManager.begin()
        assertEquals("keep me", String(fx.heap.get(reader, rid)))
    }

    @Test
    fun destroyFreesEveryPageSoALaterHeapReusesThemInsteadOfGrowingTheFile() {
        val fx = Fixture()
        val writer = fx.txnManager.begin()
        for (i in 0 until 40) fx.heap.insert(writer, ByteArray(200) { i.toByte() })
        fx.txnManager.commit(writer)
        val peak = fx.diskManager.allocatedPageCount()

        val destroyer = fx.txnManager.begin()
        fx.heap.destroy(destroyer)
        fx.txnManager.commit(destroyer)

        val fresh = TransactionalHeap.create(fx.bufferPool, fx.txnManager, fx.tracker)
        val filler = fx.txnManager.begin()
        for (i in 0 until 40) fresh.insert(filler, ByteArray(200) { i.toByte() })
        fx.txnManager.commit(filler)

        assertTrue(
            fx.diskManager.allocatedPageCount() <= peak,
            "expected the destroyed heap's pages to be reused by the new heap instead of growing the file " +
                "further (peak=$peak, now=${fx.diskManager.allocatedPageCount()})"
        )
    }

    @Test
    fun oversizedRecordFailsWithoutLoggingAnything() {
        val fx = Fixture()
        val txnId = fx.txnManager.begin()
        assertFailsWith<HeapFileException> {
            fx.heap.insert(txnId, ByteArray(kestrel.store.heap.HeapPage.MAX_RECORD_SIZE + 1))
        }
        fx.txnManager.commit(txnId)
    }
}
