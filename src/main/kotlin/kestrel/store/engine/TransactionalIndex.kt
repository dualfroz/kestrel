package kestrel.store.engine

import kestrel.store.btree.BPlusTree
import kestrel.store.page.PageStore
import kestrel.store.txn.TransactionManager

/**
 * A [BPlusTree] made crash-safe and transactional, following the same
 * per-call [LoggingPageStore] pattern as [TransactionalHeap]. Locking uses
 * [lockResourceId] rather than the tree's own root page id: [BPlusTree.delete]
 * can shrink the tree and free its former root back to the shared page free
 * list, so that id can later be reallocated to an unrelated page - using it
 * as a lock resource would risk two unrelated structures serializing against
 * each other by coincidence. [StorageEngine] hands out a disjoint id (see
 * `nextResourceId`) precisely to avoid that.
 *
 * A root split or shrink also moves the tree's root pointer itself, which is
 * plain in-memory engine state, not something [kestrel.store.wal.WriteAheadLog]
 * records - rolling a transaction back restores page *bytes* but has no way
 * to know it must also restore [rootPageId]. [rollback] does that: it
 * remembers, on a transaction's first write, the root as of just before that
 * transaction touched the tree, and restores it on abort.
 */
class TransactionalIndex private constructor(
    private val bufferPool: PageStore,
    private val txnManager: TransactionManager,
    private val tracker: TouchedPageTracker,
    private val lockResourceId: Long,
    initialRootPageId: Long
) {
    companion object {
        fun create(
            bufferPool: PageStore,
            txnManager: TransactionManager,
            tracker: TouchedPageTracker,
            lockResourceId: Long
        ): TransactionalIndex {
            val tree = BPlusTree.create(bufferPool)
            return TransactionalIndex(bufferPool, txnManager, tracker, lockResourceId, tree.rootPageId)
        }

        fun open(
            bufferPool: PageStore,
            txnManager: TransactionManager,
            tracker: TouchedPageTracker,
            lockResourceId: Long,
            rootPageId: Long
        ): TransactionalIndex = TransactionalIndex(bufferPool, txnManager, tracker, lockResourceId, rootPageId)
    }

    var rootPageId: Long = initialRootPageId
        private set

    private val rootBeforeTxn = HashMap<Long, Long>()
    private val rootAtSavepoint = HashMap<Long, Long>()

    fun insert(txnId: Long, key: ByteArray, value: Long) {
        txnManager.acquireExclusive(txnId, lockResourceId)
        rootBeforeTxn.putIfAbsent(txnId, rootPageId)
        val logging = loggingStoreFor(txnId)
        val tree = BPlusTree.open(logging, rootPageId)
        tree.insert(key, value)
        rootPageId = tree.rootPageId
        logging.finish()
    }

    fun delete(txnId: Long, key: ByteArray): Boolean {
        txnManager.acquireExclusive(txnId, lockResourceId)
        rootBeforeTxn.putIfAbsent(txnId, rootPageId)
        val logging = loggingStoreFor(txnId)
        val tree = BPlusTree.open(logging, rootPageId)
        val found = tree.delete(key)
        rootPageId = tree.rootPageId
        logging.finish()
        return found
    }

    /**
     * Frees every page this tree owns back to the shared [kestrel.store.page.DiskManager] free
     * list - used when the owning index (or its whole table) is dropped. See
     * [TransactionalHeap.destroy]'s own doc for why this is deliberately not undo-safe: the same
     * accepted tradeoff [kestrel.store.btree.BPlusTree] already makes for a merge or root-shrink's
     * own page frees.
     */
    fun destroy(txnId: Long) {
        txnManager.acquireExclusive(txnId, lockResourceId)
        for (pageId in BPlusTree.open(bufferPool, rootPageId).allPageIds()) bufferPool.deletePage(pageId)
    }

    fun search(txnId: Long, key: ByteArray): Long? {
        txnManager.acquireShared(txnId, lockResourceId)
        return BPlusTree.open(bufferPool, rootPageId).search(key)
    }

    fun rangeScan(
        txnId: Long,
        from: ByteArray? = null,
        to: ByteArray? = null,
        toInclusive: Boolean = false
    ): List<Pair<ByteArray, Long>> {
        txnManager.acquireShared(txnId, lockResourceId)
        return BPlusTree.open(bufferPool, rootPageId).rangeScan(from, to, toInclusive).asSequence().toList()
    }

    /** Restores the root pointer as of before [txnId]'s first write. Called by [StorageEngine.abort]. */
    fun rollback(txnId: Long) {
        rootBeforeTxn.remove(txnId)?.let { rootPageId = it }
    }

    /** Drops the pending rollback snapshot once [txnId] is durably resolved. Called by [StorageEngine.commit]. */
    fun forgetTransaction(txnId: Long) {
        rootBeforeTxn.remove(txnId)
    }

    /**
     * Snapshots the root pointer under [savepointLsn] (a transaction's [TransactionManager.currentLsn]
     * at the moment a SAVEPOINT is established), so a later [restoreRootAt] can undo whatever root
     * moves a split, merge or shrink made afterward - the same problem [rootBeforeTxn] solves for a
     * whole transaction, scoped to a savepoint instead. Called by [StorageEngine.savepoint].
     */
    fun snapshotRootAt(savepointLsn: Long) {
        rootAtSavepoint[savepointLsn] = rootPageId
    }

    /** Restores the root pointer captured by [snapshotRootAt]. Called by [StorageEngine.rollbackToSavepoint]. */
    fun restoreRootAt(savepointLsn: Long) {
        rootPageId = rootAtSavepoint.getValue(savepointLsn)
    }

    /** Drops a snapshot once its savepoint can no longer be rolled back to. Called by [StorageEngine.discardSavepoint]. */
    fun discardSavepoint(savepointLsn: Long) {
        rootAtSavepoint.remove(savepointLsn)
    }

    private fun loggingStoreFor(txnId: Long): LoggingPageStore =
        LoggingPageStore(bufferPool, txnManager, txnId) { pageId -> tracker.record(txnId, pageId) }
}
