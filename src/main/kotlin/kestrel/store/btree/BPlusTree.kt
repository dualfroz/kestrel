package kestrel.store.btree

import kestrel.KestrelException
import kestrel.store.codec.KeyCodec
import kestrel.store.page.Page
import kestrel.store.page.PageStore

class BTreeCorruptionException(message: String) : KestrelException(message)

/**
 * A disk-backed B+Tree mapping order-preserving byte keys (see [KeyCodec]) to
 * row pointers. Nodes live on [Page]s fetched through a [BufferPool]; leaves
 * are linked for ordered range scans. Insert splits an overflowing node and
 * propagates a promoted separator key upward, growing the root when needed.
 * Delete removes the entry and, when a node drops below
 * [UNDERFLOW_THRESHOLD], redistributes an entry from a sibling with spare
 * capacity or merges with one, shrinking the root when it is left with a
 * single child.
 */
class BPlusTree private constructor(private val bufferPool: PageStore, rootPageId: Long) {
    companion object {
        private const val UNDERFLOW_THRESHOLD = Page.PAGE_SIZE / 4

        fun create(bufferPool: PageStore): BPlusTree {
            val page = bufferPool.newPage()
            NodeCodec.writeTo(page, BTreeNode.Leaf(page.id, mutableListOf(), mutableListOf(), NodeCodec.NO_PAGE))
            bufferPool.unpinPage(page.id, true)
            return BPlusTree(bufferPool, page.id)
        }

        fun open(bufferPool: PageStore, rootPageId: Long): BPlusTree = BPlusTree(bufferPool, rootPageId)
    }

    var rootPageId: Long = rootPageId
        private set

    fun search(key: ByteArray): Long? {
        var pageId = rootPageId
        while (true) {
            val (_, node) = fetch(pageId)
            when (node) {
                is BTreeNode.Internal -> {
                    val nextId = node.children[childIndexFor(node.keys, key)]
                    bufferPool.unpinPage(pageId, false)
                    pageId = nextId
                }
                is BTreeNode.Leaf -> {
                    val idx = lowerBound(node.keys, key)
                    val result = if (idx < node.keys.size && KeyCodec.compare(node.keys[idx], key) == 0) {
                        node.values[idx]
                    } else {
                        null
                    }
                    bufferPool.unpinPage(pageId, false)
                    return result
                }
            }
        }
    }

    fun insert(key: ByteArray, value: Long) {
        val split = insertRecursive(rootPageId, key, value)
        if (split != null) {
            val newRoot = bufferPool.newPage()
            NodeCodec.writeTo(
                newRoot,
                BTreeNode.Internal(newRoot.id, mutableListOf(split.promotedKey), mutableListOf(rootPageId, split.newPageId))
            )
            bufferPool.unpinPage(newRoot.id, true)
            rootPageId = newRoot.id
        }
    }

    fun delete(key: ByteArray): Boolean {
        val found = deleteRecursive(rootPageId, key)
        val (_, rootNode) = fetch(rootPageId)
        if (rootNode is BTreeNode.Internal && rootNode.keys.isEmpty()) {
            val onlyChild = rootNode.children[0]
            bufferPool.unpinPage(rootPageId, false)
            bufferPool.deletePage(rootPageId)
            rootPageId = onlyChild
        } else {
            bufferPool.unpinPage(rootPageId, false)
        }
        return found
    }

    /** Every page belonging to this tree: every internal node and every leaf, reached by a full traversal from the root. */
    fun allPageIds(): List<Long> {
        val ids = mutableListOf<Long>()
        val pending = ArrayDeque<Long>()
        pending.add(rootPageId)
        while (pending.isNotEmpty()) {
            val pageId = pending.removeFirst()
            ids.add(pageId)
            val (_, node) = fetch(pageId)
            bufferPool.unpinPage(pageId, false)
            if (node is BTreeNode.Internal) pending.addAll(node.children)
        }
        return ids
    }

