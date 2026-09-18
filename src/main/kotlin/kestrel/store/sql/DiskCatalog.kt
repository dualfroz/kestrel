package kestrel.store.sql

import kestrel.KestrelException
import kestrel.catalog.CheckConstraint
import kestrel.catalog.CheckExpressionColumns
import kestrel.catalog.Column
import kestrel.catalog.DependencyAnalysis
import kestrel.catalog.ForeignKeyConstraint
import kestrel.catalog.RelationRename
import kestrel.catalog.Sequence
import kestrel.catalog.SequenceDefinition
import kestrel.catalog.TableSchema
import kestrel.catalog.TriggerDefinition
import kestrel.catalog.UniqueConstraint
import kestrel.persistence.SqlWriter
import kestrel.sql.ast.CreateTriggerStatement
import kestrel.sql.ast.CreateViewStatement
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.QueryStatement
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.TriggerEvent
import kestrel.sql.ast.TriggerTiming
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.store.codec.ByteReader
import kestrel.store.codec.ByteWriter
import kestrel.store.engine.StorageEngine
import kestrel.store.engine.TransactionalHeap
import kestrel.store.heap.RecordId
import kestrel.storage.Row
import kestrel.storage.Table
import kestrel.storage.Value
import java.io.File

class DiskCatalogException(message: String) : KestrelException(message)

