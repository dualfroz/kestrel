package kestrel.engine

import kestrel.KestrelException
import kestrel.catalog.Catalog
import kestrel.catalog.ForeignKeyAction
import kestrel.catalog.TableSchema
import kestrel.storage.Row
import kestrel.storage.Table
import kestrel.storage.Value

/**
 * Runtime enforcement for FOREIGN KEY constraints: existence checks on INSERT/UPDATE of the
 * referencing (child) table, and ON DELETE/ON UPDATE actions on the referenced (parent) table.
 * `referencedColumns` is always a column group with an enforced uniqueness guarantee in the
 * parent (its PRIMARY KEY, single-column or composite, or a UNIQUE constraint), so cascades track
 * a whole key tuple (`List<Value>`, one entry per column, positionally paired with `columns`) at
 * a time rather than a single value - this is what lets a single-column FK and a multi-column FK
 * share every code path below unchanged.
 *
 * Cascades are computed as a plan first and only mutate afterwards (see [CascadeStep]) so that a
 * RESTRICT violation discovered in one child table can never leave an earlier, unrelated child
 * table half-cascaded - either the whole cascade applies, or nothing does.
 */
object ForeignKeyEnforcer {

    /**
     * [uncommittedSiblings] covers a self-referencing FK (`referencedTable == schema.name`, e.g. an
     * employee/manager hierarchy) inside a multi-row INSERT/UPDATE statement: those sibling rows
     * are being validated as part of the very same statement and aren't in the catalog's table yet,
     * so without this a later row couldn't reference an earlier row from the same batch.
     *
     * Follows MATCH SIMPLE (the SQL default): if *any* column of the FK is NULL in this row, the
     * whole constraint is skipped for that row - not just the null column.
     */
    fun validateRow(
        catalog: Catalog,
        schema: TableSchema,
        values: List<Value>,
        uncommittedSiblings: List<Row> = emptyList()
    ) {
        for (fk in schema.foreignKeys) {
            val columnIndices = fk.columns.map { schema.columnIndex(it)!! }
            val key = columnIndices.map { values[it] }
            if (key.any { it is Value.NullValue }) continue
            val referenced = catalog.tableOrNull(fk.referencedTable)
                ?: throw KestrelException(
                    "FOREIGN KEY constraint failed: table '${fk.referencedTable}' referenced by " +
                        "${schema.name}(${fk.columns.joinToString(", ")}) does not exist"
                )
            val refIndices = fk.referencedColumns.map { referenced.schema.columnIndex(it)!! }
            val siblings = if (fk.referencedTable.equals(schema.name, ignoreCase = true)) uncommittedSiblings else emptyList()
            val exists = referenced.rows.any { refIndices.map { idx -> it.values[idx] } == key } ||
                siblings.any { refIndices.map { idx -> it.values[idx] } == key }
            if (!exists) {
                throw KestrelException(
                    "FOREIGN KEY constraint failed: ${schema.name}(${fk.columns.joinToString(", ")}) has no " +
                        "matching row in ${fk.referencedTable}(${fk.referencedColumns.joinToString(", ")})"
                )
            }
        }
    }

    private sealed interface CascadeStep {
        data class Delete(val table: Table, val rows: List<Row>, val next: List<CascadeStep>) : CascadeStep
        data class SetToNull(val table: Table, val columnIndices: List<Int>, val rows: List<Row>) : CascadeStep
        data class SetTo(val table: Table, val columnIndices: List<Int>, val rows: List<Row>, val values: List<Value>) : CascadeStep
    }

    fun cascadeDelete(catalog: Catalog, parentTableName: String, deletedRows: List<Row>) {
        applySteps(catalog, planDelete(catalog, parentTableName, deletedRows))
    }