    /** Ordered scan over `[from, to]` (or `[from, to)` when [toInclusive] is false). Null bounds are open. */
    fun rangeScan(from: ByteArray? = null, to: ByteArray? = null, toInclusive: Boolean = false): Iterator<Pair<ByteArray, Long>> {
        val startLeafId = leftmostLeafFor(from)
        return iterator {
            var currentLeafId = startLeafId
            var firstLeaf = true
            outer@ while (currentLeafId != NodeCodec.NO_PAGE) {
                val (_, leaf) = fetch(currentLeafId)
                leaf as BTreeNode.Leaf
                var index = if (firstLeaf && from != null) lowerBound(leaf.keys, from) else 0
                firstLeaf = false
                while (index < leaf.keys.size) {
                    val currentKey = leaf.keys[index]
                    if (to != null) {
                        val cmp = KeyCodec.compare(currentKey, to)
                        if (cmp > 0 || (cmp == 0 && !toInclusive)) {
                            bufferPool.unpinPage(currentLeafId, false)
                            break@outer
                        }
                    }
                    yield(currentKey to leaf.values[index])
                    index++
                }
                val next = leaf.nextLeaf
                bufferPool.unpinPage(currentLeafId, false)
                currentLeafId = next
            }
        }
    }

    private fun leftmostLeafFor(from: ByteArray?): Long {
        var pageId = rootPageId
        while (true) {
            val (_, node) = fetch(pageId)
            when (node) {
                is BTreeNode.Internal -> {
                    val idx = if (from == null) 0 else childIndexFor(node.keys, from)
                    val nextId = node.children[idx]
                    bufferPool.unpinPage(pageId, false)
                    pageId = nextId
                }
                is BTreeNode.Leaf -> {
                    bufferPool.unpinPage(pageId, false)
                    return pageId
                }
            }
        }
    }

    private data class SplitResult(val promotedKey: ByteArray, val newPageId: Long)

    private fun insertRecursive(pageId: Long, key: ByteArray, value: Long): SplitResult? {
        val (page, node) = fetch(pageId)
        var dirty = false
        val result: SplitResult?
        when (node) {
            is BTreeNode.Leaf -> {
                val idx = lowerBound(node.keys, key)
                if (idx < node.keys.size && KeyCodec.compare(node.keys[idx], key) == 0) {
                    node.values[idx] = value
                } else {
                    node.keys.add(idx, key)
                    node.values.add(idx, value)
                }
                dirty = true
                result = if (NodeCodec.fitsInPage(node)) {
                    NodeCodec.writeTo(page, node)
                    null
                } else {
                    splitLeaf(page, node)
                }
            }
            is BTreeNode.Internal -> {
                val idx = childIndexFor(node.keys, key)
                val childSplit = insertRecursive(node.children[idx], key, value)
                if (childSplit == null) {
                    result = null
                } else {
                    node.keys.add(idx, childSplit.promotedKey)
                    node.children.add(idx + 1, childSplit.newPageId)
                    dirty = true
                    result = if (NodeCodec.fitsInPage(node)) {
                        NodeCodec.writeTo(page, node)
                        null
                    } else {
                        splitInternal(page, node)
                    }
                }
            }
        }
        bufferPool.unpinPage(pageId, dirty)
        return result
    }

    private fun splitLeaf(page: Page, leaf: BTreeNode.Leaf): SplitResult {
        val mid = leaf.keys.size / 2
        val rightKeys = ArrayList(leaf.keys.subList(mid, leaf.keys.size))
        val rightValues = ArrayList(leaf.values.subList(mid, leaf.values.size))
        val leftKeys = ArrayList(leaf.keys.subList(0, mid))
        val leftValues = ArrayList(leaf.values.subList(0, mid))
        val rightPage = bufferPool.newPage()
        val rightNode = BTreeNode.Leaf(rightPage.id, rightKeys, rightValues, leaf.nextLeaf)
        val leftNode = BTreeNode.Leaf(page.id, leftKeys, leftValues, rightPage.id)
        NodeCodec.writeTo(page, leftNode)
        NodeCodec.writeTo(rightPage, rightNode)
        bufferPool.unpinPage(rightPage.id, true)
        return SplitResult(rightKeys[0], rightPage.id)
    }

