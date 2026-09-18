package kestrel.catalog

import kestrel.KestrelException
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.QueryStatement
import kestrel.sql.ast.TriggerEvent
import kestrel.sql.ast.TriggerTiming
import kestrel.storage.Collation
import kestrel.storage.Row
import kestrel.storage.Table
import kestrel.storage.Value

class CatalogSnapshot(
    val tables: Map<String, Pair<TableSchema, List<Row>>>,
    val indexDefinitions: List<IndexDefinition>,
    val views: Map<String, ViewDefinition>,
    val triggers: Map<String, TriggerDefinition>,
    val sequences: Map<String, Sequence>
)

class Catalog {
    private val tables = linkedMapOf<String, Table>()
    private val indexes = linkedMapOf<String, SortedIndex>()
    private val views = linkedMapOf<String, ViewDefinition>()
    private val triggers = linkedMapOf<String, TriggerDefinition>()
    private val sequences = linkedMapOf<String, Sequence>()

    fun createTable(schema: TableSchema) {
        val key = schema.name.lowercase()
        if (tables.containsKey(key)) {
            throw KestrelException("table '${schema.name}' already exists")
        }
        if (views.containsKey(key)) {
            throw KestrelException("a view named '${schema.name}' already exists")
        }
        tables[key] = Table(schema)
    }

    fun dropTable(tableName: String, cascade: Boolean = false) {
        val key = tableName.lowercase()
        if (!tables.containsKey(key)) {
            if (views.containsKey(key)) {
                throw KestrelException("'$tableName' is a view, use DROP VIEW")
            }
            throw KestrelException("no such table: $tableName")
        }
        dropDependentViews(tableName, cascade)
        dropDependentForeignKeys(tableName, cascade)
        tables.remove(key)
        indexes.entries.removeAll { it.value.definition.tableName.equals(tableName, ignoreCase = true) }
        triggers.entries.removeAll { it.value.tableName.equals(tableName, ignoreCase = true) }
    }

    private fun dropDependentForeignKeys(tableName: String, cascade: Boolean) {
        val referencing = tables.values.filter { table ->
            !table.schema.name.equals(tableName, ignoreCase = true) &&
                table.schema.foreignKeys.any { it.referencedTable.equals(tableName, ignoreCase = true) }
        }
        if (referencing.isEmpty()) return
        if (!cascade) {
            val names = referencing.map { it.schema.name }
            throw KestrelException(
                "cannot drop '$tableName' because table(s) ${names.joinToString(", ")} have a FOREIGN KEY " +
                    "referencing it; use CASCADE"
            )
        }
        for (table in referencing) {
            val key = table.schema.name.lowercase()
            val newSchema = TableSchema(
                table.schema.name,
                table.schema.columns,
                table.schema.foreignKeys.filterNot { it.referencedTable.equals(tableName, ignoreCase = true) },
                table.schema.checks,
                table.schema.uniqueConstraints,
                table.schema.compositePrimaryKey,
                table.schema.temporary
            )
            val replacement = Table(newSchema)
            replacement.rows.addAll(table.rows)
            tables[key] = replacement
        }
    }

    fun truncateTables(tableNames: List<String>, cascade: Boolean, restartIdentity: Boolean): List<String> {
        val requestedKeys = tableNames.map { name ->
            val key = name.lowercase()
            if (!tables.containsKey(key)) {
                if (views.containsKey(key)) throw KestrelException("'$name' is a view, TRUNCATE only applies to tables")
                throw KestrelException("no such table: $name")
            }
            key
        }
        val closure = truncateClosure(requestedKeys.toCollection(linkedSetOf()), cascade, tableNames)
        for (key in closure) {
            val table = tables.getValue(key)
            table.rows.clear()
            if (restartIdentity) restartOwnedSequences(table)
        }
        for (key in closure) reindexTable(tables.getValue(key).schema.name)
        return closure.map { tables.getValue(it).schema.name }
    }