    /**
     * [referencedColumns] is the full column group whose values changed together (a PRIMARY KEY or
     * UNIQUE key group) and [changedPairs] holds the old/new key tuple for every row of the parent
     * table, positionally aligned with [referencedColumns] - a matching FK may declare its own
     * `referencedColumns` in a different order, so [planUpdate] re-projects each tuple by name.
     */
    fun cascadeUpdate(
        catalog: Catalog,
        parentTableName: String,
        referencedColumns: List<String>,
        changedPairs: List<Pair<List<Value>, List<Value>>>
    ) {
        applySteps(catalog, planUpdate(catalog, parentTableName, referencedColumns, changedPairs))
    }

    private fun planDelete(catalog: Catalog, parentTableName: String, deletedRows: List<Row>): List<CascadeStep> {
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
                val matches = child.rows.filter { row -> childColumnIndices.map { row.values[it] } in deletedKeys }
                if (matches.isEmpty()) continue
                when (fk.onDelete) {
                    ForeignKeyAction.Cascade ->
                        steps.add(CascadeStep.Delete(child, matches, planDelete(catalog, child.schema.name, matches)))
                    ForeignKeyAction.SetNull -> {
                        requireNullable(child.schema, childColumnIndices, "ON DELETE SET NULL")
                        steps.add(CascadeStep.SetToNull(child, childColumnIndices, matches))
                    }
                    ForeignKeyAction.Restrict, ForeignKeyAction.NoAction -> throw KestrelException(
                        "FOREIGN KEY constraint failed: ${matches.size} row(s) in ${child.schema.name} still " +
                            "reference $parentTableName (use ON DELETE CASCADE or remove them first)"
                    )
                }
            }
        }
        return steps
    }

    private fun planUpdate(
        catalog: Catalog,
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
                    val matches = child.rows.filter { row -> childColumnIndices.map { row.values[it] } == oldKey }
                    if (matches.isEmpty()) continue
                    when (fk.onUpdate) {
                        ForeignKeyAction.Cascade ->
                            steps.add(CascadeStep.SetTo(child, childColumnIndices, matches, newKey))
                        ForeignKeyAction.SetNull -> {
                            requireNullable(child.schema, childColumnIndices, "ON UPDATE SET NULL")
                            steps.add(CascadeStep.SetToNull(child, childColumnIndices, matches))
                        }
                        ForeignKeyAction.Restrict, ForeignKeyAction.NoAction -> throw KestrelException(
                            "FOREIGN KEY constraint failed: ${matches.size} row(s) in ${child.schema.name} still " +
                                "reference $parentTableName (use ON UPDATE CASCADE)"
                        )
                    }
                }
            }
        }
        return steps
    }

    private fun applySteps(catalog: Catalog, steps: List<CascadeStep>) {
        for (step in steps) {
            when (step) {
                is CascadeStep.Delete -> {
                    step.table.rows.removeAll(step.rows)
                    catalog.reindexTable(step.table.schema.name)
                    applySteps(catalog, step.next)
                }
                is CascadeStep.SetToNull -> {
                    val nulls = step.columnIndices.map { Value.NullValue }
                    replaceRows(step.table, step.rows) { row -> withColumns(row, step.columnIndices, nulls) }
                    catalog.reindexTable(step.table.schema.name)
                }
                is CascadeStep.SetTo -> {
                    replaceRows(step.table, step.rows) { row -> withColumns(row, step.columnIndices, step.values) }
                    catalog.reindexTable(step.table.schema.name)
                }
            }
        }
    }

    private fun requireNullable(schema: TableSchema, columnIndices: List<Int>, action: String) {
        for (columnIndex in columnIndices) {
            val column = schema.columns[columnIndex]
            if (column.notNull) {
                throw KestrelException(
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

    private fun replaceRows(table: Table, targets: List<Row>, transform: (Row) -> Row) {
        val targetSet = targets.toHashSet()
        val updated = table.rows.map { row -> if (row in targetSet) transform(row) else row }
        table.rows.clear()
        table.rows.addAll(updated)
    }
}
