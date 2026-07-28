package kestrel.store.sql

import kestrel.catalog.ForeignKeyAction
import kestrel.catalog.TableSchema
import kestrel.store.heap.RecordId
import kestrel.storage.Row
import kestrel.storage.Value

/**
 * Disk-backed counterpart of [kestrel.engine.ForeignKeyEnforcer]: existence checks on INSERT/UPDATE
 * of the referencing (child) table, and ON DELETE/ON UPDATE actions on the referenced (parent)
 * table, operating on [DiskCatalog]/[DiskTable] within an active disk transaction instead of the
 * in-memory engine's `Table.rows` ArrayList. Deliberately duplicated rather than shared - same
 * reasoning as every other validation [DiskExecutor] duplicates (see
 * [DiskExecutor.validateUniqueDefinitions]).
 *
 * [validateRow]'s existence check is a full [DiskTable.scan] of the referenced table, exactly
 * mirroring the in-memory engine's own full scan of `Table.rows` - a documented performance
 * compromise (not correctness), same category as CTE materialization or the disk executor's UNIQUE
 * constraint scan: a real index-driven point lookup here would need to first prove the referenced
 * columns line up with the PRIMARY KEY or a secondary index before choosing a plan, which is scope
 * this pass does not take on.
 */
internal object DiskForeignKeyEnforcer {

    /**
     * [uncommittedSiblings] covers a self-referencing FK (`referencedTable == schema.name`, e.g. an
     * employee/manager hierarchy) inside a multi-row INSERT/UPDATE statement: those sibling rows are
     * being validated as part of the very same statement and have not been written to [txn] yet, so
     * without this a later row could not reference an earlier row from the same batch (or vice
     * versa - order within the batch must not matter).
     *
     * Follows MATCH SIMPLE (the SQL default): if *any* column of the FK is NULL in this row, the
     * whole constraint is skipped for that row - not just the null column.
     */
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
            val exists = referenced.scan(txn).any { (_, row) -> refIndices.map { idx -> row.values[idx] } == key } ||
                siblings.any { row -> refIndices.map { idx -> row.values[idx] } == key }
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

    /**
     * [referencedColumns] is the full column group whose values changed together (a PRIMARY KEY or
     * UNIQUE key group) and [changedPairs] holds the old/new key tuple for every row of the parent
     * table, positionally aligned with [referencedColumns] - a matching FK may declare its own
     * `referencedColumns` in a different order, so [planUpdate] re-projects each tuple by name.
     */
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
                val deletedKeys = deletedRows.map { row -> refIndices.map { row.values[it] } }.toHashSet()
                val childColumnIndices = fk.columns.map { child.schema.columnIndex(it)!! }
                val matches = child.scan(txn).filter { (_, row) -> childColumnIndices.map { row.values[it] } in deletedKeys }
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
                // Re-project the tuple by name: this FK's referencedColumns may list the same key
                // group in a different order than the one changedPairs was built from.
                val positions = fk.referencedColumns.map { refCol ->
                    referencedColumns.indexOfFirst { it.equals(refCol, ignoreCase = true) }
                }
                if (positions.any { it == -1 }) continue
                val childColumnIndices = fk.columns.map { child.schema.columnIndex(it)!! }
                for ((oldTuple, newTuple) in changes) {
                    val oldKey = positions.map { oldTuple[it] }
                    val newKey = positions.map { newTuple[it] }
                    if (oldKey == newKey) continue
                    val matches = child.scan(txn).filter { (_, row) -> childColumnIndices.map { row.values[it] } == oldKey }
                    if (matches.isEmpty()) continue
                    when (fk.onUpdate) {
                        ForeignKeyAction.Cascade ->
                            steps.add(CascadeStep.SetTo(child, childColumnIndices, matches, newKey))
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
