package kestrel.engine

import kestrel.KestrelException
import kestrel.catalog.Catalog
import kestrel.catalog.TableSchema
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.JoinClause
import kestrel.sql.ast.JoinType
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.SelectItem
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.StarProjection
import kestrel.sql.ast.UnaryExpression
import kestrel.storage.Row
import kestrel.storage.Value
import kestrel.storage.ValueOrdering

class SelectRunner(private val catalog: Catalog) {
    private val indexPlanner = IndexAccessPlanner(catalog)

    fun run(statement: SelectStatement): SelectResult {
        val plan = buildPlan(statement)
        val combined = plan.materialize()
        return if (plan.aggregated) plan.aggregate(combined) else plan.projectRows(combined)
    }

    fun explain(statement: SelectStatement): List<String> = buildPlan(statement).describe()

    private fun buildPlan(statement: SelectStatement): Plan {
        val sources = buildSources(statement)
        val plan = Plan(statement, sources)
        plan.validate()
        return plan
    }

    private fun buildSources(statement: SelectStatement): List<Source> {
        val sources = mutableListOf<Source>()
        var offset = 0
        fun add(tableName: String, alias: String?) {
            val table = catalog.table(tableName)
            val chosen = alias ?: tableName
            if (sources.any { it.alias.equals(chosen, ignoreCase = true) }) {
                throw KestrelException("duplicate table alias: $chosen")
            }
            sources.add(Source(chosen, table.schema.name, table.schema, table.rows, offset))
            offset += table.schema.columns.size
        }
        add(statement.tableName, statement.tableAlias)
        for (join in statement.joins) add(join.tableName, join.alias)
        return sources
    }