    private fun truncateClosure(found: LinkedHashSet<String>, cascade: Boolean, requestedNames: List<String>): LinkedHashSet<String> {
        var frontier: Set<String> = found
        while (true) {
            val referencing = tables.values.filter { table ->
                table.schema.name.lowercase() !in found &&
                    table.schema.foreignKeys.any { fk -> fk.referencedTable.lowercase() in frontier }
            }
            if (referencing.isEmpty()) return found
            if (!cascade) {
                val names = referencing.map { it.schema.name }
                throw KestrelException(
                    "cannot truncate ${requestedNames.joinToString(", ")} because table(s) " +
                        "${names.joinToString(", ")} have a FOREIGN KEY referencing them; use CASCADE"
                )
            }
            val next = referencing.map { it.schema.name.lowercase() }.toSet()
            found.addAll(next)
            frontier = next
        }
    }

    fun changeColumnType(tableName: String, columnName: String, newColumn: Column, newRows: List<Row>) {
        val schema = table(tableName).schema
        val columnIndex = schema.columnIndex(columnName)
            ?: throw KestrelException("no such column: $columnName in table $tableName")
        val newColumns = schema.columns.toMutableList().also { it[columnIndex] = newColumn }
        val newSchema = TableSchema(
            schema.name, newColumns, schema.foreignKeys, schema.checks,
            schema.uniqueConstraints, schema.compositePrimaryKey, schema.temporary
        )
        replaceTable(tableName, newSchema, newRows)
    }

    private fun restartOwnedSequences(table: Table) {
        for (column in table.schema.columns) {
            val default = column.default
            if (default is NextValueExpression) {
                val sequence = sequenceOrNull(default.sequenceName) ?: continue
                sequences[default.sequenceName.lowercase()] = sequence.altered(sequence.definition, restartAt = sequence.definition.startValue)
            }
        }
    }

    fun addColumn(tableName: String, column: Column, valueForRow: (Row) -> Value) {
        val table = table(tableName)
        val schema = table.schema
        if (schema.columnIndex(column.name) != null) {
            throw KestrelException("duplicate column name: ${column.name}")
        }
        val newSchema = TableSchema(
            schema.name,
            schema.columns + column,
            schema.foreignKeys,
            schema.checks,
            schema.uniqueConstraints,
            schema.compositePrimaryKey,
            schema.temporary
        )
        val newRows = table.rows.map { row -> Row(row.values + valueForRow(row)) }
        replaceTable(tableName, newSchema, newRows)
    }

