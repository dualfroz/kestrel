package kestrel.store.btree

import kestrel.store.codec.ByteReader
import kestrel.store.codec.ByteWriter
import kestrel.store.codec.CodecException
import kestrel.store.page.Page

sealed class BTreeNode {
    abstract val pageId: Long

    class Leaf(
        override val pageId: Long,
        val keys: MutableList<ByteArray>,
        val values: MutableList<Long>,
        var nextLeaf: Long
    ) : BTreeNode()

    class Internal(
        override val pageId: Long,
        val keys: MutableList<ByteArray>,
        val children: MutableList<Long>
    ) : BTreeNode()
}

object NodeCodec {
    private const val TYPE_LEAF = 0
    private const val TYPE_INTERNAL = 1
    const val NO_PAGE = -1L

    fun serialize(node: BTreeNode): ByteArray {
        val writer = ByteWriter(Page.PAGE_SIZE)
        when (node) {
            is BTreeNode.Leaf -> {
                writer.writeByte(TYPE_LEAF)
                writeCount(writer, node.keys.size)
                writer.writeFixedLong(node.nextLeaf)
                for (i in node.keys.indices) {
                    writer.writeLengthPrefixed(node.keys[i])
                    writer.writeFixedLong(node.values[i])
                }
            }
            is BTreeNode.Internal -> {
                writer.writeByte(TYPE_INTERNAL)
                writeCount(writer, node.keys.size)
                writer.writeFixedLong(node.children[0])
                for (i in node.keys.indices) {
                    writer.writeLengthPrefixed(node.keys[i])
                    writer.writeFixedLong(node.children[i + 1])
                }
            }
        }
        return writer.toByteArray()
    }

    fun writeTo(page: Page, node: BTreeNode) {
        val bytes = serialize(node)
        if (bytes.size > Page.PAGE_SIZE) {
            throw CodecException("btree node ${node.pageId} serializes to ${bytes.size} bytes, exceeds page size")
        }
        page.data.fill(0)
        System.arraycopy(bytes, 0, page.data, 0, bytes.size)
        page.isDirty = true
    }

    fun deserialize(pageId: Long, data: ByteArray): BTreeNode {
        val reader = ByteReader(data)
        val type = reader.readByte()
        val count = readCount(reader)
        return when (type) {
            TYPE_LEAF -> {
                val nextLeaf = reader.readFixedLong()
                val keys = ArrayList<ByteArray>(count)
                val values = ArrayList<Long>(count)
                repeat(count) {
                    keys.add(reader.readLengthPrefixed())
                    values.add(reader.readFixedLong())
                }
                BTreeNode.Leaf(pageId, keys, values, nextLeaf)
            }
            TYPE_INTERNAL -> {
                val firstChild = reader.readFixedLong()
                val keys = ArrayList<ByteArray>(count)
                val children = ArrayList<Long>(count + 1)
                children.add(firstChild)
                repeat(count) {
                    keys.add(reader.readLengthPrefixed())
                    children.add(reader.readFixedLong())
                }
                BTreeNode.Internal(pageId, keys, children)
            }
            else -> throw CodecException("unknown btree node type $type")
        }
    }

    fun readFrom(page: Page): BTreeNode = deserialize(page.id, page.data)

    fun fitsInPage(node: BTreeNode): Boolean = serialize(node).size <= Page.PAGE_SIZE

    private fun writeCount(writer: ByteWriter, count: Int) {
        writer.writeByte((count ushr 8) and 0xFF)
        writer.writeByte(count and 0xFF)
    }

    private fun readCount(reader: ByteReader): Int = (reader.readByte() shl 8) or reader.readByte()
}
