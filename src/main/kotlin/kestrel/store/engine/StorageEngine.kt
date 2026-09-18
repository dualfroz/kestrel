package kestrel.store.engine

import kestrel.store.page.BufferPool
import kestrel.store.page.DiskManager
import kestrel.store.page.Page
import kestrel.store.txn.TransactionManager
import kestrel.store.wal.RecoveryManager
import kestrel.store.wal.WriteAheadLog
import java.io.File

class StorageEngine private constructor(
    private val diskManager: DiskManager,
    private val bufferPool: BufferPool,
    private val wal: WriteAheadLog,
    private val txnManager: TransactionManager
) : AutoCloseable {
    companion object {
        fun open(dataFile: File, walFile: File, poolCapacity: Int = 64): StorageEngine {
            val diskManager = DiskManager(dataFile)
            val wal = WriteAheadLog(walFile)
            RecoveryManager(wal, diskManager).recover()
            val bufferPool = BufferPool(diskManager, poolCapacity)
            val txnManager = TransactionManager(wal, diskManager)
            return StorageEngine(diskManager, bufferPool, wal, txnManager)
        }
    }

    private val tracker = TouchedPageTracker()
    private val indexes = mutableListOf<TransactionalIndex>()
    private var nextResourceId = 0L
    private val savepointLsnsByTxn = HashMap<Long, MutableList<Long>>()

    fun beginTransaction(): Long = txnManager.begin()

    fun commit(txnId: Long) {
        txnManager.commit(txnId)
        for (index in indexes) index.forgetTransaction(txnId)
        tracker.forget(txnId)
        forgetSavepoints(txnId)
    }

    fun abort(txnId: Long) {
        txnManager.abort(txnId)
        for (index in indexes) index.rollback(txnId)
        for (pageId in tracker.forget(txnId)) bufferPool.invalidate(pageId)
        forgetSavepoints(txnId)
    }

    fun savepoint(txnId: Long): Long {
        val lsn = txnManager.currentLsn(txnId)
        for (index in indexes) index.snapshotRootAt(lsn)
        savepointLsnsByTxn.getOrPut(txnId) { mutableListOf() }.add(lsn)
        return lsn
    }

    fun rollbackToSavepoint(txnId: Long, savepointLsn: Long) {
        txnManager.rollbackTo(txnId, savepointLsn)
        for (index in indexes) index.restoreRootAt(savepointLsn)
        for (pageId in tracker.peek(txnId)) bufferPool.invalidate(pageId)
    }

    fun discardSavepoint(txnId: Long, savepointLsn: Long) {
        for (index in indexes) index.discardSavepoint(savepointLsn)
        savepointLsnsByTxn[txnId]?.remove(savepointLsn)
    }

    private fun forgetSavepoints(txnId: Long) {
        val lsns = savepointLsnsByTxn.remove(txnId) ?: return
        for (lsn in lsns) for (index in indexes) index.discardSavepoint(lsn)
    }

    fun createHeap(): TransactionalHeap = TransactionalHeap.create(bufferPool, txnManager, tracker)

    fun openHeap(headerPageId: Long): TransactionalHeap = TransactionalHeap.open(bufferPool, txnManager, tracker, headerPageId)

    fun createIndex(): TransactionalIndex {
        val index = TransactionalIndex.create(bufferPool, txnManager, tracker, allocateResourceId())
        indexes.add(index)
        return index
    }

    fun openIndex(rootPageId: Long): TransactionalIndex {
        val index = TransactionalIndex.open(bufferPool, txnManager, tracker, allocateResourceId(), rootPageId)
        indexes.add(index)
        return index
    }

    fun flush() = bufferPool.flushAll()

    fun allocatedPageCount(): Long = diskManager.allocatedPageCount()

    fun allocateRawPage(): Long = diskManager.allocatePage()

    fun writeRawPage(pageId: Long, bytes: ByteArray) {
        require(bytes.size <= Page.PAGE_SIZE) { "raw page payload of ${bytes.size} bytes exceeds the ${Page.PAGE_SIZE}-byte page size" }
        val page = Page(pageId)
        System.arraycopy(bytes, 0, page.data, 0, bytes.size)
        diskManager.writePage(page)
    }

    fun readRawPage(pageId: Long): ByteArray = diskManager.readPage(pageId).data

    fun freeRawPage(pageId: Long) = diskManager.freePage(pageId)

    override fun close() {
        bufferPool.flushAll()
        wal.close()
        diskManager.close()
    }

    private fun allocateResourceId(): Long = -(++nextResourceId)
}