    private fun splitInternal(page: Page, node: BTreeNode.Internal): SplitResult {
        val mid = node.keys.size / 2
        val promotedKey = node.keys[mid]
        val leftKeys = ArrayList(node.keys.subList(0, mid))
        val leftChildren = ArrayList(node.children.subList(0, mid + 1))
        val rightKeys = ArrayList(node.keys.subList(mid + 1, node.keys.size))
        val rightChildren = ArrayList(node.children.subList(mid + 1, node.children.size))
        val rightPage = bufferPool.newPage()
        val rightNode = BTreeNode.Internal(rightPage.id, rightKeys, rightChildren)
        val leftNode = BTreeNode.Internal(page.id, leftKeys, leftChildren)
        NodeCodec.writeTo(page, leftNode)
        NodeCodec.writeTo(rightPage, rightNode)
        bufferPool.unpinPage(rightPage.id, true)
        return SplitResult(promotedKey, rightPage.id)
    }

    private fun deleteRecursive(pageId: Long, key: ByteArray): Boolean {
        val (page, node) = fetch(pageId)
        val found: Boolean
        when (node) {
            is BTreeNode.Leaf -> {
                val idx = lowerBound(node.keys, key)
                found = idx < node.keys.size && KeyCodec.compare(node.keys[idx], key) == 0
                if (found) {
                    node.keys.removeAt(idx)
                    node.values.removeAt(idx)
                    NodeCodec.writeTo(page, node)
                }
                bufferPool.unpinPage(pageId, found)
            }
            is BTreeNode.Internal -> {
                val idx = childIndexFor(node.keys, key)
                val childId = node.children[idx]
                found = deleteRecursive(childId, key)
                if (found) {
                    val (_, childNode) = fetch(childId)
                    val underflow = isUnderflowNode(childNode)
                    bufferPool.unpinPage(childId, false)
                    if (underflow) {
                        rebalanceChild(node, idx)
                        NodeCodec.writeTo(page, node)
                    }
                }
                bufferPool.unpinPage(pageId, found)
            }
        }
        return found
    }

    private fun rebalanceChild(parent: BTreeNode.Internal, idx: Int) {
        val leftIdx = idx - 1
        val rightIdx = idx + 1

        if (leftIdx >= 0) {
            val (leftPage, leftNode) = fetch(parent.children[leftIdx])
            if (canLend(leftNode)) {
                borrowFromLeft(parent, idx, leftPage, leftNode)
                bufferPool.unpinPage(leftNode.pageId, true)
                return
            }
            bufferPool.unpinPage(leftNode.pageId, false)
        }
        if (rightIdx < parent.children.size) {
            val (rightPage, rightNode) = fetch(parent.children[rightIdx])
            if (canLend(rightNode)) {
                borrowFromRight(parent, idx, rightPage, rightNode)
                bufferPool.unpinPage(rightNode.pageId, true)
                return
            }
            bufferPool.unpinPage(rightNode.pageId, false)
        }
        if (leftIdx >= 0) mergeChildren(parent, leftIdx) else mergeChildren(parent, idx)
    }

    private fun canLend(node: BTreeNode): Boolean = when (node) {
        is BTreeNode.Leaf -> node.keys.size > 1
        is BTreeNode.Internal -> node.keys.size > 1
    }

