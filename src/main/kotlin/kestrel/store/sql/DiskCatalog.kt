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

/**
 * A disk-backed system catalog: one [DiskTable] per user table, described by
 * a [TableDirectoryEntry] row in a dedicated directory heap. The directory
 * heap is always the very first heap allocated in a fresh database file, so
 * it always lands on page 0 (see [DiskManager][kestrel.store.page.DiskManager]:
 * page ids are handed out from 0 upward and nothing else runs before it) -
 * that fixed, well-known id is what lets a fresh process find the directory
 * again without another bootstrap record pointing at it.
 *
 * Every [DiskTable] this catalog opens is created with a callback that
 * rewrites its directory row, in the same transaction as the write that
 * triggered it, whenever a primary or secondary index's root page changes
 * (see [DiskTable]'s documentation on why that matters). That keeps the
 * directory heap's on-disk contents always in sync with the last committed
 * root, so [open] can reconstruct every table and index purely from what is
 * on disk after a full process restart - not just within one process's
 * lifetime the way holding a live [kestrel.store.engine.TransactionalIndex]
 * object would.
 *
 * The same directory heap also holds one row per view, tagged (see
 * `ENTRY_TAG_TABLE`/`ENTRY_TAG_VIEW`) and encoded by [ViewDirectoryEntryCodec] instead of
 * [DirectoryEntryCodec] - a view has no heap/index pages of its own to describe, only its
 * `CREATE VIEW ... AS ...;` text (see [ViewDirectoryEntry]), so it rides along in the one heap
 * that is already guaranteed to exist at a well-known page id, rather than needing a second
 * fixed-page-id heap of its own.
 *
 * A third tag, `ENTRY_TAG_SEQUENCE`, holds one row per SEQUENCE, encoded by
 * [SequenceDirectoryEntryCodec] - see [SequenceDirectoryEntry]'s own doc for why its live counter
 * (unlike everything else in this heap) is deliberately NOT part of that transactional row.
 *
 * A fourth tag, `ENTRY_TAG_TRIGGER`, holds one row per TRIGGER, encoded by
 * [TriggerDirectoryEntryCodec] - the same "store rendered SQL text, re-parse on open" trick as
 * [ViewDirectoryEntry], since a trigger's WHEN clause and body are arbitrarily deep AST too.
 */
