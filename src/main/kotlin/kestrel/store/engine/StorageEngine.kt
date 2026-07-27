package kestrel.store.engine

import kestrel.store.page.BufferPool
import kestrel.store.page.DiskManager
import kestrel.store.page.Page
import kestrel.store.txn.TransactionManager
import kestrel.store.wal.RecoveryManager
import kestrel.store.wal.WriteAheadLog
import java.io.File

/**
 * Top-level facade tying the disk manager, buffer pool, write-ahead log and
 * transaction manager into one crash-safe engine: [open] always runs
 * [RecoveryManager.recover] first, so any table or index reopened through it
 * sees the post-recovery, consistent state whether the previous process
 * committed cleanly or crashed mid-transaction.
 *
 * [commit] and [abort] are the only way transactions started here should be
 * resolved: besides delegating to [TransactionManager], they keep every
 * [TransactionalIndex]'s in-memory root pointer and the [TouchedPageTracker]
 * in sync, which a caller driving [TransactionManager] directly would have
 * to reproduce by hand.
 */
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

    /**
     * Establishes a savepoint on [txnId]: returns a token [rollbackToSavepoint] can later undo back to,
     * capturing every open index's current root pointer alongside the write-ahead log position so a
     * later rollback can undo B+Tree structural changes (splits, merges, shrinks) too, not just page
     * bytes.
     */
    fun savepoint(txnId: Long): Long {
        val lsn = txnManager.currentLsn(txnId)
        for (index in indexes) index.snapshotRootAt(lsn)
        savepointLsnsByTxn.getOrPut(txnId) { mutableListOf() }.add(lsn)
        return lsn
    }

    /**
     * Undoes every write [txnId] made after [savepointLsn] (from [savepoint]) without resolving the
     * transaction - it remains open, holds every lock it already had, and can be written to, committed,
     * fully rolled back, or rolled back to another savepoint afterward. Unlike [abort], the savepoint
     * itself is not forgotten: [restoreRootAt]-style rollback to the same savepoint again later is safe
     * because [kestrel.store.wal.RecoveryManager.rollbackTo] rewrites the undo chain with compensation
     * records rather than physically deleting anything.
     */
    fun rollbackToSavepoint(txnId: Long, savepointLsn: Long) {
        txnManager.rollbackTo(txnId, savepointLsn)
        for (index in indexes) index.restoreRootAt(savepointLsn)
        for (pageId in tracker.peek(txnId)) bufferPool.invalidate(pageId)
    }

    /** Drops a savepoint that can no longer be rolled back to (RELEASE, or superseded by an earlier rollback). */
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

    /** Writes every dirty cached page back to the data file without closing the engine. */
    fun flush() = bufferPool.flushAll()

    /** Total pages ever allocated in the backing data file, including ones now sitting on the free list. */
    fun allocatedPageCount(): Long = diskManager.allocatedPageCount()

    /**
     * Allocates a page dedicated to content that is never covered by [beginTransaction]/[commit]/
     * [abort] - see [writeRawPage]'s own doc for why [kestrel.store.sql.DiskCatalog] needs that for
     * a SEQUENCE's live counter. Never reused as a heap or index page: [DiskManager] hands out page
     * ids from one shared counter, so a raw page can never collide with one [createHeap]/[createIndex]
     * allocates.
     */
    fun allocateRawPage(): Long = diskManager.allocatePage()

    /**
     * Writes [bytes] (padded with zeros to a full page) straight to [diskManager], bypassing the
     * buffer pool, write-ahead log and lock manager entirely - deliberately not participatory in
     * [commit]/[abort] undo. [kestrel.store.sql.DiskCatalog] uses this exclusively for a SEQUENCE's
     * NEXTVAL counter, whose whole point - like Postgres' own sequences - is to keep handing out
     * values that never repeat even across an aborted transaction, so it must survive a ROLLBACK
     * that would otherwise undo it if it were written through the normal transactional path. A page
     * allocated by [allocateRawPage] is never fetched through [bufferPool] by anything else, so there
     * is no cache to invalidate here the way [BufferPool.invalidate] does for [RecoveryManager]'s own
     * direct writes.
     */
    fun writeRawPage(pageId: Long, bytes: ByteArray) {
        require(bytes.size <= Page.PAGE_SIZE) { "raw page payload of ${bytes.size} bytes exceeds the ${Page.PAGE_SIZE}-byte page size" }
        val page = Page(pageId)
        System.arraycopy(bytes, 0, page.data, 0, bytes.size)
        diskManager.writePage(page)
    }

    /** Reads back a page written by [writeRawPage], straight from [diskManager]. */
    fun readRawPage(pageId: Long): ByteArray = diskManager.readPage(pageId).data

    /**
     * Frees a page allocated by [allocateRawPage] back to the shared [DiskManager] free list - used
     * by `DROP SEQUENCE` once its counter page will never be read again. Bypasses the buffer pool
     * like every other raw-page operation: nothing ever caches a raw page through [bufferPool], so
     * there is no frame to invalidate the way [BufferPool.deletePage] would for a heap or index page.
     */
    fun freeRawPage(pageId: Long) = diskManager.freePage(pageId)

    override fun close() {
        bufferPool.flushAll()
        wal.close()
        diskManager.close()
    }

    /** Negative so it never collides with a page id, which [DiskManager] hands out starting at zero. */
    private fun allocateResourceId(): Long = -(++nextResourceId)
}