    fun dropColumn(tableName: String, columnName: String, cascade: Boolean) {
        val table = table(tableName)
        val schema = table.schema
        schema.columnIndex(columnName) ?: throw KestrelException("no such column: $columnName in table $tableName")
        if (schema.columns.size == 1) {
            throw KestrelException("cannot drop $columnName: table $tableName would be left with no columns")
        }
        if (schema.primaryKeyColumns.any { it.equals(columnName, ignoreCase = true) }) {
            throw KestrelException("cannot drop column $columnName because it is part of the PRIMARY KEY of $tableName")
        }

        val ownGeneratedDependents = schema.columns.filter { column ->
            !column.name.equals(columnName, ignoreCase = true) &&
                column.generated?.let { CheckExpressionColumns.references(it, columnName) } == true
        }
        val droppedNames = (ownGeneratedDependents.map { it.name } + columnName).map { it.lowercase() }.toSet()
        val droppedIndices = schema.columns.withIndex()
            .filter { (_, column) -> column.name.lowercase() in droppedNames }
            .map { it.index }
            .toSet()

        val referencingForeignKeys = tables.values.filter { other ->
            !other.schema.name.equals(tableName, ignoreCase = true) &&
                other.schema.foreignKeys.any { fk ->
                    fk.referencedTable.equals(tableName, ignoreCase = true) &&
                        fk.referencedColumns.any { it.lowercase() in droppedNames }
                }
        }
        val ownForeignKeys = schema.foreignKeys.filter { fk -> fk.columns.any { it.lowercase() in droppedNames } }
        val ownUniqueConstraints =
            schema.uniqueConstraints.filter { unique -> unique.columns.any { it.lowercase() in droppedNames } }
        val ownChecks = schema.checks.filter { check -> droppedNames.any { name -> CheckExpressionColumns.references(check.expression, name) } }
        val dependentIndexes = indexes.values.filter {
            it.definition.tableName.equals(tableName, ignoreCase = true) &&
                it.definition.columnNames.any { col -> col.lowercase() in droppedNames }
        }

        if (!cascade) {
            val reasons = mutableListOf<String>()
            if (ownGeneratedDependents.isNotEmpty()) {
                reasons.add(
                    "column(s) ${ownGeneratedDependents.joinToString(", ") { it.name }} are GENERATED columns that depend on it"
                )
            }
            if (referencingForeignKeys.isNotEmpty()) {
                reasons.add(
                    "table(s) ${referencingForeignKeys.joinToString(", ") { it.schema.name }} have a FOREIGN KEY referencing it"
                )
            }
            if (ownForeignKeys.isNotEmpty()) reasons.add("it is used by one of its own FOREIGN KEY constraints")
            if (ownUniqueConstraints.isNotEmpty()) reasons.add("it is used by a UNIQUE constraint")
            if (ownChecks.isNotEmpty()) reasons.add("it is used by a CHECK constraint")
            if (dependentIndexes.isNotEmpty()) {
                reasons.add("index(es) ${dependentIndexes.joinToString(", ") { it.definition.name }} are defined on it")
            }
            if (reasons.isNotEmpty()) {
                throw KestrelException(
                    "cannot drop column $columnName from $tableName because ${reasons.joinToString("; ")}; use CASCADE"
                )
            }
        }

        for (other in referencingForeignKeys) {
            val key = other.schema.name.lowercase()
            val newSchema = TableSchema(
                other.schema.name,
                other.schema.columns,
                other.schema.foreignKeys.filterNot { fk ->
                    fk.referencedTable.equals(tableName, ignoreCase = true) &&
                        fk.referencedColumns.any { it.lowercase() in droppedNames }
                },
                other.schema.checks,
                other.schema.uniqueConstraints,
                other.schema.compositePrimaryKey,
                other.schema.temporary
            )
            val replacement = Table(newSchema)
            replacement.rows.addAll(other.rows)
            tables[key] = replacement
        }
        for (index in dependentIndexes) indexes.remove(index.definition.name.lowercase())

        val newColumns = schema.columns.filterNot { it.name.lowercase() in droppedNames }
        val newForeignKeys = schema.foreignKeys - ownForeignKeys.toSet()
        val newUniqueConstraints = schema.uniqueConstraints - ownUniqueConstraints.toSet()
        val newChecks = schema.checks - ownChecks.toSet()
        val newSchema = TableSchema(schema.name, newColumns, newForeignKeys, newChecks, newUniqueConstraints, schema.compositePrimaryKey, schema.temporary)
        val newRows = table.rows.map { row -> Row(row.values.filterIndexed { valueIndex, _ -> valueIndex !in droppedIndices }) }
        replaceTable(tableName, newSchema, newRows)
    }

