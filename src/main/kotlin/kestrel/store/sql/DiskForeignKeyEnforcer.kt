package kestrel.store.sql

import kestrel.catalog.ForeignKeyAction
import kestrel.catalog.TableSchema
import kestrel.store.heap.RecordId
import kestrel.storage.DecimalColumns
import kestrel.storage.Row
import kestrel.storage.Value
import kestrel.storage.typeName

internal object DiskForeignKeyEnforcer {

    private val keyPlanner = DiskIndexAccessPlanner()

    fun validateRow(
        catalog: DiskCatalog,
        txn: Long,
        schema: TableSchema,
        values: List<Value>,
        uncommittedSiblings: List<Row> = emptyList()
    ) {
        for (fk in schema.foreignKeys) {
            val columnIndices = fk.columns.map { schema.columnIndex(it)!! }
            val key = columnIndices.map { values[it] }
            if (key.any { it is Value.NullValue }) continue
            val referenced = catalog.tableOrNull(fk.referencedTable)
                ?: throw DiskExecutorException(
                    "FOREIGN KEY constraint failed: table '${fk.referencedTable}' referenced by " +
                        "${schema.name}(${fk.columns.joinToString(", ")}) does not exist"
                )
            val refIndices = fk.referencedColumns.map { referenced.schema.columnIndex(it)!! }
            val siblings = if (fk.referencedTable.equals(schema.name, ignoreCase = true)) uncommittedSiblings else emptyList()
            val parentKey = keyInColumnTypes(referenced.schema, refIndices, key)
            val exists = parentKey != null && (
                rowsWithKeys(referenced, txn, refIndices, setOf(parentKey)).isNotEmpty() ||
                    siblings.any { row -> refIndices.map { idx -> row.values[idx] } == parentKey }
                )
            if (!exists) {
                throw DiskExecutorException(
                    "FOREIGN KEY constraint failed: ${schema.name}(${fk.columns.joinToString(", ")}) has no " +
                        "matching row in ${fk.referencedTable}(${fk.referencedColumns.joinToString(", ")})"
                )
            }
        }
    }

    private sealed interface CascadeStep {
        data class Delete(val table: DiskTable, val rows: List<Pair<RecordId, Row>>, val next: List<CascadeStep>) : CascadeStep
        data class SetToNull(val table: DiskTable, val columnIndices: List<Int>, val rows: List<Pair<RecordId, Row>>) : CascadeStep
        data class SetTo(val table: DiskTable, val columnIndices: List<Int>, val rows: List<Pair<RecordId, Row>>, val values: List<Value>) : CascadeStep
    }

    fun cascadeDelete(catalog: DiskCatalog, txn: Long, parentTableName: String, deletedRows: List<Row>) {
        applySteps(txn, planDelete(catalog, txn, parentTableName, deletedRows))
    }

    fun cascadeUpdate(
        catalog: DiskCatalog,
        txn: Long,
        parentTableName: String,
        referencedColumns: List<String>,
        changedPairs: List<Pair<List<Value>, List<Value>>>
    ) {
        applySteps(txn, planUpdate(catalog, txn, parentTableName, referencedColumns, changedPairs))
    }

    private fun planDelete(catalog: DiskCatalog, txn: Long, parentTableName: String, deletedRows: List<Row>): List<CascadeStep> {
        if (deletedRows.isEmpty()) return emptyList()
        val parent = catalog.table(parentTableName)
        val steps = mutableListOf<CascadeStep>()
        for (childName in catalog.tableNames) {
            val child = catalog.table(childName)
            for (fk in child.schema.foreignKeys) {
                if (!fk.referencedTable.equals(parentTableName, ignoreCase = true)) continue
                val refIndices = fk.referencedColumns.map { parent.schema.columnIndex(it)!! }
                val childColumnIndices = fk.columns.map { child.schema.columnIndex(it)!! }
                val deletedKeys = deletedRows.mapNotNull { row ->
                    keyInColumnTypes(child.schema, childColumnIndices, refIndices.map { row.values[it] })
                }.toHashSet()
                val matches = rowsWithKeys(child, txn, childColumnIndices, deletedKeys)
                if (matches.isEmpty()) continue
                when (fk.onDelete) {
                    ForeignKeyAction.Cascade ->
                        steps.add(CascadeStep.Delete(child, matches, planDelete(catalog, txn, child.schema.name, matches.map { it.second })))
                    ForeignKeyAction.SetNull -> {
                        requireNullable(child.schema, childColumnIndices, "ON DELETE SET NULL")
                        steps.add(CascadeStep.SetToNull(child, childColumnIndices, matches))
                    }
                    ForeignKeyAction.Restrict, ForeignKeyAction.NoAction -> throw DiskExecutorException(
                        "FOREIGN KEY constraint failed: ${matches.size} row(s) in ${child.schema.name} still " +
                            "reference $parentTableName (use ON DELETE CASCADE or remove them first)"
                    )
                }
            }
        }
        return steps
    }

