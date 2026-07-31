package kestrel.store.sql

import kestrel.KestrelException
import kestrel.catalog.TableSchema
import kestrel.engine.collationFoldedUniquenessKey
import kestrel.store.codec.ByteWriter
import kestrel.store.codec.KeyCodec
import kestrel.store.codec.TupleCodec
import kestrel.store.engine.TransactionalHeap
import kestrel.store.engine.TransactionalIndex
import kestrel.store.heap.RecordId
import kestrel.storage.Row
import kestrel.storage.Value

class DiskTableException(message: String) : KestrelException(message)

/**
 * A live secondary index attached to a [DiskTable]: the B+Tree plus which column(s) it is keyed
 * on - [columnNames]/[columnIndices] have more than one entry for a composite (multi-column)
 * index, in the order the index was declared. [unique] mirrors a `CREATE UNIQUE INDEX` - see
 * [DiskTable.rejectDuplicateUniqueSecondary].
 */
class SecondaryIndexHandle(
    val name: String,
    val columnNames: List<String>,
    val columnIndices: List<Int>,
    val index: TransactionalIndex,
    val unique: Boolean = false
)

/**
 * The disk-resident counterpart of [kestrel.storage.Table]: a schema-aware
 * row store backed by a [TransactionalHeap] for the row bodies, an optional
 * unique [TransactionalIndex] on the primary key, and any number of
 * non-unique secondary [TransactionalIndex]es - all driven by the same
 * [kestrel.storage.Value]/[TableSchema] types the in-memory SQL engine uses,
 * so this is the storage backend a future Catalog/Executor integration would
 * sit on top of, not a parallel data model needing translation.
 *
 * [kestrel.store.btree.BPlusTree] only ever stores one value per key, so a
 * non-unique secondary index cannot key its B+Tree entries by the indexed
 * value(s) alone: two rows with the same value(s) would silently overwrite
 * each other. Every secondary index instead keys on `encodeKey(values) ++ rid`
 * (one or more columns, via [kestrel.store.codec.KeyCodec.encodeKey] - the
 * same order-preserving multi-value encoding a composite PRIMARY KEY uses), a
 * composite that is unique per row (see [compositeKey]); an equality lookup
 * becomes a range scan bounded by that encoding's smallest and largest possible
 * suffix (see [equalRangeFor]). This is the standard way an index built on a
 * unique-key structure supports duplicates (e.g. how InnoDB appends the
 * primary key to secondary index entries).
 *
 * Every mutating operation may change a primary or secondary index's root
 * page (a B+Tree split or merge) purely in the caller process's memory - see
 * [kestrel.store.engine.TransactionalIndex]'s own documentation. [onRootsChanged]
 * is how a [DiskTable] tells its owning [DiskCatalog] to persist that new
 * root into the directory row for this table, in the same transaction as the
 * data write, so a fresh process reopening the catalog later sees the
 * correct, committed root rather than the one recorded at table-creation time.
 */
