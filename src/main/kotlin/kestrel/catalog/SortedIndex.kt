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

/**
 * A key used to navigate [SortedIndex.entries]. Every key actually stored in the map is
 * [Exact] with exactly `definition.columnNames.size` values. [Low]/[High] only ever appear as
 * *boundary* arguments to `tailMap`/`headMap` when a caller supplies fewer values than the
 * index has columns (an equality prefix over the leading columns, or a range bound on a
 * trailing column while earlier columns are pinned by equality) - they tell [IndexKeyOrdering]
 * to treat the omitted trailing columns as -infinity/+infinity, which brackets exactly the
 * stored keys that share the given prefix without needing a real sentinel [Value] for every
 * column type (there's no universal "biggest TEXT" or "biggest DECIMAL").
 */
private sealed class IndexKey(val values: List<Value>) {
    class Exact(values: List<Value>) : IndexKey(values)
    class Low(values: List<Value>) : IndexKey(values)
    class High(values: List<Value>) : IndexKey(values)
}

/**
 * [collations] carries one [Collation] per indexed column, in the same order as
 * [IndexDefinition.columnNames] - a plain-[Collation.Binary] entry for a column with no declared
 * `COLLATE` (see [kestrel.catalog.Column.collation]), matching this engine's pre-COLLATE ordering
 * exactly. Threading it through this comparator (rather than always comparing bytes/`String`
 * order via the plain [ValueOrdering.compare]) is what makes an equality/range lookup through
 * this index (see [SortedIndex.equalTo]/[SortedIndex.rangeOf], both driven entirely by this
 * comparator) agree with what a full-scan `WHERE` filter through [kestrel.engine.Evaluator]
 * would decide for the same column - without it, `WHERE name = 'BOB'` on a `NOCASE`-declared,
 * indexed `name` column would silently miss a stored `'bob'` row once
 * [kestrel.engine.IndexAccessPlanner] chooses to use the index, a real (not just incomplete)
 * correctness bug rather than a documented scope-cut.
 */
private class IndexKeyOrdering(private val collations: List<Collation>) : Comparator<IndexKey> {
    override fun compare(left: IndexKey, right: IndexKey): Int {
        val common = minOf(left.values.size, right.values.size)
        for (i in 0 until common) {
            val cmp = ValueOrdering.compare(left.values[i], right.values[i], collations[i])
            if (cmp != 0) return cmp
        }
        if (left.values.size == right.values.size) return 0
        // Sizes differ only when comparing a real (Exact, full-length) stored key against a
        // boundary that carries fewer values than the index has columns; the shorter side's
        // kind decides whether the missing tail reads as -infinity (Low) or +infinity (High).
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

    /**
     * NULLs are exempt from the uniqueness check and from lookups entirely, mirroring the
     * standard SQL UNIQUE constraint (many NULLs never collide with each other or with anything
     * else). For a composite key that means ANY component being NULL takes the whole row out of
     * the index, not just the NULL column - a partial composite key is not a meaningful sort key.
     */
    fun add(key: List<Value>, rowIndex: Int) {
        require(key.size == definition.columnNames.size) { "index key arity mismatch for ${definition.name}" }
        if (key.any { it is Value.NullValue }) return
        val wrapped = IndexKey.Exact(key)
        if (definition.unique && entries[wrapped]?.isNotEmpty() == true) {
            throw KestrelException("UNIQUE constraint failed: ${definition.tableName}(${definition.columnDisplayName})")
        }
        entries.getOrPut(wrapped) { mutableListOf() }.add(rowIndex)
    }

    /** Exact lookup; [key] must supply a value for every indexed column. */
    fun equalTo(key: List<Value>): List<Int> = entries[IndexKey.Exact(key)]?.toList() ?: emptyList()

    /**
     * Range/prefix scan. [lower]/[upper] may each supply fewer values than the index has
     * columns - the missing trailing columns are then left unconstrained (a pure equality
     * prefix scan passes the same, full prefix, as both [lower] and [upper] with both bounds
     * inclusive).
     */
    fun rangeOf(lower: List<Value>?, lowerInclusive: Boolean, upper: List<Value>?, upperInclusive: Boolean): List<Int> {
        var view: NavigableMap<IndexKey, MutableList<Int>> = entries
        if (lower != null) view = view.tailMap(lowerBoundaryKey(lower, lowerInclusive), lowerInclusive)
        if (upper != null) view = view.headMap(upperBoundaryKey(upper, upperInclusive), upperInclusive)
        return view.values.flatten()
    }

    /**
     * A shorter-than-full lower boundary must still admit stored keys whose extra trailing
     * columns tie on the given prefix when the bound is inclusive (so it reads as -infinity,
     * [IndexKey.Low]); an exclusive lower bound must reject those same tying keys entirely (so
     * it reads as +infinity, [IndexKey.High]) - otherwise e.g. `age > 50` on a composite
     * (age, name) index would let `age = 50` rows back in through the unconstrained `name` tail.
     */
    private fun lowerBoundaryKey(values: List<Value>, inclusive: Boolean): IndexKey {
        if (values.size == definition.columnNames.size) return IndexKey.Exact(values)
        return if (inclusive) IndexKey.Low(values) else IndexKey.High(values)
    }

    /** Mirror image of [lowerBoundaryKey]: an inclusive upper bound reads as +infinity, an exclusive one as -infinity. */
    private fun upperBoundaryKey(values: List<Value>, inclusive: Boolean): IndexKey {
        if (values.size == definition.columnNames.size) return IndexKey.Exact(values)
        return if (inclusive) IndexKey.High(values) else IndexKey.Low(values)
    }
}
