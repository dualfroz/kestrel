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

