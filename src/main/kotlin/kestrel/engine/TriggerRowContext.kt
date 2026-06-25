package kestrel.engine

import kestrel.KestrelException
import kestrel.catalog.TableSchema
import kestrel.sql.ast.ColumnExpression
import kestrel.storage.Collation
import kestrel.storage.Row
import kestrel.storage.Value

/**
 * Binds `NEW`/`OLD` for the one row a firing trigger is currently processing, against [schema] -
 * always the trigger's OWN table (the one named in its `ON <table>`), never whatever table a
 * nested body statement happens to target. [newRow] is null for a DELETE trigger, [oldRow] is null
 * for an INSERT trigger; both are non-null only inside an UPDATE trigger.
 */
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

    /** Column-existence check with no row required, for validating a reference before it is ever fired. */
    fun checkColumnExists(expression: ColumnExpression) {
        if (schema.columnIndex(expression.name) == null) {
            throw KestrelException("no such column: ${expression.name}")
        }
    }

    /** `NEW.col`/`OLD.col` reads the same table's schema either way, so its declared COLLATE never depends on which side [expression] names. */
    fun declaredCollation(expression: ColumnExpression): Collation? = schema.column(expression.name)?.collation
}
