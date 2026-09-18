package kestrel.engine

import kestrel.catalog.Catalog
import kestrel.catalog.SortedIndex
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

private class ScoredScan(val boundColumns: Int, val scan: PrimaryAccess.IndexScan)

class IndexAccessPlanner(private val catalog: Catalog) {
    fun plan(tableName: String, alias: String, schema: TableSchema, predicate: Expression?): PrimaryAccess {
        if (predicate == null) return PrimaryAccess.FullScan
        val byColumn = LinkedHashMap<String, MutableList<Pair<BinaryOperator, Value>>>()
        for (conjunct in conjuncts(predicate)) {
            val simple = asColumnPredicate(conjunct, alias, schema) ?: continue
            byColumn.getOrPut(simple.first) { mutableListOf() }.add(simple.second to simple.third)
        }
        return catalog.indexesOn(tableName)
            .mapNotNull { index -> planIndex(index, byColumn) }
            .maxByOrNull { it.boundColumns }
            ?.scan
            ?: PrimaryAccess.FullScan
    }

    private fun planIndex(index: SortedIndex, byColumn: Map<String, List<Pair<BinaryOperator, Value>>>): ScoredScan? {
        val definition = index.definition
        val columns = definition.columnNames
        val equalityValues = mutableListOf<Value>()
        val descriptionParts = mutableListOf<String>()
        for (columnName in columns) {
            val equal = byColumn[columnName]?.firstOrNull { it.first == BinaryOperator.Equal } ?: break
            equalityValues.add(equal.second)
            descriptionParts.add("$columnName = ${literalText(equal.second)}")
        }
        if (equalityValues.size == columns.size) {
            val candidates = index.equalTo(equalityValues).sorted()
            return ScoredScan(columns.size, PrimaryAccess.IndexScan(definition.name, descriptionParts.joinToString(" AND "), candidates))
        }
        val nextColumn = columns[equalityValues.size]
        val rangePredicates = byColumn[nextColumn]?.filter { it.first != BinaryOperator.Equal }.orEmpty()
        if (rangePredicates.isNotEmpty()) {
            val scan = rangeScan(index, nextColumn, rangePredicates, prefix = equalityValues.toList(), descriptionParts)
            if (scan != null) return scan
        }
        if (equalityValues.isEmpty()) return null
        val candidates = index.rangeOf(equalityValues, true, equalityValues, true).sorted()
        return ScoredScan(equalityValues.size, PrimaryAccess.IndexScan(definition.name, descriptionParts.joinToString(" AND "), candidates))
    }

    private fun rangeScan(
        index: SortedIndex,
        columnName: String,
        predicates: List<Pair<BinaryOperator, Value>>,
        prefix: List<Value>,
        descriptionPrefix: List<String>
    ): ScoredScan? {
        var lowerValue: Value? = null
        var lowerInclusive = false
        var upperValue: Value? = null
        var upperInclusive = false
        val parts = descriptionPrefix.toMutableList()
        for ((operator, literal) in predicates) {
            when (operator) {
                BinaryOperator.Greater -> { lowerValue = literal; lowerInclusive = false; parts.add("$columnName > ${literalText(literal)}") }
                BinaryOperator.GreaterEqual -> { lowerValue = literal; lowerInclusive = true; parts.add("$columnName >= ${literalText(literal)}") }
                BinaryOperator.Less -> { upperValue = literal; upperInclusive = false; parts.add("$columnName < ${literalText(literal)}") }
                BinaryOperator.LessEqual -> { upperValue = literal; upperInclusive = true; parts.add("$columnName <= ${literalText(literal)}") }
                else -> Unit
            }
        }
        if (lowerValue == null && upperValue == null) return null
        val lower = lowerValue?.let { prefix + it }
        val upper = upperValue?.let { prefix + it }
        val candidates = index.rangeOf(lower, lowerInclusive, upper, upperInclusive).sorted()
        return ScoredScan(prefix.size + 1, PrimaryAccess.IndexScan(index.definition.name, parts.joinToString(" AND "), candidates))
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
        is Value.DecimalValue -> value.value.toPlainString()
        is Value.DateValue -> value.value.toString()
        is Value.NullValue -> "NULL"
    }

    private companion object {
        val RANGE_OPERATORS = setOf(
            BinaryOperator.Equal, BinaryOperator.Less, BinaryOperator.LessEqual,
            BinaryOperator.Greater, BinaryOperator.GreaterEqual
        )
    }
}
