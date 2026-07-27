package kestrel.store.engine

import kestrel.store.page.Page
import kestrel.store.page.PageStore
import kestrel.store.txn.TransactionManager

/**
 * A [PageStore] decorator that watches every page a single logical operation
 * touches and turns whatever ends up dirty into [TransactionManager.logUpdate]
 * calls, without [kestrel.store.heap.HeapFile] or [kestrel.store.btree.BPlusTree]
 * knowing they are being logged - they were written against [PageStore] with
 * no notion of a transaction.
 *
 * One instance is meant to wrap exactly one logical operation (an insert, a
 * delete, ...): the first [fetchPage] or [newPage] for a given page id
 * captures its "before" image (an all-zero page for a freshly allocated one,
 * since that is the state [kestrel.store.wal.RecoveryManager] would undo
 * back to), and [finish] compares that snapshot against whatever the
 * delegate's live [Page] holds once the operation is done, logging one WAL
 * record per page whose bytes actually changed. [onTouch] is called only for
 * those actually-logged pages, once [finish] has decided which ones they
 * are: a page a caller merely read must never be reported, because its
 * cached bytes can be dirty with an earlier, already-committed transaction's
 * write that just has not been flushed yet, and [kestrel.store.wal.RecoveryManager]
 * never touches such a page on disk during rollback, so invalidating it in
 * the buffer pool would silently discard real data instead of undoing
 * anything.
 */
class LoggingPageStore(
    private val delegate: PageStore,
    private val txnManager: TransactionManager,
    private val txnId: Long,
    private val onTouch: (Long) -> Unit = {}
) : PageStore {
    private val liveByPageId = LinkedHashMap<Long, Page>()
    private val beforeImages = HashMap<Long, ByteArray>()

    override fun fetchPage(pageId: Long): Page {
        val page = delegate.fetchPage(pageId)
        track(pageId, page)
        return page
    }

    override fun newPage(): Page {
        val page = delegate.newPage()
        beforeImages[page.id] = ByteArray(Page.PAGE_SIZE)
        track(page.id, page)
        return page
    }

    override fun unpinPage(pageId: Long, markDirty: Boolean) = delegate.unpinPage(pageId, markDirty)

    override fun deletePage(pageId: Long) = delegate.deletePage(pageId)

    /** Logs one update record per page whose bytes differ from when it was first touched. */
    fun finish() {
        for ((pageId, page) in liveByPageId) {
            val before = beforeImages.getValue(pageId)
            val after = page.data
            if (!before.contentEquals(after)) {
                txnManager.logUpdate(txnId, pageId, before, after.copyOf())
                onTouch(pageId)
            }
        }
    }

    private fun track(pageId: Long, page: Page) {
        liveByPageId.putIfAbsent(pageId, page)
        beforeImages.putIfAbsent(pageId, page.data.copyOf())
    }
}
