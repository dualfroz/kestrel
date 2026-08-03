package kestrel.store.wal

import kestrel.KestrelException
import kestrel.store.codec.Crc32C
import java.io.File
import java.io.RandomAccessFile

class WalException(message: String) : KestrelException(message)

/**
 * Append-only binary log file. Every record is framed as
 * `[4-byte length][payload][4-byte CRC32C]`, and a record's LSN is simply
 * the file offset its frame starts at, which lets [readAt] seek straight to
 * a `prevLsn` during undo instead of needing an in-memory LSN index.
 *
 * Tracks, for transactions begun through this instance, the LSN of their
 * most recent record so [logUpdate] can chain `prevLsn` and callers can look
 * up a transaction's tail for rollback. Reconstructing that table for
 * transactions that were active before a crash is [RecoveryManager]'s job,
 * not this class's - on reopen only `nextTxnId` and [lastCheckpointLsn] are
 * recovered by scanning the existing file once.
 */
class WriteAheadLog(file: File) : AutoCloseable {
    companion object {
        const val NO_LSN = -1L
    }

    private val raf = RandomAccessFile(file, "rw")
    private val lastLsnByTxn = HashMap<Long, Long>()
    private var nextTxnId: Long

    var lastCheckpointLsn: Long = NO_LSN
        private set

    init {
        var maxTxnId = 0L
        var pos = 0L
        val end = raf.length()
        while (pos < end) {
            val (record, next) = readFrameAt(pos)
            val txnId = when (record) {
                is LogRecord.Begin -> record.txnId
                is LogRecord.Update -> record.txnId
                is LogRecord.CompensationUpdate -> record.txnId
                is LogRecord.Commit -> record.txnId
                is LogRecord.Abort -> record.txnId
                is LogRecord.Checkpoint -> {
                    lastCheckpointLsn = pos
                    0L
                }
            }
            if (txnId > maxTxnId) maxTxnId = txnId
            pos = next
        }
        nextTxnId = maxTxnId + 1
    }

    fun beginTransaction(): Long {
        val txnId = nextTxnId++
        val lsn = appendRaw(LogRecord.Begin(txnId))
        lastLsnByTxn[txnId] = lsn
        return txnId
    }

    fun logUpdate(txnId: Long, pageId: Long, before: ByteArray, after: ByteArray): Long {
        val prevLsn = lastLsnByTxn[txnId] ?: throw WalException("transaction $txnId is not active")
        val lsn = appendRaw(LogRecord.Update(txnId, prevLsn, pageId, before, after))
        lastLsnByTxn[txnId] = lsn
        return lsn
    }

    /**
     * Logs a compensation record for an undo step taken against [txnId] (see
     * [LogRecord.CompensationUpdate]) and threads it into the transaction's `prevLsn` chain exactly
     * like [logUpdate] does for a forward write, so the transaction's tail always points at the most
     * recent record regardless of whether it was a normal write or an undo.
     */
    fun logCompensation(txnId: Long, pageId: Long, undoImage: ByteArray, undoNextLsn: Long): Long {
        val prevLsn = lastLsnByTxn[txnId] ?: throw WalException("transaction $txnId is not active")
        val lsn = appendRaw(LogRecord.CompensationUpdate(txnId, prevLsn, pageId, undoImage, undoNextLsn))
        lastLsnByTxn[txnId] = lsn
        return lsn
    }

    fun commit(txnId: Long): Long {
        if (txnId !in lastLsnByTxn) throw WalException("transaction $txnId is not active")
        val lsn = appendRaw(LogRecord.Commit(txnId))
        flush()
        lastLsnByTxn.remove(txnId)
        return lsn
    }

    /** Records that [txnId] is resolved after its updates were undone. Used by [RecoveryManager]. */
    fun markAborted(txnId: Long): Long {
        val lsn = appendRaw(LogRecord.Abort(txnId))
        flush()
        lastLsnByTxn.remove(txnId)
        return lsn
    }

    /** Requires no transaction to be active (a quiescent checkpoint). */
    fun checkpoint(): Long {
        check(lastLsnByTxn.isEmpty()) { "checkpoint requires no active transactions" }
        val lsn = appendRaw(LogRecord.Checkpoint(emptyList()))
        flush()
        lastCheckpointLsn = lsn
        return lsn
    }

    fun lastLsnOf(txnId: Long): Long = lastLsnByTxn[txnId] ?: NO_LSN

    fun flush() {
        raf.fd.sync()
    }

    /** Sequentially reads every record from [startLsn] to the end of the log. */
    fun readFrom(startLsn: Long): List<Pair<Long, LogRecord>> {
        val result = ArrayList<Pair<Long, LogRecord>>()
        var pos = startLsn
        val end = raf.length()
        while (pos < end) {
            val (record, next) = readFrameAt(pos)
            result.add(pos to record)
            pos = next
        }
        return result
    }

    fun readAt(lsn: Long): LogRecord = readFrameAt(lsn).first

    override fun close() {
        flush()
        raf.close()
    }

    private fun readFrameAt(lsn: Long): Pair<LogRecord, Long> {
        raf.seek(lsn)
        val length = readInt()
        val payload = ByteArray(length)
        raf.readFully(payload)
        val expected = readInt()
        val actual = Crc32C.of(payload)
        if (actual != expected) throw WalCorruptionException("checksum mismatch for log frame at lsn $lsn")
        return LogRecordCodec.decode(payload) to raf.filePointer
    }

    private fun appendRaw(record: LogRecord): Long {
        val payload = LogRecordCodec.encode(record)
        val lsn = raf.length()
        raf.seek(lsn)
        writeInt(payload.size)
        raf.write(payload)
        writeInt(Crc32C.of(payload))
        return lsn
    }

    private fun writeInt(value: Int) {
        raf.write(value ushr 24)
        raf.write(value ushr 16)
        raf.write(value ushr 8)
        raf.write(value)
    }

    private fun readInt(): Int {
        val a = raf.read()
        val b = raf.read()
        val c = raf.read()
        val d = raf.read()
        if (a < 0 || b < 0 || c < 0 || d < 0) throw WalCorruptionException("truncated log frame")
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }
}
