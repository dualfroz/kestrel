package kestrel.store.engine

import kestrel.store.page.Page
import kestrel.store.page.PageStore
import kestrel.store.txn.TransactionManager

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
