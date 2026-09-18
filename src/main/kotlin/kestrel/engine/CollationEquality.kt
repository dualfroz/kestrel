package kestrel.engine

import kestrel.catalog.TableSchema
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.storage.Collation
import kestrel.storage.Value

fun resolveCollation(expression: Expression, declaredCollationOf: (ColumnExpression) -> Collation?): Collation =
    (expression as? CollateExpression)?.collation
        ?: (expression as? ColumnExpression)?.let(declaredCollationOf)
        ?: Collation.Binary

fun collationFoldedKey(
    values: List<Value>,
    expressions: List<Expression>,
    declaredCollationOf: (ColumnExpression) -> Collation?
): List<Value> = values.mapIndexed { index, value ->
    if (value !is Value.TextValue) value
    else Value.TextValue(resolveCollation(expressions[index], declaredCollationOf).canonicalize(value.value))
}

fun collationFoldedUniquenessKey(schema: TableSchema, columnIndices: List<Int>, values: List<Value>): List<Value> =
    values.mapIndexed { position, value ->
        if (value !is Value.TextValue) value
        else Value.TextValue(schema.columns[columnIndices[position]].collation.canonicalize(value.value))
    }

fun List<Value>.distinctByCollation(collation: Collation): List<Value> =
    distinctBy { value -> if (value is Value.TextValue) collation.canonicalize(value.value) else value }

fun pairCollation(
    left: Expression,
    right: Expression,
    declaredCollationOf: (ColumnExpression) -> Collation?
): Collation {
    fun explicitOf(expression: Expression) = (expression as? CollateExpression)?.collation
    fun declaredOf(expression: Expression) =
        (expression as? ColumnExpression)?.let(declaredCollationOf)?.takeIf { it != Collation.Binary }
    return explicitOf(left) ?: explicitOf(right) ?: declaredOf(left) ?: declaredOf(right) ?: Collation.Binary
}
