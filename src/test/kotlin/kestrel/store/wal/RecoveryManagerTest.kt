package kestrel.store.wal

import kestrel.store.page.DiskManager
import kestrel.store.page.Page
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecoveryManagerTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun pageOf(fill: Int): ByteArray = ByteArray(Page.PAGE_SIZE) { fill.toByte() }

    @Test
    fun redoRestoresACommittedWriteThatNeverReachedTheDataFile() {
        val dataFile = tempFile("kestrel-recovery-data")
        val walFile = tempFile("kestrel-recovery-wal")

        val diskManager = DiskManager(dataFile)
        val pageId = diskManager.allocatePage()
        val before = pageOf(0)
        diskManager.writePage(Page(pageId).also { System.arraycopy(before, 0, it.data, 0, before.size) })

        val wal = WriteAheadLog(walFile)
        val txn = wal.beginTransaction()
        val after = pageOf(7)
        wal.logUpdate(txn, pageId, before, after)
        wal.commit(txn)
        // Simulate a crash: the buffer pool never flushed `after` to the data file,
        // but the WAL frame for the update and the commit are durable.

        val result = RecoveryManager(wal, diskManager).recover()

        assertEquals(1, result.redoneUpdates)
        assertTrue(result.rolledBackTxns.isEmpty())
        assertContentEquals(after, diskManager.readPage(pageId).data)
    }

    @Test
    fun undoRestoresAPageWrittenByATransactionThatNeverCommitted() {
        val dataFile = tempFile("kestrel-recovery-data")
        val walFile = tempFile("kestrel-recovery-wal")

        val diskManager = DiskManager(dataFile)
        val pageId = diskManager.allocatePage()
        val before = pageOf(1)
        diskManager.writePage(Page(pageId).also { System.arraycopy(before, 0, it.data, 0, before.size) })

        val wal = WriteAheadLog(walFile)
        val txn = wal.beginTransaction()
        val after = pageOf(9)
        wal.logUpdate(txn, pageId, before, after)
        // Simulate a crash before commit, after the dirty page happened to reach disk
        // under a steal buffer-pool policy.
        diskManager.writePage(Page(pageId).also { System.arraycopy(after, 0, it.data, 0, after.size) })

        val result = RecoveryManager(wal, diskManager).recover()

        assertEquals(listOf(txn), result.rolledBackTxns)
        assertContentEquals(before, diskManager.readPage(pageId).data)
        val abortSeen = wal.readFrom(0L).any { it.second is LogRecord.Abort && (it.second as LogRecord.Abort).txnId == txn }
        assertTrue(abortSeen, "expected recovery to append an Abort record resolving the transaction")
    }

    @Test
    fun checkpointSkipsReplayingUpdatesFromBeforeIt() {
        val dataFile = tempFile("kestrel-recovery-data")
        val walFile = tempFile("kestrel-recovery-wal")

        val diskManager = DiskManager(dataFile)
        val pageA = diskManager.allocatePage()
        val pageB = diskManager.allocatePage()
        diskManager.writePage(Page(pageA).also { System.arraycopy(pageOf(0), 0, it.data, 0, Page.PAGE_SIZE) })
        diskManager.writePage(Page(pageB).also { System.arraycopy(pageOf(0), 0, it.data, 0, Page.PAGE_SIZE) })

        val wal = WriteAheadLog(walFile)
        val firstTxn = wal.beginTransaction()
        wal.logUpdate(firstTxn, pageA, pageOf(0), pageOf(5))
        wal.commit(firstTxn)
        wal.checkpoint()

        val secondTxn = wal.beginTransaction()
        wal.logUpdate(secondTxn, pageB, pageOf(0), pageOf(6))
        wal.commit(secondTxn)

        val result = RecoveryManager(wal, diskManager).recover()

        assertEquals(1, result.redoneUpdates, "only the update after the checkpoint should be replayed")
    }

    @Test
    fun liveRollbackUndoesUpdatesWithoutACrash() {
        val dataFile = tempFile("kestrel-recovery-data")
        val walFile = tempFile("kestrel-recovery-wal")

        val diskManager = DiskManager(dataFile)
        val pageId = diskManager.allocatePage()
        val before = pageOf(2)
        diskManager.writePage(Page(pageId).also { System.arraycopy(before, 0, it.data, 0, before.size) })

        val wal = WriteAheadLog(walFile)
        val txn = wal.beginTransaction()
        val after = pageOf(8)
        wal.logUpdate(txn, pageId, before, after)
        diskManager.writePage(Page(pageId).also { System.arraycopy(after, 0, it.data, 0, after.size) })

        RecoveryManager(wal, diskManager).rollback(txn)

        assertContentEquals(before, diskManager.readPage(pageId).data)
        assertEquals(WriteAheadLog.NO_LSN, wal.lastLsnOf(txn))
    }

    @Test
    fun recoverDoesNotResurrectAWriteFromATransactionThatWasAbortedInAnEarlierSession() {
        val dataFile = tempFile("kestrel-recovery-data")
        val walFile = tempFile("kestrel-recovery-wal")

        val diskManager = DiskManager(dataFile)
        val pageId = diskManager.allocatePage()
        val before = pageOf(3)
        diskManager.writePage(Page(pageId).also { System.arraycopy(before, 0, it.data, 0, before.size) })

        val wal = WriteAheadLog(walFile)
        val txn = wal.beginTransaction()
        val after = pageOf(4)
        wal.logUpdate(txn, pageId, before, after)
        RecoveryManager(wal, diskManager).rollback(txn)
        // The transaction is now resolved (its Update is followed by an Abort record)
        // and the data file already holds `before`, exactly as a live abort left it.

        // A fresh WriteAheadLog reader over the same file, as a new process would open.
        val reopenedWal = WriteAheadLog(walFile)
        val result = RecoveryManager(reopenedWal, diskManager).recover()

        assertEquals(0, result.redoneUpdates, "an already-aborted transaction's update must not be replayed")
        assertTrue(result.rolledBackTxns.isEmpty())
        assertContentEquals(before, diskManager.readPage(pageId).data)
    }

    @Test
    fun rollingBackATransactionWithNoUpdatesJustMarksItAborted() {
        val walFile = tempFile("kestrel-recovery-wal")
        val dataFile = tempFile("kestrel-recovery-data")
        val wal = WriteAheadLog(walFile)
        val diskManager = DiskManager(dataFile)
        val txn = wal.beginTransaction()

        RecoveryManager(wal, diskManager).rollback(txn)

        assertEquals(WriteAheadLog.NO_LSN, wal.lastLsnOf(txn))
        val abortSeen = wal.readFrom(0L).any { it.second is LogRecord.Abort && (it.second as LogRecord.Abort).txnId == txn }
        assertTrue(abortSeen)
    }