    private fun borrowFromLeft(parent: BTreeNode.Internal, idx: Int, leftPage: Page, leftNode: BTreeNode) {
        val (childPage, childNode) = fetch(parent.children[idx])
        when {
            leftNode is BTreeNode.Leaf && childNode is BTreeNode.Leaf -> {
                val lastIdx = leftNode.keys.size - 1
                val borrowedKey = leftNode.keys.removeAt(lastIdx)
                val borrowedValue = leftNode.values.removeAt(lastIdx)
                childNode.keys.add(0, borrowedKey)
                childNode.values.add(0, borrowedValue)
                parent.keys[idx - 1] = childNode.keys[0]
            }
            leftNode is BTreeNode.Internal && childNode is BTreeNode.Internal -> {
                val movedChild = leftNode.children.removeAt(leftNode.children.size - 1)
                val separatorDown = leftNode.keys.removeAt(leftNode.keys.size - 1)
                childNode.children.add(0, movedChild)
                childNode.keys.add(0, parent.keys[idx - 1])
                parent.keys[idx - 1] = separatorDown
            }
            else -> throw BTreeCorruptionException("sibling type mismatch during redistribution")
        }
        NodeCodec.writeTo(leftPage, leftNode)
        NodeCodec.writeTo(childPage, childNode)
        bufferPool.unpinPage(childNode.pageId, true)
    }

    private fun borrowFromRight(parent: BTreeNode.Internal, idx: Int, rightPage: Page, rightNode: BTreeNode) {
        val (childPage, childNode) = fetch(parent.children[idx])
        when {
            rightNode is BTreeNode.Leaf && childNode is BTreeNode.Leaf -> {
                val borrowedKey = rightNode.keys.removeAt(0)
                val borrowedValue = rightNode.values.removeAt(0)
                childNode.keys.add(borrowedKey)
                childNode.values.add(borrowedValue)
                parent.keys[idx] = rightNode.keys[0]
            }
            rightNode is BTreeNode.Internal && childNode is BTreeNode.Internal -> {
                val movedChild = rightNode.children.removeAt(0)
                val separatorDown = rightNode.keys.removeAt(0)
                childNode.children.add(movedChild)
                childNode.keys.add(parent.keys[idx])
                parent.keys[idx] = separatorDown
            }
            else -> throw BTreeCorruptionException("sibling type mismatch during redistribution")
        }
        NodeCodec.writeTo(rightPage, rightNode)
        NodeCodec.writeTo(childPage, childNode)
        bufferPool.unpinPage(childNode.pageId, true)
    }

    private fun mergeChildren(parent: BTreeNode.Internal, leftIdx: Int) {
        val leftId = parent.children[leftIdx]
        val rightId = parent.children[leftIdx + 1]
        val (leftPage, leftNode) = fetch(leftId)
        val (_, rightNode) = fetch(rightId)
        when {
            leftNode is BTreeNode.Leaf && rightNode is BTreeNode.Leaf -> {
                leftNode.keys.addAll(rightNode.keys)
                leftNode.values.addAll(rightNode.values)
                leftNode.nextLeaf = rightNode.nextLeaf
            }
            leftNode is BTreeNode.Internal && rightNode is BTreeNode.Internal -> {
                leftNode.keys.add(parent.keys[leftIdx])
                leftNode.keys.addAll(rightNode.keys)
                leftNode.children.addAll(rightNode.children)
            }
            else -> throw BTreeCorruptionException("sibling type mismatch during merge")
        }
        NodeCodec.writeTo(leftPage, leftNode)
        bufferPool.unpinPage(leftId, true)
        bufferPool.unpinPage(rightId, false)
        bufferPool.deletePage(rightId)
        parent.keys.removeAt(leftIdx)
        parent.children.removeAt(leftIdx + 1)
    }

    private fun isUnderflowNode(node: BTreeNode): Boolean = NodeCodec.serialize(node).size < UNDERFLOW_THRESHOLD

    private fun fetch(pageId: Long): Pair<Page, BTreeNode> {
        val page = bufferPool.fetchPage(pageId)
        return page to NodeCodec.readFrom(page)
    }

    private fun lowerBound(keys: List<ByteArray>, key: ByteArray): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (KeyCodec.compare(keys[mid], key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun childIndexFor(keys: List<ByteArray>, key: ByteArray): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (KeyCodec.compare(keys[mid], key) <= 0) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
