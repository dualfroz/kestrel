package kestrel.catalog

import java.util.NavigableMap
import java.util.TreeMap
import kestrel.storage.Value
import kestrel.storage.ValueOrdering

data class IndexDefinition(val name: String, val tableName: String, val columnName: String)

class SortedIndex(val definition: IndexDefinition) {
    private val entries: TreeMap<Value, MutableList<Int>> = TreeMap(ValueOrdering)

    fun clear() = entries.clear()

    fun add(key: Value, rowIndex: Int) {
        if (key is Value.NullValue) return
        entries.getOrPut(key) { mutableListOf() }.add(rowIndex)
    }

    fun equalTo(key: Value): List<Int> = entries[key]?.toList() ?: emptyList()

    fun rangeOf(lower: Value?, lowerInclusive: Boolean, upper: Value?, upperInclusive: Boolean): List<Int> {
        var view: NavigableMap<Value, MutableList<Int>> = entries
        if (lower != null) view = view.tailMap(lower, lowerInclusive)
        if (upper != null) view = view.headMap(upper, upperInclusive)
        return view.values.flatten()
    }
}
