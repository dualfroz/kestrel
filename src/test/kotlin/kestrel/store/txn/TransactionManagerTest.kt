package kestrel.store.txn

import kestrel.store.page.DiskManager
import kestrel.store.page.Page
import java.io.File
import java.nio.file.Files
import kestrel.store.wal.WriteAheadLog
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TransactionManagerTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".tmp").toFile().also { it.deleteOnExit() }

    private fun pageOf(fill: Int): ByteArray = ByteArray(Page.PAGE_SIZE) { fill.toByte() }

    private class Fixture {
        val diskManager = DiskManager(Files.createTempFile("kestrel-txnmgr-data", ".db").toFile().also { it.deleteOnExit() })
        val wal = WriteAheadLog(Files.createTempFile("kestrel-txnmgr-wal", ".log").toFile().also { it.deleteOnExit() })
        val manager = TransactionManager(wal, diskManager)
    }

    @Test
    fun beginStartsATransactionInActiveStatus() {
        val fixture = Fixture()
        val txn = fixture.manager.begin()
        assertEquals(TransactionManager.Status.ACTIVE, fixture.manager.statusOf(txn))
    }

    @Test
    fun commitAppliesLoggedUpdateAndReleasesLocks() {
        val fixture = Fixture()
        val pageId = fixture.diskManager.allocatePage()
        val before = pageOf(0)
        fixture.diskManager.writePage(Page(pageId).also { System.arraycopy(before, 0, it.data, 0, before.size) })

        val txn = fixture.manager.begin()
        fixture.manager.acquireExclusive(txn, pageId)
        val after = pageOf(3)
        fixture.manager.logUpdate(txn, pageId, before, after)
        fixture.diskManager.writePage(Page(pageId).also { System.arraycopy(after, 0, it.data, 0, after.size) })
        fixture.manager.commit(txn)

        assertEquals(TransactionManager.Status.COMMITTED, fixture.manager.statusOf(txn))
        assertContentEquals(after, fixture.diskManager.readPage(pageId).data)

        val otherTxn = fixture.manager.begin()
        fixture.manager.acquireExclusive(otherTxn, pageId)
    }

    @Test
    fun abortRollsBackLoggedUpdatesAndReleasesLocks() {
        val fixture = Fixture()
        val pageId = fixture.diskManager.allocatePage()
        val before = pageOf(1)
        fixture.diskManager.writePage(Page(pageId).also { System.arraycopy(before, 0, it.data, 0, before.size) })

        val txn = fixture.manager.begin()
        fixture.manager.acquireExclusive(txn, pageId)
        val after = pageOf(4)
        fixture.manager.logUpdate(txn, pageId, before, after)
        fixture.diskManager.writePage(Page(pageId).also { System.arraycopy(after, 0, it.data, 0, after.size) })
        fixture.manager.abort(txn)

        assertEquals(TransactionManager.Status.ABORTED, fixture.manager.statusOf(txn))
        assertContentEquals(before, fixture.diskManager.readPage(pageId).data)

        val otherTxn = fixture.manager.begin()
        fixture.manager.acquireExclusive(otherTxn, pageId)
    }

    @Test
    fun operationsOnAResolvedTransactionFail() {
        val fixture = Fixture()
        val txn = fixture.manager.begin()
        fixture.manager.commit(txn)
        assertFailsWith<TransactionException> { fixture.manager.commit(txn) }
        assertFailsWith<TransactionException> { fixture.manager.abort(txn) }
        assertFailsWith<TransactionException> { fixture.manager.acquireExclusive(txn, 1L) }
    }

    @Test
    fun statusOfUnknownTransactionFails() {
        val fixture = Fixture()
        assertFailsWith<TransactionException> { fixture.manager.statusOf(999L) }
    }
}
