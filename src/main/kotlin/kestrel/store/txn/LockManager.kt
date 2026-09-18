package kestrel.store.txn

import kestrel.KestrelException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    fun releaseAll(txnId: Long) {
        guard.withLock {
            val held = locksHeldByTxn.remove(txnId) ?: return
            for (resourceId in held) {
                resources[resourceId]?.holders?.remove(txnId)
            }
            condition.signalAll()
        }
    }

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
