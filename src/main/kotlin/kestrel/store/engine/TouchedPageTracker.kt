package kestrel.store.engine

class TouchedPageTracker {
    private val pagesByTxn = HashMap<Long, MutableSet<Long>>()

    fun record(txnId: Long, pageId: Long) {
        pagesByTxn.getOrPut(txnId) { LinkedHashSet() }.add(pageId)
    }

    fun forget(txnId: Long): Set<Long> = pagesByTxn.remove(txnId) ?: emptySet()

    fun peek(txnId: Long): Set<Long> = pagesByTxn[txnId] ?: emptySet()
}
