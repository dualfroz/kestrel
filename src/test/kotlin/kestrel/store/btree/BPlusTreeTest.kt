package kestrel.store.btree

import kestrel.store.codec.KeyCodec
import kestrel.store.page.BufferPool
import kestrel.store.page.DiskManager
import kestrel.storage.Value
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BPlusTreeTest {
    private fun newPool(capacity: Int = 64): BufferPool {
        val file = Files.createTempFile("kestrel-btree", ".db").toFile().also { it.deleteOnExit() }
        return BufferPool(DiskManager(file), capacity)
    }

    private fun key(value: Long): ByteArray = KeyCodec.encode(Value.IntegerValue(value))

    private fun decodeKey(bytes: ByteArray): Long = (KeyCodec.decodeKey(bytes)[0] as Value.IntegerValue).value

    @Test
    fun searchOnEmptyTreeReturnsNull() {
        val tree = BPlusTree.create(newPool())
        assertNull(tree.search(key(1)))
    }

    @Test
    fun insertAndSearchSingleEntry() {
        val tree = BPlusTree.create(newPool())
        tree.insert(key(1), 100L)
        assertEquals(100L, tree.search(key(1)))
        assertNull(tree.search(key(2)))
    }

    @Test
    fun insertingSameKeyTwiceUpserts() {
        val tree = BPlusTree.create(newPool())
        tree.insert(key(5), 1L)
        tree.insert(key(5), 2L)
        assertEquals(2L, tree.search(key(5)))
        assertEquals(listOf(5L to 2L), tree.rangeScan().asSequence().map { decodeKey(it.first) to it.second }.toList())
    }

    @Test
    fun deleteRemovesEntryAndReturnsFalseWhenAbsent() {
        val tree = BPlusTree.create(newPool())
        tree.insert(key(1), 10L)
        assertTrue(tree.delete(key(1)))
        assertNull(tree.search(key(1)))
        assertFalse(tree.delete(key(1)))
        assertFalse(tree.delete(key(99)))
    }

    @Test
    fun rangeScanOnEmptyTreeYieldsNothing() {
        val tree = BPlusTree.create(newPool())
        assertTrue(tree.rangeScan().asSequence().toList().isEmpty())
    }

    @Test
    fun rangeScanRespectsInclusiveAndExclusiveUpperBound() {
        val tree = BPlusTree.create(newPool())
        for (i in 1L..10L) tree.insert(key(i), i * 10)
        val exclusive = tree.rangeScan(key(3), key(7)).asSequence().map { decodeKey(it.first) }.toList()
        assertEquals((3L..6L).toList(), exclusive)
        val inclusive = tree.rangeScan(key(3), key(7), toInclusive = true).asSequence().map { decodeKey(it.first) }.toList()
        assertEquals((3L..7L).toList(), inclusive)
    }

    @Test
    fun deletingEveryEntryLeavesAnEmptySearchableTree() {
        val tree = BPlusTree.create(newPool())
        for (i in 1L..20L) tree.insert(key(i), i)
        for (i in 1L..20L) assertTrue(tree.delete(key(i)))
        assertTrue(tree.rangeScan().asSequence().toList().isEmpty())
        assertNull(tree.search(key(1)))
        tree.insert(key(1), 42L)
        assertEquals(42L, tree.search(key(1)))
    }

    @Test
    fun splitsAcrossMultipleLevelsAndPersistsThroughBufferEviction() {
        val pool = newPool(capacity = 8) // small pool forces heavy eviction/reload traffic
        val tree = BPlusTree.create(pool)
        val n = 3000
        for (i in 0 until n) tree.insert(key(i.toLong()), i.toLong() * 7)
        for (i in 0 until n) assertEquals(i.toLong() * 7, tree.search(key(i.toLong())), "key $i")
        val scanned = tree.rangeScan().asSequence().map { decodeKey(it.first) }.toList()
        assertEquals((0 until n).map { it.toLong() }, scanned)
        assertTrue(tree.rootPageId >= 0)
    }

    @Test
    fun stressInsertSearchDeleteAndRangeScanAgainstReferenceMap() {
        val pool = newPool(capacity = 96)
        val tree = BPlusTree.create(pool)
        val random = Random(1234)
        val reference = java.util.TreeMap<Long, Long>()

        val keys = generateSequence { random.nextLong(-2_000_000_000L, 2_000_000_000L) }
            .distinct()
            .take(12000)
            .toList()
        for (k in keys) {
            val rowId = k * 3 + 1
            tree.insert(key(k), rowId)
            reference[k] = rowId
        }

        for ((k, v) in reference) assertEquals(v, tree.search(key(k)), "expected key $k")
        assertNull(tree.search(key(9_999_999_999L)))

        val fullScan = tree.rangeScan().asSequence().map { decodeKey(it.first) to it.second }.toList()
        assertEquals(reference.entries.map { it.key to it.value }, fullScan)

        val lo = reference.keys.elementAt(reference.size / 4)
        val hi = reference.keys.elementAt(reference.size / 2)
        val expectedRange = reference.subMap(lo, true, hi, true).entries.map { it.key to it.value }
        val actualRange = tree.rangeScan(key(lo), key(hi), toInclusive = true)
            .asSequence().map { decodeKey(it.first) to it.second }.toList()
        assertEquals(expectedRange, actualRange)

        val toDelete = keys.filterIndexed { index, _ -> index % 2 == 0 }
        for (k in toDelete) {
            assertTrue(tree.delete(key(k)), "expected to delete $k")
            reference.remove(k)
        }
        for (k in toDelete.take(50)) assertFalse(tree.delete(key(k)))

        for ((k, v) in reference) assertEquals(v, tree.search(key(k)), "expected surviving key $k")
        for (k in toDelete) assertNull(tree.search(key(k)), "expected deleted key $k to be gone")

        val afterDeleteScan = tree.rangeScan().asSequence().map { decodeKey(it.first) to it.second }.toList()
        assertEquals(reference.entries.map { it.key to it.value }, afterDeleteScan)
    }

    @Test
    fun treeReopensFromPersistedRootAfterBufferFlush() {
        val file = Files.createTempFile("kestrel-btree-reopen", ".db").toFile().also { it.deleteOnExit() }
        val pool1 = BufferPool(DiskManager(file), 32)
        val tree1 = BPlusTree.create(pool1)
        for (i in 0 until 500) tree1.insert(key(i.toLong()), i.toLong())
        pool1.flushAll()
        val rootId = tree1.rootPageId

        val manager2 = DiskManager(file)
        val pool2 = BufferPool(manager2, 32)
        val tree2 = BPlusTree.open(pool2, rootId)
        for (i in 0 until 500) assertEquals(i.toLong(), tree2.search(key(i.toLong())))
        val scanned = tree2.rangeScan().asSequence().map { decodeKey(it.first) }.toList()
        assertEquals((0 until 500).map { it.toLong() }, scanned)
    }

    @Test
    fun allPageIdsOnAFreshTreeIsJustTheRoot() {
        val tree = BPlusTree.create(newPool())
        assertEquals(listOf(tree.rootPageId), tree.allPageIds())
    }

    @Test
    fun allPageIdsCoversEveryInternalAndLeafPageAfterSplits() {
        val pool = newPool()
        val tree = BPlusTree.create(pool)
        val n = 500
        for (i in 0 until n) tree.insert(key(i.toLong()), i.toLong())

        val pageIds = tree.allPageIds()
        assertTrue(pageIds.contains(tree.rootPageId))
        assertEquals(pageIds.size, pageIds.toSet().size, "expected no page id to be visited twice")
        assertTrue(pageIds.size > 1, "expected $n inserts to have split the tree across more than one page")

        val leafKeys = pageIds.flatMap { pageId ->
            val page = pool.fetchPage(pageId)
            val node = NodeCodec.readFrom(page)
            pool.unpinPage(pageId, false)
            if (node is BTreeNode.Leaf) node.keys.map { decodeKey(it) } else emptyList()
        }
        assertEquals((0 until n).map { it.toLong() }.sorted(), leafKeys.sorted(), "every leaf page must be reachable from allPageIds")
    }
}
