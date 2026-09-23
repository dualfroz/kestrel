package kestrel.store.engine

import kestrel.store.codec.KeyCodec
import kestrel.store.codec.TupleCodec
import kestrel.store.heap.RecordId
import kestrel.storage.Value
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageEngineTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    @Test
    fun aCommittedInsertSurvivesACrashThatNeverFlushedTheBufferPool() {
        val dataFile = tempFile("kestrel-engine-data")
        val walFile = tempFile("kestrel-engine-wal")

        val engine1 = StorageEngine.open(dataFile, walFile)
        val heap = engine1.createHeap()
        val txn = engine1.beginTransaction()
        val rid = heap.insert(txn, "durable across a crash".toByteArray())
        engine1.commit(txn)

        val engine2 = StorageEngine.open(dataFile, walFile)
        val reopenedHeap = engine2.openHeap(heap.headerPageId)
        val reader = engine2.beginTransaction()
        assertEquals("durable across a crash", String(reopenedHeap.get(reader, rid)))
        assertEquals(listOf(rid), reopenedHeap.scan(reader).map { it.first })
    }

    @Test
    fun abortMakesAnInsertInvisibleWithoutRestartingTheEngine() {
        val engine = StorageEngine.open(tempFile("kestrel-engine-data"), tempFile("kestrel-engine-wal"))
        val heap = engine.createHeap()

        val writer = engine.beginTransaction()
        heap.insert(writer, "temporary".toByteArray())
        engine.abort(writer)

        val reader = engine.beginTransaction()
        assertTrue(heap.scan(reader).isEmpty())
    }

    @Test
    fun aTableAndItsSecondaryIndexCommitAndAbortTogetherAsOneTransaction() {
        val engine = StorageEngine.open(tempFile("kestrel-engine-data"), tempFile("kestrel-engine-wal"))
        val table = engine.createHeap()
        val index = engine.createIndex()

        fun rowFor(id: Long, name: String) = TupleCodec.encode(listOf(Value.IntegerValue(id), Value.TextValue(name)))
        fun keyFor(id: Long) = KeyCodec.encode(Value.IntegerValue(id))

        val setup = engine.beginTransaction()
        val committedRid = table.insert(setup, rowFor(1, "alice"))
        index.insert(setup, keyFor(1), committedRid.encode())
        engine.commit(setup)

        val doomed = engine.beginTransaction()
        val doomedRid = table.insert(doomed, rowFor(2, "bob"))
        index.insert(doomed, keyFor(2), doomedRid.encode())
        engine.abort(doomed)

        val reader = engine.beginTransaction()
        assertNull(index.search(reader, keyFor(2)), "the aborted row's index entry must not survive")
        val foundRid = RecordId.decode(index.search(reader, keyFor(1))!!)
        assertEquals(committedRid, foundRid)
        val row = TupleCodec.decode(table.get(reader, foundRid))
        assertEquals(listOf(Value.IntegerValue(1), Value.TextValue("alice")), row)
        assertEquals(listOf(committedRid), table.scan(reader).map { it.first })
    }

    @Test
    fun anAbortedInsertFromAPriorSessionStaysGoneAfterReopening() {
        val dataFile = tempFile("kestrel-engine-data")
        val walFile = tempFile("kestrel-engine-wal")

        val engine1 = StorageEngine.open(dataFile, walFile)
        val heap = engine1.createHeap()

        val committed = engine1.beginTransaction()
        heap.insert(committed, "kept".toByteArray())
        engine1.commit(committed)

        val doomed = engine1.beginTransaction()
        heap.insert(doomed, "discarded".toByteArray())
        engine1.abort(doomed)
        engine1.close()

        val engine2 = StorageEngine.open(dataFile, walFile)
        val reopenedHeap = engine2.openHeap(heap.headerPageId)
        val reader = engine2.beginTransaction()
        assertEquals(listOf("kept"), reopenedHeap.scan(reader).map { String(it.second) })
    }

    @Test
    fun closingAndReopeningPreservesBothTableAndIndexData() {
        val dataFile = tempFile("kestrel-engine-data")
        val walFile = tempFile("kestrel-engine-wal")

        val engine1 = StorageEngine.open(dataFile, walFile)
        val table = engine1.createHeap()
        val index = engine1.createIndex()
        val txn = engine1.beginTransaction()
        val rid = table.insert(txn, TupleCodec.encode(listOf(Value.IntegerValue(7))))
        index.insert(txn, KeyCodec.encode(Value.IntegerValue(7)), rid.encode())
        engine1.commit(txn)
        engine1.close()

        val engine2 = StorageEngine.open(dataFile, walFile)
        val reopenedTable = engine2.openHeap(table.headerPageId)
        val reopenedIndex = engine2.openIndex(index.rootPageId)
        val reader = engine2.beginTransaction()
        val foundRid = RecordId.decode(reopenedIndex.search(reader, KeyCodec.encode(Value.IntegerValue(7)))!!)
        assertEquals(listOf(Value.IntegerValue(7)), TupleCodec.decode(reopenedTable.get(reader, foundRid)))
    }
}
