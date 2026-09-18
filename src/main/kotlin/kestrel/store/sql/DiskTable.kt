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

class SecondaryIndexHandle(
    val name: String,
    val columnNames: List<String>,
    val columnIndices: List<Int>,
    val index: TransactionalIndex,
    val unique: Boolean = false
)

class DiskTable internal constructor(
    schema: TableSchema,
    private val heap: TransactionalHeap,
    private var primaryIndex: TransactionalIndex?,
    private val secondaryIndexHandles: MutableList<SecondaryIndexHandle>,
    private val onRootsChanged: (txnId: Long) -> Unit
) {
    var schema: TableSchema = schema
        private set

    val headerPageId: Long get() = heap.headerPageId
    val secondaryIndexes: List<SecondaryIndexHandle> get() = secondaryIndexHandles.toList()

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

    internal fun destroyStorage(txnId: Long) {
        for (handle in secondaryIndexHandles) handle.index.destroy(txnId)
        primaryIndex?.destroy(txnId)
        heap.destroy(txnId)
    }

    internal fun replaceSchemaMetadata(newSchema: TableSchema) {
        schema = newSchema
    }

    internal fun renameColumnAndIndexes(newSchema: TableSchema, oldName: String, newName: String) {
        schema = newSchema
        for (i in secondaryIndexHandles.indices) {
            val handle = secondaryIndexHandles[i]
            if (handle.columnNames.none { it.equals(oldName, ignoreCase = true) }) continue
            val renamedNames = handle.columnNames.map { if (it.equals(oldName, ignoreCase = true)) newName else it }
            secondaryIndexHandles[i] = SecondaryIndexHandle(handle.name, renamedNames, handle.columnIndices, handle.index, handle.unique)
        }
    }

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

    fun findByPrimaryKey(txnId: Long, key: Value): Pair<RecordId, Row>? = findByPrimaryKey(txnId, listOf(key))

    fun findByPrimaryKey(txnId: Long, keys: List<Value>): Pair<RecordId, Row>? {
        pointLookupCount++
        val index = primaryIndex ?: throw DiskTableException("table ${schema.name} has no primary key index")
        val ridBits = index.search(txnId, encodePrimaryKey(keys)) ?: return null
        val rid = RecordId.decode(ridBits)
        return rid to get(txnId, rid)
    }

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

    fun findBySecondary(txnId: Long, indexName: String, key: Value): List<Pair<RecordId, Row>> =
        findBySecondary(txnId, indexName, listOf(key))

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

    internal fun backfillSecondaryIndex(txnId: Long, handle: SecondaryIndexHandle) {
        val seen = if (handle.unique) HashSet<List<Value>>() else null
        for ((rid, row) in scan(txnId)) {
            val values = handle.columnIndices.map { row.values[it] }
            if (values.any { it.isNull }) continue
            if (seen != null && !seen.add(collationFoldedUniquenessKey(schema, handle.columnIndices, values))) {
                throw DiskTableException("UNIQUE constraint failed: ${schema.name}.${handle.columnNames.joinToString(", ")}")
            }
            handle.index.insert(txnId, compositeKey(handle.columnIndices, values, rid), rid.encode())
        }
    }

    fun snapshotDirectoryEntry(): TableDirectoryEntry = TableDirectoryEntry(
        tableName = schema.name,
        columns = schema.columns,
        dataHeaderPageId = heap.headerPageId,
        primaryKeyIndexRootPageId = primaryIndex?.rootPageId,
        secondaryIndexes = secondaryIndexHandles.map { SecondaryIndexEntry(it.name, it.columnNames, it.index.rootPageId, it.unique) },
        compositePrimaryKey = schema.compositePrimaryKey,
        uniqueConstraints = schema.uniqueConstraints,
        checks = schema.checks.map { CheckConstraintEntry.from(it) }
    )

    private fun indexRow(txnId: Long, rid: RecordId, row: Row, pkValues: List<Value>?) {
        primaryIndex?.let { index -> if (pkValues != null && pkValues.none { it.isNull }) index.insert(txnId, encodePrimaryKey(pkValues), rid.encode()) }
        for (handle in secondaryIndexHandles) {
            val values = handle.columnIndices.map { row.values[it] }
            if (values.none { it.isNull }) handle.index.insert(txnId, compositeKey(handle.columnIndices, values, rid), rid.encode())
        }
    }

    private fun unindexRow(txnId: Long, rid: RecordId, row: Row) {
        primaryIndex?.let { index ->
            val pkValues = primaryKeyValuesOf(row)
            if (pkValues != null && pkValues.none { it.isNull }) index.delete(txnId, encodePrimaryKey(pkValues))
        }
        for (handle in secondaryIndexHandles) {
            val values = handle.columnIndices.map { row.values[it] }
            if (values.none { it.isNull }) handle.index.delete(txnId, compositeKey(handle.columnIndices, values, rid))
        }
    }

    private fun rejectDuplicatePrimaryKey(txnId: Long, pkValues: List<Value>?) {
        val index = primaryIndex ?: return
        if (pkValues == null || pkValues.any { it.isNull }) return
        if (index.search(txnId, encodePrimaryKey(pkValues)) != null) {
            throw DiskTableException("UNIQUE constraint failed: ${schema.name}.${schema.primaryKeyColumns.joinToString(", ")}")
        }
    }

    private fun rejectDuplicateUniqueSecondary(txnId: Long, row: Row, excludeRid: RecordId?) {
        for (handle in secondaryIndexHandles) {
            if (!handle.unique) continue
            val values = handle.columnIndices.map { row.values[it] }
            if (values.any { it.isNull }) continue
            val (lower, upper) = equalRangeFor(handle.columnIndices, values)
            val conflict = handle.index.rangeScan(txnId, lower, upper, toInclusive = true).any { (_, ridBits) ->
                RecordId.decode(ridBits) != excludeRid
            }
            if (conflict) throw DiskTableException("UNIQUE constraint failed: ${schema.name}.${handle.columnNames.joinToString(", ")}")
        }
    }

    private fun rejectDuplicateUniqueConstraints(txnId: Long, row: Row, excludeRid: RecordId?) {
        if (schema.uniqueConstraints.isEmpty()) return
        val rows = rawScan(txnId)
        for (unique in schema.uniqueConstraints) {
            val indices = unique.columns.map { schema.columnIndex(it)!! }
            val rawKey = indices.map { row.values[it] }
            if (rawKey.any { it.isNull }) continue
            val key = collationFoldedUniquenessKey(schema, indices, rawKey)
            val conflict = rows.any { (candidateRid, candidateRow) ->
                candidateRid != excludeRid && collationFoldedUniquenessKey(schema, indices, indices.map { candidateRow.values[it] }) == key
            }
            if (conflict) {
                val label = unique.name?.let { " $it" } ?: ""
                throw DiskTableException("UNIQUE constraint$label failed: ${schema.name}(${unique.columns.joinToString(", ")})")
            }
        }
    }

    private fun primaryKeyValuesOf(row: Row): List<Value>? {
        val indices = schema.primaryKeyIndices
        if (indices.isEmpty()) return null
        return indices.map { row.values[it] }
    }

    private fun requireArity(row: Row) {
        if (row.values.size != schema.columns.size) {
            throw DiskTableException("table ${schema.name} expects ${schema.columns.size} values but got ${row.values.size}")
        }
        for ((index, column) in schema.columns.withIndex()) {
            if (column.notNull && row.values[index].isNull) {
                throw DiskTableException("NOT NULL constraint failed: ${schema.name}.${column.name}")
            }
        }
    }

    private fun currentRoots(): List<Long> =
        listOfNotNull(primaryIndex?.rootPageId) + secondaryIndexHandles.map { it.index.rootPageId }

    private fun persistIfRootsChanged(txnId: Long, before: List<Long>) {
        if (before != currentRoots()) onRootsChanged(txnId)
    }

    private fun compositeKey(columnIndices: List<Int>, values: List<Value>, rid: RecordId): ByteArray =
        ByteWriter().writeBytes(KeyCodec.encodeKey(collationFoldedUniquenessKey(schema, columnIndices, values)))
            .writeFixedLong(rid.encode()).toByteArray()

    private fun equalRangeFor(columnIndices: List<Int>, values: List<Value>): Pair<ByteArray, ByteArray> {
        val prefix = KeyCodec.encodeKey(collationFoldedUniquenessKey(schema, columnIndices, values))
        val lower = ByteWriter().writeBytes(prefix).writeFixedLong(0L).toByteArray()
        val upper = ByteWriter().writeBytes(prefix).writeFixedLong(-1L).toByteArray()
        return lower to upper
    }

    private fun encodePrimaryKey(values: List<Value>): ByteArray =
        KeyCodec.encodeKey(collationFoldedUniquenessKey(schema, schema.primaryKeyIndices, values))

    private fun strictlyAfter(bytes: ByteArray): ByteArray = bytes + byteArrayOf(0)
}