class DiskTable internal constructor(
    schema: TableSchema,
    private val heap: TransactionalHeap,
    private var primaryIndex: TransactionalIndex?,
    private val secondaryIndexHandles: MutableList<SecondaryIndexHandle>,
    private val onRootsChanged: (txnId: Long) -> Unit
) {
    /**
     * Mutable so `ALTER TABLE` (see [replaceSchemaMetadata]/[renameColumnAndIndexes]/[rewriteRows])
     * can swap in a new [TableSchema] after CREATE TABLE - private setter because every mutation
     * needs to happen alongside bookkeeping (secondary index column realignment, directory
     * persistence) that only this class and [DiskCatalog] (its sole caller for these methods,
     * same module) know how to do correctly.
     */
    var schema: TableSchema = schema
        private set

    val headerPageId: Long get() = heap.headerPageId
    val secondaryIndexes: List<SecondaryIndexHandle> get() = secondaryIndexHandles.toList()

    /**
     * Access counters, incremented by [scan]/[findByPrimaryKey]/[rangeByPrimaryKey]/
     * [findBySecondary]. Exists so a caller (chiefly [DiskIndexAccessPlanner]-driven
     * code and its tests) can confirm an index lookup actually happened instead of
     * a full heap scan - correctness alone cannot distinguish the two, since both
     * return the same rows.
     */
    var scanCount: Int = 0; private set
    var pointLookupCount: Int = 0; private set
    var rangeScanCount: Int = 0; private set
    var secondaryLookupCount: Int = 0; private set

    internal fun attachSecondaryIndex(handle: SecondaryIndexHandle) {
        secondaryIndexHandles.add(handle)
    }

    internal fun dropSecondaryIndexByName(name: String, txnId: Long) {
        val handle = secondaryIndexHandles.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
        secondaryIndexHandles.remove(handle)
        handle.index.destroy(txnId)
    }

    /**
     * Frees this table's own storage - its heap and every attached index (primary and secondary) -
     * back to the shared page free list. Called by `DROP TABLE` once the directory row itself is
     * already gone, since nothing will ever reopen this [DiskTable] again.
     */
    internal fun destroyStorage(txnId: Long) {
        for (handle in secondaryIndexHandles) handle.index.destroy(txnId)
        primaryIndex?.destroy(txnId)
        heap.destroy(txnId)
    }

    /**
     * Metadata-only schema swap used by `ALTER TABLE ... RENAME TO` (this table's own name/
     * self-referencing FK) and, on an unrelated table, by `RENAME TO`/`RENAME COLUMN`/`DROP COLUMN`
     * rewriting a FOREIGN KEY that points at the table being altered - none of these change this
     * table's own column count or positions, so no row rewrite or index realignment is needed.
     */
    internal fun replaceSchemaMetadata(newSchema: TableSchema) {
        schema = newSchema
    }

    /**
     * `ALTER TABLE ... RENAME COLUMN`: swaps in [newSchema] (column list already renamed by the
     * caller) and renames [oldName] to [newName] wherever it appears in a secondary index's
     * [SecondaryIndexHandle.columnNames] - the index's [SecondaryIndexHandle.columnIndices] stay
     * exactly the same, since a pure rename never moves a column's position.
     */
    internal fun renameColumnAndIndexes(newSchema: TableSchema, oldName: String, newName: String) {
        schema = newSchema
        for (i in secondaryIndexHandles.indices) {
            val handle = secondaryIndexHandles[i]
            if (handle.columnNames.none { it.equals(oldName, ignoreCase = true) }) continue
            val renamedNames = handle.columnNames.map { if (it.equals(oldName, ignoreCase = true)) newName else it }
            secondaryIndexHandles[i] = SecondaryIndexHandle(handle.name, renamedNames, handle.columnIndices, handle.index, handle.unique)
        }
    }

    /**
     * `ALTER TABLE ... ADD/DROP COLUMN`: rewrites every row's stored bytes through [transform] and
     * swaps in [newSchema] (already reflecting the added/removed column). Every surviving secondary
     * index's [SecondaryIndexHandle.columnIndices] is recomputed fresh from [newSchema] right after
     * the swap (mirrors [kestrel.catalog.Catalog.reindexOne] always recomputing positions from
     * names rather than trusting a cached one) - required for DROP COLUMN, where every column after
     * the dropped one shifts down by one position; a no-op for ADD COLUMN, which only ever appends
     * at the end. Reuses the ordinary [update] for each row (not a hand-rolled unindex/reindex pair)
     * so a row relocated to a new [RecordId] by a growing/shrinking body - see
     * [kestrel.store.heap.HeapFile.update] - keeps every index entry correctly pointed at it,
     * exactly like a normal SQL `UPDATE` already guarantees. The caller is responsible for having
     * already dropped (via [dropSecondaryIndexByName]) any index that depends on a column being
     * removed - a survivor's columns are always still present in [newSchema] by the time this runs.
     */
    internal fun rewriteRows(txnId: Long, newSchema: TableSchema, transform: (Row) -> Row) {
        val existing = rawScan(txnId)
        schema = newSchema
        for (i in secondaryIndexHandles.indices) {
            val handle = secondaryIndexHandles[i]
            val realignedIndices = handle.columnNames.map {
                newSchema.columnIndex(it) ?: throw DiskTableException("index ${handle.name} references unknown column $it")
            }
            secondaryIndexHandles[i] = SecondaryIndexHandle(handle.name, handle.columnNames, realignedIndices, handle.index, handle.unique)
        }
        for ((rid, row) in existing) update(txnId, rid, transform(row))
    }

    fun insert(txnId: Long, row: Row): RecordId {
        requireArity(row)
        val pkValues = primaryKeyValuesOf(row)
        rejectDuplicatePrimaryKey(txnId, pkValues)
        rejectDuplicateUniqueSecondary(txnId, row, excludeRid = null)
        rejectDuplicateUniqueConstraints(txnId, row, excludeRid = null)
        val roots = currentRoots()
        val rid = heap.insert(txnId, TupleCodec.encode(row.values))
        indexRow(txnId, rid, row, pkValues)
        persistIfRootsChanged(txnId, roots)
        return rid
    }

    fun get(txnId: Long, rid: RecordId): Row = Row(TupleCodec.decode(heap.get(txnId, rid)))

    fun scan(txnId: Long): List<Pair<RecordId, Row>> {
        scanCount++
        return rawScan(txnId)
    }

    private fun rawScan(txnId: Long): List<Pair<RecordId, Row>> =
        heap.scan(txnId).map { (rid, bytes) -> rid to Row(TupleCodec.decode(bytes)) }

    fun delete(txnId: Long, rid: RecordId) {
        val row = get(txnId, rid)
        val roots = currentRoots()
        unindexRow(txnId, rid, row)
        heap.delete(txnId, rid)
        persistIfRootsChanged(txnId, roots)
    }

    fun update(txnId: Long, rid: RecordId, newRow: Row): RecordId {
        requireArity(newRow)
        val oldRow = get(txnId, rid)
        val newPk = primaryKeyValuesOf(newRow)
        val oldPk = primaryKeyValuesOf(oldRow)
        if (newPk != oldPk) rejectDuplicatePrimaryKey(txnId, newPk)
        rejectDuplicateUniqueSecondary(txnId, newRow, excludeRid = rid)
        rejectDuplicateUniqueConstraints(txnId, newRow, excludeRid = rid)
        val roots = currentRoots()
        unindexRow(txnId, rid, oldRow)
        val newRid = heap.update(txnId, rid, TupleCodec.encode(newRow.values))
        indexRow(txnId, newRid, newRow, newPk)
        persistIfRootsChanged(txnId, roots)
        return newRid
    }

    /** Convenience for a single-column PRIMARY KEY; delegates to the general, composite-capable overload below. */
    fun findByPrimaryKey(txnId: Long, key: Value): Pair<RecordId, Row>? = findByPrimaryKey(txnId, listOf(key))

    /**
     * Point lookup by the table's PRIMARY KEY, whether inline-single-column or table-level
     * composite - [keys] must be given in [TableSchema.primaryKeyColumns] order. [KeyCodec.encodeKey]
     * concatenates each value's own order-preserving encoding, so a single-element list here
     * produces byte-identical output to [KeyCodec.encode] on that same value, keeping this the
     * one lookup path both PRIMARY KEY shapes share. [keys] is folded through
     * [encodePrimaryKey] first, so a search literal on a `COLLATE NOCASE`/`RTRIM`-declared PRIMARY
     * KEY column finds a case-/trailing-space-variant row the same way a plain `=` comparison does.
     */
    fun findByPrimaryKey(txnId: Long, keys: List<Value>): Pair<RecordId, Row>? {
        pointLookupCount++
        val index = primaryIndex ?: throw DiskTableException("table ${schema.name} has no primary key index")
        val ridBits = index.search(txnId, encodePrimaryKey(keys)) ?: return null
        val rid = RecordId.decode(ridBits)
        return rid to get(txnId, rid)
    }

    /**
     * [DiskIndexAccessPlanner] plans a range against either a single-column PRIMARY KEY ([prefix]
     * empty, see [DiskAccessPlan.PrimaryKeyRange]'s own doc) or the LAST column of a table-level
     * composite one, after an equality prefix on every column before it ([prefix] non-empty, see
     * [DiskAccessPlan.CompositePrimaryKeyRange]'s own doc) - either way, [prefix] plus [lower]/
     * [upper] always names every PRIMARY KEY column, so [encodePrimaryKey]'s own per-position
     * collation folding ([kestrel.engine.collationFoldedUniquenessKey]) applies directly and always
     * produces a COMPLETE, full-arity key: unlike [rangeBySecondary], no RID-suffix bracketing
     * trick is needed here, since a PRIMARY KEY entry is a bare `encodeKey(values)` with nothing
     * after it to disambiguate.
     */
    fun rangeByPrimaryKey(
        txnId: Long,
        lower: Value? = null,
        lowerInclusive: Boolean = true,
        upper: Value? = null,
        upperInclusive: Boolean = true,
        prefix: List<Value> = emptyList()
    ): List<Pair<RecordId, Row>> {
        rangeScanCount++
        val index = primaryIndex ?: throw DiskTableException("table ${schema.name} has no primary key index")
        fun encodeBound(value: Value): ByteArray =
            KeyCodec.encodeKey(collationFoldedUniquenessKey(schema, schema.primaryKeyIndices, prefix + value))
        val lowerBytes = lower?.let(::encodeBound)?.let { if (lowerInclusive) it else strictlyAfter(it) }
        val upperBytes = upper?.let(::encodeBound)
        return index.rangeScan(txnId, lowerBytes, upperBytes, toInclusive = upperInclusive).map { (_, ridBits) ->
            val rid = RecordId.decode(ridBits)
            rid to get(txnId, rid)
        }
    }

    /** Convenience for a single-column secondary index; delegates to the general, composite-capable overload below. */
    fun findBySecondary(txnId: Long, indexName: String, key: Value): List<Pair<RecordId, Row>> =
        findBySecondary(txnId, indexName, listOf(key))

    /**
     * Equality lookup on a secondary index, whether single-column or composite - [keys] must be
     * given in [SecondaryIndexHandle.columnNames] order. A single-element list here produces a
     * byte-identical prefix to the single-[Value] overload, keeping this the one lookup path both
     * shapes share (mirrors [findByPrimaryKey]). [keys] is folded per-column through
     * [equalRangeFor], so equality against a declared-collation column finds a case-/trailing-
     * space-variant row instead of missing it.
     */
    fun findBySecondary(txnId: Long, indexName: String, keys: List<Value>): List<Pair<RecordId, Row>> {
        secondaryLookupCount++
        val handle = secondaryIndexHandles.firstOrNull { it.name.equals(indexName, ignoreCase = true) }
            ?: throw DiskTableException("no such index: $indexName on table ${schema.name}")
        val (lower, upper) = equalRangeFor(handle.columnIndices, keys)
        return handle.index.rangeScan(txnId, lower, upper, toInclusive = true).map { (_, ridBits) ->
            val rid = RecordId.decode(ridBits)
            rid to get(txnId, rid)
        }
    }

    /**
     * [DiskIndexAccessPlanner] plans a range against a secondary index's column right after
     * [prefix]'s equality run (see [kestrel.store.sql.DiskAccessPlan.SecondaryRange]'s own doc) -
     * [prefix] is empty for a single-column index or a composite one ranged on its very first
     * column. Unlike [rangeByPrimaryKey] (whose B+Tree entries are bare `encodeKey(values)`, no RID
     * suffix, since a PRIMARY KEY is unique), a secondary index entry is `encodeKey(values) ++ rid`
     * (see the class doc), so a bound naming just [lower]/[upper]'s own encoded bytes would sit
     * strictly *before* every real entry sharing that value (any real key, being longer, sorts
     * after its own prefix) - reusing [equalRangeFor]'s (all-zero-suffix, all-one-suffix) pair for
     * `prefix + value` sidesteps this: the all-zero-suffix bound is a valid inclusive lower
     * bound for "this value or greater" (it sits at or before every RID-suffixed entry of that
     * exact value), and the all-one-suffix bound is a valid inclusive upper bound for "this value
     * or less" (it sits at or after every RID-suffixed entry of that exact value) - swapped for the
     * opposite (exclusive) side of each bound, so e.g. `> X` starts scanning right after the last
     * possible entry of exactly `X`. When [prefix] is non-empty and one side has no explicit bound
     * (e.g. `WHERE a = 5 AND b < 10`, no lower bound on `b`), that side falls back to
     * [equalRangeFor]'s own bound for [prefix] alone, so the scan still stays within the `a = 5`
     * group instead of running off into rows from a smaller/larger `a` - [prefix]'s own encoding is
     * a byte-string prefix of every real entry sharing it (each value's encoding is self-delimited),
     * so this bound is exact regardless of how many values [prefix] holds relative to the index's
     * full column count.
     */
    fun rangeBySecondary(
        txnId: Long,
        indexName: String,
        prefix: List<Value> = emptyList(),
        lower: Value? = null,
        lowerInclusive: Boolean = true,
        upper: Value? = null,
        upperInclusive: Boolean = true
    ): List<Pair<RecordId, Row>> {
        rangeScanCount++
        val handle = secondaryIndexHandles.firstOrNull { it.name.equals(indexName, ignoreCase = true) }
            ?: throw DiskTableException("no such index: $indexName on table ${schema.name}")
        val prefixBounds = if (prefix.isNotEmpty()) equalRangeFor(handle.columnIndices, prefix) else null
        val lowerBytes = if (lower != null) {
            val (inclusiveBound, exclusiveBound) = equalRangeFor(handle.columnIndices, prefix + lower)
            if (lowerInclusive) inclusiveBound else exclusiveBound
        } else {
            prefixBounds?.first
        }
        val upperBytes = if (upper != null) {
            val (exclusiveBound, inclusiveBound) = equalRangeFor(handle.columnIndices, prefix + upper)
            if (upperInclusive) inclusiveBound else exclusiveBound
        } else {
            prefixBounds?.second
        }
        return handle.index.rangeScan(txnId, lowerBytes, upperBytes, toInclusive = true).map { (_, ridBits) ->
            val rid = RecordId.decode(ridBits)
            rid to get(txnId, rid)
        }
    }

