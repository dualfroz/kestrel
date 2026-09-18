package kestrel.store.page

interface PageStore {
    fun fetchPage(pageId: Long): Page
    fun newPage(): Page
    fun unpinPage(pageId: Long, markDirty: Boolean)
    fun deletePage(pageId: Long)
}
