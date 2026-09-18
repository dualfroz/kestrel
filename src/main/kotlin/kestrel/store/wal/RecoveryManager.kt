package kestrel.store.wal

import kestrel.KestrelException
import kestrel.store.page.DiskManager
import kestrel.store.page.Page

class RecoveryException(message: String) : KestrelException(message)

class RecoveryManager(private val wal: WriteAheadLog, private val diskManager: DiskManager) {

    class Result(val redoneUpdates: Int, val rolledBackTxns: List<Long>)

    fun recover(): Result {
        val startLsn = if (wal.lastCheckpointLsn == WriteAheadLog.NO_LSN) 0L else wal.lastCheckpointLsn
        val history = wal.readFrom(startLsn)

        val aborted = HashSet<Long>()
        for ((_, record) in history) {
            if (record is LogRecord.Abort) aborted.add(record.txnId)
        }

        val activeTxns = LinkedHashMap<Long, Long>()
        var redone = 0

        for ((lsn, record) in history) {
            when (record) {
                is LogRecord.Begin -> activeTxns[record.txnId] = lsn
                is LogRecord.Update -> {
                    activeTxns[record.txnId] = lsn
                    if (record.txnId !in aborted) {
                        applyImage(record.pageId, record.after)
                        redone++
                    }
                }
                is LogRecord.CompensationUpdate -> {
                    activeTxns[record.txnId] = lsn
                    if (record.txnId !in aborted) {
                        applyImage(record.pageId, record.undoImage)
                        redone++
                    }
                }
                is LogRecord.Commit -> activeTxns.remove(record.txnId)
                is LogRecord.Abort -> activeTxns.remove(record.txnId)
                is LogRecord.Checkpoint -> Unit
            }
        }

        val rolledBack = activeTxns.keys.toList()
        for (txnId in rolledBack) rollback(txnId, activeTxns.getValue(txnId))
        diskManager.fsync()
        return Result(redone, rolledBack)
    }

    fun rollback(txnId: Long, fromLsn: Long = wal.lastLsnOf(txnId)) {
        require(fromLsn != WriteAheadLog.NO_LSN) { "transaction $txnId has no recorded updates" }
        var lsn = fromLsn
        while (lsn != WriteAheadLog.NO_LSN) {
            when (val record = wal.readAt(lsn)) {
                is LogRecord.Update -> {
                    applyImage(record.pageId, record.before)
                    lsn = record.prevLsn
                }
                is LogRecord.CompensationUpdate -> lsn = record.undoNextLsn
                is LogRecord.Begin -> lsn = WriteAheadLog.NO_LSN
                else -> throw RecoveryException("unexpected record in undo chain for txn $txnId at lsn $lsn")
            }
        }
        wal.markAborted(txnId)
    }

    fun rollbackTo(txnId: Long, savepointLsn: Long) {
        var lsn = wal.lastLsnOf(txnId)
        while (lsn != savepointLsn) {
            if (lsn == WriteAheadLog.NO_LSN) {
                throw RecoveryException("savepoint lsn $savepointLsn is not on transaction $txnId's undo chain")
            }
            when (val record = wal.readAt(lsn)) {
                is LogRecord.Update -> {
                    applyImage(record.pageId, record.before)
                    wal.logCompensation(txnId, record.pageId, record.before, record.prevLsn)
                    lsn = record.prevLsn
                }
                is LogRecord.CompensationUpdate -> lsn = record.undoNextLsn
                is LogRecord.Begin -> throw RecoveryException(
                    "savepoint lsn $savepointLsn is not on transaction $txnId's undo chain"
                )
                else -> throw RecoveryException("unexpected record in undo chain for txn $txnId at lsn $lsn")
            }
        }
    }

    private fun applyImage(pageId: Long, image: ByteArray) {
        if (pageId >= diskManager.allocatedPageCount()) {
            throw RecoveryException(
                "page $pageId referenced by the log was never allocated; " +
                    "this recovery scope only redoes updates to already-allocated pages"
            )
        }
        val page = Page(pageId)
        System.arraycopy(image, 0, page.data, 0, image.size)
        diskManager.writePage(page)
    }
}
