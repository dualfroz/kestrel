package kestrel.store.wal

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WriteAheadLogTest {
    private fun tempFile(): File = Files.createTempFile("kestrel-wal", ".log").toFile().also { it.deleteOnExit() }

    @Test
    fun beginAssignsIncreasingTransactionIds() {
        val wal = WriteAheadLog(tempFile())
        val a = wal.beginTransaction()
        val b = wal.beginTransaction()
        assertTrue(b > a)
        wal.close()
    }

    @Test
    fun logUpdateChainsPrevLsnToTheTransactionsPriorRecord() {
        val wal = WriteAheadLog(tempFile())
        val txn = wal.beginTransaction()
        val beginLsn = wal.lastLsnOf(txn)
        val firstUpdateLsn = wal.logUpdate(txn, 0L, ByteArray(1), ByteArray(1))
        val secondUpdateLsn = wal.logUpdate(txn, 0L, ByteArray(1), ByteArray(1))

        val firstRecord = wal.readAt(firstUpdateLsn) as LogRecord.Update
        val secondRecord = wal.readAt(secondUpdateLsn) as LogRecord.Update
        assertEquals(beginLsn, firstRecord.prevLsn)
        assertEquals(firstUpdateLsn, secondRecord.prevLsn)
        wal.close()
    }

    @Test
    fun commitClearsActiveTrackingForTheTransaction() {
        val wal = WriteAheadLog(tempFile())
        val txn = wal.beginTransaction()
        wal.commit(txn)
        assertEquals(WriteAheadLog.NO_LSN, wal.lastLsnOf(txn))
        wal.close()
    }

    @Test
    fun loggingUpdateForUnknownTransactionFails() {
        val wal = WriteAheadLog(tempFile())
        assertFailsWith<WalException> { wal.logUpdate(999L, 0L, ByteArray(1), ByteArray(1)) }
        wal.close()
    }

    @Test
    fun checkpointRejectsActiveTransactions() {
        val wal = WriteAheadLog(tempFile())
        wal.beginTransaction()
        assertFailsWith<IllegalStateException> { wal.checkpoint() }
        wal.close()
    }

    @Test
    fun checkpointIsRecordedAsLastCheckpointLsn() {
        val wal = WriteAheadLog(tempFile())
        val txn = wal.beginTransaction()
        wal.commit(txn)
        val checkpointLsn = wal.checkpoint()
        assertEquals(checkpointLsn, wal.lastCheckpointLsn)
        wal.close()
    }

    @Test
    fun readFromReturnsRecordsInLogOrder() {
        val wal = WriteAheadLog(tempFile())
        val txn = wal.beginTransaction()
        wal.logUpdate(txn, 1L, ByteArray(1), ByteArray(1))
        wal.commit(txn)
        val records = wal.readFrom(0L).map { it.second }
        assertTrue(records[0] is LogRecord.Begin)
        assertTrue(records[1] is LogRecord.Update)
        assertTrue(records[2] is LogRecord.Commit)
        wal.close()
    }

    @Test
    fun reopeningResumesTransactionIdsPastThoseAlreadyLogged() {
        val file = tempFile()
        val first = WriteAheadLog(file)
        val txn = first.beginTransaction()
        first.commit(txn)
        first.close()

        val reopened = WriteAheadLog(file)
        val nextTxn = reopened.beginTransaction()
        assertTrue(nextTxn > txn)
        reopened.close()
    }

    @Test
    fun reopeningRecoversLastCheckpointLsn() {
        val file = tempFile()
        val first = WriteAheadLog(file)
        val txn = first.beginTransaction()
        first.commit(txn)
        val checkpointLsn = first.checkpoint()
        first.close()

        val reopened = WriteAheadLog(file)
        assertEquals(checkpointLsn, reopened.lastCheckpointLsn)
        reopened.close()
    }

    @Test
    fun corruptedFrameFailsChecksumValidation() {
        val file = tempFile()
        val wal = WriteAheadLog(file)
        val txn = wal.beginTransaction()
        wal.commit(txn)
        wal.close()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(10)
            raf.write(0xFF)
        }

        assertFailsWith<WalCorruptionException> { WriteAheadLog(file) }
    }
}
