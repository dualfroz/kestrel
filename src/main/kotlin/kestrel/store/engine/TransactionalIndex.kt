package kestrel.store.engine

import kestrel.store.btree.BPlusTree
import kestrel.store.page.PageStore
import kestrel.store.txn.TransactionManager

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

    fun rollback(txnId: Long) {
        rootBeforeTxn.remove(txnId)?.let { rootPageId = it }
    }

    fun forgetTransaction(txnId: Long) {
        rootBeforeTxn.remove(txnId)
    }

    fun snapshotRootAt(savepointLsn: Long) {
        rootAtSavepoint[savepointLsn] = rootPageId
    }

    fun restoreRootAt(savepointLsn: Long) {
        rootPageId = rootAtSavepoint.getValue(savepointLsn)
    }

    fun discardSavepoint(savepointLsn: Long) {
        rootAtSavepoint.remove(savepointLsn)
    }

    private fun loggingStoreFor(txnId: Long): LoggingPageStore =
        LoggingPageStore(bufferPool, txnManager, txnId) { pageId -> tracker.record(txnId, pageId) }
}
