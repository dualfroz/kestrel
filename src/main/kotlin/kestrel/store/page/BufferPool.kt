package kestrel.store.page

import kestrel.KestrelException

class BufferPoolExhaustedException(message: String) : KestrelException(message)

class BufferPool(private val diskManager: DiskManager, private val capacity: Int) : PageStore {
    init {
        require(capacity > 0) { "buffer pool capacity must be positive" }
    }

    private val frames = arrayOfNulls<Page>(capacity)
    private val referenceBit = BooleanArray(capacity)
    private val pageTable = HashMap<Long, Int>()
    private val freeFrames = ArrayDeque((0 until capacity).toList())
    private var clockHand = 0

    override fun fetchPage(pageId: Long): Page {
        pageTable[pageId]?.let { idx ->
            val page = frames[idx]!!
            page.pinCount++
            referenceBit[idx] = true
            return page
        }
        val idx = allocateFrame()
        val page = diskManager.readPage(pageId)
        page.pinCount = 1
        frames[idx] = page
        pageTable[pageId] = idx
        referenceBit[idx] = true
        return page
    }

    override fun newPage(): Page {
        val idx = allocateFrame()
        val pageId = diskManager.allocatePage()
        val page = Page(pageId)
        page.pinCount = 1
        page.isDirty = true
        frames[idx] = page
        pageTable[pageId] = idx
        referenceBit[idx] = true
        return page
    }

    override fun unpinPage(pageId: Long, markDirty: Boolean) {
        val idx = pageTable[pageId] ?: return
        val page = frames[idx]!!
        if (markDirty) page.isDirty = true
        if (page.pinCount > 0) page.pinCount--
    }

    fun flushPage(pageId: Long) {
        val idx = pageTable[pageId] ?: return
        flushFrame(idx)
    }

    fun flushAll() {
        for (idx in frames.indices) {
            if (frames[idx] != null) flushFrame(idx)
        }
    }

    override fun deletePage(pageId: Long) {
        dropFromCache(pageId)
        diskManager.freePage(pageId)
    }

    fun invalidate(pageId: Long) {
        dropFromCache(pageId)
    }

    private fun dropFromCache(pageId: Long) {
        val idx = pageTable[pageId] ?: return
        val page = frames[idx]!!
        check(page.pinCount == 0) { "cannot drop pinned page $pageId from the cache" }
        pageTable.remove(pageId)
        frames[idx] = null
        referenceBit[idx] = false
        freeFrames.addLast(idx)
    }

    fun isPinned(pageId: Long): Boolean = pageTable[pageId]?.let { frames[it]?.pinCount ?: 0 > 0 } ?: false

    private fun flushFrame(idx: Int) {
        val page = frames[idx] ?: return
        if (page.isDirty) {
            diskManager.writePage(page)
            page.isDirty = false
        }
    }

    private fun allocateFrame(): Int {
        if (freeFrames.isNotEmpty()) return freeFrames.removeFirst()
        var sweeps = 0
        val maxSweeps = 2 * capacity + 1
        while (sweeps <= maxSweeps) {
            val idx = clockHand
            clockHand = (clockHand + 1) % capacity
            val page = frames[idx]
            if (page == null) return idx
            if (page.pinCount > 0) {
                sweeps++
                continue
            }
            if (referenceBit[idx]) {
                referenceBit[idx] = false
                sweeps++
                continue
            }
            if (page.isDirty) diskManager.writePage(page)
            pageTable.remove(page.id)
            frames[idx] = null
            return idx
        }
        throw BufferPoolExhaustedException("no unpinned frame available to evict (capacity=$capacity)")
    }
}
