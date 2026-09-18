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
    val default: Expression? = null,
    val generated: Expression? = null,
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

data class ForeignKeyConstraint(
    val columns: List<String>,
    val referencedTable: String,
    val referencedColumns: List<String>,
    val onDelete: ForeignKeyAction = ForeignKeyAction.NoAction,
    val onUpdate: ForeignKeyAction = ForeignKeyAction.NoAction,
    val name: String? = null
)

data class CheckConstraint(val name: String?, val expression: Expression)

data class UniqueConstraint(val name: String?, val columns: List<String>)

class TableSchema(
    val name: String,
    val columns: List<Column>,
    val foreignKeys: List<ForeignKeyConstraint> = emptyList(),
    val checks: List<CheckConstraint> = emptyList(),
    val uniqueConstraints: List<UniqueConstraint> = emptyList(),
    val compositePrimaryKey: List<String> = emptyList(),
    val temporary: Boolean = false
) {
    private val indexByName: Map<String, Int> =
        columns.withIndex().associate { (index, column) -> column.name.lowercase() to index }

    val columnNames: List<String> get() = columns.map { it.name }

    fun columnIndex(columnName: String): Int? = indexByName[columnName.lowercase()]

    fun column(columnName: String): Column? = columnIndex(columnName)?.let { columns[it] }

    val primaryKeyColumns: List<String> get() =
        compositePrimaryKey.ifEmpty { columns.filter { it.primaryKey }.map { it.name } }

    val primaryKeyIndices: List<Int> get() = primaryKeyColumns.map { columnIndex(it)!! }

    val primaryKeyIndex: Int? get() = primaryKeyIndices.singleOrNull()

    fun enforcedUniqueKeyGroups(): List<List<String>> {
        val groups = mutableListOf<List<String>>()
        if (primaryKeyColumns.isNotEmpty()) groups.add(primaryKeyColumns)
        for (unique in uniqueConstraints) groups.add(unique.columns)
        return groups
    }

    fun hasEnforcedUniqueness(columnNames: List<String>): Boolean {
        val target = columnNames.map { it.lowercase() }.toSet()
        if (target.size != columnNames.size) return false
        return enforcedUniqueKeyGroups().any { group -> group.map { it.lowercase() }.toSet() == target }
    }
}