    private fun planUpdate(
        catalog: DiskCatalog,
        txn: Long,
        parentTableName: String,
        referencedColumns: List<String>,
        changedPairs: List<Pair<List<Value>, List<Value>>>
    ): List<CascadeStep> {
        val changes = changedPairs.filter { (old, new) -> old != new }
        if (changes.isEmpty()) return emptyList()
        val steps = mutableListOf<CascadeStep>()
        for (childName in catalog.tableNames) {
            val child = catalog.table(childName)
            for (fk in child.schema.foreignKeys) {
                if (!fk.referencedTable.equals(parentTableName, ignoreCase = true)) continue
                if (fk.referencedColumns.size != referencedColumns.size) continue
                val positions = fk.referencedColumns.map { refCol ->
                    referencedColumns.indexOfFirst { it.equals(refCol, ignoreCase = true) }
                }
                if (positions.any { it == -1 }) continue
                val childColumnIndices = fk.columns.map { child.schema.columnIndex(it)!! }
                for ((oldTuple, newTuple) in changes) {
                    val oldKey = positions.map { oldTuple[it] }
                    val newKey = positions.map { newTuple[it] }
                    if (oldKey == newKey) continue
                    val childKey = keyInColumnTypes(child.schema, childColumnIndices, oldKey) ?: continue
                    val matches = rowsWithKeys(child, txn, childColumnIndices, setOf(childKey))
                    if (matches.isEmpty()) continue
                    when (fk.onUpdate) {
                        ForeignKeyAction.Cascade ->
                            steps.add(CascadeStep.SetTo(child, childColumnIndices, matches, cascadedKey(child.schema, childColumnIndices, newKey)))
                        ForeignKeyAction.SetNull -> {
                            requireNullable(child.schema, childColumnIndices, "ON UPDATE SET NULL")
                            steps.add(CascadeStep.SetToNull(child, childColumnIndices, matches))
                        }
                        ForeignKeyAction.Restrict, ForeignKeyAction.NoAction -> throw DiskExecutorException(
                            "FOREIGN KEY constraint failed: ${matches.size} row(s) in ${child.schema.name} still " +
                                "reference $parentTableName (use ON UPDATE CASCADE)"
                        )
                    }
                }
            }
        }
        return steps
    }

    private fun applySteps(txn: Long, steps: List<CascadeStep>) {
        for (step in steps) {
            when (step) {
                is CascadeStep.Delete -> {
                    for ((rid, _) in step.rows) step.table.delete(txn, rid)
                    applySteps(txn, step.next)
                }
                is CascadeStep.SetToNull -> {
                    val nulls = step.columnIndices.map { Value.NullValue }
                    for ((rid, row) in step.rows) step.table.update(txn, rid, withColumns(row, step.columnIndices, nulls))
                }
                is CascadeStep.SetTo -> {
                    for ((rid, row) in step.rows) step.table.update(txn, rid, withColumns(row, step.columnIndices, step.values))
                }
            }
        }
    }

    private fun keyInColumnTypes(schema: TableSchema, columnIndices: List<Int>, key: List<Value>): List<Value>? =
        columnIndices.mapIndexed { position, columnIndex ->
            keyPlanner.equalityKey(schema.columns[columnIndex].type, key[position]) ?: return null
        }

    private fun rowsWithKeys(table: DiskTable, txn: Long, columnIndices: List<Int>, keys: Set<List<Value>>): List<Pair<RecordId, Row>> {
        if (keys.isEmpty()) return emptyList()
        val keyColumns = columnIndices.toSet()
        val primaryKeyIndices = table.schema.primaryKeyIndices
        val secondary = table.secondaryIndexes.firstOrNull { it.columnIndices.toSet() == keyColumns }
        val candidates = when {
            primaryKeyIndices.toSet() == keyColumns -> keys.mapNotNull { key ->
                val keyByColumn = columnIndices.zip(key).toMap()
                table.findByPrimaryKey(txn, primaryKeyIndices.map { keyByColumn.getValue(it) })
            }
            secondary != null -> keys.flatMap { key ->
                val keyByColumn = columnIndices.zip(key).toMap()
                table.findBySecondary(txn, secondary.name, secondary.columnIndices.map { keyByColumn.getValue(it) })
            }
            else -> table.scan(txn)
        }
        return candidates.filter { (_, row) -> columnIndices.map { row.values[it] } in keys }
    }

    private fun cascadedKey(schema: TableSchema, columnIndices: List<Int>, parentKey: List<Value>): List<Value> =
        columnIndices.mapIndexed { position, columnIndex ->
            val parentValue = parentKey[position]
            if (parentValue is Value.NullValue) return@mapIndexed parentValue
            val column = schema.columns[columnIndex]
            val key = keyPlanner.equalityKey(column.type, parentValue)
            val stored = if (key is Value.DecimalValue) {
                Value.DecimalValue(DecimalColumns.normalize(key.value, column.precision, column.scale, "column ${column.name}"))
            } else {
                key
            }
            if (stored == null || stored != key) {
                throw DiskExecutorException(
                    "FOREIGN KEY constraint failed: cannot cascade ${parentValue.typeName} key into " +
                        "${schema.name}.${column.name} of type ${column.type.displayName} without changing its value"
                )
            }
            stored
        }

    private fun requireNullable(schema: TableSchema, columnIndices: List<Int>, action: String) {
        for (columnIndex in columnIndices) {
            val column = schema.columns[columnIndex]
            if (column.notNull) {
                throw DiskExecutorException(
                    "FOREIGN KEY constraint failed: cannot $action on NOT NULL column ${schema.name}.${column.name}"
                )
            }
        }
    }

    private fun withColumns(row: Row, columnIndices: List<Int>, values: List<Value>): Row {
        val result = row.values.toMutableList()
        columnIndices.forEachIndexed { position, columnIndex -> result[columnIndex] = values[position] }
        return Row(result)
    }
}
