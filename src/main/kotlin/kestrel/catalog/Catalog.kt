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
    /**
     * A reference copy of the map, not a deep copy of each [Sequence] - see [Sequence]'s own doc
     * for why that is exactly what makes ROLLBACK undo CREATE/DROP/ALTER SEQUENCE while leaving a
     * NEXTVAL advance in place.
     */
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
        // A trigger without its table makes no sense to keep around - unlike a FOREIGN KEY
        // reference, nothing else depends on it existing, so this always happens, CASCADE or not.
        triggers.entries.removeAll { it.value.tableName.equals(tableName, ignoreCase = true) }
    }

    /**
     * Mirrors Postgres semantics: a FOREIGN KEY pointing at [tableName] blocks the drop under
     * RESTRICT, while CASCADE only drops the constraint itself (the referencing table and its
     * rows survive) - dropping a table never implies dropping every table that happens to
     * reference it. A self-referencing FK is excluded because it disappears together with the
     * table being dropped, with nothing left dangling in any other table.
     */
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

    /**
     * TRUNCATE's own dependency handling, deliberately NOT built on [dropDependentForeignKeys] or
     * `ForeignKeyEnforcer.cascadeDelete`: wiping every row of [tableNames] makes EVERY row of a
     * table with a FOREIGN KEY into any of them dangling at once, not just rows that happened to
     * match specific parent rows about to disappear one at a time - so RESTRICT (default) blocks on
     * any other table's FOREIGN KEY referencing one of [tableNames] regardless of that FK's own
     * `onDelete` action (real TRUNCATE never consults it - there is no per-row delete to run it
     * against), and [cascade] means "also truncate the whole referencing table", computed as a
     * transitive closure exactly like [dropDependentViews]'s [transitiveViewDependents], not a
     * per-row plan/apply cascade. A self-reference (a table whose own FOREIGN KEY points at itself,
     * or two requested tables that reference each other) never blocks or duplicates, since every
     * requested table starts in the closure before the first pass over `tables` even runs.
     *
     * Returns every table actually truncated - [tableNames] plus, under CASCADE, every transitive
     * referencer - in an unspecified order, so the caller can report and (for RESTART IDENTITY)
     * restart sequences for all of them, not just the ones named in the statement.
     */
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

    /**
     * `ALTER TABLE t ALTER COLUMN col TYPE ...` - replaces an EXISTING column's declared
     * [Column.type]/[Column.precision]/[Column.scale] in place, keeping its name/position/
     * notNull/default/generated/collation untouched. Unlike [addColumn]'s per-row `valueForRow`
     * callback, [newRows] arrives already fully built by [kestrel.engine.Executor] - every row's
     * `USING` (or implicit "recompute the column as its own old value") expression evaluated and
     * coerced, and every NOT NULL/CHECK re-validated against the NEW schema - BEFORE this is ever
     * called, so this method never observes a row that fails validation partway through: either
     * all of [newRows] is already known-good and gets installed atomically, or the caller never
     * reaches this method at all.
     */
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

    /**
     * RESTART IDENTITY has no explicit "sequence OWNED BY column" catalog entry to consult (Postgres
     * has one; this engine does not) - a column counts as identity-backed exactly when its own
     * DEFAULT is a bare `NEXTVAL('seq')`, the same structural signal `SqlWriter`/`executeAddColumn`
     * already treat as "this column is an auto-increment". A dangling reference to an
     * already-dropped sequence (DROP SEQUENCE has no dependent-column check of its own) is silently
     * skipped rather than thrown, matching how the rest of this engine only ever discovers that gap
     * lazily, at the next NEXTVAL/INSERT.
     */
    private fun restartOwnedSequences(table: Table) {
        for (column in table.schema.columns) {
            val default = column.default
            if (default is NextValueExpression) {
                val sequence = sequenceOrNull(default.sequenceName) ?: continue
                sequences[default.sequenceName.lowercase()] = sequence.altered(sequence.definition, restartAt = sequence.definition.startValue)
            }
        }
    }

    /**
     * Appends a new column (already validated by the executor) to every existing row.
     * [valueForRow] computes the new column's value from each row's OLD (pre-append) values - a
     * constant lambda for a plain DEFAULT, or a fresh per-row expression evaluation for GENERATED
     * ALWAYS AS, mirroring how INSERT already treats those two cases differently.
     */
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

    /**
     * RESTRICT (default) rejects the drop if [columnName] is used by another table's FOREIGN KEY,
     * this table's own FOREIGN KEY, a UNIQUE constraint, a CHECK constraint, or an index - CASCADE
     * removes all of those alongside the column itself. Dropping a PRIMARY KEY column is never
     * allowed, with or without CASCADE: this engine has no standalone "DROP CONSTRAINT", so
     * redesign the PRIMARY KEY (or drop the table) instead.
     */
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

        // A GENERATED column can only ever read this table's own non-GENERATED columns (never
        // another GENERATED one - validateGeneratedDefinitions forbids that chain), so dropping
        // columnName orphans any GENERATED column whose expression reads it, exactly like a CHECK
        // constraint referencing it - it must be dropped right along with columnName (RESTRICT
        // blocks the whole drop instead, same as every other dependent kind below). GENERATED can
        // never be a PRIMARY KEY column (Executor.validateGeneratedDefinitions), so there is no
        // "cascaded into dropping the PRIMARY KEY" case to guard against here.
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

    /**
     * Always fully propagates rather than being rejected the way [dropColumn] can be: the
     * identifier is rewritten everywhere it is used by name rather than removed, so nothing
     * becomes newly invalid - this table's own PRIMARY KEY/FOREIGN KEY/UNIQUE/CHECK definitions,
     * any other table's FOREIGN KEY referencing this column, and any index defined on it.
     */
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
            // A GENERATED column can only ever reference this table's own columns, so renaming one
            // of them must rewrite that reference too, exactly like a CHECK constraint's own
            // reference to it (below) - otherwise the GENERATED expression would silently keep
            // pointing at a name that no longer exists.
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

    /**
     * Renames a table and propagates the identifier everywhere it is used by name, mirroring
     * [renameColumn]'s "always fully propagates" contract: this table's own FOREIGN KEY(s) that
     * self-reference it, any other table's FOREIGN KEY pointing at it, every index defined on it,
     * and every view whose defining query mentions it anywhere (FROM/JOIN, derived tables, CTEs,
     * set operations, scalar/EXISTS/IN subqueries - see [RelationRename]).
     */
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

    /**
     * `ALTER TABLE t ADD CONSTRAINT name FOREIGN KEY (...) REFERENCES ...` - installs a FOREIGN KEY
     * onto an already-existing table. Unlike [createTable]'s FOREIGN KEY definitions, this never
     * touches existing rows itself: [kestrel.engine.Executor.executeAddConstraint] validates every
     * existing row against [foreignKey] (via [kestrel.engine.ForeignKeyEnforcer.validateRow]) BEFORE
     * calling this, so by the time the schema actually changes the constraint is already known to
     * hold for every row already in the table.
     */
    fun addForeignKey(tableName: String, foreignKey: ForeignKeyConstraint) {
        val table = table(tableName)
        val schema = table.schema
        val newSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys + foreignKey, schema.checks,
            schema.uniqueConstraints, schema.compositePrimaryKey, schema.temporary
        )
        replaceTable(tableName, newSchema, table.rows)
    }

    /** [addForeignKey]'s counterpart for `ADD CONSTRAINT name UNIQUE (...)` - see its own doc for the validate-before-install contract. */
    fun addUniqueConstraint(tableName: String, unique: UniqueConstraint) {
        val table = table(tableName)
        val schema = table.schema
        val newSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys, schema.checks,
            schema.uniqueConstraints + unique, schema.compositePrimaryKey, schema.temporary
        )
        replaceTable(tableName, newSchema, table.rows)
    }

    /** [addForeignKey]'s counterpart for `ADD CONSTRAINT name CHECK (...)` - see its own doc for the validate-before-install contract. */
    fun addCheckConstraint(tableName: String, check: CheckConstraint) {
        val table = table(tableName)
        val schema = table.schema
        val newSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys, schema.checks + check,
            schema.uniqueConstraints, schema.compositePrimaryKey, schema.temporary
        )
        replaceTable(tableName, newSchema, table.rows)
    }

    /**
     * `ALTER TABLE t DROP CONSTRAINT name [RESTRICT | CASCADE]` - finds a named CHECK, UNIQUE, or
     * FOREIGN KEY constraint on [tableName] (case-insensitive) and removes it. A PRIMARY KEY is
     * never a match: it has no name to compare against (see [dropColumn]'s own doc on why
     * redesigning the PRIMARY KEY, or dropping the table, is the only way to get rid of one), so a
     * name that happens to target one simply falls through to "no such constraint" like any other
     * unknown name. Only a UNIQUE constraint has cross-table fallout worth a RESTRICT/CASCADE
     * decision - see [dropUniqueConstraint].
     */
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

    /**
     * A FOREIGN KEY's `referencedColumns` must exactly match one of the parent's enforced-unique
     * key groups ([TableSchema.enforcedUniqueKeyGroups] - PRIMARY KEY or a UNIQUE constraint) to
     * have been accepted in the first place, so dropping a UNIQUE constraint can silently invalidate
     * any FOREIGN KEY (on this table itself, via a self-reference, or on any other table) that leans
     * on exactly this column set for its uniqueness guarantee - UNLESS another remaining group
     * (another UNIQUE constraint over the same columns, or the PRIMARY KEY) still covers it, in
     * which case nothing actually depends on THIS particular constraint continuing to exist.
     * RESTRICT (default) blocks the drop when a real dependent exists; CASCADE drops that FOREIGN
     * KEY right along with it, mirroring [dropDependentForeignKeys].
     */
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

    fun tableOrNull(tableName: String): Table? = tables[tableName.lowercase()]

    val tableNames: List<String> get() = tables.values.map { it.schema.name }

    fun createIndex(definition: IndexDefinition) {
        val key = definition.name.lowercase()
        if (indexes.containsKey(key)) {
            throw KestrelException("index '${definition.name}' already exists")
        }
        val table = table(definition.tableName)
        if (table.schema.columnIndex(definition.columnName) == null) {
            throw KestrelException("no such column: ${definition.columnName} in table ${definition.tableName}")
        }
        val index = SortedIndex(definition)
        indexes[key] = index
        reindexOne(table, index)
    }

    fun dropIndex(indexName: String) {
        if (indexes.remove(indexName.lowercase()) == null) {
            throw KestrelException("no such index: $indexName")
        }
    }

    fun indexOn(tableName: String, columnName: String): SortedIndex? = indexes.values.firstOrNull {
        it.definition.tableName.equals(tableName, ignoreCase = true) &&
            it.definition.columnName.equals(columnName, ignoreCase = true)
    }

    val indexNames: List<String> get() = indexes.values.map { it.definition.name }

    fun indexDefinitions(): List<IndexDefinition> = indexes.values.map { it.definition }

    fun reindexTable(tableName: String) {
        val table = table(tableName)
        indexes.values
            .filter { it.definition.tableName.equals(tableName, ignoreCase = true) }
            .forEach { reindexOne(table, it) }
    }

    fun snapshot(): CatalogSnapshot = CatalogSnapshot(
        tables = tables.mapValues { (_, table) -> table.schema to table.rows.toList() },
        indexDefinitions = indexes.values.map { it.definition }
    )

    fun restore(snapshot: CatalogSnapshot) {
        tables.clear()
        indexes.clear()
        for ((key, image) in snapshot.tables) {
            val table = Table(image.first)
            table.rows.addAll(image.second)
            tables[key] = table
        }
        for (definition in snapshot.indexDefinitions) {
            val index = SortedIndex(definition)
            indexes[definition.name.lowercase()] = index
            reindexOne(tables.getValue(definition.tableName.lowercase()), index)
        }
    }

    private fun reindexOne(table: Table, index: SortedIndex) {
        index.clear()
        val columnIndex = table.schema.columnIndex(index.definition.columnName)!!
        table.rows.forEachIndexed { rowIndex, row -> index.add(row.values[columnIndex], rowIndex) }
    }
}
