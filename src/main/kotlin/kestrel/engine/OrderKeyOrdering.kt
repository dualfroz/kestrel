package kestrel.engine

import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.OrderKey
import kestrel.storage.Collation
import kestrel.storage.Value
import kestrel.storage.ValueOrdering

fun compareOrderKeyValues(
    left: Value,
    right: Value,
    key: OrderKey,
    declaredCollationOf: (ColumnExpression) -> Collation? = { null }
): Int {
    val leftNull = left is Value.NullValue
    val rightNull = right is Value.NullValue
    if (leftNull || rightNull) {
        if (leftNull && rightNull) return 0
        val nullsFirst = key.nullsFirst ?: !key.descending
        return if (leftNull == nullsFirst) -1 else 1
    }
    val collation = resolveCollation(key.expression, declaredCollationOf)
    val comparison = ValueOrdering.compare(left, right, collation)
    return if (key.descending) -comparison else comparison
}