class DiskCatalog private constructor(
    private val engine: StorageEngine,
    private val directory: TransactionalHeap
) : AutoCloseable {
    companion object {
        private const val DIRECTORY_HEADER_PAGE_ID = 0L

        private const val ENTRY_TAG_TABLE: Int = 0
        private const val ENTRY_TAG_VIEW: Int = 1
        private const val ENTRY_TAG_SEQUENCE: Int = 2
        private const val ENTRY_TAG_TRIGGER: Int = 3

        fun open(dataFile: File, walFile: File, poolCapacity: Int = 64): DiskCatalog {
            val isFresh = !dataFile.exists() || dataFile.length() == 0L
            val engine = StorageEngine.open(dataFile, walFile, poolCapacity)
            val directory = if (isFresh) {
                val heap = engine.createHeap()
                check(heap.headerPageId == DIRECTORY_HEADER_PAGE_ID) {
                    "expected the catalog directory to be the first page allocated in a fresh file, " +
                        "got page ${heap.headerPageId}"
                }
                heap
            } else {
                engine.openHeap(DIRECTORY_HEADER_PAGE_ID)
            }
            val catalog = DiskCatalog(engine, directory)
            catalog.loadDirectory()
            return catalog
        }
    }

    private class TableKeyBox(var value: String)

    private class DirectoryRow(var rid: RecordId, var entry: TableDirectoryEntry, val table: DiskTable, val keyBox: TableKeyBox)
    private class ViewRow(var rid: RecordId, val entry: ViewDirectoryEntry, val query: QueryStatement)
    private class SequenceRow(var rid: RecordId, var entry: SequenceDirectoryEntry, var sequence: Sequence)
    private class TriggerRow(var rid: RecordId, var entry: TriggerDirectoryEntry, var definition: TriggerDefinition)

    private val rowsByName = LinkedHashMap<String, DirectoryRow>()
    private val viewsByName = LinkedHashMap<String, ViewRow>()
    private val sequencesByName = LinkedHashMap<String, SequenceRow>()
    private val triggersByName = LinkedHashMap<String, TriggerRow>()

    private val temporaryTables = LinkedHashMap<String, Table>()

    private fun loadDirectory() {
        val txn = engine.beginTransaction()
        for ((rid, bytes) in directory.scan(txn)) {
            val payload = bytes.copyOfRange(1, bytes.size)
            when (val tag = bytes[0].toInt()) {
                ENTRY_TAG_TABLE -> {
                    val entry = DirectoryEntryCodec.decode(payload)
                    val keyBox = TableKeyBox(entry.tableName.lowercase())
                    val table = openTableFromEntry(entry, keyBox)
                    rowsByName[keyBox.value] = DirectoryRow(rid, entry, table, keyBox)
                }
                ENTRY_TAG_VIEW -> {
                    val entry = ViewDirectoryEntryCodec.decode(payload)
                    viewsByName[entry.viewName.lowercase()] = ViewRow(rid, entry, parseViewQuery(entry))
                }
                ENTRY_TAG_SEQUENCE -> {
                    val entry = SequenceDirectoryEntryCodec.decode(payload)
                    sequencesByName[entry.name.lowercase()] = SequenceRow(rid, entry, sequenceFromEntry(entry))
                }
                ENTRY_TAG_TRIGGER -> {
                    val entry = TriggerDirectoryEntryCodec.decode(payload)
                    triggersByName[entry.triggerName.lowercase()] = TriggerRow(rid, entry, parseTriggerDefinition(entry))
                }
                else -> throw DiskCatalogException("unknown directory entry tag: $tag")
            }
        }
        engine.commit(txn)
    }

    private fun parseViewQuery(entry: ViewDirectoryEntry): QueryStatement {
        val statements = Parser(Lexer(entry.statementText).tokenize()).parseProgram()
        val statement = statements.singleOrNull() as? CreateViewStatement
            ?: throw DiskCatalogException("corrupt view directory entry for ${entry.viewName}")
        return statement.query
    }

    private fun parseTriggerDefinition(entry: TriggerDirectoryEntry): TriggerDefinition {
        val statements = Parser(Lexer(entry.statementText).tokenize()).parseProgram()
        val statement = statements.singleOrNull() as? CreateTriggerStatement
            ?: throw DiskCatalogException("corrupt trigger directory entry for ${entry.triggerName}")
        return TriggerDefinition(
            statement.triggerName, statement.timing, statement.event, statement.tableName, statement.whenClause, statement.body
        )
    }

    private fun parseCheckExpressionText(text: String): Expression {
        val statements = Parser(Lexer("SELECT ($text) FROM check_constraint_placeholder;").tokenize()).parseProgram()
        val statement = statements.singleOrNull() as? SelectStatement
            ?: throw DiskCatalogException("corrupt CHECK constraint expression: $text")
        val projection = statement.projection as? ExpressionProjection
            ?: throw DiskCatalogException("corrupt CHECK constraint expression: $text")
        return projection.items.single().expression
    }

    private fun openTableFromEntry(entry: TableDirectoryEntry, keyBox: TableKeyBox): DiskTable {
        val heap = engine.openHeap(entry.dataHeaderPageId)
        val primaryIndex = entry.primaryKeyIndexRootPageId?.let { engine.openIndex(it) }
        val schema = TableSchema(
            entry.tableName, entry.columns,
            foreignKeys = entry.foreignKeys,
            uniqueConstraints = entry.uniqueConstraints,
            compositePrimaryKey = entry.compositePrimaryKey,
            checks = entry.checks.map { CheckConstraint(it.name, parseCheckExpressionText(it.expressionText)) }
        )
        val table = DiskTable(schema, heap, primaryIndex, mutableListOf()) { txnId ->
            persistEntry(keyBox.value, txnId)
        }
        for (secondary in entry.secondaryIndexes) {
            val columnIndices = secondary.columnNames.map { columnName ->
                schema.columnIndex(columnName)
                    ?: throw DiskCatalogException("directory entry for ${entry.tableName} references unknown column $columnName")
            }
            val index = engine.openIndex(secondary.rootPageId)
            table.attachSecondaryIndex(SecondaryIndexHandle(secondary.name, secondary.columnNames, columnIndices, index, secondary.unique))
        }
        return table
    }

    fun beginTransaction(): Long = engine.beginTransaction()
    fun commit(txnId: Long) = engine.commit(txnId)
    fun abort(txnId: Long) = engine.abort(txnId)
    fun savepoint(txnId: Long): Long = engine.savepoint(txnId)
    fun rollbackToSavepoint(txnId: Long, savepointLsn: Long) = engine.rollbackToSavepoint(txnId, savepointLsn)
    fun discardSavepoint(txnId: Long, savepointLsn: Long) = engine.discardSavepoint(txnId, savepointLsn)

    val tableNames: List<String> get() = rowsByName.values.map { it.entry.tableName } + temporaryTables.values.map { it.schema.name }

    fun allocatedPageCount(): Long = engine.allocatedPageCount()

    fun table(name: String): DiskTable {
        val key = name.lowercase()
        rowsByName[key]?.let { return it.table }
        if (viewsByName.containsKey(key)) throw DiskCatalogException("cannot modify view: $name")
        throw DiskCatalogException("no such table: $name")
    }

    fun tableOrNull(name: String): DiskTable? = rowsByName[name.lowercase()]?.table

    fun createTable(
        name: String,
        columns: List<Column>,
        compositePrimaryKey: List<String> = emptyList(),
        uniqueConstraints: List<UniqueConstraint> = emptyList(),
        checks: List<CheckConstraint> = emptyList(),
        foreignKeys: List<ForeignKeyConstraint> = emptyList()
    ): DiskTable {
        val key = name.lowercase()
        if (rowsByName.containsKey(key)) throw DiskCatalogException("table '$name' already exists")
        if (viewsByName.containsKey(key)) throw DiskCatalogException("a view named '$name' already exists")
        if (temporaryTables.containsKey(key)) throw DiskCatalogException("a temporary table named '$name' already exists")
        if (columns.count { it.primaryKey } > 1 ||
            (compositePrimaryKey.isNotEmpty() && columns.any { it.primaryKey })
        ) {
            throw DiskCatalogException("table '$name' declares more than one primary key")
        }
        if (compositePrimaryKey.isNotEmpty()) {
            val declared = compositePrimaryKey.map { it.lowercase() }.toSet()
            if (declared.size != compositePrimaryKey.size) {
                throw DiskCatalogException("table '$name' declares a duplicate column in its PRIMARY KEY")
            }
            for (columnName in compositePrimaryKey) {
                if (columns.none { it.name.equals(columnName, ignoreCase = true) }) {
                    throw DiskCatalogException("PRIMARY KEY refers to unknown column: $columnName")
                }
            }
        }
        for (unique in uniqueConstraints) {
            for (columnName in unique.columns) {
                if (columns.none { it.name.equals(columnName, ignoreCase = true) }) {
                    throw DiskCatalogException("UNIQUE constraint refers to unknown column: $columnName")
                }
            }
        }
        val hasPrimaryKey = columns.any { it.primaryKey } || compositePrimaryKey.isNotEmpty()

        val heap = engine.createHeap()
        val primaryIndex = if (hasPrimaryKey) engine.createIndex() else null
        val entry = TableDirectoryEntry(
            name, columns, heap.headerPageId, primaryIndex?.rootPageId, emptyList(),
            compositePrimaryKey, uniqueConstraints, checks.map { CheckConstraintEntry.from(it) }, foreignKeys
        )

        val txn = engine.beginTransaction()
        val rid = directory.insert(txn, taggedTable(entry))
        engine.commit(txn)

        val schema = TableSchema(
            name, columns,
            foreignKeys = foreignKeys,
            uniqueConstraints = uniqueConstraints,
            compositePrimaryKey = compositePrimaryKey,
            checks = checks
        )
        val keyBox = TableKeyBox(key)
        val table = DiskTable(schema, heap, primaryIndex, mutableListOf()) { txnId -> persistEntry(keyBox.value, txnId) }
        rowsByName[key] = DirectoryRow(rid, entry, table, keyBox)
        return table
    }

    fun createTemporaryTable(schema: TableSchema): Table {
        val key = schema.name.lowercase()
        if (rowsByName.containsKey(key)) throw DiskCatalogException("table '${schema.name}' already exists")
        if (viewsByName.containsKey(key)) throw DiskCatalogException("a view named '${schema.name}' already exists")
        if (temporaryTables.containsKey(key)) throw DiskCatalogException("table '${schema.name}' already exists")
        val table = Table(schema)
        temporaryTables[key] = table
        return table
    }

    fun temporaryTableOrNull(name: String): Table? = temporaryTables[name.lowercase()]

    fun dropTemporaryTable(name: String) {
        temporaryTables.remove(name.lowercase()) ?: throw DiskCatalogException("no such temporary table: $name")
    }

    val temporaryTableValues: List<Table> get() = temporaryTables.values.toList()

    fun dropTable(name: String, cascade: Boolean = false) {
        val key = name.lowercase()
        val row = rowsByName[key] ?: run {
            if (viewsByName.containsKey(key)) throw DiskCatalogException("'$name' is a view, use DROP VIEW")
            throw DiskCatalogException("no such table: $name")
        }
        val viewClosure = dependentViewsOrThrow(name, cascade)
        val referencingTables = dependentForeignKeysOrThrow(name, cascade)
        val droppedTriggerKeys = triggersByName.filterValues { it.definition.tableName.equals(name, ignoreCase = true) }.keys.toList()

        val txn = engine.beginTransaction()
        try {
            for (viewKey in viewClosure) directory.delete(txn, viewsByName.getValue(viewKey).rid)
            for (triggerKey in droppedTriggerKeys) directory.delete(txn, triggersByName.getValue(triggerKey).rid)
            directory.delete(txn, row.rid)
            row.table.destroyStorage(txn)
            for ((otherKey, other) in referencingTables) {
                val stripped = other.table.schema.foreignKeys.filterNot { it.referencedTable.equals(name, ignoreCase = true) }
                other.table.replaceSchemaMetadata(withForeignKeys(other.table.schema, stripped))
                persistEntry(otherKey, txn)
            }
            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
        for (viewKey in viewClosure) viewsByName.remove(viewKey)
        for (triggerKey in droppedTriggerKeys) triggersByName.remove(triggerKey)
        rowsByName.remove(key)
    }

    private fun dependentViewsOrThrow(name: String, cascade: Boolean): Set<String> {
        val closure = transitiveViewDependents(name.lowercase())
        if (closure.isEmpty() || cascade) return closure
        val names = closure.mapNotNull { viewsByName[it]?.entry?.viewName }
        throw DiskCatalogException("cannot drop '$name' because view(s) ${names.joinToString(", ")} depend on it")
    }

    private fun dependentForeignKeysOrThrow(name: String, cascade: Boolean): List<Map.Entry<String, DirectoryRow>> {
        val key = name.lowercase()
        val referencing = rowsByName.entries.filter { (otherKey, other) ->
            otherKey != key && other.table.schema.foreignKeys.any { fk -> fk.referencedTable.equals(name, ignoreCase = true) }
        }
        if (referencing.isEmpty() || cascade) return referencing
        val names = referencing.map { it.value.table.schema.name }
        throw DiskCatalogException(
            "cannot drop '$name' because table(s) ${names.joinToString(", ")} have a FOREIGN KEY referencing it"
        )
    }

    private fun rowOrThrow(tableName: String): DirectoryRow {
        val key = tableName.lowercase()
        rowsByName[key]?.let { return it }
        if (viewsByName.containsKey(key)) throw DiskCatalogException("cannot modify view: $tableName")
        throw DiskCatalogException("no such table: $tableName")
    }

    fun truncateTables(txn: Long, tableNames: List<String>, cascade: Boolean): List<String> {
        tableNames.forEach { rowOrThrow(it) }
        val closure = truncateClosure(tableNames.map { it.lowercase() }.toCollection(linkedSetOf()), cascade, tableNames)
        for (key in closure) {
            val table = rowsByName.getValue(key).table
            for ((rid, _) in table.scan(txn)) table.delete(txn, rid)
        }
        return closure.map { rowsByName.getValue(it).entry.tableName }
    }

    private fun truncateClosure(found: LinkedHashSet<String>, cascade: Boolean, requestedNames: List<String>): LinkedHashSet<String> {
        var frontier: Set<String> = found
        while (true) {
            val referencing = rowsByName.values.filter { row ->
                row.entry.tableName.lowercase() !in found &&
                    row.table.schema.foreignKeys.any { fk -> fk.referencedTable.lowercase() in frontier }
            }
            if (referencing.isEmpty()) return found
            if (!cascade) {
                val names = referencing.map { it.entry.tableName }
                throw DiskCatalogException(
                    "cannot truncate ${requestedNames.joinToString(", ")} because table(s) " +
                        "${names.joinToString(", ")} have a FOREIGN KEY referencing them; use CASCADE"
                )
            }
            val next = referencing.map { it.entry.tableName.lowercase() }.toSet()
            found.addAll(next)
            frontier = next
        }
    }

    fun addColumn(tableName: String, column: Column, valueForRow: (Row) -> Value) {
        val row = rowOrThrow(tableName)
        val schema = row.table.schema
        if (schema.columnIndex(column.name) != null) {
            throw DiskCatalogException("duplicate column name: ${column.name}")
        }
        val newSchema = TableSchema(
            schema.name, schema.columns + column, schema.foreignKeys, schema.checks, schema.uniqueConstraints, schema.compositePrimaryKey
        )
        val txn = engine.beginTransaction()
        try {
            row.table.rewriteRows(txn, newSchema) { existingRow -> Row(existingRow.values + valueForRow(existingRow)) }
            persistEntry(tableName.lowercase(), txn)
            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
    }

    fun changeColumnType(tableName: String, columnName: String, newColumn: Column, valueForRow: (Row) -> Value) {
        val row = rowOrThrow(tableName)
        val schema = row.table.schema
        val columnIndex = schema.columnIndex(columnName)
            ?: throw DiskCatalogException("no such column: $columnName in table $tableName")
        val newColumns = schema.columns.toMutableList().also { it[columnIndex] = newColumn }
        val newSchema = TableSchema(
            schema.name, newColumns, schema.foreignKeys, schema.checks, schema.uniqueConstraints, schema.compositePrimaryKey
        )
        val txn = engine.beginTransaction()
        try {
            row.table.rewriteRows(txn, newSchema) { existingRow ->
                val values = existingRow.values.toMutableList()
                values[columnIndex] = valueForRow(existingRow)
                Row(values)
            }
            persistEntry(tableName.lowercase(), txn)
            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
    }

    fun dropColumn(tableName: String, columnName: String, cascade: Boolean) {
        val key = tableName.lowercase()
        val row = rowOrThrow(tableName)
        val schema = row.table.schema
        schema.columnIndex(columnName) ?: throw DiskCatalogException("no such column: $columnName in table $tableName")
        if (schema.columns.size == 1) {
            throw DiskCatalogException("cannot drop $columnName: table $tableName would be left with no columns")
        }
        if (schema.primaryKeyColumns.any { it.equals(columnName, ignoreCase = true) }) {
            throw DiskCatalogException("cannot drop column $columnName because it is part of the PRIMARY KEY of $tableName")
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

        val referencingForeignKeys = rowsByName.entries.filter { (otherKey, other) ->
            otherKey != key && other.table.schema.foreignKeys.any { fk ->
                fk.referencedTable.equals(tableName, ignoreCase = true) && fk.referencedColumns.any { it.lowercase() in droppedNames }
            }
        }
        val ownForeignKeys = schema.foreignKeys.filter { fk -> fk.columns.any { it.lowercase() in droppedNames } }
        val ownUniqueConstraints = schema.uniqueConstraints.filter { unique -> unique.columns.any { it.lowercase() in droppedNames } }
        val ownChecks = schema.checks.filter { check -> droppedNames.any { name -> CheckExpressionColumns.references(check.expression, name) } }
        val dependentIndexes = row.table.secondaryIndexes.filter { it.columnNames.any { c -> c.lowercase() in droppedNames } }

        if (!cascade) {
            val reasons = mutableListOf<String>()
            if (ownGeneratedDependents.isNotEmpty()) {
                reasons.add(
                    "column(s) ${ownGeneratedDependents.joinToString(", ") { it.name }} are GENERATED columns that depend on it"
                )
            }
            if (referencingForeignKeys.isNotEmpty()) {
                reasons.add(
                    "table(s) ${referencingForeignKeys.joinToString(", ") { it.value.table.schema.name }} have a FOREIGN KEY referencing it"
                )
            }
            if (ownForeignKeys.isNotEmpty()) reasons.add("it is used by one of its own FOREIGN KEY constraints")
            if (ownUniqueConstraints.isNotEmpty()) reasons.add("it is used by a UNIQUE constraint")
            if (ownChecks.isNotEmpty()) reasons.add("it is used by a CHECK constraint")
            if (dependentIndexes.isNotEmpty()) {
                reasons.add("index(es) ${dependentIndexes.joinToString(", ") { it.name }} are defined on it")
            }
            if (reasons.isNotEmpty()) {
                throw DiskCatalogException(
                    "cannot drop column $columnName from $tableName because ${reasons.joinToString("; ")}; use CASCADE"
                )
            }
        }

        val newColumns = schema.columns.filterNot { it.name.lowercase() in droppedNames }
        val newForeignKeys = schema.foreignKeys - ownForeignKeys.toSet()
        val newUniqueConstraints = schema.uniqueConstraints - ownUniqueConstraints.toSet()
        val newChecks = schema.checks - ownChecks.toSet()
        val newSchema = TableSchema(schema.name, newColumns, newForeignKeys, newChecks, newUniqueConstraints, schema.compositePrimaryKey)

        val txn = engine.beginTransaction()
        try {
            for (indexName in dependentIndexes.map { it.name }) row.table.dropSecondaryIndexByName(indexName, txn)
            row.table.rewriteRows(txn, newSchema) { existingRow ->
                Row(existingRow.values.filterIndexed { valueIndex, _ -> valueIndex !in droppedIndices })
            }
            persistEntry(key, txn)
            for ((otherKey, other) in referencingForeignKeys) {
                val updatedForeignKeys = other.table.schema.foreignKeys.filterNot { fk ->
                    fk.referencedTable.equals(tableName, ignoreCase = true) && fk.referencedColumns.any { it.lowercase() in droppedNames }
                }
                other.table.replaceSchemaMetadata(withForeignKeys(other.table.schema, updatedForeignKeys))
                persistEntry(otherKey, txn)
            }
            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
    }

    fun renameColumn(tableName: String, oldName: String, newName: String) {
        val key = tableName.lowercase()
        val row = rowOrThrow(tableName)
        val schema = row.table.schema
        if (schema.columnIndex(oldName) == null) {
            throw DiskCatalogException("no such column: $oldName in table $tableName")
        }
        if (!oldName.equals(newName, ignoreCase = true) && schema.columnIndex(newName) != null) {
            throw DiskCatalogException("column $newName already exists in table $tableName")
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
        val newChecks = schema.checks.map { CheckConstraint(it.name, CheckExpressionColumns.rename(it.expression, oldName, newName)) }
        val newCompositePrimaryKey = renameInList(schema.compositePrimaryKey, oldName, newName)
        val newSchema = TableSchema(schema.name, newColumns, newForeignKeys, newChecks, newUniqueConstraints, newCompositePrimaryKey)

        val affectedOthers = rowsByName.entries.filter { (otherKey, other) ->
            otherKey != key && other.table.schema.foreignKeys.any { fk ->
                fk.referencedTable.equals(tableName, ignoreCase = true) && fk.referencedColumns.any { it.equals(oldName, ignoreCase = true) }
            }
        }

        val txn = engine.beginTransaction()
        try {
            row.table.renameColumnAndIndexes(newSchema, oldName, newName)
            persistEntry(key, txn)
            for ((otherKey, other) in affectedOthers) {
                val updatedForeignKeys = other.table.schema.foreignKeys.map { fk ->
                    if (fk.referencedTable.equals(tableName, ignoreCase = true)) {
                        fk.copy(referencedColumns = renameInList(fk.referencedColumns, oldName, newName))
                    } else {
                        fk
                    }
                }
                other.table.replaceSchemaMetadata(withForeignKeys(other.table.schema, updatedForeignKeys))
                persistEntry(otherKey, txn)
            }
            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
    }

    fun renameTable(oldName: String, newName: String) {
        val oldKey = oldName.lowercase()
        val row = rowOrThrow(oldName)
        val newKey = newName.lowercase()
        if (!oldName.equals(newName, ignoreCase = true)) {
            if (rowsByName.containsKey(newKey)) throw DiskCatalogException("table '$newName' already exists")
            if (viewsByName.containsKey(newKey)) throw DiskCatalogException("a view named '$newName' already exists")
        }

        val schema = row.table.schema
        val selfReferencing = schema.foreignKeys.any { it.referencedTable.equals(oldName, ignoreCase = true) }
        val ownNewForeignKeys = if (selfReferencing) {
            schema.foreignKeys.map { fk -> if (fk.referencedTable.equals(oldName, ignoreCase = true)) fk.copy(referencedTable = newName) else fk }
        } else {
            schema.foreignKeys
        }
        val newSchema = TableSchema(newName, schema.columns, ownNewForeignKeys, schema.checks, schema.uniqueConstraints, schema.compositePrimaryKey)

        val affectedOthers = rowsByName.entries.filter { (otherKey, other) ->
            otherKey != oldKey && other.table.schema.foreignKeys.any { it.referencedTable.equals(oldName, ignoreCase = true) }
        }
        val affectedViews = viewsByName.entries.filter { (_, view) -> DependencyAnalysis.referencesRelation(view.query, oldName) }
        val affectedTriggers = triggersByName.entries.filter { (_, trigger) -> trigger.definition.tableName.equals(oldName, ignoreCase = true) }

        val txn = engine.beginTransaction()
        try {
            row.table.replaceSchemaMetadata(newSchema)
            if (oldKey != newKey) {
                rowsByName.remove(oldKey)
                row.keyBox.value = newKey
                rowsByName[newKey] = row
            }
            persistEntry(newKey, txn)

            for ((otherKey, other) in affectedOthers) {
                val updatedForeignKeys = other.table.schema.foreignKeys.map { fk ->
                    if (fk.referencedTable.equals(oldName, ignoreCase = true)) fk.copy(referencedTable = newName) else fk
                }
                other.table.replaceSchemaMetadata(withForeignKeys(other.table.schema, updatedForeignKeys))
                persistEntry(otherKey, txn)
            }

            for ((viewKey, viewRow) in affectedViews) {
                val renamedQuery = RelationRename.rename(viewRow.query, oldName, newName)
                val newEntry = ViewDirectoryEntry(
                    viewRow.entry.viewName, SqlWriter.write(CreateViewStatement(viewRow.entry.viewName, renamedQuery, orReplace = false))
                )
                val rid = directory.update(txn, viewRow.rid, taggedView(newEntry))
                viewsByName[viewKey] = ViewRow(rid, newEntry, renamedQuery)
            }

            for ((triggerKey, triggerRow) in affectedTriggers) {
                val renamedDefinition = triggerRow.definition.copy(tableName = newName)
                val newEntry = TriggerDirectoryEntry(triggerRow.entry.triggerName, SqlWriter.triggerText(renamedDefinition))
                val rid = directory.update(txn, triggerRow.rid, taggedTrigger(newEntry))
                triggersByName[triggerKey] = TriggerRow(rid, newEntry, renamedDefinition)
            }

            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
    }

    private fun withForeignKeys(schema: TableSchema, foreignKeys: List<ForeignKeyConstraint>): TableSchema = TableSchema(
        schema.name, schema.columns, foreignKeys, schema.checks, schema.uniqueConstraints, schema.compositePrimaryKey
    )

    fun addForeignKey(tableName: String, foreignKey: ForeignKeyConstraint) {
        val key = tableName.lowercase()
        val row = rowOrThrow(tableName)
        val schema = row.table.schema
        val newSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys + foreignKey, schema.checks, schema.uniqueConstraints, schema.compositePrimaryKey
        )
        val txn = engine.beginTransaction()
        try {
            row.table.replaceSchemaMetadata(newSchema)
            persistEntry(key, txn)
            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
    }

    fun addUniqueConstraint(tableName: String, unique: UniqueConstraint) {
        val key = tableName.lowercase()
        val row = rowOrThrow(tableName)
        val schema = row.table.schema
        val newSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys, schema.checks, schema.uniqueConstraints + unique, schema.compositePrimaryKey
        )
        val txn = engine.beginTransaction()
        try {
            row.table.replaceSchemaMetadata(newSchema)
            persistEntry(key, txn)
            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
    }

    fun addCheckConstraint(tableName: String, check: CheckConstraint) {
        val key = tableName.lowercase()
        val row = rowOrThrow(tableName)
        val schema = row.table.schema
        val newSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys, schema.checks + check, schema.uniqueConstraints, schema.compositePrimaryKey
        )
        val txn = engine.beginTransaction()
        try {
            row.table.replaceSchemaMetadata(newSchema)
            persistEntry(key, txn)
            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
    }

    fun dropConstraint(tableName: String, name: String, cascade: Boolean) {
        val key = tableName.lowercase()
        val row = rowOrThrow(tableName)
        val schema = row.table.schema
        val foreignKey = schema.foreignKeys.find { it.name?.equals(name, ignoreCase = true) == true }
        val check = schema.checks.find { it.name?.equals(name, ignoreCase = true) == true }
        val unique = schema.uniqueConstraints.find { it.name?.equals(name, ignoreCase = true) == true }
        when {
            foreignKey != null -> {
                val newSchema = TableSchema(
                    schema.name, schema.columns, schema.foreignKeys - foreignKey, schema.checks,
                    schema.uniqueConstraints, schema.compositePrimaryKey
                )
                val txn = engine.beginTransaction()
                try {
                    row.table.replaceSchemaMetadata(newSchema)
                    persistEntry(key, txn)
                    engine.commit(txn)
                } catch (failure: Exception) {
                    engine.abort(txn)
                    throw failure
                }
            }
            check != null -> {
                val newSchema = TableSchema(
                    schema.name, schema.columns, schema.foreignKeys, schema.checks - check,
                    schema.uniqueConstraints, schema.compositePrimaryKey
                )
                val txn = engine.beginTransaction()
                try {
                    row.table.replaceSchemaMetadata(newSchema)
                    persistEntry(key, txn)
                    engine.commit(txn)
                } catch (failure: Exception) {
                    engine.abort(txn)
                    throw failure
                }
            }
            unique != null -> dropUniqueConstraint(tableName, unique, cascade)
            else -> throw DiskCatalogException("no such constraint: $name in table $tableName")
        }
    }

    private fun dropUniqueConstraint(tableName: String, unique: UniqueConstraint, cascade: Boolean) {
        val key = tableName.lowercase()
        val schema = rowOrThrow(tableName).table.schema
        val remainingUniques = schema.uniqueConstraints - unique
        val targetSet = unique.columns.map { it.lowercase() }.toSet()
        val stillEnforced = TableSchema(
            schema.name, schema.columns, schema.foreignKeys, schema.checks, remainingUniques, schema.compositePrimaryKey
        ).enforcedUniqueKeyGroups().any { group -> group.map { it.lowercase() }.toSet() == targetSet }

        fun dependsOnTarget(fk: ForeignKeyConstraint) =
            fk.referencedTable.equals(tableName, ignoreCase = true) && fk.referencedColumns.map { it.lowercase() }.toSet() == targetSet

        val dependents = if (stillEnforced) emptyList() else rowsByName.values.filter { other -> other.table.schema.foreignKeys.any(::dependsOnTarget) }
        if (!cascade && dependents.isNotEmpty()) {
            val names = dependents.map { it.table.schema.name }.distinct()
            throw DiskCatalogException(
                "cannot drop constraint ${unique.name} because table(s) ${names.joinToString(", ")} have a " +
                    "FOREIGN KEY referencing it; use CASCADE"
            )
        }

        val txn = engine.beginTransaction()
        try {
            for (other in dependents) {
                val otherKey = other.table.schema.name.lowercase()
                val strippedForeignKeys = other.table.schema.foreignKeys.filterNot(::dependsOnTarget)
                other.table.replaceSchemaMetadata(withForeignKeys(other.table.schema, strippedForeignKeys))
                persistEntry(otherKey, txn)
            }
            val current = rowOrThrow(tableName)
            val newSchema = TableSchema(
                current.table.schema.name, current.table.schema.columns, current.table.schema.foreignKeys,
                current.table.schema.checks, current.table.schema.uniqueConstraints - unique, current.table.schema.compositePrimaryKey
            )
            current.table.replaceSchemaMetadata(newSchema)
            persistEntry(key, txn)
            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
    }

    private fun renameInList(names: List<String>, oldName: String, newName: String): List<String> =
        names.map { if (it.equals(oldName, ignoreCase = true)) newName else it }

    fun createIndex(tableName: String, indexName: String, columnName: String, unique: Boolean = false): SecondaryIndexHandle =
        createIndex(tableName, indexName, listOf(columnName), unique)

    fun createIndex(tableName: String, indexName: String, columnNames: List<String>, unique: Boolean = false): SecondaryIndexHandle {
        require(columnNames.isNotEmpty()) { "index must cover at least one column" }
        val key = tableName.lowercase()
        val row = rowsByName[key] ?: throw DiskCatalogException("no such table: $tableName")
        if (row.table.secondaryIndexes.any { it.name.equals(indexName, ignoreCase = true) }) {
            throw DiskCatalogException("index '$indexName' already exists")
        }
        val columnIndices = columnNames.map { columnName ->
            row.table.schema.columnIndex(columnName)
                ?: throw DiskCatalogException("no such column: $columnName in table $tableName")
        }

        val index = engine.createIndex()
        val handle = SecondaryIndexHandle(indexName, columnNames, columnIndices, index, unique)
        val backfill = engine.beginTransaction()
        try {
            row.table.backfillSecondaryIndex(backfill, handle)
        } catch (failure: Exception) {
            engine.abort(backfill)
            throw failure
        }
        engine.commit(backfill)

        row.table.attachSecondaryIndex(handle)
        val persistTxn = engine.beginTransaction()
        persistEntry(key, persistTxn)
        engine.commit(persistTxn)
        return handle
    }

    fun dropIndex(indexName: String) {
        val (key, row) = rowsByName.entries.firstOrNull { (_, row) ->
            row.table.secondaryIndexes.any { it.name.equals(indexName, ignoreCase = true) }
        }?.let { it.key to it.value } ?: throw DiskCatalogException("no such index: $indexName")
        val txn = engine.beginTransaction()
        try {
            row.table.dropSecondaryIndexByName(indexName, txn)
            persistEntry(key, txn)
            engine.commit(txn)
        } catch (failure: Exception) {
            engine.abort(txn)
            throw failure
        }
    }

    private fun persistEntry(tableKey: String, txnId: Long) {
        val row = rowsByName.getValue(tableKey)
        val updated = row.table.snapshotDirectoryEntry()
        row.rid = directory.update(txnId, row.rid, taggedTable(updated))
        row.entry = updated
    }

    private fun taggedTable(entry: TableDirectoryEntry): ByteArray =
        byteArrayOf(ENTRY_TAG_TABLE.toByte()) + DirectoryEntryCodec.encode(entry)

    private fun taggedView(entry: ViewDirectoryEntry): ByteArray =
        byteArrayOf(ENTRY_TAG_VIEW.toByte()) + ViewDirectoryEntryCodec.encode(entry)

    private fun taggedTrigger(entry: TriggerDirectoryEntry): ByteArray =
        byteArrayOf(ENTRY_TAG_TRIGGER.toByte()) + TriggerDirectoryEntryCodec.encode(entry)

    val viewNames: List<String> get() = viewsByName.values.map { it.entry.viewName }

    fun viewOrNull(name: String): QueryStatement? = viewsByName[name.lowercase()]?.query

    fun createView(name: String, query: QueryStatement, orReplace: Boolean) {
        val key = name.lowercase()
        if (rowsByName.containsKey(key)) throw DiskCatalogException("a table named '$name' already exists")
        if (!orReplace && viewsByName.containsKey(key)) throw DiskCatalogException("view '$name' already exists")
        val entry = ViewDirectoryEntry(name, SqlWriter.write(CreateViewStatement(name, query, orReplace = false)))
        val bytes = taggedView(entry)
        val txn = engine.beginTransaction()
        val existing = viewsByName[key]
        val rid = if (existing != null) directory.update(txn, existing.rid, bytes) else directory.insert(txn, bytes)
        engine.commit(txn)
        viewsByName[key] = ViewRow(rid, entry, query)
    }

    fun dropView(name: String, cascade: Boolean = false) {
        val key = name.lowercase()
        if (!viewsByName.containsKey(key)) {
            if (rowsByName.containsKey(key)) throw DiskCatalogException("'$name' is a table, use DROP TABLE")
            throw DiskCatalogException("no such view: $name")
        }
        val closure = transitiveViewDependents(key)
        if (closure.isNotEmpty() && !cascade) {
            val names = closure.mapNotNull { viewsByName[it]?.entry?.viewName }
            throw DiskCatalogException(
                "cannot drop '$name' because view(s) ${names.joinToString(", ")} depend on it; use CASCADE"
            )
        }
        val txn = engine.beginTransaction()
        for (depKey in closure) directory.delete(txn, viewsByName.getValue(depKey).rid)
        directory.delete(txn, viewsByName.getValue(key).rid)
        engine.commit(txn)
        for (depKey in closure) viewsByName.remove(depKey)
        viewsByName.remove(key)
    }

    private fun transitiveViewDependents(rootKey: String): Set<String> {
        val found = linkedSetOf<String>()
        var frontier = setOf(rootKey)
        while (true) {
            val next = viewsByName.entries
                .filter { (key, _) -> key != rootKey && key !in found }
                .filter { (_, row) -> frontier.any { DependencyAnalysis.referencesRelation(row.query, it) } }
                .map { it.key }
                .toSet()
            if (next.isEmpty()) return found
            found.addAll(next)
            frontier = next
        }
    }

    private fun sequenceFromEntry(entry: SequenceDirectoryEntry): Sequence {
        val definition = SequenceDefinition(entry.name, entry.incrementBy, entry.minValue, entry.maxValue, entry.startValue, entry.cycle)
        val reader = ByteReader(engine.readRawPage(entry.positionPageId))
        val value = reader.readFixedLong()
        val called = reader.readByte() != 0
        return Sequence(definition, initialValue = value, initialCalled = called)
    }

    private fun writeSequencePosition(pageId: Long, position: Pair<Long, Boolean>) {
        val (value, called) = position
        engine.writeRawPage(pageId, ByteWriter(9).writeFixedLong(value).writeByte(if (called) 1 else 0).toByteArray())
    }

    private fun taggedSequence(entry: SequenceDirectoryEntry): ByteArray =
        byteArrayOf(ENTRY_TAG_SEQUENCE.toByte()) + SequenceDirectoryEntryCodec.encode(entry)

    val sequenceNames: List<String> get() = sequencesByName.values.map { it.entry.name }

    fun sequence(name: String): Sequence = sequenceOrNull(name) ?: throw DiskCatalogException("no such sequence: $name")

    fun sequenceOrNull(name: String): Sequence? = sequencesByName[name.lowercase()]?.sequence

    fun createSequence(definition: SequenceDefinition): Sequence {
        val key = definition.name.lowercase()
        if (sequencesByName.containsKey(key)) throw DiskCatalogException("sequence '${definition.name}' already exists")
        val positionPageId = engine.allocateRawPage()
        writeSequencePosition(positionPageId, definition.startValue to false)
        val entry = SequenceDirectoryEntry(
            definition.name, definition.incrementBy, definition.minValue, definition.maxValue,
            definition.startValue, definition.cycle, positionPageId
        )
        val txn = engine.beginTransaction()
        val rid = directory.insert(txn, taggedSequence(entry))
        engine.commit(txn)
        val sequence = Sequence(definition)
        sequencesByName[key] = SequenceRow(rid, entry, sequence)
        return sequence
    }

    fun dropSequence(name: String) {
        val key = name.lowercase()
        val row = sequencesByName[key] ?: throw DiskCatalogException("no such sequence: $name")
        val txn = engine.beginTransaction()
        directory.delete(txn, row.rid)
        engine.commit(txn)
        engine.freeRawPage(row.entry.positionPageId)
        sequencesByName.remove(key)
    }

    fun alterSequence(name: String, newDefinition: SequenceDefinition, restartAt: Long?) {
        val key = name.lowercase()
        val row = sequencesByName[key] ?: throw DiskCatalogException("no such sequence: $name")
        val altered = row.sequence.altered(newDefinition, restartAt)
        val newEntry = SequenceDirectoryEntry(
            newDefinition.name, newDefinition.incrementBy, newDefinition.minValue, newDefinition.maxValue,
            newDefinition.startValue, newDefinition.cycle, row.entry.positionPageId
        )
        val txn = engine.beginTransaction()
        val rid = directory.update(txn, row.rid, taggedSequence(newEntry))
        engine.commit(txn)
        writeSequencePosition(row.entry.positionPageId, altered.snapshotPosition())
        row.rid = rid
        row.entry = newEntry
        row.sequence = altered
    }

    fun nextValue(name: String): Long = synchronized(this) {
        val row = sequencesByName[name.lowercase()] ?: throw DiskCatalogException("no such sequence: $name")
        val value = row.sequence.next()
        writeSequencePosition(row.entry.positionPageId, row.sequence.snapshotPosition())
        value
    }

    val triggerNames: List<String> get() = triggersByName.values.map { it.entry.triggerName }

    fun triggerDefinitions(): List<TriggerDefinition> = triggersByName.values.map { it.definition }

    fun hasTriggers(tableName: String): Boolean = triggersByName.values.any { it.definition.tableName.equals(tableName, ignoreCase = true) }

    fun triggersOn(tableName: String, timing: TriggerTiming, event: TriggerEvent): List<TriggerDefinition> =
        triggersByName.values.filter {
            it.definition.tableName.equals(tableName, ignoreCase = true) && it.definition.timing == timing && it.definition.event == event
        }.map { it.definition }

    fun createTrigger(definition: TriggerDefinition) {
        val key = definition.name.lowercase()
        if (triggersByName.containsKey(key)) throw DiskCatalogException("trigger '${definition.name}' already exists")
        val entry = TriggerDirectoryEntry(definition.name, SqlWriter.triggerText(definition))
        val txn = engine.beginTransaction()
        val rid = directory.insert(txn, taggedTrigger(entry))
        engine.commit(txn)
        triggersByName[key] = TriggerRow(rid, entry, definition)
    }

    fun dropTrigger(name: String) {
        val key = name.lowercase()
        val row = triggersByName[key] ?: throw DiskCatalogException("no such trigger: $name")
        val txn = engine.beginTransaction()
        directory.delete(txn, row.rid)
        engine.commit(txn)
        triggersByName.remove(key)
    }

    override fun close() = engine.close()
}
