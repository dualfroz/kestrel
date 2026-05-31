package kestrel.engine

import kestrel.catalog.Catalog
import kestrel.catalog.TableSchema
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.LiteralExpression
import kestrel.storage.Value

sealed class PrimaryAccess {
    data object FullScan : PrimaryAccess()
    class IndexScan(val indexName: String, val description: String, val rowIndices: List<Int>) : PrimaryAccess()
}

class IndexAccessPlanner(private val catalog: Catalog) {
    fun plan(tableName: String, alias: String, schema: TableSchema, predicate: Expression?): PrimaryAccess {
        if (predicate == null) return PrimaryAccess.FullScan
        val byColumn = LinkedHashMap<String, MutableList<Pair<BinaryOperator, Value>>>()
        for (conjunct in conjuncts(predicate)) {
            val simple = asColumnPredicate(conjunct, alias, schema) ?: continue
            byColumn.getOrPut(simple.first) { mutableListOf() }.add(simple.second to simple.third)
        }
        for ((columnName, predicates) in byColumn) {
            val index = catalog.indexOn(tableName, columnName) ?: continue
            val equal = predicates.firstOrNull { it.first == BinaryOperator.Equal }
            if (equal != null) {
                val candidates = index.equalTo(equal.second).sorted()
                return PrimaryAccess.IndexScan(index.definition.name, "$columnName = ${literalText(equal.second)}", candidates)
            }
            var lower: Value? = null
            var lowerInclusive = false
            var upper: Value? = null
            var upperInclusive = false
            val parts = mutableListOf<String>()
            for ((operator, literal) in predicates) {
                when (operator) {
                    BinaryOperator.Greater -> { lower = literal; lowerInclusive = false; parts.add("$columnName > ${literalText(literal)}") }
                    BinaryOperator.GreaterEqual -> { lower = literal; lowerInclusive = true; parts.add("$columnName >= ${literalText(literal)}") }
                    BinaryOperator.Less -> { upper = literal; upperInclusive = false; parts.add("$columnName < ${literalText(literal)}") }
                    BinaryOperator.LessEqual -> { upper = literal; upperInclusive = true; parts.add("$columnName <= ${literalText(literal)}") }
                    else -> Unit
                }
            }
            if (lower == null && upper == null) continue
            val candidates = index.rangeOf(lower, lowerInclusive, upper, upperInclusive).sorted()
            return PrimaryAccess.IndexScan(index.definition.name, parts.joinToString(" AND "), candidates)
        }
        return PrimaryAccess.FullScan
    }

    private fun asColumnPredicate(
        expression: Expression,
        alias: String,
        schema: TableSchema
    ): Triple<String, BinaryOperator, Value>? {
        if (expression !is BinaryExpression) return null
        val operator = expression.operator
        if (operator !in RANGE_OPERATORS) return null
        val left = expression.left
        val right = expression.right
        fun matches(column: ColumnExpression): String? {
            if (column.qualifier != null && !column.qualifier.equals(alias, ignoreCase = true)) return null
            return if (schema.columnIndex(column.name) != null) column.name else null
        }
        if (left is ColumnExpression && right is LiteralExpression && right.value !is Value.NullValue) {
            val name = matches(left) ?: return null
            return Triple(name, operator, right.value)
        }
        if (right is ColumnExpression && left is LiteralExpression && left.value !is Value.NullValue) {
            val name = matches(right) ?: return null
            return Triple(name, flip(operator), left.value)
        }
        return null
    }

    private fun conjuncts(expression: Expression): List<Expression> =
        if (expression is BinaryExpression && expression.operator == BinaryOperator.And) {
            conjuncts(expression.left) + conjuncts(expression.right)
        } else {
            listOf(expression)
        }

    private fun flip(operator: BinaryOperator): BinaryOperator = when (operator) {
        BinaryOperator.Less -> BinaryOperator.Greater
        BinaryOperator.LessEqual -> BinaryOperator.GreaterEqual
        BinaryOperator.Greater -> BinaryOperator.Less
        BinaryOperator.GreaterEqual -> BinaryOperator.LessEqual
        else -> operator
    }

    private fun literalText(value: Value): String = when (value) {
        is Value.IntegerValue -> value.value.toString()
        is Value.RealValue -> value.value.toString()
        is Value.TextValue -> "'${value.value}'"
        is Value.BooleanValue -> value.value.toString()
        is Value.NullValue -> "NULL"
    }

    private companion object {
        val RANGE_OPERATORS = setOf(
            BinaryOperator.Equal, BinaryOperator.Less, BinaryOperator.LessEqual,
            BinaryOperator.Greater, BinaryOperator.GreaterEqual
        )
    }
}
