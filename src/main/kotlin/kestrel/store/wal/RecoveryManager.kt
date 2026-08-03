package kestrel.store.wal

import kestrel.KestrelException
import kestrel.store.page.DiskManager
import kestrel.store.page.Page

class RecoveryException(message: String) : KestrelException(message)

/**
 * ARIES-lite recovery over a [WriteAheadLog] of whole-page physical images.
 *
 * Because every [LogRecord.Update] carries the exact after-image a page had
 * once that write completed, replaying every update from the last
 * checkpoint forward in LSN order reproduces the pre-crash contents exactly
 * - no page-LSN comparison is needed to make redo idempotent, unlike
 * classic ARIES with incremental (delta) logging. The tradeoff is a bigger
 * log: every logged write carries a full page image rather than a diff.
 *
 * Undo walks each unresolved transaction's [LogRecord.Update] chain
 * backward via `prevLsn`, restoring the before-image at each step. A crash
 * partway through undoing a transaction requires re-running undo for it
 * from the start on the next recovery pass ([recover]'s forward redo pass
 * reproduces whatever partial undo already happened before the crash, via
 * any [LogRecord.CompensationUpdate] records it wrote, and the backward undo
 * pass then continues from there) rather than resuming partway through a
 * still-in-flight undo.
 *
 * [rollbackTo] undoes only a *suffix* of a transaction's updates, back to a
 * savepoint LSN captured earlier in the same transaction, and writes a
 * [LogRecord.CompensationUpdate] for each [LogRecord.Update] it undoes. A
 * later full [rollback] (or a rollback to an earlier savepoint) that walks
 * back through a CLR does not undo it again - it follows [LogRecord.CompensationUpdate.undoNextLsn]
 * straight past the range it already compensated for. This is the classic
 * ARIES technique for making undo of undo safe without needing a full CLR
 * chain of its own.
 *
 * Redo only ever targets pages already allocated in [diskManager]; page
 * allocation itself is not logged in this scope, so the log can only
 * restore content, not resurrect a page directory lost mid-crash.
 *
 * Because [rollback] restores before-images with plain physical writes
 * rather than logging its own compensation records, a transaction that
 * finished with an [LogRecord.Abort] before this recovery pass ran already
 * has the correct (pre-write) bytes on disk. Blindly redoing every
 * [LogRecord.Update] forward in LSN order - as classic ARIES redo does,
 * since it always has CLRs to make that safe - would reapply that
 * transaction's after-images and resurrect data it rolled back. [recover]
 * therefore runs an analysis pass first to learn each transaction's final
 * disposition, then skips redoing updates belonging to one that aborted.
 */
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

    /**
     * Rolls back a transaction by walking its update chain backward from
     * [fromLsn] (defaulting to the tail this [wal] instance has tracked for
     * a still-live transaction) and restoring each before-image. Also used
     * by [recover] to undo transactions left active by a crash.
     */
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

    /**
     * Undoes [txnId]'s updates back to [savepointLsn] - the LSN [WriteAheadLog.lastLsnOf] reported for
     * [txnId] at the moment the savepoint was established - without resolving the transaction: it
     * remains active, can accumulate further writes, and can later be committed, fully rolled back, or
     * rolled back to another savepoint. Each undone [LogRecord.Update] gets a
     * [LogRecord.CompensationUpdate] so this is safe to call again (e.g. a second rollback to the same
     * savepoint after more writes) and safe to crash through (see the class doc).
     *
     * Walking backward from the transaction's current tail, a [LogRecord.CompensationUpdate] already on
     * the chain (written by an earlier, deeper savepoint rollback) is skipped via [LogRecord.CompensationUpdate.undoNextLsn]
     * rather than undone again - the range it compensates for is already at its pre-savepoint bytes.
     */
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
