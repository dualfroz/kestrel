package kestrel.store.page

/** A fixed-size in-memory buffer for one on-disk page, tracked by the buffer pool. */
class Page(val id: Long) {
    companion object {
        const val PAGE_SIZE = 4096
    }

    val data: ByteArray = ByteArray(PAGE_SIZE)
    var isDirty: Boolean = false
    var pinCount: Int = 0

    fun reset() {
        data.fill(0)
        isDirty = false
    }
}