    fun renameColumn(tableName: String, oldName: String, newName: String) {
        val table = table(tableName)
        val schema = table.schema
        if (schema.columnIndex(oldName) == null) {
            throw KestrelException("no such column: $oldName in table $tableName")
        }
        if (!oldName.equals(newName, ignoreCase = true) && schema.columnIndex(newName) != null) {
            throw KestrelException("column $newName already exists in table $tableName")
        }

        val newColumns = schema.columns.map { column ->
            val renamed = if (column.name.equals(oldName, ignoreCase = true)) column.copy(name = newName) else column
            renamed.generated?.let { renamed.copy(generated = CheckExpressionColumns.rename(it, oldName, newName)) } ?: renamed
        }
        val newForeignKeys = schema.foreignKeys.map { fk ->
            val selfReference = fk.referencedTable.equals(tableName, ignoreCase = true)
            fk.copy(
                columns = renameInList(fk.columns, oldName, newName),
                referencedColumns = if (selfReference) renameInList(fk.referencedColumns, oldName, newName) else fk.referencedColumns
            )
        }
        val newUniqueConstraints = schema.uniqueConstraints.map { it.copy(columns = renameInList(it.columns, oldName, newName)) }
        val newChecks = schema.checks.map { it.copy(expression = CheckExpressionColumns.rename(it.expression, oldName, newName)) }
        val newCompositePrimaryKey = renameInList(schema.compositePrimaryKey, oldName, newName)
        val newSchema = TableSchema(schema.name, newColumns, newForeignKeys, newChecks, newUniqueConstraints, newCompositePrimaryKey, schema.temporary)
        replaceTable(tableName, newSchema, table.rows)

        for ((key, other) in tables.entries.toList()) {
            if (other.schema.name.equals(tableName, ignoreCase = true)) continue
            val touched = other.schema.foreignKeys.any { fk ->
                fk.referencedTable.equals(tableName, ignoreCase = true) &&
                    fk.referencedColumns.any { it.equals(oldName, ignoreCase = true) }
            }
            if (!touched) continue
            val updatedForeignKeys = other.schema.foreignKeys.map { fk ->
                if (fk.referencedTable.equals(tableName, ignoreCase = true)) {
                    fk.copy(referencedColumns = renameInList(fk.referencedColumns, oldName, newName))
                } else {
                    fk
                }
            }
            val updatedSchema = TableSchema(
                other.schema.name,
                other.schema.columns,
                updatedForeignKeys,
                other.schema.checks,
                other.schema.uniqueConstraints,
                other.schema.compositePrimaryKey,
                other.schema.temporary
            )
            val replacement = Table(updatedSchema)
            replacement.rows.addAll(other.rows)
            tables[key] = replacement
        }

        for ((key, index) in indexes.entries.toList()) {
            if (!index.definition.tableName.equals(tableName, ignoreCase = true)) continue
            if (index.definition.columnNames.none { it.equals(oldName, ignoreCase = true) }) continue
            val renamedDefinition = index.definition.copy(columnNames = renameInList(index.definition.columnNames, oldName, newName))
            val renamed = SortedIndex(renamedDefinition, collationsFor(newSchema, renamedDefinition.columnNames))
            reindexOne(tables.getValue(tableName.lowercase()), renamed)
            indexes[key] = renamed
        }
    }

    fun renameTable(oldName: String, newName: String) {
        val existing = table(oldName)
        if (!oldName.equals(newName, ignoreCase = true)) {
            val key = newName.lowercase()
            if (tables.containsKey(key)) throw KestrelException("table '$newName' already exists")
            if (views.containsKey(key)) throw KestrelException("a view named '$newName' already exists")
        }

        val selfReferencing = existing.schema.foreignKeys.any { it.referencedTable.equals(oldName, ignoreCase = true) }
        val newForeignKeys = if (selfReferencing) {
            existing.schema.foreignKeys.map { fk ->
                if (fk.referencedTable.equals(oldName, ignoreCase = true)) fk.copy(referencedTable = newName) else fk
            }
        } else {
            existing.schema.foreignKeys
        }
        val newSchema = TableSchema(
            newName,
            existing.schema.columns,
            newForeignKeys,
            existing.schema.checks,
            existing.schema.uniqueConstraints,
            existing.schema.compositePrimaryKey,
            existing.schema.temporary
        )
        tables.remove(oldName.lowercase())
        val replacement = Table(newSchema)
        replacement.rows.addAll(existing.rows)
        tables[newName.lowercase()] = replacement

        for ((key, other) in tables.entries.toList()) {
            if (other.schema.name.equals(newName, ignoreCase = true)) continue
            val touched = other.schema.foreignKeys.any { it.referencedTable.equals(oldName, ignoreCase = true) }
            if (!touched) continue
            val updatedForeignKeys = other.schema.foreignKeys.map { fk ->
                if (fk.referencedTable.equals(oldName, ignoreCase = true)) fk.copy(referencedTable = newName) else fk
            }
            val updatedSchema = TableSchema(
                other.schema.name,
                other.schema.columns,
                updatedForeignKeys,
                other.schema.checks,
                other.schema.uniqueConstraints,
                other.schema.compositePrimaryKey,
                other.schema.temporary
            )
            val replacementOther = Table(updatedSchema)
            replacementOther.rows.addAll(other.rows)
            tables[key] = replacementOther
        }

        for ((key, index) in indexes.entries.toList()) {
            if (!index.definition.tableName.equals(oldName, ignoreCase = true)) continue
            val renamedDefinition = index.definition.copy(tableName = newName)
            indexes[key] = SortedIndex(renamedDefinition, collationsFor(newSchema, renamedDefinition.columnNames)).also { renamed ->
                reindexOne(tables.getValue(newName.lowercase()), renamed)
            }
        }

        for ((key, view) in views.entries.toList()) {
            if (!DependencyAnalysis.referencesRelation(view.query, oldName)) continue
            views[key] = view.copy(query = RelationRename.rename(view.query, oldName, newName))
        }

        for ((key, trigger) in triggers.entries.toList()) {
            if (!trigger.tableName.equals(oldName, ignoreCase = true)) continue
            triggers[key] = trigger.copy(tableName = newName)
        }
    }

