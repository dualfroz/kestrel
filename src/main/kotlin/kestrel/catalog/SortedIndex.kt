package kestrel.catalog

import java.util.NavigableMap
import java.util.TreeMap
import kestrel.KestrelException
import kestrel.storage.Collation
import kestrel.storage.Value
import kestrel.storage.ValueOrdering

data class IndexDefinition(
    val name: String,
    val tableName: String,
    val columnNames: List<String>,
    val unique: Boolean = false
) {
    val columnDisplayName: String get() = columnNames.joinToString(", ")
}

private sealed class IndexKey(val values: List<Value>) {
    class Exact(values: List<Value>) : IndexKey(values)
    class Low(values: List<Value>) : IndexKey(values)
    class High(values: List<Value>) : IndexKey(values)
}

private class IndexKeyOrdering(private val collations: List<Collation>) : Comparator<IndexKey> {
    override fun compare(left: IndexKey, right: IndexKey): Int {
        val common = minOf(left.values.size, right.values.size)
        for (i in 0 until common) {
            val cmp = ValueOrdering.compare(left.values[i], right.values[i], collations[i])
            if (cmp != 0) return cmp
        }
        if (left.values.size == right.values.size) return 0
        return if (left.values.size < right.values.size) {
            when (left) { is IndexKey.Low -> -1; is IndexKey.High -> 1; is IndexKey.Exact -> -1 }
        } else {
            when (right) { is IndexKey.Low -> 1; is IndexKey.High -> -1; is IndexKey.Exact -> 1 }
        }
    }
}

class SortedIndex(
    val definition: IndexDefinition,
    collations: List<Collation> = List(definition.columnNames.size) { Collation.Binary }
) {
    private val entries: TreeMap<IndexKey, MutableList<Int>> = TreeMap(IndexKeyOrdering(collations))

    fun clear() = entries.clear()

    fun add(key: List<Value>, rowIndex: Int) {
        require(key.size == definition.columnNames.size) { "index key arity mismatch for ${definition.name}" }
        if (key.any { it is Value.NullValue }) return
        val wrapped = IndexKey.Exact(key)
        if (definition.unique && entries[wrapped]?.isNotEmpty() == true) {
            throw KestrelException("UNIQUE constraint failed: ${definition.tableName}(${definition.columnDisplayName})")
        }
        entries.getOrPut(wrapped) { mutableListOf() }.add(rowIndex)
    }

    fun equalTo(key: List<Value>): List<Int> = entries[IndexKey.Exact(key)]?.toList() ?: emptyList()

    fun rangeOf(lower: List<Value>?, lowerInclusive: Boolean, upper: List<Value>?, upperInclusive: Boolean): List<Int> {
        var view: NavigableMap<IndexKey, MutableList<Int>> = entries
        if (lower != null) view = view.tailMap(lowerBoundaryKey(lower, lowerInclusive), lowerInclusive)
        if (upper != null) view = view.headMap(upperBoundaryKey(upper, upperInclusive), upperInclusive)
        return view.values.flatten()
    }

    private fun lowerBoundaryKey(values: List<Value>, inclusive: Boolean): IndexKey {
        if (values.size == definition.columnNames.size) return IndexKey.Exact(values)
        return if (inclusive) IndexKey.Low(values) else IndexKey.High(values)
    }

    private fun upperBoundaryKey(values: List<Value>, inclusive: Boolean): IndexKey {
        if (values.size == definition.columnNames.size) return IndexKey.Exact(values)
        return if (inclusive) IndexKey.High(values) else IndexKey.Low(values)
    }
}
