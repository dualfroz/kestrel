package kestrel.engine

import kestrel.KestrelException
import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseBranch
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.OrderKey
import kestrel.sql.ast.SelectItem
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.UnaryExpression
import kestrel.storage.Collation
import kestrel.storage.Value

class GroupAggregation(
    private val resolveIndex: (kestrel.sql.ast.ColumnExpression) -> Int,
    private val evaluateRow: (Expression, List<Value>) -> Value,
    private val label: (SelectItem, Int) -> String,
    private val subqueries: SubqueryRunner? = null,
    private val declaredCollationOf: (kestrel.sql.ast.ColumnExpression) -> Collation? = { null }
) {
    private data class LogicalGroup(val rows: List<List<Value>>, val activeExpressions: List<Expression>)

    fun aggregate(statement: SelectStatement, orderKeys: List<OrderKey>, combined: List<List<Value>>): SelectResult {
        val allGroupingColumns = statement.groupBy
        val sets = GroupingSetExpansion.setsFor(statement.groupingSpec, statement.groupBy)
        var logicalGroups = sets.flatMap { groupsForSet(it, combined) }
        statement.having?.let { having ->
            logicalGroups = logicalGroups.filter { isTrue(evaluateInGroup(having, it, allGroupingColumns)) }
        }
        if (orderKeys.isNotEmpty()) {
            val comparator = Comparator<LogicalGroup> { first, second ->
                var result = 0
                for (key in orderKeys) {
                    val comparison = compareOrderKeyValues(
                        evaluateInGroup(key.expression, first, allGroupingColumns),
                        evaluateInGroup(key.expression, second, allGroupingColumns),
                        key,
                        declaredCollationOf
                    )
                    if (comparison != 0) {
                        result = comparison
                        break
                    }
                }
                result
            }
            logicalGroups = logicalGroups.sortedWith(comparator)
        }
        statement.offset?.let { if (it < 0) throw KestrelException("OFFSET must not be negative") }
        statement.limit?.let { if (it < 0) throw KestrelException("LIMIT must not be negative") }
        if (!statement.distinct && (statement.offset != null || statement.limit != null)) {
            logicalGroups = logicalGroups.drop(statement.offset ?: 0)
            statement.limit?.let { logicalGroups = logicalGroups.take(it) }
        }
        val projection = statement.projection as ExpressionProjection
        val labels = projection.items.mapIndexed { index, item -> label(item, index) }
        var projected = logicalGroups.map { group -> projection.items.map { evaluateInGroup(it.expression, group, allGroupingColumns) } }
        if (statement.distinct) {
            val itemExpressions = projection.items.map { it.expression }
            projected = projected.distinctBy { row -> collationFoldedKey(row, itemExpressions, declaredCollationOf) }
            if (statement.offset != null || statement.limit != null) {
                projected = projected.drop(statement.offset ?: 0)
                statement.limit?.let { projected = projected.take(it) }
            }
        }
        return SelectResult(labels, projected)
    }

    private fun groupsForSet(setExpressions: List<Expression>, combined: List<List<Value>>): List<LogicalGroup> {
        val buckets = LinkedHashMap<List<Value>, MutableList<List<Value>>>()
        for (row in combined) {
            val key = setExpressions.map { evaluateRow(it, row) }
            val bucketKey = collationFoldedKey(key, setExpressions, declaredCollationOf)
            buckets.getOrPut(bucketKey) { mutableListOf() }.add(row)
        }
        return if (setExpressions.isEmpty() && buckets.isEmpty()) {
            listOf(LogicalGroup(emptyList(), setExpressions))
        } else {
            buckets.values.map { LogicalGroup(it, setExpressions) }
        }
    }

    private fun evaluateInGroup(expression: Expression, group: LogicalGroup, allGroupingColumns: List<Expression>): Value {
        val nulled = substituteInactiveGroupingColumns(expression, group.activeExpressions, allGroupingColumns)
        val substituted = substituteAggregates(nulled, group.rows)
        val resolver = object : ColumnResolver {
            override fun resolve(columnExpression: kestrel.sql.ast.ColumnExpression): Value =
                group.rows.first()[resolveIndex(columnExpression)]

            override fun declaredCollation(columnExpression: kestrel.sql.ast.ColumnExpression): Collation? =
                declaredCollationOf(columnExpression)
        }
        return Evaluator(resolver, subqueries).evaluate(substituted)
    }

    private fun substituteInactiveGroupingColumns(
        expression: Expression,
        activeExpressions: List<Expression>,
        allGroupingColumns: List<Expression>
    ): Expression {
        if (expression in allGroupingColumns && expression !in activeExpressions) {
            return LiteralExpression(Value.NullValue)
        }
        fun recurse(child: Expression) = substituteInactiveGroupingColumns(child, activeExpressions, allGroupingColumns)
        return when (expression) {
            is BinaryExpression -> BinaryExpression(expression.operator, recurse(expression.left), recurse(expression.right))
            is UnaryExpression -> UnaryExpression(expression.operator, recurse(expression.operand))
            is CallExpression ->
                if (expression.name.equals("GROUPING", ignoreCase = true)) {
                    LiteralExpression(computeGrouping(expression, activeExpressions))
                } else {
                    CallExpression(expression.name, expression.arguments.map(::recurse))
                }
            is IsNullExpression -> IsNullExpression(recurse(expression.operand), expression.negated)
            is InExpression -> InExpression(recurse(expression.operand), expression.values.map(::recurse), expression.negated)
            is BetweenExpression -> BetweenExpression(
                recurse(expression.operand), recurse(expression.low), recurse(expression.high), expression.negated
            )
            is LikeExpression -> LikeExpression(recurse(expression.operand), recurse(expression.pattern), expression.negated)
            is CaseExpression -> CaseExpression(
                expression.operand?.let(::recurse),
                expression.branches.map { CaseBranch(recurse(it.condition), recurse(it.result)) },
                expression.elseExpression?.let(::recurse)
            )
            is CastExpression -> CastExpression(recurse(expression.operand), expression.targetType, expression.precision, expression.scale)
            else -> expression
        }
    }

    private fun computeGrouping(call: CallExpression, activeExpressions: List<Expression>): Value {
        if (call.arguments.isEmpty()) throw KestrelException("GROUPING requires at least one argument")
        var bits = 0L
        for (argument in call.arguments) {
            bits = (bits shl 1) or if (argument in activeExpressions) 0L else 1L
        }
        return Value.IntegerValue(bits)
    }

    private fun substituteAggregates(expression: Expression, group: List<List<Value>>): Expression = when (expression) {
        is FunctionExpression -> LiteralExpression(computeAggregate(expression, group))
        is BinaryExpression -> BinaryExpression(
            expression.operator,
            substituteAggregates(expression.left, group),
            substituteAggregates(expression.right, group)
        )
        is UnaryExpression -> UnaryExpression(expression.operator, substituteAggregates(expression.operand, group))
        is CallExpression -> CallExpression(expression.name, expression.arguments.map { substituteAggregates(it, group) })
        is IsNullExpression -> IsNullExpression(substituteAggregates(expression.operand, group), expression.negated)
        is InExpression -> InExpression(
            substituteAggregates(expression.operand, group),
            expression.values.map { substituteAggregates(it, group) },
            expression.negated
        )
        is BetweenExpression -> BetweenExpression(
            substituteAggregates(expression.operand, group),
            substituteAggregates(expression.low, group),
            substituteAggregates(expression.high, group),
            expression.negated
        )
        is LikeExpression -> LikeExpression(
            substituteAggregates(expression.operand, group),
            substituteAggregates(expression.pattern, group),
            expression.negated
        )
        is CaseExpression -> CaseExpression(
            expression.operand?.let { substituteAggregates(it, group) },
            expression.branches.map {
                CaseBranch(substituteAggregates(it.condition, group), substituteAggregates(it.result, group))
            },
            expression.elseExpression?.let { substituteAggregates(it, group) }
        )
        is CastExpression -> CastExpression(
            substituteAggregates(expression.operand, group),
            expression.targetType,
            expression.precision,
            expression.scale
        )
        else -> expression
    }

    private fun computeAggregate(function: FunctionExpression, group: List<List<Value>>): Value {
        val filtered = function.filter?.let { predicate -> group.filter { isTrue(evaluateRow(predicate, it)) } } ?: group
        return AggregateFunctions.compute(
            function.name, function.argument, function.star, filtered, evaluateRow,
            function.separator, function.orderBy, function.distinct, declaredCollationOf
        )
    }

    private fun isTrue(value: Value): Boolean = value is Value.BooleanValue && value.value
}
