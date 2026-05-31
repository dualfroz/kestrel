package kestrel.catalog

import kestrel.storage.ColumnType

data class Column(
    val name: String,
    val type: ColumnType,
    val notNull: Boolean,
    val primaryKey: Boolean
)

class TableSchema(val name: String, val columns: List<Column>) {
    private val indexByName: Map<String, Int> =
        columns.withIndex().associate { (index, column) -> column.name.lowercase() to index }

    val columnNames: List<String> get() = columns.map { it.name }

    fun columnIndex(columnName: String): Int? = indexByName[columnName.lowercase()]

    fun column(columnName: String): Column? = columnIndex(columnName)?.let { columns[it] }

    val primaryKeyIndex: Int? get() = columns.indexOfFirst { it.primaryKey }.takeIf { it >= 0 }
}