    private inner class Plan(val statement: SelectStatement, val sources: List<Source>) {
        val aggregated: Boolean =
            statement.groupBy.isNotEmpty() ||
                containsAggregate(statement.projection) ||
                (statement.having != null && containsAggregate(statement.having))

        private val columnNames: List<String> = sources.flatMap { it.schema.columnNames }

        private val aliasExpressions: Map<String, Expression> =
            (statement.projection as? ExpressionProjection)?.items
                ?.mapNotNull { item -> item.alias?.let { it.lowercase() to item.expression } }
                ?.toMap() ?: emptyMap()

        private val orderExpression: Expression? = statement.orderBy?.let { resolveOrderAlias(it.expression) }

        private fun resolveOrderAlias(expression: Expression): Expression {
            if (expression is ColumnExpression && expression.qualifier == null) {
                aliasExpressions[expression.name.lowercase()]?.let { return it }
            }
            return expression
        }

        fun resolveIndex(expression: ColumnExpression): Int {
            val qualifier = expression.qualifier
            val matches = mutableListOf<Int>()
            for (source in sources) {
                if (qualifier != null && !source.alias.equals(qualifier, ignoreCase = true)) continue
                val local = source.schema.columnIndex(expression.name) ?: continue
                matches.add(source.offset + local)
            }
            return when {
                matches.isEmpty() -> throw KestrelException("no such column: ${describe(expression)}")
                matches.size > 1 -> throw KestrelException("ambiguous column reference: ${describe(expression)}")
                else -> matches.single()
            }
        }

        private fun sourceOfGlobal(global: Int): Int =
            sources.indexOfLast { global >= it.offset }

        private fun referencedSources(expression: Expression): Set<Int> =
            collectColumns(expression).map { sourceOfGlobal(resolveIndex(it)) }.toSet()

        fun validate() {
            statement.where?.let { validateColumns(it) }
            orderExpression?.let { validateColumns(it) }
            statement.groupBy.forEach { validateColumns(it) }
            statement.having?.let { validateColumns(it) }
            if (statement.projection is ExpressionProjection) {
                statement.projection.items.forEach { validateColumns(it.expression) }
            }
            statement.joins.forEachIndexed { position, join ->
                validateColumns(join.on)
                val allowed = position + 1
                if (referencedSources(join.on).any { it > allowed }) {
                    throw KestrelException("ON condition for ${join.tableName} references a table joined later")
                }
            }
            if (aggregated) validateAggregation()
        }

        private fun validateColumns(expression: Expression) {
            collectColumns(expression).forEach { resolveIndex(it) }
        }

        private fun validateAggregation() {
            if (statement.projection is StarProjection) {
                throw KestrelException("cannot use * with GROUP BY or aggregate functions")
            }
            val groupColumns = statement.groupBy.filterIsInstance<ColumnExpression>()
                .map { resolveIndex(it) }.toSet()
            if (statement.projection is ExpressionProjection) {
                statement.projection.items.forEach { requireGrouped(it.expression, groupColumns) }
            }
            statement.having?.let { requireGrouped(it, groupColumns) }
            orderExpression?.let { requireGrouped(it, groupColumns) }
        }

        private fun requireGrouped(expression: Expression, groupColumns: Set<Int>) {
            when (expression) {
                is FunctionExpression -> Unit
                is ColumnExpression -> if (resolveIndex(expression) !in groupColumns) {
                    throw KestrelException(
                        "column ${describe(expression)} must appear in GROUP BY or be used in an aggregate function"
                    )
                }
                is BinaryExpression -> {
                    requireGrouped(expression.left, groupColumns)
                    requireGrouped(expression.right, groupColumns)
                }
                is UnaryExpression -> requireGrouped(expression.operand, groupColumns)
                is LiteralExpression -> Unit
            }
        }

        private fun rowResolver(row: List<Value>) =
            kestrel.engine.ColumnResolver { expression -> row[resolveIndex(expression)] }

        private fun evaluateRow(expression: Expression, row: List<Value>): Value =
            Evaluator(rowResolver(row)).evaluate(expression)

        fun materialize(): List<List<Value>> {
            var combined = primaryRows().map { it.values }
            statement.joins.forEachIndexed { position, join ->
                combined = executeJoin(combined, join, position + 1)
            }
            statement.where?.let { predicate ->
                combined = combined.filter { isTrue(evaluateRow(predicate, it)) }
            }
            return combined
        }

        private fun primaryRows(): List<Row> {
            val access = primaryAccess()
            val source = sources.first()
            return when (access) {
                is PrimaryAccess.FullScan -> source.rows
                is PrimaryAccess.IndexScan -> access.rowIndices.map { source.rows[it] }
            }
        }

        private fun executeJoin(left: List<List<Value>>, join: JoinClause, rightIndex: Int): List<List<Value>> {
            val right = sources[rightIndex]
            val nullRight = List(right.schema.columns.size) { Value.NullValue }
            val hashKeys = hashJoinKeys(join, rightIndex)
            val result = mutableListOf<List<Value>>()
            if (hashKeys != null) {
                val buckets = HashMap<Value, MutableList<Row>>()
                for (rightRow in right.rows) {
                    val key = evaluateOnRight(hashKeys.buildExpression, right, rightRow)
                    if (key !is Value.NullValue) buckets.getOrPut(key) { mutableListOf() }.add(rightRow)
                }
                for (leftRow in left) {
                    val probe = evaluateRow(hashKeys.probeExpression, leftRow)
                    val matches = if (probe is Value.NullValue) emptyList() else buckets[probe].orEmpty()
                    if (matches.isNotEmpty()) {
                        matches.forEach { result.add(leftRow + it.values) }
                    } else if (join.type == JoinType.Left) {
                        result.add(leftRow + nullRight)
                    }
                }
                return result
            }
            for (leftRow in left) {
                var matched = false
                for (rightRow in right.rows) {
                    val candidate = leftRow + rightRow.values
                    if (isTrue(evaluateRow(join.on, candidate))) {
                        result.add(candidate)
                        matched = true
                    }
                }
                if (!matched && join.type == JoinType.Left) result.add(leftRow + nullRight)
            }
            return result
        }

        private fun evaluateOnRight(expression: Expression, right: Source, rightRow: Row): Value {
            val resolver = kestrel.engine.ColumnResolver { columnExpression ->
                rightRow.values[resolveIndex(columnExpression) - right.offset]
            }
            return Evaluator(resolver).evaluate(expression)
        }

        private fun hashJoinKeys(join: JoinClause, rightIndex: Int): HashKeys? {
            val condition = join.on
            if (condition !is BinaryExpression || condition.operator != BinaryOperator.Equal) return null
            val built = (0 until rightIndex).toSet()
            val leftRefs = referencedSources(condition.left)
            val rightRefs = referencedSources(condition.right)
            return when {
                rightRefs == setOf(rightIndex) && leftRefs.all { it in built } ->
                    HashKeys(buildExpression = condition.right, probeExpression = condition.left)
                leftRefs == setOf(rightIndex) && rightRefs.all { it in built } ->
                    HashKeys(buildExpression = condition.left, probeExpression = condition.right)
                else -> null
            }
        }

        fun primaryAccess(): PrimaryAccess {
            if (statement.joins.isNotEmpty()) return PrimaryAccess.FullScan
            val source = sources.first()
            return indexPlanner.plan(source.tableName, source.alias, source.schema, statement.where)
        }

        fun projectRows(combined: List<List<Value>>): SelectResult {
            var rows = combined
            orderExpression?.let { expression ->
                val comparator = Comparator<List<Value>> { first, second ->
                    ValueOrdering.compare(evaluateRow(expression, first), evaluateRow(expression, second))
                }
                rows = rows.sortedWith(if (statement.orderBy!!.descending) comparator.reversed() else comparator)
            }
            statement.limit?.let {
                if (it < 0) throw KestrelException("LIMIT must not be negative")
                rows = rows.take(it)
            }
            return when (val projection = statement.projection) {
                is StarProjection -> SelectResult(columnNames, rows)
                is ExpressionProjection -> {
                    val labels = projection.items.mapIndexed { index, item -> columnLabel(item, index) }
                    val projected = rows.map { row -> projection.items.map { evaluateRow(it.expression, row) } }
                    SelectResult(labels, projected)
                }
            }
        }

        fun aggregate(combined: List<List<Value>>): SelectResult =
            GroupAggregation(::resolveIndex, ::evaluateRow, this@SelectRunner::columnLabel)
                .aggregate(statement, orderExpression, combined)

        fun describe(): List<String> {
            val lines = mutableListOf<String>()
            var body = joinLines()
            if (aggregated) {
                val keys = statement.groupBy.map { describeExpression(it) }
                val header = if (keys.isEmpty()) "Aggregate" else "GroupAggregate keys: ${keys.joinToString(", ")}"
                body = listOf(header) + body.map { "  $it" }
            }
            statement.orderBy?.let { body = listOf("Sort") + body.map { "  $it" } }
            statement.limit?.let { body = listOf("Limit ${statement.limit}") + body.map { "  $it" } }
            lines.addAll(body)
            return lines
        }

        private fun joinLines(): List<String> {
            var lines = listOf(scanLine(sources.first(), primaryAccess()))
            statement.joins.forEachIndexed { position, join ->
                val right = sources[position + 1]
                val hash = hashJoinKeys(join, position + 1) != null
                val header = if (hash) "Hash Join ${describeExpression(join.on)}" else "Nested Loop ${joinLabel(join)}"
                lines = listOf(header) + lines.map { "  -> $it" } + listOf("  -> Seq Scan on ${right.tableName}")
            }
            return lines
        }

        private fun scanLine(source: Source, access: PrimaryAccess): String = when (access) {
            is PrimaryAccess.FullScan -> "Seq Scan on ${source.tableName}"
            is PrimaryAccess.IndexScan ->
                "Index Scan using ${access.indexName} on ${source.tableName} (${access.description})"
        }

        private fun joinLabel(join: JoinClause): String =
            if (join.type == JoinType.Left) "Left" else "Inner"
    }