class DiskCatalog private constructor(
    private val engine: StorageEngine,
    private val directory: TransactionalHeap
) : AutoCloseable {
    companion object {
        private const val DIRECTORY_HEADER_PAGE_ID = 0L

        /**
         * Leading byte on every row stored in the directory heap, distinguishing a
         * [TableDirectoryEntry] from a [ViewDirectoryEntry] - both share the same heap rather than
         * each getting a dedicated one, since a fresh heap's header page id is only deterministic
         * (page 0) for the very first heap [open] allocates; a second fixed-page-id heap would need
         * its own bootstrap record pointing at it, which this tag byte avoids entirely.
         */
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

    /**
     * A mutable box for the [rowsByName] key a [DiskTable]'s `onRootsChanged` callback should
     * persist under - captured by reference rather than by value so `ALTER TABLE ... RENAME TO`
     * (see [renameTable]) can repoint an already-constructed [DiskTable]'s callback at its new key
     * without having to reconstruct the table (which would mean reopening its heap/index pages).
     */
    private class TableKeyBox(var value: String)

    private class DirectoryRow(var rid: RecordId, var entry: TableDirectoryEntry, val table: DiskTable, val keyBox: TableKeyBox)
    private class ViewRow(var rid: RecordId, val entry: ViewDirectoryEntry, val query: QueryStatement)
    private class SequenceRow(var rid: RecordId, var entry: SequenceDirectoryEntry, var sequence: Sequence)
    private class TriggerRow(var rid: RecordId, var entry: TriggerDirectoryEntry, var definition: TriggerDefinition)

    private val rowsByName = LinkedHashMap<String, DirectoryRow>()
    private val viewsByName = LinkedHashMap<String, ViewRow>()
    private val sequencesByName = LinkedHashMap<String, SequenceRow>()
    private val triggersByName = LinkedHashMap<String, TriggerRow>()

    /**
     * `CREATE TEMP TABLE`/`CREATE TEMPORARY TABLE`: a plain in-memory [Table] - the exact same
     * class [kestrel.catalog.Catalog] uses for every table on the in-memory engine - rather than a
     * [DiskTable]. It never allocates a heap or index page and never gains a directory entry, so
     * [loadDirectory] can never discover it again after a process restart: there is no "don't
     * persist this one" flag to thread through the pager/WAL at all, unlike
     * [kestrel.catalog.TableSchema.temporary] on the in-memory engine (which shares its `tables` map
     * with every ordinary table and instead relies on [kestrel.persistence.SqlWriter] skipping it).
     * See [DiskExecutor]'s temporary-table INSERT/UPDATE/DELETE/SELECT path for how it is read and
     * mutated, and its BEGIN/SAVEPOINT/ROLLBACK handling for why a live [Table] reference (not just
     * its rows) needs to be reachable from outside this class.
     */
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

    /**
     * Re-derives a view's [QueryStatement] from its stored `CREATE VIEW ... AS ...;` text using the
     * same [Lexer]/[Parser] pair the REPL and every other SQL entry point already go through - no
     * bespoke AST codec, see [ViewDirectoryEntry]'s own KDoc for why.
     */
    private fun parseViewQuery(entry: ViewDirectoryEntry): QueryStatement {
        val statements = Parser(Lexer(entry.statementText).tokenize()).parseProgram()
        val statement = statements.singleOrNull() as? CreateViewStatement
            ?: throw DiskCatalogException("corrupt view directory entry for ${entry.viewName}")
        return statement.query
    }

    /**
     * Re-derives a trigger's [TriggerDefinition] from its stored `CREATE TRIGGER ...;` text - same
     * "reuse the real Lexer/Parser instead of a bespoke codec" trick [parseViewQuery] uses for a
     * whole query.
     */
    private fun parseTriggerDefinition(entry: TriggerDirectoryEntry): TriggerDefinition {
        val statements = Parser(Lexer(entry.statementText).tokenize()).parseProgram()
        val statement = statements.singleOrNull() as? CreateTriggerStatement
            ?: throw DiskCatalogException("corrupt trigger directory entry for ${entry.triggerName}")
        return TriggerDefinition(
            statement.triggerName, statement.timing, statement.event, statement.tableName, statement.whenClause, statement.body
        )
    }

    /**
     * Re-derives a CHECK constraint's [Expression] from its stored text (see [CheckConstraintEntry])
     * by wrapping it back into a throwaway, never-executed `SELECT (<expr>) FROM ...` and pulling the
     * expression back out of the parsed projection - the same "reuse the real Lexer/Parser instead of
     * a bespoke codec" trick [parseViewQuery] uses for a whole query. The FROM target is never
     * resolved against this or any catalog, so its name is arbitrary.
     */
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

    /**
     * Total pages ever allocated in the backing data file, including any now sitting on the free
     * list after a `DROP TABLE`/`DROP INDEX` (see [DiskTable.destroyStorage]/[DiskTable.dropSecondaryIndexByName]) -
     * exposed so a caller (chiefly a test) can confirm a later `CREATE TABLE`/`CREATE INDEX` reused
     * that reclaimed space instead of only ever growing the file, the same proof-by-counter
     * [DiskTable]'s own `scanCount`/`pointLookupCount`/etc. give for index pushdown.
     */
    fun allocatedPageCount(): Long = engine.allocatedPageCount()

    fun table(name: String): DiskTable {
        val key = name.lowercase()
        rowsByName[key]?.let { return it.table }
        if (viewsByName.containsKey(key)) throw DiskCatalogException("cannot modify view: $name")
        throw DiskCatalogException("no such table: $name")
    }

    fun tableOrNull(name: String): DiskTable? = rowsByName[name.lowercase()]?.table

    /**
     * `compositePrimaryKey` mirrors [kestrel.catalog.TableSchema.compositePrimaryKey]: a
     * table-level `PRIMARY KEY (a, b, ...)`, as an alternative to an inline `col TYPE PRIMARY KEY`
     * tracked by [Column.primaryKey]. Marking the listed columns NOT NULL (like the in-memory
     * [kestrel.engine.Executor] does for the same clause) is the caller's responsibility - this
     * catalog only records schema/storage, it does not rewrite the SQL-level column definitions.
     */
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

    /**
     * Registers a brand new `CREATE TEMP TABLE`/`CREATE TEMPORARY TABLE` - see [temporaryTables]'
     * own KDoc for why this never touches [engine]/[directory] at all, unlike [createTable]. Name
     * collisions are checked against every namespace a permanent table already checks against
     * ([rowsByName]/[viewsByName]) plus every other temp table, deliberately NOT allowing a temp
     * table to shadow a same-named permanent table/view the way SQLite does - see [DiskExecutor]'s
     * own resolution order for the reasoning (a single, simpler, unambiguous namespace is a
     * deliberate, narrower scope-cut for this executor).
     */
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

    /**
     * Every currently-open temp table, oldest first - used by [DiskExecutor]'s BEGIN/SAVEPOINT/
     * ROLLBACK handling to snapshot/restore live [Table.rows] lists around a disk transaction. Since
     * `CREATE`/`DROP TEMPORARY TABLE` are DDL and already rejected inside an explicit transaction
     * (see [DiskExecutor.isDdl]), the *set* of temp tables never changes mid-transaction - only their
     * row contents can - so returning the live objects (not a name list) is enough for that caller to
     * snapshot/restore without a second lookup.
     */
    val temporaryTableValues: List<Table> get() = temporaryTables.values.toList()

    /**
     * Drops a table's directory entry so it is no longer visible, and frees every page its heap
     * and indexes (primary and secondary) own back to the shared [kestrel.store.page.DiskManager]
     * free list, via [DiskTable.destroyStorage] - so a later `CREATE TABLE`/`CREATE INDEX` reuses
     * this space instead of only ever growing the data file. RESTRICT (default) is blocked by any view depending on it, directly or
     * through another view, or by any other table's FOREIGN KEY referencing it (a self-referencing
     * FK does not count - it disappears together with the table itself). [cascade] drops every
     * dependent view (transitively, diamond-safe like [dropView]) and strips - never drops - every
     * other table's FOREIGN KEY referencing this one, mirroring [kestrel.catalog.Catalog.dropTable]'s
     * `dropDependentViews`/`dropDependentForeignKeys`: CASCADE never implies dropping a table that
     * merely references the one being dropped, only the constraint pointing at it. Everything - the
     * view deletions, the table's own row, and every stripped referencing schema - commits in one
     * transaction so a mid-way failure leaves nothing half-dropped. A trigger defined on [name] is
     * always dropped alongside it, CASCADE or not - mirrors [kestrel.catalog.Catalog.dropTable]: a
     * trigger without its table makes no sense to keep around, unlike a FOREIGN KEY reference, so
     * there is nothing to RESTRICT against.
     */
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

    /**
     * RESTRICT check against every view depending on [name] - returns the transitive closure to
     * delete instead when [cascade] is set. See [dropTable]'s KDoc.
     */
    private fun dependentViewsOrThrow(name: String, cascade: Boolean): Set<String> {
        val closure = transitiveViewDependents(name.lowercase())
        if (closure.isEmpty() || cascade) return closure
        val names = closure.mapNotNull { viewsByName[it]?.entry?.viewName }
        throw DiskCatalogException("cannot drop '$name' because view(s) ${names.joinToString(", ")} depend on it")
    }

    /**
     * RESTRICT check against every other table's FOREIGN KEY referencing [name] - returns those
     * rows instead when [cascade] is set, so the caller can strip the constraint from each. See
     * [dropTable]'s KDoc.
     */
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

    /**
     * Mirrors [kestrel.catalog.Catalog.truncateTables]: wiping every row of [tableNames] makes every
     * row of any OTHER table with a FOREIGN KEY into one of them dangling at once, so RESTRICT
     * (default) blocks on that regardless of the FK's own `onDelete` action - there is no per-row
     * delete for it to act on - and [cascade] means "also truncate the whole referencing table", a
     * transitive closure over FOREIGN KEY edges computed exactly like [dependentForeignKeysOrThrow]'s
     * single-hop version, never a per-row [DiskForeignKeyEnforcer.cascadeDelete]-style plan. Every
     * table in the closure is emptied against the SAME [txn] the caller already opened (see
     * [DiskExecutor.executeTruncate]), so it shares that statement's commit/rollback exactly like a
     * WHERE-less DELETE does - unlike RESTART IDENTITY's sequence reset, which
     * [DiskExecutor.executeTruncate] deliberately runs outside any transaction, mirroring [nextValue]'s
     * own already-non-transactional sequence position.
     */
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

    /**
     * Appends a new column (already validated by [DiskExecutor]) to every existing row - mirrors
     * [kestrel.catalog.Catalog.addColumn]. [valueForRow] backfills every pre-existing row (each
     * called with that row's OLD, pre-append values, then [DiskTable.rewriteRows]-transformed to
     * carry the result) - a constant lambda for a plain DEFAULT, or a fresh per-row expression
     * evaluation for GENERATED ALWAYS AS; a table with zero rows just gets the new schema with
     * nothing to rewrite.
     */
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

    /**
     * `ALTER TABLE t ALTER COLUMN col TYPE ...` - mirrors [kestrel.catalog.Catalog.changeColumnType],
     * built on [DiskTable.rewriteRows] exactly like [addColumn] rather than [addColumn]'s own
     * `List<Value> -> Value` append shape, since this replaces an EXISTING position instead of
     * appending a new one. Unlike the in-memory engine's [kestrel.engine.Executor], which validates
     * every row up front and only ever calls its own `Catalog.changeColumnType` once every row is
     * already known-good, [valueForRow] here is BOTH the value-computation AND the NOT NULL/CHECK
     * validation step (see [DiskExecutor.executeChangeColumnType]) - a failure partway through
     * throws out of [DiskTable.rewriteRows]'s loop, and the `catch` below aborts the transaction,
     * unwinding every row already rewritten. Same "validate lazily, rely on abort to undo a partial
     * per-row rewrite" contract [addColumn] already leans on for a NOT NULL `GENERATED ALWAYS AS`
     * backfill.
     */
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

    /**
     * RESTRICT (default) rejects the drop if [columnName] is used by another table's FOREIGN KEY,
     * this table's own FOREIGN KEY, a UNIQUE constraint, a CHECK constraint, or a secondary index -
     * CASCADE removes all of those alongside the column itself. Mirrors
     * [kestrel.catalog.Catalog.dropColumn], including that a PRIMARY KEY column can never be
     * dropped, with or without CASCADE.
     */
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

        // Mirrors kestrel.catalog.Catalog.dropColumn: a GENERATED column can only ever read this
        // table's own non-GENERATED columns, so dropping columnName orphans any GENERATED column
        // depending on it exactly like a CHECK constraint referencing it - CASCADE drops it too,
        // RESTRICT blocks the whole drop instead. GENERATED can never be a PRIMARY KEY column, so
        // there is no "cascaded into dropping the PRIMARY KEY" case to guard against here.
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

    /**
     * Always fully propagates rather than being rejected the way [dropColumn] can be - mirrors
     * [kestrel.catalog.Catalog.renameColumn]: this table's own PRIMARY KEY/FOREIGN KEY/UNIQUE/CHECK
     * definitions, any other table's FOREIGN KEY referencing this column, and any secondary index
     * defined on it (see [DiskTable.renameColumnAndIndexes]) all get the new name.
     */
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
            // A GENERATED column can only ever reference this table's own columns, so renaming one
            // of them must rewrite that reference too - mirrors kestrel.catalog.Catalog.renameColumn.
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

    /**
     * Renames a table and propagates the identifier everywhere it is used by name - mirrors
     * [kestrel.catalog.Catalog.renameTable]: this table's own FOREIGN KEY(s) that self-reference it,
     * any other table's FOREIGN KEY pointing at it, every view whose defining query mentions it
     * anywhere (see [RelationRename]), and every trigger defined ON it (just the [TriggerDefinition.
     * tableName] field, re-rendered and re-persisted - trigger body statements are NOT rewritten,
     * same documented scope-cut as [kestrel.catalog.Catalog.renameTable]). Unlike the in-memory
     * engine there is no separate global index map to fix up - every secondary index lives inside its
     * own [DiskTable], keyed by column name, which a table rename never touches.
     */
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

    /**
     * `ALTER TABLE t ADD CONSTRAINT name FOREIGN KEY (...) REFERENCES ...` - mirrors
     * [kestrel.catalog.Catalog.addForeignKey]: [kestrel.store.sql.DiskExecutor.executeAddConstraint]
     * has already validated every existing row against [foreignKey] before calling this, so installing
     * it is pure schema metadata - [DiskTable.replaceSchemaMetadata] plus a re-persisted directory
     * entry, no row rewrite (row shape is unchanged) and no index to build.
     */
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

    /** [addForeignKey]'s counterpart for `ADD CONSTRAINT name UNIQUE (...)` - see its own doc for the validate-before-install contract. */
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

    /** [addForeignKey]'s counterpart for `ADD CONSTRAINT name CHECK (...)` - see its own doc for the validate-before-install contract. */
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

    /**
     * `ALTER TABLE t DROP CONSTRAINT name [RESTRICT | CASCADE]` - mirrors
     * [kestrel.catalog.Catalog.dropConstraint]: finds a named CHECK, UNIQUE, or FOREIGN KEY
     * constraint on [tableName] (case-insensitive; a PRIMARY KEY has no name to match against) and
     * removes it. Only a UNIQUE constraint has cross-table fallout - see [dropUniqueConstraint].
     */
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

    /**
     * Mirrors [kestrel.catalog.Catalog.dropUniqueConstraint]: a FOREIGN KEY's `referencedColumns`
     * must exactly match one of the parent's enforced-unique key groups to have been accepted in the
     * first place, so dropping a UNIQUE constraint can silently invalidate any FOREIGN KEY (on this
     * table itself, via a self-reference, or on any other table) that leans on exactly this column
     * set for its uniqueness guarantee, UNLESS another remaining group still covers it. RESTRICT
     * (default) blocks the drop when a real dependent exists; CASCADE strips that FOREIGN KEY right
     * along with it, mirroring [dropTable]'s own FOREIGN KEY handling.
     */
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
            // Re-fetched: a self-referencing FOREIGN KEY on tableName itself may have just been
            // rewritten above (dependents is not restricted to OTHER tables).
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

    /** Convenience for a single-column secondary index; delegates to the general, composite-capable overload below. */
    fun createIndex(tableName: String, indexName: String, columnName: String, unique: Boolean = false): SecondaryIndexHandle =
        createIndex(tableName, indexName, listOf(columnName), unique)

    /**
     * Creates a secondary index over [columnNames] (more than one entry for a composite,
     * multi-column index) - see [SecondaryIndexHandle]/[DiskTable] for how a composite key is
     * encoded and how uniqueness is enforced.
     */
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

    /**
     * Drops a secondary index by name, searched across every table since `DROP INDEX` names only
     * the index, not its owning table - mirrors [kestrel.catalog.Catalog.dropIndex]'s flat,
     * table-less namespace. Unlike [dropColumn]'s CASCADE-guarded removal, this never needs a
     * dependency check: nothing in this schema (FOREIGN KEY/CHECK/UNIQUE constraint) ever
     * references an index by name, only by the columns it happens to cover, so
     * [kestrel.catalog.Catalog.dropIndex] never needed one either. Frees the dropped B+Tree's own
     * pages back to the shared free list via [DiskTable.dropSecondaryIndexByName], the same
     * reclamation [dropTable] does for a whole table's storage.
     */
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

    /**
     * Persists a view's defining [query] as rendered SQL text (see [ViewDirectoryEntry]) - mirrors
     * [kestrel.catalog.Catalog.createView]'s name-collision rules (a view and a table can never
     * share a name, `orReplace` only waives the view-already-exists check).
     */
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

    /**
     * Drops a view, blocked (RESTRICT, the default) by any other view depending on it - directly or
     * transitively through a chain of views - unless [cascade] is set, in which case the whole
     * transitive closure of dependents is dropped alongside it. Mirrors
     * [kestrel.catalog.Catalog.dropView]/`dropDependentViews`'s "diamond-safe" ordering: the closure
     * is computed once, up front, before anything is removed.
     */
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

    /**
     * Every view depending on [rootKey] (already lowercase), directly or through another view -
     * [rootKey] itself may name a table or a view, [DependencyAnalysis] does not care which.
     * Identical fixed-point walk to [kestrel.catalog.Catalog.transitiveViewDependents].
     */
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

    // ---- SEQUENCE ----

    /**
     * Rebuilds a live [Sequence] from its persisted definition plus whatever [SequenceDirectoryEntry.
     * positionPageId] currently holds - the exact `(value, called)` pair [Sequence.snapshotPosition]
     * last wrote there, so reload reproduces the live instance's internal state precisely rather than
     * approximating it (see [Sequence.snapshotPosition]'s own doc for why [Sequence.peekNext] is the
     * wrong thing to persist this way).
     */
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

    /**
     * Sequence names deliberately live in their own namespace here, same as
     * [kestrel.catalog.Catalog.createSequence] - no collision check against [rowsByName]/[viewsByName].
     */
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

    /**
     * Frees the dropped sequence's [SequenceDirectoryEntry.positionPageId] back to the shared page
     * free list via [StorageEngine.freeRawPage], outside the directory-row transaction - like every
     * other read/write of that page (see [writeSequencePosition]), freeing it is deliberately not
     * part of the transactional/WAL-logged path, so an abort of surrounding SQL never resurrects a
     * page already handed back to the free list.
     */
    fun dropSequence(name: String) {
        val key = name.lowercase()
        val row = sequencesByName[key] ?: throw DiskCatalogException("no such sequence: $name")
        val txn = engine.beginTransaction()
        directory.delete(txn, row.rid)
        engine.commit(txn)
        engine.freeRawPage(row.entry.positionPageId)
        sequencesByName.remove(key)
    }

    /**
     * Mirrors [kestrel.catalog.Catalog.alterSequence]: [Sequence.altered] computes the position ALTER
     * SEQUENCE leaves the counter at (carried over unchanged, or reset by `RESTART`, see [Sequence]'s
     * own doc) purely in memory first. Only once the transactional definition update in [directory]
     * has actually committed is that new position written to the raw counter page - so a failure
     * building or persisting the new [SequenceDirectoryEntry] (e.g. the sequence vanished from under
     * a concurrent caller) can never leave the on-disk counter reflecting an ALTER whose definition
     * change did not itself survive.
     */
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

    /**
     * Advances [name] and durably persists the new position immediately via
     * [StorageEngine.writeRawPage][kestrel.store.engine.StorageEngine.writeRawPage] - deliberately
     * outside of any [beginTransaction]/[commit]/[abort], so the advance survives even a ROLLBACK of
     * whichever statement's transaction called it (see [SequenceDirectoryEntry]'s own doc). Guarded
     * by a lock on this catalog instance rather than [kestrel.store.txn.LockManager] (which the raw
     * counter page never goes through at all) - the one piece of this catalog actually exercised by
     * more than one thread at a time in this codebase (see the concurrent-session BEGIN/COMMIT test),
     * so two overlapping NEXTVAL calls on the same sequence must not race on [Sequence.next]'s plain,
     * unsynchronized `var`s.
     */
    fun nextValue(name: String): Long = synchronized(this) {
        val row = sequencesByName[name.lowercase()] ?: throw DiskCatalogException("no such sequence: $name")
        val value = row.sequence.next()
        writeSequencePosition(row.entry.positionPageId, row.sequence.snapshotPosition())
        value
    }

    // ---- TRIGGER ----

    val triggerNames: List<String> get() = triggersByName.values.map { it.entry.triggerName }

    /** Every trigger definition, in creation order - mirrors [kestrel.catalog.Catalog.triggerDefinitions]. */
    fun triggerDefinitions(): List<TriggerDefinition> = triggersByName.values.map { it.definition }

    /** Whether any trigger (any timing/event) is defined ON [tableName] - mirrors [kestrel.catalog.Catalog.hasTriggers]. */
    fun hasTriggers(tableName: String): Boolean = triggersByName.values.any { it.definition.tableName.equals(tableName, ignoreCase = true) }

    /** Every trigger registered for ([tableName], [timing], [event]), in creation order - mirrors [kestrel.catalog.Catalog.triggersOn]. */
    fun triggersOn(tableName: String, timing: TriggerTiming, event: TriggerEvent): List<TriggerDefinition> =
        triggersByName.values.filter {
            it.definition.tableName.equals(tableName, ignoreCase = true) && it.definition.timing == timing && it.definition.event == event
        }.map { it.definition }

    /**
     * Persists [definition] as rendered `CREATE TRIGGER ...;` text (see [TriggerDirectoryEntry]) -
     * mirrors [kestrel.catalog.Catalog.createTrigger]'s name-collision rule. Every other eager check
     * (the trigger's own table and every body statement's target table exist, the WHEN clause shape)
     * is [DiskExecutor]'s responsibility, same split as [createView] versus [DiskExecutor.
     * executeCreateView].
     */
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
