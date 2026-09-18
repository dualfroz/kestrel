package kestrel.store.txn

import kestrel.KestrelException
import kestrel.store.page.DiskManager
import kestrel.store.wal.RecoveryManager
import kestrel.store.wal.WriteAheadLog

class TransactionException(message: String) : KestrelException(message)

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

    fun currentLsn(txnId: Long): Long {
        requireActive(txnId)
        return wal.lastLsnOf(txnId)
    }

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