    private fun columnLabel(item: SelectItem, index: Int): String =
        item.alias ?: when (val expression = item.expression) {
            is ColumnExpression -> expression.name
            is FunctionExpression -> expression.name.lowercase()
            else -> "column${index + 1}"
        }

    private fun collectColumns(expression: Expression): List<ColumnExpression> = when (expression) {
        is ColumnExpression -> listOf(expression)
        is BinaryExpression -> collectColumns(expression.left) + collectColumns(expression.right)
        is UnaryExpression -> collectColumns(expression.operand)
        is FunctionExpression -> expression.argument?.let { collectColumns(it) } ?: emptyList()
        is LiteralExpression -> emptyList()
    }

    private fun containsAggregate(projection: kestrel.sql.ast.Projection): Boolean = when (projection) {
        is StarProjection -> false
        is ExpressionProjection -> projection.items.any { containsAggregate(it.expression) }
    }

    private fun containsAggregate(expression: Expression): Boolean = when (expression) {
        is FunctionExpression -> true
        is BinaryExpression -> containsAggregate(expression.left) || containsAggregate(expression.right)
        is UnaryExpression -> containsAggregate(expression.operand)
        else -> false
    }

    private fun isTrue(value: Value): Boolean = value is Value.BooleanValue && value.value

    private fun describe(expression: ColumnExpression): String =
        expression.qualifier?.let { "$it.${expression.name}" } ?: expression.name

    private fun describeExpression(expression: Expression): String = when (expression) {
        is ColumnExpression -> describe(expression)
        is LiteralExpression -> literalText(expression.value)
        is FunctionExpression -> "${expression.name}(${if (expression.star) "*" else expression.argument?.let { describeExpression(it) } ?: ""})"
        is BinaryExpression -> "${describeExpression(expression.left)} ${expression.operator.symbol} ${describeExpression(expression.right)}"
        is UnaryExpression -> "${expression.operator.symbol} ${describeExpression(expression.operand)}"
    }

    private fun literalText(value: Value): String = when (value) {
        is Value.IntegerValue -> value.value.toString()
        is Value.RealValue -> value.value.toString()
        is Value.TextValue -> "'${value.value}'"
        is Value.BooleanValue -> value.value.toString()
        is Value.NullValue -> "NULL"
    }

    private class Source(
        val alias: String,
        val tableName: String,
        val schema: TableSchema,
        val rows: List<Row>,
        val offset: Int
    )

    private class HashKeys(val buildExpression: Expression, val probeExpression: Expression)
}
