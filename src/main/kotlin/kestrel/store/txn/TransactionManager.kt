package kestrel.store.txn

import kestrel.KestrelException
import kestrel.store.page.DiskManager
import kestrel.store.wal.RecoveryManager
import kestrel.store.wal.WriteAheadLog

class TransactionException(message: String) : KestrelException(message)

/**
 * Ties [WriteAheadLog], [RecoveryManager] and [LockManager] into a single
 * begin/commit/abort surface: a transaction acquires locks as it accesses
 * resources, logs a before/after page image for every write, and on abort
 * is unwound with the same [RecoveryManager.rollback] logic that undoes an
 * unresolved transaction during crash recovery. Every lock a transaction
 * holds is released in one shot on commit or abort, per strict 2PL.
 */
class TransactionManager(private val wal: WriteAheadLog, diskManager: DiskManager) {
    enum class Status { ACTIVE, COMMITTED, ABORTED }

    private val lockManager = LockManager()
    private val recoveryManager = RecoveryManager(wal, diskManager)
    private val statusByTxn = HashMap<Long, Status>()

    fun begin(): Long {
        val txnId = wal.beginTransaction()
        statusByTxn[txnId] = Status.ACTIVE
        return txnId
    }

    fun acquireShared(txnId: Long, resourceId: Long) {
        requireActive(txnId)
        lockManager.acquireShared(txnId, resourceId)
    }

    fun acquireExclusive(txnId: Long, resourceId: Long) {
        requireActive(txnId)
        lockManager.acquireExclusive(txnId, resourceId)
    }

    fun logUpdate(txnId: Long, pageId: Long, before: ByteArray, after: ByteArray): Long {
        requireActive(txnId)
        return wal.logUpdate(txnId, pageId, before, after)
    }

    /** The LSN a savepoint on [txnId] should capture right now - see [rollbackTo]. */
    fun currentLsn(txnId: Long): Long {
        requireActive(txnId)
        return wal.lastLsnOf(txnId)
    }

    /**
     * Undoes [txnId]'s updates back to a [savepointLsn] earlier captured via [currentLsn], without
     * resolving the transaction - unlike [abort], it remains ACTIVE and keeps every lock it holds (2PL
     * only releases locks at the transaction's own commit or abort, never at a savepoint boundary, so
     * an equally-isolated concurrent transaction can't observe a savepoint rollback happening).
     */
    fun rollbackTo(txnId: Long, savepointLsn: Long) {
        requireActive(txnId)
        recoveryManager.rollbackTo(txnId, savepointLsn)
    }

    fun commit(txnId: Long) {
        requireActive(txnId)
        wal.commit(txnId)
        lockManager.releaseAll(txnId)
        statusByTxn[txnId] = Status.COMMITTED
    }

    fun abort(txnId: Long) {
        requireActive(txnId)
        recoveryManager.rollback(txnId)
        lockManager.releaseAll(txnId)
        statusByTxn[txnId] = Status.ABORTED
    }

    fun statusOf(txnId: Long): Status = statusByTxn[txnId] ?: throw TransactionException("unknown transaction $txnId")

    private fun requireActive(txnId: Long) {
        if (statusByTxn[txnId] != Status.ACTIVE) {
            throw TransactionException("transaction $txnId is not active")
        }
    }
}