    fun addForeignKey(tableName: String, foreignKey: ForeignKeyConstraint) {
        val table = table(tableName)
        val schema = table.schema
        val newSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys + foreignKey, schema.checks,
            schema.uniqueConstraints, schema.compositePrimaryKey, schema.temporary
        )
        replaceTable(tableName, newSchema, table.rows)
    }

    fun addUniqueConstraint(tableName: String, unique: UniqueConstraint) {
        val table = table(tableName)
        val schema = table.schema
        val newSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys, schema.checks,
            schema.uniqueConstraints + unique, schema.compositePrimaryKey, schema.temporary
        )
        replaceTable(tableName, newSchema, table.rows)
    }

    fun addCheckConstraint(tableName: String, check: CheckConstraint) {
        val table = table(tableName)
        val schema = table.schema
        val newSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys, schema.checks + check,
            schema.uniqueConstraints, schema.compositePrimaryKey, schema.temporary
        )
        replaceTable(tableName, newSchema, table.rows)
    }

    fun dropConstraint(tableName: String, name: String, cascade: Boolean) {
        val table = table(tableName)
        val schema = table.schema
        val foreignKey = schema.foreignKeys.find { it.name?.equals(name, ignoreCase = true) == true }
        val check = schema.checks.find { it.name?.equals(name, ignoreCase = true) == true }
        val unique = schema.uniqueConstraints.find { it.name?.equals(name, ignoreCase = true) == true }
        when {
            foreignKey != null -> {
                val newSchema = TableSchema(
                    schema.name, schema.columns, schema.foreignKeys - foreignKey, schema.checks,
                    schema.uniqueConstraints, schema.compositePrimaryKey, schema.temporary
                )
                replaceTable(tableName, newSchema, table.rows)
            }
            check != null -> {
                val newSchema = TableSchema(
                    schema.name, schema.columns, schema.foreignKeys, schema.checks - check,
                    schema.uniqueConstraints, schema.compositePrimaryKey, schema.temporary
                )
                replaceTable(tableName, newSchema, table.rows)
            }
            unique != null -> dropUniqueConstraint(tableName, unique, cascade)
            else -> throw KestrelException("no such constraint: $name in table $tableName")
        }
    }

    private fun dropUniqueConstraint(tableName: String, unique: UniqueConstraint, cascade: Boolean) {
        val schema = table(tableName).schema
        val remainingUniques = schema.uniqueConstraints - unique
        val targetSet = unique.columns.map { it.lowercase() }.toSet()
        val stillEnforced = TableSchema(
            schema.name, schema.columns, schema.foreignKeys, schema.checks, remainingUniques,
            schema.compositePrimaryKey, schema.temporary
        ).enforcedUniqueKeyGroups().any { group -> group.map { it.lowercase() }.toSet() == targetSet }

        fun dependsOnTarget(fk: ForeignKeyConstraint) =
            fk.referencedTable.equals(tableName, ignoreCase = true) &&
                fk.referencedColumns.map { it.lowercase() }.toSet() == targetSet

        val dependents = if (stillEnforced) emptyList() else tables.values.filter { other -> other.schema.foreignKeys.any(::dependsOnTarget) }
        if (!cascade && dependents.isNotEmpty()) {
            val names = dependents.map { it.schema.name }.distinct()
            throw KestrelException(
                "cannot drop constraint ${unique.name} because table(s) ${names.joinToString(", ")} have a " +
                    "FOREIGN KEY referencing it; use CASCADE"
            )
        }
        for (other in dependents) {
            val key = other.schema.name.lowercase()
            val newSchema = TableSchema(
                other.schema.name, other.schema.columns, other.schema.foreignKeys.filterNot(::dependsOnTarget),
                other.schema.checks, other.schema.uniqueConstraints, other.schema.compositePrimaryKey, other.schema.temporary
            )
            val replacement = Table(newSchema)
            replacement.rows.addAll(other.rows)
            tables[key] = replacement
        }
        val current = table(tableName)
        val newSchema = TableSchema(
            current.schema.name, current.schema.columns, current.schema.foreignKeys, current.schema.checks,
            current.schema.uniqueConstraints - unique, current.schema.compositePrimaryKey, current.schema.temporary
        )
        replaceTable(tableName, newSchema, current.rows)
    }

    private fun renameInList(names: List<String>, oldName: String, newName: String): List<String> =
        names.map { if (it.equals(oldName, ignoreCase = true)) newName else it }

    private fun replaceTable(tableName: String, newSchema: TableSchema, rows: List<Row>) {
        val key = tableName.lowercase()
        val replacement = Table(newSchema)
        replacement.rows.addAll(rows)
        tables[key] = replacement
    }

    fun table(tableName: String): Table {
        val key = tableName.lowercase()
        tables[key]?.let { return it }
        if (views.containsKey(key)) {
            throw KestrelException("cannot modify view: $tableName")
        }
        throw KestrelException("no such table: $tableName")
    }

    fun tableOrNull(tableName: String): Table? = tables[tableName.lowercase()]

    val tableNames: List<String> get() = tables.values.map { it.schema.name }

    fun createView(name: String, query: QueryStatement, orReplace: Boolean) {
        val key = name.lowercase()
        if (tables.containsKey(key)) {
            throw KestrelException("a table named '$name' already exists")
        }
        if (!orReplace && views.containsKey(key)) {
            throw KestrelException("view '$name' already exists")
        }
        views[key] = ViewDefinition(name, query)
    }

    fun dropView(name: String, cascade: Boolean = false) {
        val key = name.lowercase()
        if (!views.containsKey(key)) {
            if (tables.containsKey(key)) {
                throw KestrelException("'$name' is a table, use DROP TABLE")
            }
            throw KestrelException("no such view: $name")
        }
        dropDependentViews(name, cascade)
        views.remove(key)
    }

    private fun dropDependentViews(name: String, cascade: Boolean) {
        val closure = transitiveViewDependents(name.lowercase())
        if (closure.isEmpty()) return
        if (!cascade) {
            val names = closure.mapNotNull { views[it]?.name }
            throw KestrelException(
                "cannot drop '$name' because view(s) ${names.joinToString(", ")} depend on it; use CASCADE"
            )
        }
        for (key in closure) views.remove(key)
    }

    private fun transitiveViewDependents(rootKey: String): Set<String> {
        val found = linkedSetOf<String>()
        var frontier = setOf(rootKey)
        while (true) {
            val next = views.values
                .filter { it.name.lowercase() != rootKey && it.name.lowercase() !in found }
                .filter { view -> frontier.any { DependencyAnalysis.referencesRelation(view.query, it) } }
                .map { it.name.lowercase() }
                .toSet()
            if (next.isEmpty()) return found
            found.addAll(next)
            frontier = next
        }
    }

    fun viewOrNull(name: String): ViewDefinition? = views[name.lowercase()]

    val viewNames: List<String> get() = views.values.map { it.name }

    fun createIndex(definition: IndexDefinition) {
        val key = definition.name.lowercase()
        if (indexes.containsKey(key)) {
            throw KestrelException("index '${definition.name}' already exists")
        }
        require(definition.columnNames.isNotEmpty()) { "index must cover at least one column" }
        val table = table(definition.tableName)
        for (columnName in definition.columnNames) {
            if (table.schema.columnIndex(columnName) == null) {
                throw KestrelException("no such column: $columnName in table ${definition.tableName}")
            }
        }
        val index = SortedIndex(definition, collationsFor(table.schema, definition.columnNames))
        reindexOne(table, index)
        indexes[key] = index
    }

    fun dropIndex(indexName: String) {
        if (indexes.remove(indexName.lowercase()) == null) {
            throw KestrelException("no such index: $indexName")
        }
    }

    fun indexesOn(tableName: String): List<SortedIndex> = indexes.values.filter {
        it.definition.tableName.equals(tableName, ignoreCase = true)
    }

    val indexNames: List<String> get() = indexes.values.map { it.definition.name }

    fun indexDefinitions(): List<IndexDefinition> = indexes.values.map { it.definition }

    fun reindexTable(tableName: String) {
        val table = table(tableName)
        indexes.values
            .filter { it.definition.tableName.equals(tableName, ignoreCase = true) }
            .forEach { reindexOne(table, it) }
    }

    fun createTrigger(definition: TriggerDefinition) {
        val key = definition.name.lowercase()
        if (triggers.containsKey(key)) {
            throw KestrelException("trigger '${definition.name}' already exists")
        }
        table(definition.tableName)
        triggers[key] = definition
    }

    fun dropTrigger(name: String) {
        if (triggers.remove(name.lowercase()) == null) {
            throw KestrelException("no such trigger: $name")
        }
    }

    fun triggersOn(tableName: String, timing: TriggerTiming, event: TriggerEvent): List<TriggerDefinition> =
        triggers.values.filter {
            it.tableName.equals(tableName, ignoreCase = true) && it.timing == timing && it.event == event
        }

    fun hasTriggers(tableName: String): Boolean = triggers.values.any { it.tableName.equals(tableName, ignoreCase = true) }

    val triggerNames: List<String> get() = triggers.values.map { it.name }

    fun triggerDefinitions(): List<TriggerDefinition> = triggers.values.toList()

    fun createSequence(definition: SequenceDefinition) {
        val key = definition.name.lowercase()
        if (sequences.containsKey(key)) {
            throw KestrelException("sequence '${definition.name}' already exists")
        }
        sequences[key] = Sequence(definition)
    }

    fun dropSequence(name: String) {
        if (sequences.remove(name.lowercase()) == null) {
            throw KestrelException("no such sequence: $name")
        }
    }

    fun alterSequence(name: String, newDefinition: SequenceDefinition, restartAt: Long?) {
        val key = name.lowercase()
        val existing = sequences[key] ?: throw KestrelException("no such sequence: $name")
        sequences[key] = existing.altered(newDefinition, restartAt)
    }

    fun sequence(name: String): Sequence = sequenceOrNull(name) ?: throw KestrelException("no such sequence: $name")

    fun sequenceOrNull(name: String): Sequence? = sequences[name.lowercase()]

    val sequenceNames: List<String> get() = sequences.values.map { it.definition.name }

    fun sequenceDefinitions(): List<SequenceDefinition> = sequences.values.map { it.definition }

    fun snapshot(): CatalogSnapshot = CatalogSnapshot(
        tables = tables.mapValues { (_, table) -> table.schema to table.rows.toList() },
        indexDefinitions = indexes.values.map { it.definition },
        views = views.toMap(),
        triggers = triggers.toMap(),
        sequences = sequences.toMap()
    )

    fun restore(snapshot: CatalogSnapshot) {
        tables.clear()
        indexes.clear()
        views.clear()
        triggers.clear()
        sequences.clear()
        views.putAll(snapshot.views)
        triggers.putAll(snapshot.triggers)
        sequences.putAll(snapshot.sequences)
        for ((key, image) in snapshot.tables) {
            val table = Table(image.first)
            table.rows.addAll(image.second)
            tables[key] = table
        }
        for (definition in snapshot.indexDefinitions) {
            val schema = tables.getValue(definition.tableName.lowercase()).schema
            val index = SortedIndex(definition, collationsFor(schema, definition.columnNames))
            indexes[definition.name.lowercase()] = index
            reindexOne(tables.getValue(definition.tableName.lowercase()), index)
        }
    }

    private fun reindexOne(table: Table, index: SortedIndex) {
        index.clear()
        val columnIndices = index.definition.columnNames.map { table.schema.columnIndex(it)!! }
        table.rows.forEachIndexed { rowIndex, row ->
            index.add(columnIndices.map { row.values[it] }, rowIndex)
        }
    }

    private fun collationsFor(schema: TableSchema, columnNames: List<String>): List<Collation> =
        columnNames.map { schema.column(it)?.collation ?: Collation.Binary }
}
