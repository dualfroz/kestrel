package kestrel.store.wal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LogRecordCodecTest {
    @Test
    fun beginRoundTrips() {
        val decoded = LogRecordCodec.decode(LogRecordCodec.encode(LogRecord.Begin(7L)))
        assertTrue(decoded is LogRecord.Begin)
        assertEquals(7L, decoded.txnId)
    }

    @Test
    fun commitRoundTrips() {
        val decoded = LogRecordCodec.decode(LogRecordCodec.encode(LogRecord.Commit(9L)))
        assertTrue(decoded is LogRecord.Commit)
        assertEquals(9L, decoded.txnId)
    }

    @Test
    fun abortRoundTrips() {
        val decoded = LogRecordCodec.decode(LogRecordCodec.encode(LogRecord.Abort(3L)))
        assertTrue(decoded is LogRecord.Abort)
        assertEquals(3L, decoded.txnId)
    }

    @Test
    fun updateRoundTripsPageImagesAndChainPointer() {
        val before = ByteArray(4096) { 1 }
        val after = ByteArray(4096) { 2 }
        val original = LogRecord.Update(txnId = 5L, prevLsn = 128L, pageId = 42L, before = before, after = after)
        val decoded = LogRecordCodec.decode(LogRecordCodec.encode(original))
        assertTrue(decoded is LogRecord.Update)
        assertEquals(5L, decoded.txnId)
        assertEquals(128L, decoded.prevLsn)
        assertEquals(42L, decoded.pageId)
        assertContentEquals(before, decoded.before)
        assertContentEquals(after, decoded.after)
    }

    @Test
    fun compensationUpdateRoundTripsUndoImageAndSkipPointer() {
        val undoImage = ByteArray(4096) { 3 }
        val original = LogRecord.CompensationUpdate(
            txnId = 11L,
            prevLsn = 256L,
            pageId = 17L,
            undoImage = undoImage,
            undoNextLsn = 64L
        )
        val decoded = LogRecordCodec.decode(LogRecordCodec.encode(original))
        assertTrue(decoded is LogRecord.CompensationUpdate)
        assertEquals(11L, decoded.txnId)
        assertEquals(256L, decoded.prevLsn)
        assertEquals(17L, decoded.pageId)
        assertContentEquals(undoImage, decoded.undoImage)
        assertEquals(64L, decoded.undoNextLsn)
    }

    @Test
    fun checkpointRoundTripsActiveTxnList() {
        val decoded = LogRecordCodec.decode(LogRecordCodec.encode(LogRecord.Checkpoint(listOf(1L, 2L, 3L))))
        assertTrue(decoded is LogRecord.Checkpoint)
        assertEquals(listOf(1L, 2L, 3L), decoded.activeTxnIds)
    }

    @Test
    fun decodingUnknownTypeTagFails() {
        assertFailsWith<WalCorruptionException> { LogRecordCodec.decode(byteArrayOf(99)) }
    }
}
