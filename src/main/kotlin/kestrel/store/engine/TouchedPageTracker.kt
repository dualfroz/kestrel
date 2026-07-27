package kestrel.store.engine

/**
 * Remembers every page id a still-active transaction has touched across
 * however many [TransactionalHeap] or [TransactionalIndex] operations it
 * performs, so [StorageEngine.abort] can invalidate the buffer pool's cached
 * copies of exactly those pages once the underlying rollback has restored
 * their before-images on disk.
 */
class TouchedPageTracker {
    private val pagesByTxn = HashMap<Long, MutableSet<Long>>()

    fun record(txnId: Long, pageId: Long) {
        pagesByTxn.getOrPut(txnId) { LinkedHashSet() }.add(pageId)
    }

    fun forget(txnId: Long): Set<Long> = pagesByTxn.remove(txnId) ?: emptySet()

    /**
     * Every page [txnId] has touched so far, without forgetting them - used after a savepoint
     * rollback, which (unlike [forget]'s callers) does not resolve the transaction, so pages touched
     * before the savepoint may still be written again later in the same transaction.
     */
    fun peek(txnId: Long): Set<Long> = pagesByTxn[txnId] ?: emptySet()
}
