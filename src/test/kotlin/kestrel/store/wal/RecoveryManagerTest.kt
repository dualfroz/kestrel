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

    @Test
    fun rollingBackAnUnknownTransactionFails() {
        val walFile = tempFile("kestrel-recovery-wal")
        val dataFile = tempFile("kestrel-recovery-data")
        val wal = WriteAheadLog(walFile)
        val diskManager = DiskManager(dataFile)
        assertFailsWith<IllegalArgumentException> { RecoveryManager(wal, diskManager).rollback(12345L) }
    }

    @Test
    fun rollbackToUndoesOnlyUpdatesAfterTheSavepoint() {
        val dataFile = tempFile("kestrel-recovery-data")
        val walFile = tempFile("kestrel-recovery-wal")

        val diskManager = DiskManager(dataFile)
        val pageId = diskManager.allocatePage()
        val v0 = pageOf(0)
        diskManager.writePage(Page(pageId).also { System.arraycopy(v0, 0, it.data, 0, v0.size) })

        val wal = WriteAheadLog(walFile)
        val txn = wal.beginTransaction()
        val v1 = pageOf(1)
        wal.logUpdate(txn, pageId, v0, v1)
        diskManager.writePage(Page(pageId).also { System.arraycopy(v1, 0, it.data, 0, v1.size) })
        val savepointLsn = wal.lastLsnOf(txn)
        val v2 = pageOf(2)
        wal.logUpdate(txn, pageId, v1, v2)
        diskManager.writePage(Page(pageId).also { System.arraycopy(v2, 0, it.data, 0, v2.size) })

        RecoveryManager(wal, diskManager).rollbackTo(txn, savepointLsn)

        assertContentEquals(v1, diskManager.readPage(pageId).data, "only the write after the savepoint should be undone")
        val newTail = wal.lastLsnOf(txn)
        val clr = wal.readAt(newTail)
        assertTrue(clr is LogRecord.CompensationUpdate, "the transaction's chain tail should land on a new compensation record, not the undone update")
        assertEquals(savepointLsn, clr.undoNextLsn, "the compensation record must skip straight back to the savepoint on a later undo walk")
    }

    @Test
    fun rollbackToLeavesTheTransactionActiveForFurtherWritesOrAFullResolution() {
        val dataFile = tempFile("kestrel-recovery-data")
        val walFile = tempFile("kestrel-recovery-wal")

        val diskManager = DiskManager(dataFile)
        val pageId = diskManager.allocatePage()
        val v0 = pageOf(0)
        diskManager.writePage(Page(pageId).also { System.arraycopy(v0, 0, it.data, 0, v0.size) })

        val wal = WriteAheadLog(walFile)
        val txn = wal.beginTransaction()
        val savepointLsn = wal.lastLsnOf(txn)
        val v1 = pageOf(1)
        wal.logUpdate(txn, pageId, v0, v1)
        diskManager.writePage(Page(pageId).also { System.arraycopy(v1, 0, it.data, 0, v1.size) })

        val recovery = RecoveryManager(wal, diskManager)
        recovery.rollbackTo(txn, savepointLsn)
        assertContentEquals(v0, diskManager.readPage(pageId).data)

        val v3 = pageOf(3)
        wal.logUpdate(txn, pageId, v0, v3)
        diskManager.writePage(Page(pageId).also { System.arraycopy(v3, 0, it.data, 0, v3.size) })
        recovery.rollback(txn)

        assertContentEquals(v0, diskManager.readPage(pageId).data, "a full rollback after a savepoint rollback must undo everything")
        assertEquals(WriteAheadLog.NO_LSN, wal.lastLsnOf(txn))
    }

    @Test
    fun fullRollbackAfterAPartialRollbackDoesNotMisapplyAnAlreadyUndoneBeforeImage() {
        val dataFile = tempFile("kestrel-recovery-data")
        val walFile = tempFile("kestrel-recovery-wal")

        val diskManager = DiskManager(dataFile)
        val pageA = diskManager.allocatePage()
        val pageB = diskManager.allocatePage()
        diskManager.writePage(Page(pageA).also { System.arraycopy(pageOf(0), 0, it.data, 0, Page.PAGE_SIZE) })
        diskManager.writePage(Page(pageB).also { System.arraycopy(pageOf(0), 0, it.data, 0, Page.PAGE_SIZE) })

        val wal = WriteAheadLog(walFile)
        val txn = wal.beginTransaction()
        wal.logUpdate(txn, pageA, pageOf(0), pageOf(1))
        diskManager.writePage(Page(pageA).also { System.arraycopy(pageOf(1), 0, it.data, 0, Page.PAGE_SIZE) })
        val savepointLsn = wal.lastLsnOf(txn)
        wal.logUpdate(txn, pageB, pageOf(0), pageOf(9))
        diskManager.writePage(Page(pageB).also { System.arraycopy(pageOf(9), 0, it.data, 0, Page.PAGE_SIZE) })

        val recovery = RecoveryManager(wal, diskManager)
        recovery.rollbackTo(txn, savepointLsn)
        wal.logUpdate(txn, pageB, pageOf(0), pageOf(20))
        diskManager.writePage(Page(pageB).also { System.arraycopy(pageOf(20), 0, it.data, 0, Page.PAGE_SIZE) })

        recovery.rollback(txn)

        assertContentEquals(pageOf(0), diskManager.readPage(pageA).data)
        assertContentEquals(pageOf(0), diskManager.readPage(pageB).data)
    }

    @Test
    fun rollbackToASavepointLsnNotOnTheTransactionsChainFails() {
        val dataFile = tempFile("kestrel-recovery-data")
        val walFile = tempFile("kestrel-recovery-wal")
        val diskManager = DiskManager(dataFile)
        val pageId = diskManager.allocatePage()
        diskManager.writePage(Page(pageId).also { System.arraycopy(pageOf(0), 0, it.data, 0, Page.PAGE_SIZE) })

        val wal = WriteAheadLog(walFile)
        val otherTxn = wal.beginTransaction()
        val foreignLsn = wal.lastLsnOf(otherTxn)
        val txn = wal.beginTransaction()
        wal.logUpdate(txn, pageId, pageOf(0), pageOf(1))

        assertFailsWith<RecoveryException> { RecoveryManager(wal, diskManager).rollbackTo(txn, foreignLsn) }
    }

    @Test
    fun recoverFullyUndoesATransactionThatCrashedRightAfterASavepointRollback() {
        val dataFile = tempFile("kestrel-recovery-data")
        val walFile = tempFile("kestrel-recovery-wal")

        val diskManager = DiskManager(dataFile)
        val pageId = diskManager.allocatePage()
        val v0 = pageOf(0)
        diskManager.writePage(Page(pageId).also { System.arraycopy(v0, 0, it.data, 0, v0.size) })

        val wal = WriteAheadLog(walFile)
        val txn = wal.beginTransaction()
        val v1 = pageOf(1)
        wal.logUpdate(txn, pageId, v0, v1)
        diskManager.writePage(Page(pageId).also { System.arraycopy(v1, 0, it.data, 0, v1.size) })
        val savepointLsn = wal.lastLsnOf(txn)
        val v2 = pageOf(2)
        wal.logUpdate(txn, pageId, v1, v2)
        RecoveryManager(wal, diskManager).rollbackTo(txn, savepointLsn)

        val reopenedWal = WriteAheadLog(walFile)
        val result = RecoveryManager(reopenedWal, diskManager).recover()

        assertEquals(listOf(txn), result.rolledBackTxns)
        assertContentEquals(v0, diskManager.readPage(pageId).data, "the unresolved transaction must be fully undone, including its pre-savepoint write")
    }

    @Test
    fun redoOfAnUnallocatedPageFailsWithADescriptiveError() {
        val walFile = tempFile("kestrel-recovery-wal")
        val dataFile = tempFile("kestrel-recovery-data")
        val diskManager = DiskManager(dataFile)
        val wal = WriteAheadLog(walFile)
        val txn = wal.beginTransaction()
        wal.logUpdate(txn, 99L, pageOf(0), pageOf(1))
        wal.commit(txn)

        assertFailsWith<RecoveryException> { RecoveryManager(wal, diskManager).recover() }
    }
}
