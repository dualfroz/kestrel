package kestrel.engine

import kestrel.KestrelException
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.SelectItem
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.UnaryExpression
import kestrel.storage.Value
import kestrel.storage.ValueOrdering
import kestrel.storage.typeName

class GroupAggregation(
    private val resolveIndex: (kestrel.sql.ast.ColumnExpression) -> Int,
    private val evaluateRow: (Expression, List<Value>) -> Value,
    private val label: (SelectItem, Int) -> String
) {
    fun aggregate(statement: SelectStatement, orderExpression: Expression?, combined: List<List<Value>>): SelectResult {
        val groups = LinkedHashMap<List<Value>, MutableList<List<Value>>>()
        for (row in combined) {
            val key = statement.groupBy.map { evaluateRow(it, row) }
            groups.getOrPut(key) { mutableListOf() }.add(row)
        }
        var groupRows: List<List<List<Value>>> =
            if (statement.groupBy.isEmpty() && groups.isEmpty()) listOf(emptyList()) else groups.values.toList()
        statement.having?.let { having ->
            groupRows = groupRows.filter { isTrue(evaluateInGroup(having, it)) }
        }
        orderExpression?.let { expression ->
            val comparator = Comparator<List<List<Value>>> { first, second ->
                ValueOrdering.compare(evaluateInGroup(expression, first), evaluateInGroup(expression, second))
            }
            groupRows = groupRows.sortedWith(if (statement.orderBy!!.descending) comparator.reversed() else comparator)
        }
        statement.limit?.let {
            if (it < 0) throw KestrelException("LIMIT must not be negative")
            groupRows = groupRows.take(it)
        }
        val projection = statement.projection as ExpressionProjection
        val labels = projection.items.mapIndexed { index, item -> label(item, index) }
        val projected = groupRows.map { group -> projection.items.map { evaluateInGroup(it.expression, group) } }
        return SelectResult(labels, projected)
    }

    private fun evaluateInGroup(expression: Expression, group: List<List<Value>>): Value {
        val substituted = substituteAggregates(expression, group)
        val resolver = ColumnResolver { columnExpression -> group.first()[resolveIndex(columnExpression)] }
        return Evaluator(resolver).evaluate(substituted)
    }

    private fun substituteAggregates(expression: Expression, group: List<List<Value>>): Expression = when (expression) {
        is FunctionExpression -> LiteralExpression(computeAggregate(expression, group))
        is BinaryExpression -> BinaryExpression(
            expression.operator,
            substituteAggregates(expression.left, group),
            substituteAggregates(expression.right, group)
        )
        is UnaryExpression -> UnaryExpression(expression.operator, substituteAggregates(expression.operand, group))
        else -> expression
    }

    private fun computeAggregate(function: FunctionExpression, group: List<List<Value>>): Value {
        val name = function.name.uppercase()
        if (name == "COUNT") {
            if (function.star) return Value.IntegerValue(group.size.toLong())
            val argument = function.argument ?: return Value.IntegerValue(group.size.toLong())
            val count = group.count { evaluateRow(argument, it) !is Value.NullValue }
            return Value.IntegerValue(count.toLong())
        }
        val argument = function.argument
            ?: throw KestrelException("aggregate function ${function.name} requires an argument")
        val values = group.map { evaluateRow(argument, it) }.filter { it !is Value.NullValue }
        return when (name) {
            "SUM" -> sum(values)
            "AVG" -> average(values)
            "MIN" -> values.minWithOrNull(ValueOrdering) ?: Value.NullValue
            "MAX" -> values.maxWithOrNull(ValueOrdering) ?: Value.NullValue
            else -> throw KestrelException("unknown function: ${function.name}")
        }
    }

    private fun sum(values: List<Value>): Value {
        if (values.isEmpty()) return Value.NullValue
        if (values.all { it is Value.IntegerValue }) {
            return Value.IntegerValue(values.sumOf { (it as Value.IntegerValue).value })
        }
        return Value.RealValue(values.sumOf { numeric(it) })
    }

    private fun average(values: List<Value>): Value {
        if (values.isEmpty()) return Value.NullValue
        return Value.RealValue(values.sumOf { numeric(it) } / values.size)
    }

    private fun numeric(value: Value): Double = when (value) {
        is Value.IntegerValue -> value.value.toDouble()
        is Value.RealValue -> value.value
        else -> throw KestrelException("aggregate requires numeric operands but got ${value.typeName}")
    }

    private fun isTrue(value: Value): Boolean = value is Value.BooleanValue && value.value
}
