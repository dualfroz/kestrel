package kestrel.catalog

import kestrel.sql.ast.Expression
import kestrel.storage.Collation
import kestrel.storage.ColumnType

data class Column(
    val name: String,
    val type: ColumnType,
    val notNull: Boolean,
    val primaryKey: Boolean,
    val precision: Int? = null,
    val scale: Int? = null,
    /**
     * A constant-only DEFAULT expression (validated by the executor to contain no column
     * reference, subquery, or aggregate), used to fill a column omitted from an INSERT's column
     * list and to backfill existing rows when the column is added later via ALTER TABLE.
     */
    val default: Expression? = null,
    /**
     * `GENERATED ALWAYS AS (expression) STORED` - recomputed from this row's OTHER column values
     * on every INSERT/UPDATE (the executor rejects an explicit value for this column outright,
     * matching standard SQL). Mutually exclusive with [default] and never itself references
     * another GENERATED column (validated by the executor at CREATE TABLE time, both engines) -
     * see [kestrel.engine.Executor.validateGeneratedDefinitions].
     */
    val generated: Expression? = null,
    /**
     * `col TYPE COLLATE name` - the collating sequence a bare reference to this column compares
     * with when the query itself writes no explicit `COLLATE` (see [kestrel.sql.ast.CollateExpression],
     * which always wins over this when present). Threaded through [kestrel.engine.ColumnResolver.declaredCollation]
     * by every resolver built directly over a table's own schema (both engines' row/EXCLUDED/MERGE
     * resolvers, [kestrel.engine.SelectRunner]'s per-source column lookup) so `WHERE`/`IN`/`BETWEEN`/
     * a searched-`CASE` operand honor it automatically - a resolver with no such schema to consult
     * (a set operation's output row, a trigger's NEW/OLD side falling through to its own schema
     * notwithstanding) defaults to [Collation.Binary] via [kestrel.engine.ColumnResolver]'s own
     * default method, exactly like a column never had one declared.
     */
    val collation: Collation = Collation.Binary
)

enum class ForeignKeyAction { NoAction, Restrict, Cascade, SetNull }

val ForeignKeyAction.sqlKeyword: String
    get() = when (this) {
        ForeignKeyAction.NoAction -> "NO ACTION"
        ForeignKeyAction.Restrict -> "RESTRICT"
        ForeignKeyAction.Cascade -> "CASCADE"
        ForeignKeyAction.SetNull -> "SET NULL"
    }

/**
 * `referencedColumns` is required to carry an enforced uniqueness guarantee in the referenced
 * table as a group - either it is exactly the table's PRIMARY KEY (single-column or composite),
 * or it is named (as an exact set) by one of that table's [UniqueConstraint]s. Those are the only
 * two ways this engine guarantees a set of columns has no duplicate value combination to safely
 * match a foreign key against. `columns`/`referencedColumns` always have the same size (validated
 * at CREATE TABLE time) and are positionally paired: `columns[i]` corresponds to
 * `referencedColumns[i]`.
 */
data class ForeignKeyConstraint(
    val columns: List<String>,
    val referencedTable: String,
    val referencedColumns: List<String>,
    val onDelete: ForeignKeyAction = ForeignKeyAction.NoAction,
    val onUpdate: ForeignKeyAction = ForeignKeyAction.NoAction,
    /** `CONSTRAINT name FOREIGN KEY (...)` - see [CheckConstraint.name]/[UniqueConstraint.name]'s own doc. */
    val name: String? = null
)

data class CheckConstraint(val name: String?, val expression: Expression)

/**
 * A UNIQUE constraint independent of the table's PRIMARY KEY. `columns` holds one entry for an
 * inline `col TYPE UNIQUE` column definition, or several for a table-level `UNIQUE (a, b, ...)`.
 * Per standard SQL, a row where any of these columns is NULL is exempt from the check (NULLs
 * are never considered duplicates of each other).
 */
data class UniqueConstraint(val name: String?, val columns: List<String>)

class TableSchema(
    val name: String,
    val columns: List<Column>,
    val foreignKeys: List<ForeignKeyConstraint> = emptyList(),
    val checks: List<CheckConstraint> = emptyList(),
    val uniqueConstraints: List<UniqueConstraint> = emptyList(),
    /**
     * Table-level `PRIMARY KEY (a, b, ...)`. Empty when the table instead declares its PRIMARY KEY
     * inline on a single column (`col TYPE PRIMARY KEY`, tracked by [Column.primaryKey]) - see
     * [primaryKeyColumns], which unifies both spellings into one canonical list.
     */
    val compositePrimaryKey: List<String> = emptyList(),
    /**
     * `CREATE TEMP TABLE`/`CREATE TEMPORARY TABLE` - a table exactly like any other at runtime
     * (same constraints, indexes, triggers all work), except [kestrel.persistence.SqlWriter] never
     * writes it (or its rows, or any index/trigger defined on it) to a checkpoint or the redo log,
     * so it does not survive a process restart, matching Postgres. Every place in [Catalog] that
     * rebuilds a [TableSchema] from an existing one's fields (ADD/DROP/RENAME COLUMN, RENAME TABLE,
     * a referencing table's FOREIGN KEY cleanup) must carry this flag forward explicitly - there is
     * no `copy()` to do it automatically, since this stays a plain class, not a data class.
     */
    val temporary: Boolean = false
) {
    private val indexByName: Map<String, Int> =
        columns.withIndex().associate { (index, column) -> column.name.lowercase() to index }

    val columnNames: List<String> get() = columns.map { it.name }

    fun columnIndex(columnName: String): Int? = indexByName[columnName.lowercase()]

    fun column(columnName: String): Column? = columnIndex(columnName)?.let { columns[it] }

    /**
     * The table's PRIMARY KEY column names in declared order, regardless of whether it came from
     * an inline `col TYPE PRIMARY KEY` (exactly one [Column] with `primaryKey = true`) or a
     * table-level `PRIMARY KEY (a, b, ...)` clause ([compositePrimaryKey]). Empty when the table
     * has no PRIMARY KEY at all.
     */
    val primaryKeyColumns: List<String> get() =
        compositePrimaryKey.ifEmpty { columns.filter { it.primaryKey }.map { it.name } }

    val primaryKeyIndices: List<Int> get() = primaryKeyColumns.map { columnIndex(it)!! }

    /** Convenience for callers that only ever deal with single-column PRIMARY KEYs. */
    val primaryKeyIndex: Int? get() = primaryKeyIndices.singleOrNull()

    /**
     * Every column group in this table with a guaranteed-unique combination of values: the
     * PRIMARY KEY (if any, single-column or composite) plus each UNIQUE constraint. A valid
     * FOREIGN KEY target must match one of these groups exactly (as a set of names).
     */
    fun enforcedUniqueKeyGroups(): List<List<String>> {
        val groups = mutableListOf<List<String>>()
        if (primaryKeyColumns.isNotEmpty()) groups.add(primaryKeyColumns)
        for (unique in uniqueConstraints) groups.add(unique.columns)
        return groups
    }

    /** True when [columnNames], taken together as a set, exactly matches one enforced-unique key group. */
    fun hasEnforcedUniqueness(columnNames: List<String>): Boolean {
        val target = columnNames.map { it.lowercase() }.toSet()
        if (target.size != columnNames.size) return false
        return enforcedUniqueKeyGroups().any { group -> group.map { it.lowercase() }.toSet() == target }
    }
}
