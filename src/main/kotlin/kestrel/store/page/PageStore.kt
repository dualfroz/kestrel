package kestrel.store.page

/**
 * The page-level surface [kestrel.store.heap.HeapFile] and
 * [kestrel.store.btree.BPlusTree] depend on, extracted from [BufferPool] so a
 * transactional caller can substitute a decorator (see
 * [kestrel.store.engine.LoggingPageStore]) that observes every page touched
 * during an operation without either data structure knowing it is being
 * logged.
 */
interface PageStore {
    fun fetchPage(pageId: Long): Page
    fun newPage(): Page
    fun unpinPage(pageId: Long, markDirty: Boolean)
    fun deletePage(pageId: Long)
}
