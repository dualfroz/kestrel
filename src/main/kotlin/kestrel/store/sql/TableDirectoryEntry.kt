package kestrel.store.sql

import kestrel.catalog.CheckConstraint
import kestrel.catalog.Column
import kestrel.catalog.ForeignKeyConstraint
import kestrel.catalog.UniqueConstraint
import kestrel.persistence.SqlWriter

/**
 * One secondary index recorded against a table's directory entry - [columnNames] has more than
 * one entry for a composite (multi-column) index. [unique] mirrors a `CREATE UNIQUE INDEX`:
 * [DiskTable] rejects an insert/update that would give two rows the same non-null tuple of
 * values across [columnNames], the same way it already does for the primary key.
 */
data class SecondaryIndexEntry(val name: String, val columnNames: List<String>, val rootPageId: Long, val unique: Boolean = false)

/**
 * The durable form of a [CheckConstraint]: its expression rendered to SQL text by
 * [SqlWriter.expressionText] rather than a bespoke expression codec - mirrors [ViewDirectoryEntry]
 * storing a view's query as `CREATE VIEW ... AS ...;` text, for the same reason (a CHECK expression
 * can nest arbitrarily deep, so a dedicated binary codec would have to mirror the whole [kestrel.sql.ast.Expression]
 * hierarchy). [DiskCatalog] re-derives the [Expression][kestrel.sql.ast.Expression] on load by parsing
 * it back through the same [kestrel.sql.lexer.Lexer]/[kestrel.sql.parser.Parser] pair every other SQL
 * entry point uses.
 */
data class CheckConstraintEntry(val name: String?, val expressionText: String) {
    companion object {
        fun from(check: CheckConstraint): CheckConstraintEntry = CheckConstraintEntry(check.name, SqlWriter.expressionText(check.expression))
    }
}

/**
 * The durable, on-disk description of one table: its schema plus every page
 * id needed to reopen its storage after a process restart. [DiskCatalog]
 * keeps one of these per table in its directory heap, re-encoding it (via
 * [DirectoryEntryCodec]) whenever a page id it references changes - most
 * often [primaryKeyIndexRootPageId] or a [SecondaryIndexEntry.rootPageId]
 * after a B+Tree split or merge moves the tree's root.
 */
data class TableDirectoryEntry(
    val tableName: String,
    val columns: List<Column>,
    val dataHeaderPageId: Long,
    val primaryKeyIndexRootPageId: Long?,
    val secondaryIndexes: List<SecondaryIndexEntry>,
    /**
     * A table-level `PRIMARY KEY (a, b, ...)`, empty when the table instead declares its
     * PRIMARY KEY inline on a single [Column] (tracked by [Column.primaryKey]) - mirrors
     * [kestrel.catalog.TableSchema.compositePrimaryKey].
     */
    val compositePrimaryKey: List<String> = emptyList(),
    /**
     * Table-level or inline `UNIQUE`/`UNIQUE (a, b, ...)` constraints, mirroring
     * [kestrel.catalog.TableSchema.uniqueConstraints]. Unlike [secondaryIndexes] these are not
     * backed by a B+Tree - [DiskTable] enforces them with a full heap scan per write, exactly like
     * [kestrel.engine.Executor] does for the in-memory engine, which is what lets a single
     * constraint span more than one column without a composite-key index format.
     */
    val uniqueConstraints: List<UniqueConstraint> = emptyList(),
    /** Table-level or column-level `CHECK (...)` constraints, mirroring [kestrel.catalog.TableSchema.checks]. */
    val checks: List<CheckConstraintEntry> = emptyList(),
    /**
     * Column-level `REFERENCES ...` or table-level `FOREIGN KEY (...) REFERENCES ...` constraints,
     * mirroring [kestrel.catalog.TableSchema.foreignKeys]. Stored as plain typed fields (unlike
     * [CheckConstraintEntry]) since [ForeignKeyConstraint] holds no arbitrary expression to render.
     */
    val foreignKeys: List<ForeignKeyConstraint> = emptyList()
)
