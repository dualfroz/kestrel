package kestrel.store.txn

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LockManagerTest {
    private fun waitUntil(timeoutMs: Long = 2000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("condition never became true")
            Thread.sleep(5)
        }
    }

    @Test
    fun sharedLocksAreCompatibleAcrossTransactions() {
        val locks = LockManager()
        locks.acquireShared(1L, 100L)
        locks.acquireShared(2L, 100L)
        assertEquals(mapOf(1L to LockManager.LockMode.SHARED, 2L to LockManager.LockMode.SHARED), locks.holders(100L))
    }

    @Test
    fun reacquiringTheSameOrWeakerModeIsANoop() {
        val locks = LockManager()
        locks.acquireExclusive(1L, 100L)
        locks.acquireShared(1L, 100L)
        assertEquals(mapOf(1L to LockManager.LockMode.EXCLUSIVE), locks.holders(100L))
    }

    @Test
    fun upgradeFromSharedToExclusiveSucceedsWhenSoleHolder() {
        val locks = LockManager()
        locks.acquireShared(1L, 100L)
        locks.acquireExclusive(1L, 100L)
        assertEquals(mapOf(1L to LockManager.LockMode.EXCLUSIVE), locks.holders(100L))
    }

    @Test
    fun exclusiveLockBlocksAnotherTransactionUntilReleased() {
        val locks = LockManager()
        locks.acquireExclusive(1L, 100L)

        val acquired = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            locks.acquireExclusive(2L, 100L)
            acquired.set(true)
        }
        thread.start()
        waitUntil { locks.waiters(100L).contains(2L) }
        assertTrue(!acquired.get(), "txn 2 must not acquire while txn 1 holds the exclusive lock")

        locks.release(1L, 100L)
        thread.join(2000)
        assertTrue(acquired.get())
        assertEquals(mapOf(2L to LockManager.LockMode.EXCLUSIVE), locks.holders(100L))
    }

    @Test
    fun releaseAllDropsEveryLockHeldByATransaction() {
        val locks = LockManager()
        locks.acquireExclusive(1L, 100L)
        locks.acquireShared(1L, 200L)
        locks.releaseAll(1L)
        assertEquals(emptyMap(), locks.holders(100L))
        assertEquals(emptyMap(), locks.holders(200L))
    }

    @Test
    fun circularWaitIsDetectedAndRejectedWithoutBlocking() {
        val locks = LockManager()
        locks.acquireExclusive(1L, 100L)
        locks.acquireExclusive(2L, 200L)

        val blocked = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            locks.acquireExclusive(1L, 200L)
            blocked.set(true)
        }
        thread.start()
        waitUntil { locks.waiters(200L).contains(1L) }

        assertFailsWith<LockManager.DeadlockException> { locks.acquireExclusive(2L, 100L) }

        locks.release(2L, 200L)
        thread.join(2000)
        assertTrue(blocked.get())
    }

    @Test
    fun unrelatedWaitersDoNotFalselyTriggerDeadlock() {
        val locks = LockManager()
        locks.acquireExclusive(1L, 100L)
        locks.acquireExclusive(2L, 200L)
    }

    @Test
    fun exclusiveWaiterEventuallyRunsToCompletionAfterHolderReleases() {
        val locks = LockManager()
        locks.acquireExclusive(1L, 100L)
        val latch = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            locks.acquireExclusive(2L, 100L)
            latch.countDown()
        }
        thread.start()
        waitUntil { locks.waiters(100L).contains(2L) }
        locks.releaseAll(1L)
        assertTrue(latch.await(2, TimeUnit.SECONDS))
    }
}
