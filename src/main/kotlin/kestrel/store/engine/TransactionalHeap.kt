package kestrel.store.engine

import kestrel.store.heap.HeapFile
import kestrel.store.heap.RecordId
import kestrel.store.page.PageStore
import kestrel.store.txn.TransactionManager

/**
 * A [HeapFile] made crash-safe and transactional: every write acquires an
 * exclusive lock on [headerPageId] (the whole table is one lock resource -
 * a deliberately coarse granularity, ahead of page-level locking) and is
 * wrapped in a fresh [LoggingPageStore] per call so its page mutations reach
 * the write-ahead log before [TransactionManager.commit] makes them durable.
 * Reads take a shared lock on the same resource, so a reader never observes
 * a partially applied write from another transaction under strict 2PL.
 */
class TransactionalHeap private constructor(
    private val bufferPool: PageStore,
    private val txnManager: TransactionManager,
    private val tracker: TouchedPageTracker,
    val headerPageId: Long
) {
    companion object {
        fun create(bufferPool: PageStore, txnManager: TransactionManager, tracker: TouchedPageTracker): TransactionalHeap {
            val heap = HeapFile.create(bufferPool)
            return TransactionalHeap(bufferPool, txnManager, tracker, heap.headerPageId)
        }

        fun open(
            bufferPool: PageStore,
            txnManager: TransactionManager,
            tracker: TouchedPageTracker,
            headerPageId: Long
        ): TransactionalHeap = TransactionalHeap(bufferPool, txnManager, tracker, headerPageId)
    }

    fun insert(txnId: Long, bytes: ByteArray): RecordId {
        txnManager.acquireExclusive(txnId, headerPageId)
        val logging = loggingStoreFor(txnId)
        val rid = HeapFile.open(logging, headerPageId).insert(bytes)
        logging.finish()
        return rid
    }

    fun delete(txnId: Long, rid: RecordId) {
        txnManager.acquireExclusive(txnId, headerPageId)
        val logging = loggingStoreFor(txnId)
        HeapFile.open(logging, headerPageId).delete(rid)
        logging.finish()
    }

    fun update(txnId: Long, rid: RecordId, bytes: ByteArray): RecordId {
        txnManager.acquireExclusive(txnId, headerPageId)
        val logging = loggingStoreFor(txnId)
        val newRid = HeapFile.open(logging, headerPageId).update(rid, bytes)
        logging.finish()
        return newRid
    }

    /**
     * Frees every page this heap owns (header and every data page) back to the shared
     * [kestrel.store.page.DiskManager] free list - used when the owning table is dropped, since
     * nothing will ever open this [headerPageId] again. Not part of the crash-recoverable log the
     * way [insert]/[delete]/[update] are: like [kestrel.store.btree.BPlusTree]'s own page frees on
     * merge/root-shrink (see [kestrel.store.engine.LoggingPageStore.deletePage]), an abort after
     * this runs cannot un-free the pages - acceptable because the sole caller ([DiskCatalog][kestrel.store.sql.DiskCatalog]'s
     * `dropTable`) only calls this once the directory row removal in the same transaction has
     * already succeeded, with nothing left afterward that could still fail.
     */
    fun destroy(txnId: Long) {
        txnManager.acquireExclusive(txnId, headerPageId)
        for (pageId in HeapFile.open(bufferPool, headerPageId).allPageIds()) bufferPool.deletePage(pageId)
    }

    fun get(txnId: Long, rid: RecordId): ByteArray {
        txnManager.acquireShared(txnId, headerPageId)
        return HeapFile.open(bufferPool, headerPageId).get(rid)
    }

    fun scan(txnId: Long): List<Pair<RecordId, ByteArray>> {
        txnManager.acquireShared(txnId, headerPageId)
        return HeapFile.open(bufferPool, headerPageId).scan().toList()
    }

    private fun loggingStoreFor(txnId: Long): LoggingPageStore =
        LoggingPageStore(bufferPool, txnManager, txnId) { pageId -> tracker.record(txnId, pageId) }
}
