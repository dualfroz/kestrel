package kestrel.store.sql

import kestrel.catalog.CheckConstraint
import kestrel.catalog.Column
import kestrel.catalog.ForeignKeyConstraint
import kestrel.catalog.UniqueConstraint
import kestrel.persistence.SqlWriter

data class SecondaryIndexEntry(val name: String, val columnNames: List<String>, val rootPageId: Long, val unique: Boolean = false)

data class CheckConstraintEntry(val name: String?, val expressionText: String) {
    companion object {
        fun from(check: CheckConstraint): CheckConstraintEntry = CheckConstraintEntry(check.name, SqlWriter.expressionText(check.expression))
    }
}

data class TableDirectoryEntry(
    val tableName: String,
    val columns: List<Column>,
    val dataHeaderPageId: Long,
    val primaryKeyIndexRootPageId: Long?,
    val secondaryIndexes: List<SecondaryIndexEntry>,
    val compositePrimaryKey: List<String> = emptyList(),
    val uniqueConstraints: List<UniqueConstraint> = emptyList(),
    val checks: List<CheckConstraintEntry> = emptyList(),
    val foreignKeys: List<ForeignKeyConstraint> = emptyList()
)
