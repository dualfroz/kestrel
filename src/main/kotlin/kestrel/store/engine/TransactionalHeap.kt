package kestrel.store.engine

import kestrel.store.heap.HeapFile
import kestrel.store.heap.RecordId
import kestrel.store.page.PageStore
import kestrel.store.txn.TransactionManager

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
