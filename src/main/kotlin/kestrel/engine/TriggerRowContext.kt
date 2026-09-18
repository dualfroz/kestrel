package kestrel.engine

import kestrel.KestrelException
import kestrel.catalog.TableSchema
import kestrel.sql.ast.ColumnExpression
import kestrel.storage.Collation
import kestrel.storage.Row
import kestrel.storage.Value

class TriggerRowContext(private val schema: TableSchema, private val newRow: Row?, private val oldRow: Row?) {
    fun matches(expression: ColumnExpression): Boolean {
        val qualifier = expression.qualifier ?: return false
        return (qualifier.equals("new", ignoreCase = true) && newRow != null) ||
            (qualifier.equals("old", ignoreCase = true) && oldRow != null)
    }

    fun resolve(expression: ColumnExpression): Value {
        val row = if (expression.qualifier!!.equals("new", ignoreCase = true)) newRow!! else oldRow!!
        val index = schema.columnIndex(expression.name)
            ?: throw KestrelException("no such column: ${expression.name}")
        return row.values[index]
    }

    fun checkColumnExists(expression: ColumnExpression) {
        if (schema.columnIndex(expression.name) == null) {
            throw KestrelException("no such column: ${expression.name}")
        }
    }

    fun declaredCollation(expression: ColumnExpression): Collation? = schema.column(expression.name)?.collation
}
