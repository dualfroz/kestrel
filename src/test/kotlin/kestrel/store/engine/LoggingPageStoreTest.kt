package kestrel.store.engine

import kestrel.store.heap.HeapFile
import kestrel.store.heap.HeapPageException
import kestrel.store.page.BufferPool
import kestrel.store.page.DiskManager
import kestrel.store.txn.TransactionManager
import kestrel.store.wal.RecoveryManager
import kestrel.store.wal.WriteAheadLog
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoggingPageStoreTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private inner class Fixture {
        val diskManager = DiskManager(tempFile("kestrel-logstore-data"))
        val bufferPool = BufferPool(diskManager, 16)
        val wal = WriteAheadLog(tempFile("kestrel-logstore-wal"))
        val txnManager = TransactionManager(wal, diskManager)
    }

    @Test
    fun insertThroughTheLoggingStoreProducesAWalRecordForTheMutatedPage() {
        val fx = Fixture()
        val heapHeaderId = HeapFile.create(fx.bufferPool).headerPageId

        val txnId = fx.txnManager.begin()
        val touched = mutableListOf<Long>()
        val logging = LoggingPageStore(fx.bufferPool, fx.txnManager, txnId) { touched.add(it) }
        val rid = HeapFile.open(logging, heapHeaderId).insert("hello".toByteArray())
        logging.finish()

        assertTrue(rid.pageId in touched, "the page the record landed on must be reported to the touch callback")
        assertTrue(fx.wal.lastLsnOf(txnId) != WriteAheadLog.NO_LSN, "insert must produce at least one logged update")
    }

    @Test
    fun readOnlyAccessProducesNoWalRecord() {
        val fx = Fixture()
        val heap = HeapFile.create(fx.bufferPool)
        val rid = heap.insert("already there".toByteArray())
        fx.bufferPool.flushAll()

        val txnId = fx.txnManager.begin()
        val lsnAfterBegin = fx.wal.lastLsnOf(txnId)
        val logging = LoggingPageStore(fx.bufferPool, fx.txnManager, txnId)
        val bytes = HeapFile.open(logging, heap.headerPageId).get(rid)
        logging.finish()

        assertEquals("already there", String(bytes))
        assertEquals(lsnAfterBegin, fx.wal.lastLsnOf(txnId), "a pure read must not append any update record")
    }

    @Test
    fun rollingBackAnUnfinishedInsertErasesItFromThePage() {
        val fx = Fixture()
        val heapHeaderId = HeapFile.create(fx.bufferPool).headerPageId

        val txnId = fx.txnManager.begin()
        val logging = LoggingPageStore(fx.bufferPool, fx.txnManager, txnId)
        val rid = HeapFile.open(logging, heapHeaderId).insert("temporary".toByteArray())
        logging.finish()

        RecoveryManager(fx.wal, fx.diskManager).rollback(txnId)
        fx.bufferPool.invalidate(rid.pageId)

        assertFailsWith<HeapPageException> { HeapFile.open(fx.bufferPool, heapHeaderId).get(rid) }
    }
}
