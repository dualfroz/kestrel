package kestrel.store.txn

import kestrel.KestrelException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A resource-level shared/exclusive lock table for strict two-phase locking:
 * transactions acquire locks as they go and are expected to release
 * everything at once, via [releaseAll], on commit or abort.
 *
 * Blocked requests are detected for deadlock *before* they wait: acquiring a
 * lock builds the tentative wait-for edges from the requester to the
 * resource's current holders and walks the existing wait-for graph (formed
 * by every other transaction currently blocked) to see whether any of those
 * holders already, transitively, waits on the requester. This avoids ever
 * parking a thread that could not possibly be woken. What it does not do is
 * enforce waiter ordering, so a steady stream of compatible shared requests
 * can starve a waiting exclusive request - a real risk this scope accepts in
 * exchange for a single shared [java.util.concurrent.locks.Condition]
 * instead of a priority queue.
 */
class LockManager {
    enum class LockMode { SHARED, EXCLUSIVE }

    class DeadlockException(message: String) : KestrelException(message)

    private data class Waiter(val txnId: Long, val mode: LockMode)

    private class ResourceLock {
        val holders = LinkedHashMap<Long, LockMode>()
        val waiters = ArrayList<Waiter>()
    }

    private val guard = ReentrantLock()
    private val condition = guard.newCondition()
    private val resources = HashMap<Long, ResourceLock>()
    private val locksHeldByTxn = HashMap<Long, MutableSet<Long>>()

    fun acquireShared(txnId: Long, resourceId: Long) = acquire(txnId, resourceId, LockMode.SHARED)

    fun acquireExclusive(txnId: Long, resourceId: Long) = acquire(txnId, resourceId, LockMode.EXCLUSIVE)

    fun release(txnId: Long, resourceId: Long) {
        guard.withLock {
            val lock = resources[resourceId] ?: return
            if (lock.holders.remove(txnId) != null) {
                locksHeldByTxn[txnId]?.remove(resourceId)
                condition.signalAll()
            }
        }
    }

    /** Releases every lock [txnId] holds. Called once, on commit or abort, under strict 2PL. */
    fun releaseAll(txnId: Long) {
        guard.withLock {
            val held = locksHeldByTxn.remove(txnId) ?: return
            for (resourceId in held) {
                resources[resourceId]?.holders?.remove(txnId)
            }
            condition.signalAll()
        }
    }

    /** Snapshot of current holders, e.g. for a `pg_locks`-style inspection view. */
    fun holders(resourceId: Long): Map<Long, LockMode> = guard.withLock {
        resources[resourceId]?.holders?.toMap() ?: emptyMap()
    }

    fun waiters(resourceId: Long): List<Long> = guard.withLock {
        resources[resourceId]?.waiters?.map { it.txnId } ?: emptyList()
    }

    private fun acquire(txnId: Long, resourceId: Long, mode: LockMode) {
        guard.withLock {
            val lock = resources.getOrPut(resourceId) { ResourceLock() }
            val current = lock.holders[txnId]
            if (current == LockMode.EXCLUSIVE || current == mode) return@withLock

            if (!canGrant(lock, txnId, mode)) {
                val blockers = lock.holders.keys.filter { it != txnId }
                if (hasCycle(txnId, blockers)) {
                    throw DeadlockException(
                        "granting $mode on resource $resourceId to txn $txnId would deadlock with $blockers"
                    )
                }
                val waiter = Waiter(txnId, mode)
                lock.waiters.add(waiter)
                try {
                    while (!canGrant(lock, txnId, mode)) {
                        condition.awaitUninterruptibly()
                    }
                } finally {
                    lock.waiters.remove(waiter)
                }
            }

            lock.holders[txnId] = mode
            locksHeldByTxn.getOrPut(txnId) { LinkedHashSet() }.add(resourceId)
        }
    }

    private fun canGrant(lock: ResourceLock, txnId: Long, mode: LockMode): Boolean {
        val others = lock.holders.filterKeys { it != txnId }
        if (others.isEmpty()) return true
        return mode == LockMode.SHARED && others.values.all { it == LockMode.SHARED }
    }

    /** True if any txn in [from] transitively waits, via the existing wait-for graph, on [target]. */
    private fun hasCycle(target: Long, from: List<Long>): Boolean {
        val visited = HashSet<Long>()
        val stack = ArrayDeque(from)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current == target) return true
            if (!visited.add(current)) continue
            for (lock in resources.values) {
                if (lock.waiters.any { it.txnId == current }) {
                    for (holderTxn in lock.holders.keys) {
                        if (holderTxn != current) stack.addLast(holderTxn)
                    }
                }
            }
        }
        return false
    }
}
