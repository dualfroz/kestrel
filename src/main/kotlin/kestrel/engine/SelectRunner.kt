package kestrel.engine

import kestrel.KestrelException
import kestrel.catalog.Catalog
import kestrel.catalog.Column
import kestrel.catalog.TableSchema
import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.CommonTableExpression
import kestrel.sql.ast.CurrentValueExpression
import kestrel.sql.ast.ExistsExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.FrameBound
import kestrel.sql.ast.FrameBoundType
import kestrel.sql.ast.FrameExclude
import kestrel.sql.ast.FrameUnit
import kestrel.sql.ast.FromItem
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.InSubqueryExpression
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.JoinClause
import kestrel.sql.ast.JoinType
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.OrderKey
import kestrel.sql.ast.QueryStatement
import kestrel.sql.ast.ScalarSubqueryExpression
import kestrel.sql.ast.SelectItem
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.SetOperationStatement
import kestrel.sql.ast.SetOperator
import kestrel.sql.ast.StarProjection
import kestrel.sql.ast.SubquerySource
import kestrel.sql.ast.TableSource
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.WindowExpression
import kestrel.sql.ast.WindowFrame
import kestrel.sql.ast.WindowFunctionKind
import kestrel.sql.ast.WithQuery
import kestrel.storage.Collation
import kestrel.storage.ColumnType
import kestrel.storage.Row
import kestrel.storage.Value
import kestrel.storage.displayWith
import java.math.BigDecimal

class SelectRunner(private val catalog: Catalog) {
    private val indexPlanner = IndexAccessPlanner(catalog)
    private val subqueryRunner = SubqueryRunner { select, outer -> run(select, outer) }
    private val expandingViews = mutableSetOf<String>()
    private val activeCtes = mutableListOf<CteScope>()

    fun run(statement: SelectStatement, outer: ColumnResolver? = null): SelectResult {
        val plan = buildPlan(statement, outer)
        val combined = plan.materialize()
        return if (plan.aggregated) plan.aggregate(combined) else plan.projectRows(combined)
    }

    fun runQuery(query: QueryStatement, outer: ColumnResolver? = null): SelectResult = when (query) {
        is SelectStatement -> run(query, outer)
        is SetOperationStatement -> runSetOperation(query, outer)
        is WithQuery -> runWith(query, outer)
    }

    fun explain(query: QueryStatement): List<String> = when (query) {
        is SelectStatement -> buildPlan(query, null).describe()
        is SetOperationStatement -> explainSetOperation(query)
        is WithQuery -> explainWith(query)
    }

    /**
     * Resolves a single [FromItem] to its schema and row snapshot - the same lookup a `FROM`/`JOIN`
     * source uses ([resolveSource]), exposed for [Executor.executeMerge]'s `USING` clause so a MERGE
     * source can be either a real table or a derived-table subquery without duplicating view/CTE/
     * subquery resolution.
     */
    fun resolveMergeSource(item: FromItem): Pair<TableSchema, List<Row>> = resolveSource(item)

    /** The name a MERGE `ON`/`WHEN` condition would qualify a column from [item] with - its alias if given, its bare table name otherwise (a subquery source always has one). */
    fun mergeSourceLabel(item: FromItem): String = sourceLabel(item)

    private fun explainSetOperation(statement: SetOperationStatement): List<String> {
        val header = setOperatorKeyword(statement.operator)
        val left = explain(statement.left).map { "  -> $it" }
        val right = explain(statement.right).map { "  -> $it" }
        var lines = listOf(header) + left + right
        statement.orderBy?.let { lines = listOf("Sort") + lines.map { line -> "  $line" } }
        statement.offset?.let { lines = listOf("Offset ${statement.offset}") + lines.map { line -> "  $line" } }
        statement.limit?.let { lines = listOf("Limit ${statement.limit}") + lines.map { line -> "  $line" } }
        return lines
    }

    private fun explainWith(query: WithQuery): List<String> {
        var pushed = 0
        try {
            for (cte in query.ctes) {
                activeCtes.add(buildCteScope(query.recursive, cte))
                pushed++
            }
            val cteLines = query.ctes.flatMap { cte ->
                listOf("CTE ${cte.name}") + explain(cte.query).map { "  -> $it" }
            }
            return cteLines + explain(query.body)
        } finally {
            repeat(pushed) { activeCtes.removeAt(activeCtes.size - 1) }
        }
    }

    private fun setOperatorKeyword(operator: SetOperator): String = when (operator) {
        SetOperator.Union -> "Union"
        SetOperator.UnionAll -> "Union All"
        SetOperator.Intersect -> "Intersect"
        SetOperator.IntersectAll -> "Intersect All"
        SetOperator.Except -> "Except"
        SetOperator.ExceptAll -> "Except All"
    }

    private fun runSetOperation(statement: SetOperationStatement, outer: ColumnResolver?): SelectResult {
        val left = runQuery(statement.left, outer)
        val right = runQuery(statement.right, outer)
        if (left.columns.size != right.columns.size) {
            throw KestrelException(
                "${setOperatorKeyword(statement.operator)} queries must have the same number of columns " +
                    "but got ${left.columns.size} and ${right.columns.size}"
            )
        }
        val declaredCollationOf = declaredSetOperationCollation(statement, left.columns)
        val outputExpressions = left.columns.map { ColumnExpression(it) }
        fun foldedKey(row: List<Value>) = collationFoldedKey(row, outputExpressions, declaredCollationOf)
        var rows = when (statement.operator) {
            SetOperator.UnionAll -> left.rows + right.rows
            SetOperator.Union -> (left.rows + right.rows).distinctBy(::foldedKey)
            SetOperator.Intersect -> {
                val rightKeys = right.rows.mapTo(HashSet(), ::foldedKey)
                left.rows.filter { foldedKey(it) in rightKeys }.distinctBy(::foldedKey)
            }
            SetOperator.Except -> {
                val rightKeys = right.rows.mapTo(HashSet(), ::foldedKey)
                left.rows.filter { foldedKey(it) !in rightKeys }.distinctBy(::foldedKey)
            }
            SetOperator.IntersectAll -> multisetFilter(left.rows, right.rows, keepOnMatch = true, keyOf = ::foldedKey)
            SetOperator.ExceptAll -> multisetFilter(left.rows, right.rows, keepOnMatch = false, keyOf = ::foldedKey)
        }
        val orderKeys = statement.orderBy?.keys.orEmpty()
        if (orderKeys.isNotEmpty()) {
            rows = rows.sortedWith(
                multiKeyComparator(
                    orderKeys,
                    evaluate = { expression, row -> evaluateOutputRow(left.columns, expression, row) },
                    declaredCollationOf = declaredCollationOf
                )
            )
        }
        statement.offset?.let { if (it < 0) throw KestrelException("OFFSET must not be negative") }
        statement.limit?.let { if (it < 0) throw KestrelException("LIMIT must not be negative") }
        if (statement.offset != null || statement.limit != null) {
            rows = rows.drop(statement.offset ?: 0)
            statement.limit?.let { rows = rows.take(it) }
        }
        return SelectResult(left.columns, rows)
    }

    /**
     * The declared `COLLATE` of [statement]'s combined output column named by an ORDER BY key
     * expression (an unqualified [ColumnExpression] - the only kind [evaluateOutputRow] resolves
     * for a set operation's own ORDER BY, since it rejects any qualifier), traced back to a real
     * table column in [statement]'s LEFT branch only - the branch [SelectResult.columns] itself
     * always takes column identity from (`runSetOperation` returns `SelectResult(left.columns,
     * ...)`, never consulting the right side's names). Resolves only when that branch is a plain
     * `SELECT ... FROM realTable [JOIN realTable ...]` (no CTE, view, or derived table anywhere in
     * its FROM clause) referencing the column bare - going further would mean inheriting collation
     * through a view/CTE/derived-table boundary, the still-open scope-cut already left by #65/#66
     * for a plain SELECT's own WHERE/ORDER BY over such a source (see kestrel-stan.md), not
     * extended here.
     */
    private fun declaredSetOperationCollation(statement: SetOperationStatement, columns: List<String>): (ColumnExpression) -> Collation? =
        (statement.left as? SelectStatement)?.let { declaredCollationForSelectOutput(it, columns) } ?: { null }

    /**
     * The same "plain `SELECT ... FROM realTable [JOIN realTable ...]`, no CTE/view/derived table"
     * structural resolution [declaredSetOperationCollation] does for a set operation's LEFT branch,
     * factored out so [declaredRecursiveCteCollation] can apply the identical rule to a recursive
     * CTE's own anchor (base) term - the branch that owns [CteScope.schema]/column identity there,
     * exactly like the LEFT branch owns [SelectResult.columns] for a plain set operation.
     */
    private fun declaredCollationForSelectOutput(select: SelectStatement, columns: List<String>): (ColumnExpression) -> Collation? {
        val items = listOf(select.from) + select.joins.map { it.from }
        val sources = items.map { item ->
            (item as? TableSource)?.let { source -> catalog.tableOrNull(source.name)?.schema?.let { (source.alias ?: source.name) to it } }
        }
        if (sources.any { it == null }) return { null }
        val resolvedSources = sources.filterNotNull()
        fun columnCollation(expression: ColumnExpression): Collation? {
            val matches = resolvedSources
                .filter { (alias, _) -> expression.qualifier == null || alias.equals(expression.qualifier, ignoreCase = true) }
                .mapNotNull { (_, schema) -> schema.column(expression.name) }
            return matches.singleOrNull()?.collation
        }
        return when (val projection = select.projection) {
            is ExpressionProjection -> outputExpr@{ outputExpr ->
                val index = columns.indexOfFirst { it.equals(outputExpr.name, ignoreCase = true) }
                val itemExpression = index.takeIf { it in projection.items.indices }?.let { projection.items[it].expression }
                (itemExpression as? ColumnExpression)?.let(::columnCollation)
            }
            is StarProjection -> outputExpr@{ outputExpr ->
                val index = columns.indexOfFirst { it.equals(outputExpr.name, ignoreCase = true) }
                if (index < 0) return@outputExpr null
                var remaining = index
                for ((_, schema) in resolvedSources) {
                    if (remaining < schema.columns.size) return@outputExpr schema.columns[remaining].collation
                    remaining -= schema.columns.size
                }
                null
            }
        }
    }

    /** Mirrors [declaredSetOperationCollation] but resolves against a recursive CTE's anchor (`base`) term. */
    private fun declaredRecursiveCteCollation(base: QueryStatement, columns: List<String>): (ColumnExpression) -> Collation? =
        (base as? SelectStatement)?.let { declaredCollationForSelectOutput(it, columns) } ?: { null }

    private fun multisetFilter(
        left: List<List<Value>>,
        right: List<List<Value>>,
        keepOnMatch: Boolean,
        keyOf: (List<Value>) -> List<Value>
    ): List<List<Value>> {
        val remainingInRight = right.groupingBy(keyOf).eachCount().toMutableMap()
        return left.filter { row ->
            val key = keyOf(row)
            val remaining = remainingInRight[key] ?: 0
            if (remaining > 0) {
                remainingInRight[key] = remaining - 1
                keepOnMatch
            } else {
                !keepOnMatch
            }
        }
    }

    private fun multiKeyComparator(
        keys: List<OrderKey>,
        evaluate: (Expression, List<Value>) -> Value,
        declaredCollationOf: (ColumnExpression) -> Collation? = { null }
    ): Comparator<List<Value>> = Comparator { first, second ->
        var result = 0
        for (key in keys) {
            val comparison = compareOrderKeyValues(
                evaluate(key.expression, first), evaluate(key.expression, second), key, declaredCollationOf
            )
            if (comparison != 0) {
                result = comparison
                break
            }
        }
        result
    }

    private fun evaluateOutputRow(columns: List<String>, expression: Expression, row: List<Value>): Value {
        val resolver = kestrel.engine.ColumnResolver { column ->
            if (column.qualifier != null) throw KestrelException("no such column: ${column.qualifier}.${column.name}")
            val index = columns.indexOfFirst { it.equals(column.name, ignoreCase = true) }
            if (index == -1) throw KestrelException("no such column: ${column.name}")
            row[index]
        }
        return Evaluator(resolver, subqueryRunner).evaluate(expression)
    }

    private fun runWith(query: WithQuery, outer: ColumnResolver?): SelectResult {
        var pushed = 0
        try {
            for (cte in query.ctes) {
                activeCtes.add(buildCteScope(query.recursive, cte))
                pushed++
            }
            return runQuery(query.body, outer)
        } finally {
            repeat(pushed) { activeCtes.removeAt(activeCtes.size - 1) }
        }
    }

    private fun buildCteScope(recursive: Boolean, cte: CommonTableExpression): CteScope {
        val recursivePlan = if (recursive) splitRecursiveCte(cte) else null
        if (recursivePlan == null) {
            val result = runQuery(cte.query, null)
            return CteScope(cte.name, cteSchema(cte, result), result.rows.map { Row(it) })
        }
        val (base, operator, recursiveTerm) = recursivePlan
        val baseResult = runQuery(base, null)
        val schema = cteSchema(cte, baseResult)
        val scope = CteScope(cte.name, schema, baseResult.rows.map { Row(it) })
        val declaredCollationOf = declaredRecursiveCteCollation(base, baseResult.columns)
        val outputExpressions = baseResult.columns.map { ColumnExpression(it) }
        val seenKeys = if (operator == SetOperator.Union)
            baseResult.rows.mapTo(HashSet()) { collationFoldedKey(it, outputExpressions, declaredCollationOf) }
        else null
        activeCtes.add(scope)
        try {
            val accumulated = mutableListOf<List<Value>>()
            accumulated.addAll(baseResult.rows)
            var working = baseResult.rows
            var iterations = 0
            while (working.isNotEmpty()) {
                iterations++
                if (iterations > MAX_RECURSION_ITERATIONS) {
                    throw KestrelException(
                        "recursive common table expression '${cte.name}' did not terminate within $MAX_RECURSION_ITERATIONS iterations"
                    )
                }
                scope.rows = working.map { Row(it) }
                var newRows = runQuery(recursiveTerm, null).rows
                if (operator == SetOperator.Union) {
                    newRows = newRows.filter { candidate -> seenKeys!!.add(collationFoldedKey(candidate, outputExpressions, declaredCollationOf)) }
                }
                if (newRows.isEmpty()) break
                accumulated.addAll(newRows)
                working = newRows
            }
            scope.rows = accumulated.map { Row(it) }
        } finally {
            activeCtes.removeAt(activeCtes.size - 1)
        }
        return scope
    }

    private data class RecursiveCtePlan(val base: QueryStatement, val operator: SetOperator, val recursiveTerm: QueryStatement)

    private fun splitRecursiveCte(cte: CommonTableExpression): RecursiveCtePlan? {
        val query = cte.query
        if (query !is SetOperationStatement) return null
        if (query.operator != SetOperator.Union && query.operator != SetOperator.UnionAll) return null
        val leftRefs = referencesName(query.left, cte.name)
        val rightRefs = referencesName(query.right, cte.name)
        return when {
            leftRefs && rightRefs ->
                throw KestrelException("recursive common table expression '${cte.name}' must not reference itself on both sides of ${query.operator}")
            rightRefs -> RecursiveCtePlan(query.left, query.operator, query.right)
            leftRefs -> RecursiveCtePlan(query.right, query.operator, query.left)
            else -> null
        }
    }

    private fun referencesName(query: QueryStatement, name: String): Boolean = when (query) {
        is SelectStatement -> referencesName(query.from, name) || query.joins.any { referencesName(it.from, name) }
        is SetOperationStatement -> referencesName(query.left, name) || referencesName(query.right, name)
        is WithQuery -> query.ctes.none { it.name.equals(name, ignoreCase = true) } && referencesName(query.body, name)
    }

    private fun referencesName(item: FromItem, name: String): Boolean = when (item) {
        is TableSource -> item.name.equals(name, ignoreCase = true)
        is SubquerySource -> referencesName(item.select, name)
    }

    private fun cteSchema(cte: CommonTableExpression, result: SelectResult): TableSchema {
        val labels = cte.columns ?: result.columns
        if (labels.size != result.columns.size) {
            throw KestrelException(
                "common table expression '${cte.name}' expects ${labels.size} columns but its query produces ${result.columns.size}"
            )
        }
        return derivedSchema(cte.name, result, labels)
    }

    private fun buildPlan(statement: SelectStatement, outer: ColumnResolver?): Plan {
        val sources = buildSources(statement)
        val plan = Plan(statement, sources, outer)
        plan.validate()
        return plan
    }

    private fun buildSources(statement: SelectStatement): List<Source> {
        val sources = mutableListOf<Source>()
        var offset = 0
        fun add(item: FromItem) {
            // A LATERAL derived table's rows depend on the row of an earlier FROM item it
            // correlates with, so they cannot be resolved once up front like every other source -
            // only its column *names* are needed here (for offset bookkeeping and validating
            // references to its alias elsewhere in the query), computed structurally from the
            // subquery's own AST without running it. Real rows are produced per left row in
            // [executeJoin].
            val (schema, rows) = if (item is SubquerySource && item.lateral) {
                lateralSchema(item) to emptyList()
            } else {
                resolveSource(item)
            }
            val chosen = sourceLabel(item)
            if (sources.any { it.alias.equals(chosen, ignoreCase = true) }) {
                throw KestrelException("duplicate table alias: $chosen")
            }
            sources.add(Source(chosen, schema.name, schema, rows, offset))
            offset += schema.columns.size
        }
        add(statement.from)
        for (join in statement.joins) add(join.from)
        return sources
    }

    private fun lateralSchema(item: SubquerySource): TableSchema {
        val labels = structuralColumnNames(item.select)
        val columns = labels.map { Column(it, ColumnType.Text, notNull = false, primaryKey = false) }
        return TableSchema(item.alias, columns)
    }

    /**
     * The output column names of [query], derived purely from its AST (no execution, no catalog
     * row data) - needed to give a LATERAL derived table a stable schema before any row of the
     * source it correlates with is known. Column names never depend on data (aliases and bare
     * column references are syntactic, and a table/view/CTE's shape is fixed), so this is safe
     * even though the real rows can only be produced later, per outer row.
     */
    private fun structuralColumnNames(query: QueryStatement, localCtes: Map<String, List<String>> = emptyMap()): List<String> =
        when (query) {
            is SelectStatement -> when (val projection = query.projection) {
                is StarProjection ->
                    structuralSourceColumnNames(query.from, localCtes) +
                        query.joins.flatMap { structuralSourceColumnNames(it.from, localCtes) }
                is ExpressionProjection -> projection.items.mapIndexed { index, item -> columnLabel(item, index) }
            }
            is SetOperationStatement -> structuralColumnNames(query.left, localCtes)
            is WithQuery -> {
                val extended = localCtes.toMutableMap()
                for (cte in query.ctes) {
                    extended[cte.name.lowercase()] = cte.columns ?: structuralColumnNames(cte.query, extended)
                }
                structuralColumnNames(query.body, extended)
            }
        }

    private fun structuralSourceColumnNames(item: FromItem, localCtes: Map<String, List<String>>): List<String> =
        when (item) {
            is TableSource -> {
                val key = item.name.lowercase()
                localCtes[key]
                    ?: activeCtes.lastOrNull { it.name.equals(item.name, ignoreCase = true) }?.schema?.columnNames
                    ?: catalog.tableOrNull(item.name)?.schema?.columnNames
                    ?: catalog.viewOrNull(item.name)?.let { structuralColumnNames(it.query, localCtes) }
                    ?: throw KestrelException("no such table: ${item.name}")
            }
            is SubquerySource -> structuralColumnNames(item.select, localCtes)
        }

    private fun sourceLabel(item: FromItem): String = when (item) {
        is TableSource -> item.alias ?: item.name
        is SubquerySource -> item.alias
    }

    private fun resolveSource(item: FromItem): Pair<TableSchema, List<Row>> = when (item) {
        is TableSource -> resolveNamedSource(item.name)
        is SubquerySource -> {
            val result = runQuery(item.select)
            derivedSchema(item.alias, result) to result.rows.map { Row(it) }
        }
    }

    private fun resolveNamedSource(name: String): Pair<TableSchema, List<Row>> {
        activeCtes.lastOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.schema to it.rows }
        catalog.tableOrNull(name)?.let { return it.schema to it.rows }
        val view = catalog.viewOrNull(name) ?: throw KestrelException("no such table: $name")
        val key = name.lowercase()
        if (!expandingViews.add(key)) {
            throw KestrelException("recursive view definition: $name")
        }
        try {
            val result = runQuery(view.query)
            return derivedSchema(view.name, result) to result.rows.map { Row(it) }
        } finally {
            expandingViews.remove(key)
        }
    }

    private fun derivedSchema(name: String, result: SelectResult, labels: List<String> = result.columns): TableSchema {
        val columns = labels.mapIndexed { index, columnName ->
            val type = result.rows.asSequence().map { it[index] }.firstOrNull { !it.isNull }?.type ?: ColumnType.Text
            Column(columnName, type, notNull = false, primaryKey = false)
        }
        return TableSchema(name, columns)
    }

    private class CteScope(val name: String, val schema: TableSchema, var rows: List<Row>)

    private inner class Plan(val statement: SelectStatement, val sources: List<Source>, val outer: ColumnResolver?) {
        val aggregated: Boolean =
            statement.groupBy.isNotEmpty() ||
                statement.groupingSpec != null ||
                containsAggregate(statement.projection) ||
                (statement.having != null && containsAggregate(statement.having))

        private val columnNames: List<String> = sources.flatMap { it.schema.columnNames }

        private val aliasExpressions: Map<String, Expression> =
            (statement.projection as? ExpressionProjection)?.items
                ?.mapNotNull { item -> item.alias?.let { it.lowercase() to item.expression } }
                ?.toMap() ?: emptyMap()

        private val orderKeys: List<OrderKey> =
            statement.orderBy?.keys.orEmpty().map { OrderKey(resolveOrderAlias(it.expression), it.descending, it.nullsFirst) }

        private fun resolveOrderAlias(expression: Expression): Expression {
            if (expression is ColumnExpression && expression.qualifier == null) {
                aliasExpressions[expression.name.lowercase()]?.let { return it }
            }
            return expression
        }

        /**
         * Every distinct window-function call in the SELECT list or (post alias-resolution) ORDER
         * BY - the only two clauses where one is allowed. Computed once per [Plan] and reused both
         * to append the values ([appendWindowColumns]) and to substitute them back in ([evaluateRow]).
         */
        private val windowExpressions: List<WindowExpression> = run {
            val projectionWindows = (statement.projection as? ExpressionProjection)?.items
                ?.flatMap { collectWindowExpressions(it.expression) } ?: emptyList()
            val orderWindows = orderKeys.flatMap { collectWindowExpressions(it.expression) }
            (projectionWindows + orderWindows).distinct()
        }

        fun resolveIndexOrNull(expression: ColumnExpression): Int? {
            val qualifier = expression.qualifier
            val matches = mutableListOf<Int>()
            for (source in sources) {
                if (qualifier != null && !source.alias.equals(qualifier, ignoreCase = true)) continue
                val local = source.schema.columnIndex(expression.name) ?: continue
                matches.add(source.offset + local)
            }
            return when {
                matches.isEmpty() -> null
                matches.size > 1 -> throw KestrelException("ambiguous column reference: ${describe(expression)}")
                else -> matches.single()
            }
        }

        fun resolveIndex(expression: ColumnExpression): Int =
            resolveIndexOrNull(expression) ?: throw KestrelException("no such column: ${describe(expression)}")

        /**
         * [expression]'s declared `COLLATE` (its [kestrel.catalog.Column.collation]) from whichever
         * [sources] entry it resolves against - `null` when it names no column here (ambiguous
         * across sources, same as [resolveIndexOrNull], resolves to nothing at all, or the matching
         * column simply declared no COLLATE of its own).
         */
        fun declaredCollationOrNull(expression: ColumnExpression): Collation? {
            val qualifier = expression.qualifier
            val matches = mutableListOf<Collation>()
            for (source in sources) {
                if (qualifier != null && !source.alias.equals(qualifier, ignoreCase = true)) continue
                val column = source.schema.column(expression.name) ?: continue
                matches.add(column.collation)
            }
            return matches.singleOrNull()
        }

        private fun sourceOfGlobal(global: Int): Int =
            sources.indexOfLast { global >= it.offset }

        private fun referencedSources(expression: Expression): Set<Int> =
            collectColumns(expression).map { sourceOfGlobal(resolveIndex(it)) }.toSet()

        fun validate() {
            statement.where?.let { validateColumns(it); rejectWindowFunction(it, "WHERE") }
            orderKeys.forEach { validateColumns(it.expression) }
            statement.groupBy.forEach { validateColumns(it); rejectWindowFunction(it, "GROUP BY") }
            statement.having?.let { validateColumns(it); rejectWindowFunction(it, "HAVING") }
            if (statement.projection is ExpressionProjection) {
                statement.projection.items.forEach { validateColumns(it.expression) }
            }
            statement.joins.forEachIndexed { position, join ->
                // CROSS JOIN has no ON condition at all (its `on` is null) - nothing to validate.
                val on = join.on ?: return@forEachIndexed
                validateColumns(on)
                rejectWindowFunction(on, "an ON condition")
                val allowed = position + 1
                if (referencedSources(on).any { it > allowed }) {
                    throw KestrelException("ON condition for ${sourceLabel(join.from)} references a table joined later")
                }
            }
            if (aggregated) validateAggregation()
            if (windowExpressions.isNotEmpty()) validateWindows()
            if (statement.distinctOn.isNotEmpty()) validateDistinctOn()
        }

        /**
         * `DISTINCT ON (...)` has no unsurprising meaning once rows are already collapsed into
         * aggregate groups - rejected outright rather than guessing which pre-aggregate row a group
         * should be "first" for. The leading-`ORDER BY` requirement mirrors Postgres: `DISTINCT ON`
         * exists to pick a deterministic row per group, which only means something if `ORDER BY`
         * (when present at all) agrees on what "first" means for those same expressions.
         */
        private fun validateDistinctOn() {
            statement.distinctOn.forEach { validateColumns(it); rejectWindowFunction(it, "DISTINCT ON") }
            if (aggregated) {
                throw KestrelException("DISTINCT ON cannot be combined with GROUP BY or aggregate functions")
            }
            if (statement.orderBy != null) {
                val leading = orderKeys.take(statement.distinctOn.size).map { it.expression }
                if (leading != statement.distinctOn) {
                    throw KestrelException("SELECT DISTINCT ON expressions must match initial ORDER BY expressions")
                }
            }
        }

        private fun rejectWindowFunction(expression: Expression, clause: String) {
            if (containsWindowFunction(expression)) {
                throw KestrelException("window functions are not allowed in $clause")
            }
        }

        /**
         * Window functions are rejected outright alongside GROUP BY/aggregates ([aggregated]) -
         * windowing over an already-grouped result is valid SQL but requires running GroupAggregation
         * first and then a second pass over its output rows, which this engine does not implement.
         * RANK/DENSE_RANK need an explicit tie-break (unlike ROW_NUMBER, which falls back to this
         * partition's row arrival order when OVER has no ORDER BY) and nesting one window function
         * inside another's argument/PARTITION BY/ORDER BY is invalid SQL in the first place.
         */
        private fun validateWindows() {
            if (aggregated) {
                throw KestrelException("window functions cannot be combined with GROUP BY or aggregate functions in the same query")
            }
            for (window in windowExpressions) {
                if ((window.kind == WindowFunctionKind.Rank || window.kind == WindowFunctionKind.DenseRank) && window.orderBy.isEmpty()) {
                    throw KestrelException("${window.name}() OVER (...) requires an ORDER BY clause")
                }
                if (window.frame != null && window.kind !in FRAME_CAPABLE_KINDS) {
                    throw KestrelException("${window.name}() cannot be used with a ROWS/RANGE/GROUPS frame clause")
                }
                val nested = listOfNotNull(
                    window.argument, window.offset, window.defaultValue,
                    window.frame?.start?.offset, window.frame?.end?.offset
                ) + window.partitionBy + window.orderBy.map { it.expression }
                if (nested.any { containsWindowFunction(it) }) {
                    throw KestrelException("window functions cannot be nested")
                }
            }
        }

        private fun validateColumns(expression: Expression) {
            collectColumns(expression).forEach { column ->
                if (resolveIndexOrNull(column) == null && outer == null) {
                    throw KestrelException("no such column: ${describe(column)}")
                }
            }
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
            orderKeys.forEach { requireGrouped(it.expression, groupColumns) }
        }

        private fun requireGrouped(expression: Expression, groupColumns: Set<Int>) {
            if (expression in statement.groupBy) return
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
                is CallExpression -> expression.arguments.forEach { requireGrouped(it, groupColumns) }
                is IsNullExpression -> requireGrouped(expression.operand, groupColumns)
                is InExpression -> {
                    requireGrouped(expression.operand, groupColumns)
                    expression.values.forEach { requireGrouped(it, groupColumns) }
                }
                is BetweenExpression -> {
                    requireGrouped(expression.operand, groupColumns)
                    requireGrouped(expression.low, groupColumns)
                    requireGrouped(expression.high, groupColumns)
                }
                is LikeExpression -> {
                    requireGrouped(expression.operand, groupColumns)
                    requireGrouped(expression.pattern, groupColumns)
                }
                is CaseExpression -> {
                    expression.operand?.let { requireGrouped(it, groupColumns) }
                    expression.branches.forEach {
                        requireGrouped(it.condition, groupColumns)
                        requireGrouped(it.result, groupColumns)
                    }
                    expression.elseExpression?.let { requireGrouped(it, groupColumns) }
                }
                is ScalarSubqueryExpression -> Unit
                is ExistsExpression -> Unit
                is InSubqueryExpression -> requireGrouped(expression.operand, groupColumns)
                is CastExpression -> requireGrouped(expression.operand, groupColumns)
                is CollateExpression -> requireGrouped(expression.operand, groupColumns)
                // Unreachable: validateWindows() rejects mixing window functions with GROUP BY/
                // aggregates before requireGrouped ever runs, so this branch only keeps `when` exhaustive.
                is WindowExpression -> Unit
                // Unreachable in practice: NEXTVAL/CURRVAL/MERGE_ACTION() are never legal in a SELECT
                // at all (MERGE's RETURNING is evaluated by Executor.evaluateMergeReturning, an
                // entirely separate projection path from this one) - kept here only to keep `when`
                // exhaustive.
                is NextValueExpression, is CurrentValueExpression, is MergeActionExpression -> Unit
                // A parameter reference behaves like any other constant here - `SELECT $1, SUM(x)
                // FROM t GROUP BY y` needs $1 in GROUP BY no more than a literal would.
                is ParameterExpression -> Unit
            }
        }

        private fun rowResolver(row: List<Value>) = object : kestrel.engine.ColumnResolver {
            override fun resolve(expression: ColumnExpression): Value {
                val index = resolveIndexOrNull(expression)
                return if (index != null) {
                    row[index]
                } else {
                    (outer ?: throw KestrelException("no such column: ${describe(expression)}")).resolve(expression)
                }
            }

            override fun declaredCollation(expression: ColumnExpression): Collation? =
                declaredCollationOrNull(expression) ?: outer?.declaredCollation(expression)
        }

        /**
         * When [windowExpressions] is non-empty, a [row] handed in here may carry extra trailing
         * values appended by [appendWindowColumns] (one per entry of [windowExpressions], in order,
         * right after the real columns) - [substituteWindowValues] rewrites any window-function node
         * in [expression] into a [LiteralExpression] reading straight from that trailing slot before
         * the plain [Evaluator] ever sees it, the same trick [GroupAggregation] uses for aggregates.
         */
        private fun evaluateRow(expression: Expression, row: List<Value>): Value {
            val prepared = if (windowExpressions.isEmpty()) expression else substituteWindowValues(expression, row)
            return Evaluator(rowResolver(row), subqueryRunner).evaluate(prepared)
        }

        private fun substituteWindowValues(expression: Expression, row: List<Value>): Expression = when (expression) {
            is WindowExpression -> LiteralExpression(row[columnNames.size + windowExpressions.indexOf(expression)])
            is BinaryExpression -> expression.copy(
                left = substituteWindowValues(expression.left, row),
                right = substituteWindowValues(expression.right, row)
            )
            is UnaryExpression -> expression.copy(operand = substituteWindowValues(expression.operand, row))
            is CallExpression -> expression.copy(arguments = expression.arguments.map { substituteWindowValues(it, row) })
            is IsNullExpression -> expression.copy(operand = substituteWindowValues(expression.operand, row))
            is InExpression -> expression.copy(
                operand = substituteWindowValues(expression.operand, row),
                values = expression.values.map { substituteWindowValues(it, row) }
            )
            is BetweenExpression -> expression.copy(
                operand = substituteWindowValues(expression.operand, row),
                low = substituteWindowValues(expression.low, row),
                high = substituteWindowValues(expression.high, row)
            )
            is LikeExpression -> expression.copy(
                operand = substituteWindowValues(expression.operand, row),
                pattern = substituteWindowValues(expression.pattern, row)
            )
            is CaseExpression -> expression.copy(
                operand = expression.operand?.let { substituteWindowValues(it, row) },
                branches = expression.branches.map {
                    it.copy(condition = substituteWindowValues(it.condition, row), result = substituteWindowValues(it.result, row))
                },
                elseExpression = expression.elseExpression?.let { substituteWindowValues(it, row) }
            )
            is CastExpression -> expression.copy(operand = substituteWindowValues(expression.operand, row))
            is CollateExpression -> expression.copy(operand = substituteWindowValues(expression.operand, row))
            else -> expression
        }

        fun materialize(): List<List<Value>> {
            var combined = primaryRows().map { it.values }
            statement.joins.forEachIndexed { position, join ->
                combined = executeJoin(combined, join, position + 1)
            }
            statement.where?.let { predicate ->
                combined = combined.filter { isTrue(evaluateRow(predicate, it)) }
            }
            return appendWindowColumns(combined)
        }

        /**
         * Runs after FROM/JOIN/WHERE and (per [validateWindows]) never alongside GROUP BY, matching
         * standard SQL's logical order of window-function evaluation. Each entry of
         * [windowExpressions] contributes one trailing value per row, computed once over the whole
         * (already filtered) row set - [evaluateRow]/[substituteWindowValues] read them back out by
         * position for the final projection and this query's own ORDER BY.
         */
        private fun appendWindowColumns(combined: List<List<Value>>): List<List<Value>> {
            if (windowExpressions.isEmpty()) return combined
            val perExpression = windowExpressions.map { computeWindowValues(it, combined) }
            return combined.mapIndexed { rowIndex, row -> row + windowExpressions.indices.map { perExpression[it][rowIndex] } }
        }

        private fun computeWindowValues(window: WindowExpression, combined: List<List<Value>>): List<Value> {
            val values = arrayOfNulls<Value>(combined.size)
            val partitions = combined.indices.groupBy { index -> window.partitionBy.map { evaluateRow(it, combined[index]) } }
            for (memberIndices in partitions.values) {
                val ordered = if (window.orderBy.isEmpty()) {
                    memberIndices
                } else {
                    memberIndices.sortedWith(Comparator { first, second ->
                        var result = 0
                        for (key in window.orderBy) {
                            val comparison = compareOrderKeyValues(
                                evaluateRow(key.expression, combined[first]),
                                evaluateRow(key.expression, combined[second]),
                                key,
                                ::declaredCollationOrNull
                            )
                            if (comparison != 0) {
                                result = comparison
                                break
                            }
                        }
                        result
                    })
                }
                assignWindowValues(window, ordered, combined, values)
            }
            return values.map { it ?: error("window value not computed for every row") }
        }

        private fun assignWindowValues(window: WindowExpression, ordered: List<Int>, combined: List<List<Value>>, out: Array<Value?>) {
            when (window.kind) {
                WindowFunctionKind.RowNumber ->
                    ordered.forEachIndexed { position, index -> out[index] = Value.IntegerValue((position + 1).toLong()) }
                WindowFunctionKind.Rank, WindowFunctionKind.DenseRank -> {
                    var rank = 0
                    var denseRank = 0
                    var previousKey: List<Value>? = null
                    ordered.forEachIndexed { position, index ->
                        val key = window.orderBy.map { evaluateRow(it.expression, combined[index]) }
                        if (previousKey == null || key != previousKey) {
                            rank = position + 1
                            denseRank++
                            previousKey = key
                        }
                        out[index] = Value.IntegerValue((if (window.kind == WindowFunctionKind.Rank) rank else denseRank).toLong())
                    }
                }
                WindowFunctionKind.Aggregate -> assignAggregateWindowValues(window, ordered, combined, out)
                WindowFunctionKind.Lag -> assignOffsetWindowValues(window, ordered, combined, out, direction = -1)
                WindowFunctionKind.Lead -> assignOffsetWindowValues(window, ordered, combined, out, direction = 1)
                WindowFunctionKind.FirstValue -> {
                    val frame = window.frame
                    if (frame == null) {
                        val firstValue = evaluateRow(window.argument!!, combined[ordered.first()])
                        ordered.forEach { index -> out[index] = firstValue }
                    } else {
                        ordered.indices.forEach { position ->
                            val members = frameMembers(window, position, ordered, combined)
                            out[ordered[position]] =
                                if (members.isEmpty()) Value.NullValue else evaluateRow(window.argument!!, combined[ordered[members.first()]])
                        }
                    }
                }
                WindowFunctionKind.LastValue -> {
                    val frame = window.frame
                    if (frame == null) {
                        for (range in peerGroupRanges(window.orderBy, ordered, combined)) {
                            val value = evaluateRow(window.argument!!, combined[ordered[range.last]])
                            for (position in range) out[ordered[position]] = value
                        }
                    } else {
                        ordered.indices.forEach { position ->
                            val members = frameMembers(window, position, ordered, combined)
                            out[ordered[position]] =
                                if (members.isEmpty()) Value.NullValue else evaluateRow(window.argument!!, combined[ordered[members.last()]])
                        }
                    }
                }
                WindowFunctionKind.Ntile -> assignNtileWindowValues(window, ordered, combined, out)
            }
        }

        /**
         * With no ORDER BY in OVER, the whole partition is one frame (every row gets the same
         * value). With an ORDER BY, the default SQL frame is `RANGE BETWEEN UNBOUNDED PRECEDING AND
         * CURRENT ROW`: a running aggregate where rows tied on the order key (a "peer group") share
         * one value - the aggregate through the end of their peer group, not through their own row
         * alone. A single-column running total (`ORDER BY` with no ties) falls out of this as the
         * special case where every peer group has size 1.
         */
        private fun assignAggregateWindowValues(window: WindowExpression, ordered: List<Int>, combined: List<List<Value>>, out: Array<Value?>) {
            val frame = window.frame
            if (frame == null) {
                for (range in peerGroupRanges(window.orderBy, ordered, combined)) {
                    val value = aggregateWindowValue(window, (0..range.last).map { combined[ordered[it]] })
                    for (position in range) out[ordered[position]] = value
                }
            } else {
                ordered.indices.forEach { position ->
                    val members = frameMembers(window, position, ordered, combined)
                    out[ordered[position]] = aggregateWindowValue(window, members.map { combined[ordered[it]] })
                }
            }
        }

        /**
         * The frame's base [frameRange], narrowed by its [FrameExclude] mode (default [FrameExclude.NoOthers],
         * which leaves the range untouched). Excluding rows can split a contiguous range into two
         * pieces (e.g. `EXCLUDE CURRENT ROW` on a frame that spans the current row), so this returns
         * the actual member positions rather than an [IntRange].
         */
        private fun frameMembers(window: WindowExpression, position: Int, ordered: List<Int>, combined: List<List<Value>>): List<Int> {
            val range = frameRange(window, position, ordered, combined)
            if (range.isEmpty()) return emptyList()
            return when (window.frame!!.exclude) {
                FrameExclude.NoOthers -> range.toList()
                FrameExclude.CurrentRow -> range.filter { it != position }
                FrameExclude.Group -> {
                    val peer = peerBoundary(window.orderBy, position, ordered, combined, isStart = true)..
                        peerBoundary(window.orderBy, position, ordered, combined, isStart = false)
                    range.filter { it !in peer }
                }
                FrameExclude.Ties -> {
                    val peer = peerBoundary(window.orderBy, position, ordered, combined, isStart = true)..
                        peerBoundary(window.orderBy, position, ordered, combined, isStart = false)
                    range.filter { it == position || it !in peer }
                }
            }
        }

        /**
         * Resolves an explicit `ROWS|RANGE BETWEEN ... AND ...` [WindowFrame] to a range of positions
         * into [ordered] for the row at [position] - clamped to the partition's edges rather than
         * erroring, exactly like the implicit default frame already does. A frame that falls entirely
         * off one edge (e.g. `2 FOLLOWING AND 3 FOLLOWING` on the partition's last row) yields an empty
         * range, which [aggregateWindowValue]/FIRST_VALUE/LAST_VALUE each already know how to treat as
         * "no rows" (NULL, or 0 for COUNT).
         */
        private fun frameRange(window: WindowExpression, position: Int, ordered: List<Int>, combined: List<List<Value>>): IntRange {
            val frame = window.frame!!
            if (frame.unit == FrameUnit.Groups) {
                return groupsFrameRange(frame, window.orderBy, position, ordered, combined)
            }
            val size = ordered.size
            val start = resolveFrameBound(frame, frame.start, window, position, ordered, combined, isStart = true)
            val end = resolveFrameBound(frame, frame.end, window, position, ordered, combined, isStart = false)
            val clampedStart = start.coerceAtLeast(0)
            val clampedEnd = end.coerceAtMost(size - 1)
            return if (clampedStart > clampedEnd) IntRange.EMPTY else clampedStart..clampedEnd
        }

        private fun resolveFrameBound(
            frame: WindowFrame,
            bound: FrameBound,
            window: WindowExpression,
            position: Int,
            ordered: List<Int>,
            combined: List<List<Value>>,
            isStart: Boolean
        ): Int = when (bound.type) {
            FrameBoundType.UnboundedPreceding -> 0
            FrameBoundType.UnboundedFollowing -> ordered.size - 1
            FrameBoundType.CurrentRow ->
                if (frame.unit == FrameUnit.Rows) position else peerBoundary(window.orderBy, position, ordered, combined, isStart)
            FrameBoundType.Preceding ->
                if (frame.unit == FrameUnit.Rows) position - resolveIntegerFrameOffset(bound, combined[ordered[position]], "ROWS")
                else rangeBoundary(bound, window.orderBy, position, ordered, combined, isStart, subtract = true)
            FrameBoundType.Following ->
                if (frame.unit == FrameUnit.Rows) position + resolveIntegerFrameOffset(bound, combined[ordered[position]], "ROWS")
                else rangeBoundary(bound, window.orderBy, position, ordered, combined, isStart, subtract = false)
        }

        private fun resolveIntegerFrameOffset(bound: FrameBound, row: List<Value>, unit: String): Int {
            val value = evaluateRow(bound.offset!!, row)
            val amount = (value as? Value.IntegerValue)?.value
                ?: throw KestrelException("$unit frame offset must be an integer")
            if (amount < 0) throw KestrelException("$unit frame offset must not be negative")
            return amount.toInt()
        }

        /**
         * `GROUPS BETWEEN <bound> AND <bound>` counts whole peer groups (rows tied on the OVER's
         * ORDER BY) rather than physical rows ([FrameUnit.Rows]) or distance in a value
         * ([FrameUnit.Range]) - `1 PRECEDING` means "the peer group immediately before the current
         * row's own peer group", however many rows that group has. Resolved entirely in group-index
         * space (mirroring exactly how [frameRange] resolves ROWS/RANGE in row-index space, including
         * the same one-sided-clamp trick for correctly detecting an out-of-partition frame as empty
         * rather than accidentally clamping it back into range), then translated to a row [IntRange]
         * only once both bounds land on a valid group. Unlike [rangeBoundary], this places no
         * restriction on the OVER's ORDER BY - zero, one, or many columns of any type all define peer
         * groups equally well, since [peerGroupRanges] only ever compares them for equality.
         */
        private fun groupsFrameRange(
            frame: WindowFrame,
            orderBy: List<OrderKey>,
            position: Int,
            ordered: List<Int>,
            combined: List<List<Value>>
        ): IntRange {
            val groups = peerGroupRanges(orderBy, ordered, combined)
            val currentGroup = groups.indexOfFirst { position in it }
            val row = combined[ordered[position]]
            val startGroup = resolveGroupBound(frame.start, currentGroup, groups.size, row).coerceAtLeast(0)
            val endGroup = resolveGroupBound(frame.end, currentGroup, groups.size, row).coerceAtMost(groups.size - 1)
            return if (startGroup > endGroup) IntRange.EMPTY else groups[startGroup].first..groups[endGroup].last
        }

        private fun resolveGroupBound(bound: FrameBound, currentGroup: Int, groupCount: Int, row: List<Value>): Int = when (bound.type) {
            FrameBoundType.UnboundedPreceding -> 0
            FrameBoundType.UnboundedFollowing -> groupCount - 1
            FrameBoundType.CurrentRow -> currentGroup
            FrameBoundType.Preceding -> currentGroup - resolveIntegerFrameOffset(bound, row, "GROUPS")
            FrameBoundType.Following -> currentGroup + resolveIntegerFrameOffset(bound, row, "GROUPS")
        }

        /**
         * `CURRENT ROW` in a RANGE frame does not mean "just this row" (that is what ROWS is for) - it
         * means the boundary of the current row's peer group (every row tied with it on the OVER's
         * ORDER BY, or the whole partition when there is no ORDER BY). [isStart] picks which edge of
         * that peer group to return.
         */
        private fun peerBoundary(orderBy: List<OrderKey>, position: Int, ordered: List<Int>, combined: List<List<Value>>, isStart: Boolean): Int {
            if (orderBy.isEmpty()) return if (isStart) 0 else ordered.size - 1
            fun keyAt(index: Int) = orderBy.map { evaluateRow(it.expression, combined[ordered[index]]) }
            val key = keyAt(position)
            var boundary = position
            if (isStart) {
                while (boundary > 0 && keyAt(boundary - 1) == key) boundary--
            } else {
                while (boundary < ordered.size - 1 && keyAt(boundary + 1) == key) boundary++
            }
            return boundary
        }

        /**
         * `<value> PRECEDING`/`<value> FOLLOWING` in a RANGE frame measure distance in the value of a
         * single numeric or DATE ORDER BY column rather than a row count, so the frame can span a
         * different number of physical rows for every current row (e.g. `RANGE BETWEEN 5 PRECEDING AND
         * 5 FOLLOWING` on `ORDER BY amount` widens or narrows with how densely `amount` is populated
         * nearby). Binary search works because [ordered] is already sorted on this column - ascending
         * in "logical" terms via [rangeLogicalValue], which folds DESC into a sign flip so the same
         * `>=`/`<=` comparisons apply regardless of sort direction.
         */
        private fun rangeBoundary(
            bound: FrameBound,
            orderBy: List<OrderKey>,
            position: Int,
            ordered: List<Int>,
            combined: List<List<Value>>,
            isStart: Boolean,
            subtract: Boolean
        ): Int {
            if (orderBy.size != 1) {
                throw KestrelException("a RANGE frame with a PRECEDING/FOLLOWING bound requires exactly one ORDER BY column")
            }
            val key = orderBy.single()
            val current = rangeLogicalValue(key, combined[ordered[position]])
            val offset = resolveRangeFrameOffset(bound, combined[ordered[position]])
            val threshold = if (subtract) current - offset else current + offset
            return if (isStart) {
                rangeLowerBound(ordered, combined, key, threshold)
            } else {
                rangeUpperBound(ordered, combined, key, threshold)
            }
        }

        private fun rangeLowerBound(ordered: List<Int>, combined: List<List<Value>>, key: OrderKey, threshold: BigDecimal): Int {
            var low = 0
            var high = ordered.size
            while (low < high) {
                val mid = (low + high) / 2
                if (rangeLogicalValue(key, combined[ordered[mid]]) >= threshold) high = mid else low = mid + 1
            }
            return low
        }

        private fun rangeUpperBound(ordered: List<Int>, combined: List<List<Value>>, key: OrderKey, threshold: BigDecimal): Int {
            var low = 0
            var high = ordered.size
            while (low < high) {
                val mid = (low + high) / 2
                if (rangeLogicalValue(key, combined[ordered[mid]]) <= threshold) low = mid + 1 else high = mid
            }
            return low - 1
        }

        private fun rangeLogicalValue(key: OrderKey, row: List<Value>): BigDecimal {
            val magnitude = rangeMagnitude(evaluateRow(key.expression, row))
            return if (key.descending) magnitude.negate() else magnitude
        }

        private fun rangeMagnitude(value: Value): BigDecimal = when (value) {
            is Value.IntegerValue -> BigDecimal(value.value)
            is Value.DecimalValue -> value.value
            is Value.RealValue -> BigDecimal.valueOf(value.value)
            is Value.DateValue -> BigDecimal(value.value.toEpochDay())
            else -> throw KestrelException("a RANGE frame requires the ORDER BY column to be numeric or DATE")
        }

        private fun resolveRangeFrameOffset(bound: FrameBound, row: List<Value>): BigDecimal {
            val offset = when (val value = evaluateRow(bound.offset!!, row)) {
                is Value.IntegerValue -> BigDecimal(value.value)
                is Value.DecimalValue -> value.value
                is Value.RealValue -> BigDecimal.valueOf(value.value)
                else -> throw KestrelException("RANGE frame offset must be numeric")
            }
            if (offset.signum() < 0) throw KestrelException("RANGE frame offset must not be negative")
            return offset
        }

        /**
         * Splits [ordered] (positions into [combined], already sorted by the OVER's ORDER BY) into
         * contiguous "peer group" ranges of rows tied on the order key - or one range spanning the
         * whole partition when there is no ORDER BY, matching the default SQL frame. Shared by
         * [assignAggregateWindowValues] (running aggregate through the end of a peer group) and
         * [WindowFunctionKind.LastValue] (last row of a peer group).
         */
        private fun peerGroupRanges(orderBy: List<OrderKey>, ordered: List<Int>, combined: List<List<Value>>): List<IntRange> {
            if (orderBy.isEmpty()) return listOf(ordered.indices)
            val keys = ordered.map { index -> orderBy.map { key -> evaluateRow(key.expression, combined[index]) } }
            val ranges = mutableListOf<IntRange>()
            var i = 0
            while (i < ordered.size) {
                var j = i
                while (j < ordered.size && keys[j] == keys[i]) j++
                ranges.add(i until j)
                i = j
            }
            return ranges
        }

        private fun aggregateWindowValue(window: WindowExpression, rows: List<List<Value>>): Value =
            AggregateFunctions.compute(window.name, window.argument, window.star, rows, ::evaluateRow)

        /**
         * LAG (direction -1) / LEAD (direction +1): reads [WindowExpression.argument] from the row
         * [WindowExpression.offset] positions before/after the current one in [ordered] (default 1,
         * evaluated per row so it may itself be a computed expression, though in practice it is
         * almost always a literal). Past either edge of the partition, falls back to
         * [WindowExpression.defaultValue] (evaluated against the *current* row) or NULL.
         */
        private fun assignOffsetWindowValues(
            window: WindowExpression,
            ordered: List<Int>,
            combined: List<List<Value>>,
            out: Array<Value?>,
            direction: Int
        ) {
            ordered.forEachIndexed { position, index ->
                val offset = resolveOffset(window, combined[index])
                val targetPosition = position + direction * offset
                out[index] = if (targetPosition in ordered.indices) {
                    evaluateRow(window.argument!!, combined[ordered[targetPosition]])
                } else {
                    window.defaultValue?.let { evaluateRow(it, combined[index]) } ?: Value.NullValue
                }
            }
        }

        private fun resolveOffset(window: WindowExpression, row: List<Value>): Int {
            val expression = window.offset ?: return 1
            val value = evaluateRow(expression, row)
            val amount = (value as? Value.IntegerValue)?.value
                ?: throw KestrelException("${window.name}() offset must be an integer")
            if (amount < 0) throw KestrelException("${window.name}() offset must not be negative")
            return amount.toInt()
        }

        /**
         * NTILE(n): splits the partition into `n` buckets as evenly as possible - when the partition
         * size does not divide evenly, the first `size % n` buckets get one extra row, exactly like
         * every other SQL engine's NTILE. `n` larger than the partition just leaves the highest
         * bucket numbers unused, rather than being an error.
         */
        private fun assignNtileWindowValues(window: WindowExpression, ordered: List<Int>, combined: List<List<Value>>, out: Array<Value?>) {
            val bucketValue = evaluateRow(window.argument!!, combined[ordered.first()])
            val buckets = (bucketValue as? Value.IntegerValue)?.value
                ?: throw KestrelException("${window.name}() bucket count must be an integer")
            if (buckets <= 0) throw KestrelException("${window.name}() bucket count must be positive")
            val bucketCount = buckets.toInt()
            val base = ordered.size / bucketCount
            val remainder = ordered.size % bucketCount
            var position = 0
            for (bucket in 1..bucketCount) {
                val size = base + if (bucket <= remainder) 1 else 0
                repeat(size) {
                    out[ordered[position]] = Value.IntegerValue(bucket.toLong())
                    position++
                }
            }
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
            // Width of everything already combined to the left of this join - exactly what a RIGHT/
            // FULL JOIN needs to pad an unmatched right row with, since `right.offset` is defined as
            // the running column count of every source before it.
            val nullLeft = List(right.offset) { Value.NullValue }
            val outerRight = join.type == JoinType.Right || join.type == JoinType.Full
            val outerLeft = join.type == JoinType.Left || join.type == JoinType.Full
            val lateralSelect = lateralSelectOf(join.from)
            if (lateralSelect != null) return executeLateralJoin(left, join, lateralSelect, nullRight)
            val hashKeys = hashJoinKeys(join, rightIndex)
            val result = mutableListOf<List<Value>>()
            if (hashKeys != null) {
                val buckets = HashMap<Value, MutableList<IndexedValue<Row>>>()
                for (indexedRow in right.rows.withIndex()) {
                    val key = foldHashJoinKey(evaluateOnRight(hashKeys.buildExpression, right, indexedRow.value), hashKeys.collation)
                    if (key !is Value.NullValue) buckets.getOrPut(key) { mutableListOf() }.add(indexedRow)
                }
                val matchedRight = BooleanArray(right.rows.size)
                for (leftRow in left) {
                    val probe = foldHashJoinKey(evaluateRow(hashKeys.probeExpression, leftRow), hashKeys.collation)
                    val matches = if (probe is Value.NullValue) emptyList() else buckets[probe].orEmpty()
                    if (matches.isNotEmpty()) {
                        matches.forEach { (index, row) -> result.add(leftRow + row.values); matchedRight[index] = true }
                    } else if (outerLeft) {
                        result.add(leftRow + nullRight)
                    }
                }
                if (outerRight) {
                    right.rows.forEachIndexed { index, row -> if (!matchedRight[index]) result.add(nullLeft + row.values) }
                }
                return result
            }
            val condition = join.on
            val matchedRight = BooleanArray(right.rows.size)
            for (leftRow in left) {
                var matched = false
                right.rows.forEachIndexed { index, rightRow ->
                    val candidate = leftRow + rightRow.values
                    if (condition == null || isTrue(evaluateRow(condition, candidate))) {
                        result.add(candidate)
                        matched = true
                        matchedRight[index] = true
                    }
                }
                if (!matched && outerLeft) result.add(leftRow + nullRight)
            }
            if (outerRight) {
                right.rows.forEachIndexed { index, row -> if (!matchedRight[index]) result.add(nullLeft + row.values) }
            }
            return result
        }

        private fun lateralSelectOf(item: FromItem): QueryStatement? =
            (item as? SubquerySource)?.takeIf { it.lateral }?.select

        /**
         * Unlike every other join, a LATERAL derived table has no fixed row set: it is
         * re-evaluated once per row already accumulated on the left, correlated to that row via
         * [lateralOuterResolver] exactly like a correlated subquery in WHERE/SELECT (same
         * [SubqueryRunner] plumbing, just invoked from FROM instead). Hash-joining is therefore
         * meaningless here (there is no static right-hand row set to build buckets from) - always
         * nested loop, matching how real engines execute LATERAL.
         */
        private fun executeLateralJoin(
            left: List<List<Value>>,
            join: JoinClause,
            lateralSelect: QueryStatement,
            nullRight: List<Value>
        ): List<List<Value>> {
            val condition = join.on
            val result = mutableListOf<List<Value>>()
            for (leftRow in left) {
                val rightRows = runQuery(lateralSelect, lateralOuterResolver(leftRow)).rows
                var matched = false
                for (rightRow in rightRows) {
                    val candidate = leftRow + rightRow
                    if (condition == null || isTrue(evaluateRow(condition, candidate))) {
                        result.add(candidate)
                        matched = true
                    }
                }
                if (!matched && join.type == JoinType.Left) result.add(leftRow + nullRight)
            }
            return result
        }

        /**
         * Resolves a column against the sources already joined by the time a LATERAL subquery
         * runs (everything to its left in the FROM clause) - [leftRow] holds exactly their values,
         * so a resolved global index landing past its end means the expression names a source that
         * has not been joined yet (or is the LATERAL item itself), which SQL forbids.
         */
        private fun lateralOuterResolver(leftRow: List<Value>): kestrel.engine.ColumnResolver = object : kestrel.engine.ColumnResolver {
            override fun resolve(expression: ColumnExpression): Value {
                val index = resolveIndex(expression)
                if (index >= leftRow.size) {
                    throw KestrelException(
                        "LATERAL subquery cannot reference ${describe(expression)}: not joined yet at this point in the FROM clause"
                    )
                }
                return leftRow[index]
            }

            override fun declaredCollation(expression: ColumnExpression): Collation? =
                resolveIndexOrNull(expression)?.takeIf { it < leftRow.size }?.let { declaredCollationOrNull(expression) }
        }

        private fun evaluateOnRight(expression: Expression, right: Source, rightRow: Row): Value {
            val resolver = kestrel.engine.ColumnResolver { columnExpression ->
                rightRow.values[resolveIndex(columnExpression) - right.offset]
            }
            return Evaluator(resolver).evaluate(expression)
        }

        /**
         * A [Value.TextValue] hash-join key under any collation but [Collation.Binary] must land in
         * the same bucket as every other spelling the declared/explicit `COLLATE` treats as equal
         * (e.g. `'Bob'`/`'bob'` under `NOCASE`) - otherwise the fast path silently disagrees with
         * the nested-loop `Evaluator` path it is an optimization of, which already resolves this via
         * [pairCollation] for every non-hashable equality condition. [Collation.Binary]'s
         * [Collation.canonicalize] is the identity, so a key from an equality with no COLLATE
         * anywhere in play keeps its exact original `equals`/`hashCode`.
         */
        private fun foldHashJoinKey(value: Value, collation: Collation): Value =
            if (value is Value.TextValue) Value.TextValue(collation.canonicalize(value.value)) else value

        private fun hashJoinKeys(join: JoinClause, rightIndex: Int): HashKeys? {
            if (lateralSelectOf(join.from) != null) return null
            val condition = join.on
            if (condition !is BinaryExpression || condition.operator != BinaryOperator.Equal) return null
            val built = (0 until rightIndex).toSet()
            val leftRefs = referencedSources(condition.left)
            val rightRefs = referencedSources(condition.right)
            // Resolved from the ORIGINAL (unswapped) left/right of `a = b`, not buildExpression/
            // probeExpression below, so `pairCollation`'s left-first tie-break matches exactly what
            // evaluating this same `=` through Evaluator would have decided regardless of which side
            // ends up building the hash buckets.
            val collation = pairCollation(condition.left, condition.right, ::declaredCollationOrNull)
            return when {
                rightRefs == setOf(rightIndex) && leftRefs.all { it in built } ->
                    HashKeys(buildExpression = condition.right, probeExpression = condition.left, collation = collation)
                leftRefs == setOf(rightIndex) && rightRefs.all { it in built } ->
                    HashKeys(buildExpression = condition.left, probeExpression = condition.right, collation = collation)
                else -> null
            }
        }

        fun primaryAccess(): PrimaryAccess {
            if (statement.joins.isNotEmpty()) return PrimaryAccess.FullScan
            val source = sources.first()
            return indexPlanner.plan(source.tableName, source.alias, source.schema, statement.where)
        }

        fun projectRows(combined: List<List<Value>>): SelectResult {
            statement.limit?.let { if (it < 0) throw KestrelException("LIMIT must not be negative") }
            statement.offset?.let { if (it < 0) throw KestrelException("OFFSET must not be negative") }
            var rows = combined
            if (orderKeys.isNotEmpty()) {
                rows = rows.sortedWith(multiKeyComparator(orderKeys, ::evaluateRow, ::declaredCollationOrNull))
            }
            if (statement.distinctOn.isNotEmpty()) {
                // Kept in the order already established above (scan order with no ORDER BY, sorted
                // order otherwise) - `LinkedHashMap.putIfAbsent` keeps the first row per key and
                // never disturbs where that first row sits relative to the others kept.
                val kept = LinkedHashMap<List<Value>, List<Value>>()
                for (row in rows) {
                    val key = statement.distinctOn.map { evaluateRow(it, row) }
                    kept.putIfAbsent(collationFoldedKey(key, statement.distinctOn, ::declaredCollationOrNull), row)
                }
                rows = kept.values.toList()
            }
            if (!statement.distinct) {
                if (statement.offset != null || statement.limit != null) {
                    rows = rows.drop(statement.offset ?: 0)
                    statement.limit?.let { rows = rows.take(it) }
                }
            }
            val (labels, projected) = when (val projection = statement.projection) {
                // rows may carry trailing window-function values appended by appendWindowColumns
                // (only possible here if ORDER BY names one directly, since `SELECT *` cannot spell
                // an OVER clause itself) - trimmed back off so the row width matches columnNames.
                is StarProjection -> columnNames to rows.map { it.subList(0, columnNames.size) }
                is ExpressionProjection -> {
                    val labels = projection.items.mapIndexed { index, item -> columnLabel(item, index) }
                    labels to rows.map { row -> projection.items.map { evaluateRow(it.expression, row) } }
                }
            }
            var resultRows = projected
            if (statement.distinct) {
                // Plain DISTINCT dedups the *output* row (post-projection); DISTINCT ON already
                // collapsed to one row per group above, on the pre-projection row, so re-deduping the
                // projected columns here would incorrectly merge distinct groups that happen to
                // project to the same visible values.
                if (statement.distinctOn.isEmpty()) {
                    val itemExpressions = when (val projection = statement.projection) {
                        is ExpressionProjection -> projection.items.map { it.expression }
                        is StarProjection -> columnNames.map { ColumnExpression(it) }
                    }
                    resultRows = resultRows.distinctBy { row -> collationFoldedKey(row, itemExpressions, ::declaredCollationOrNull) }
                }
                if (statement.offset != null || statement.limit != null) {
                    resultRows = resultRows.drop(statement.offset ?: 0)
                    statement.limit?.let { resultRows = resultRows.take(it) }
                }
            }
            return SelectResult(labels, resultRows)
        }

        fun aggregate(combined: List<List<Value>>): SelectResult =
            GroupAggregation(::resolveIndex, ::evaluateRow, this@SelectRunner::columnLabel, subqueryRunner, ::declaredCollationOrNull)
                .aggregate(statement, orderKeys, combined)

        fun describe(): List<String> {
            val lines = mutableListOf<String>()
            var body = joinLines()
            if (aggregated) {
                val keys = statement.groupBy.map { describeExpression(it) }
                val header = if (keys.isEmpty()) {
                    "Aggregate"
                } else {
                    val setCount = GroupingSetExpansion.setsFor(statement.groupingSpec, statement.groupBy).size
                    val suffix = if (setCount > 1) " ($setCount grouping sets)" else ""
                    "GroupAggregate keys: ${keys.joinToString(", ")}$suffix"
                }
                body = listOf(header) + body.map { "  $it" }
            }
            statement.orderBy?.let { body = listOf("Sort") + body.map { "  $it" } }
            if (statement.distinct) {
                val label = if (statement.distinctOn.isNotEmpty()) "Unique (DISTINCT ON)" else "Unique"
                body = listOf(label) + body.map { "  $it" }
            }
            statement.offset?.let { body = listOf("Offset ${statement.offset}") + body.map { "  $it" } }
            statement.limit?.let { body = listOf("Limit ${statement.limit}") + body.map { "  $it" } }
            lines.addAll(body)
            return lines
        }

        private fun joinLines(): List<String> {
            var lines = listOf(scanLine(sources.first(), primaryAccess()))
            statement.joins.forEachIndexed { position, join ->
                val right = sources[position + 1]
                val lateral = lateralSelectOf(join.from) != null
                val hash = !lateral && hashJoinKeys(join, position + 1) != null
                val label = joinLabel(join) + if (lateral) " Lateral" else ""
                // `hash` is only true when hashJoinKeys() matched a binary equality condition, which
                // means join.on is guaranteed non-null here (CROSS JOIN's null `on` never hashes).
                val header = if (hash) "Hash Join ${describeExpression(join.on!!)}" else "Nested Loop $label"
                val rightScan = if (lateral) "Lateral Subquery Scan on ${right.tableName}" else "Seq Scan on ${right.tableName}"
                lines = listOf(header) + lines.map { "  -> $it" } + listOf("  -> $rightScan")
            }
            return lines
        }

        private fun scanLine(source: Source, access: PrimaryAccess): String = when (access) {
            is PrimaryAccess.FullScan -> "Seq Scan on ${source.tableName}"
            is PrimaryAccess.IndexScan ->
                "Index Scan using ${access.indexName} on ${source.tableName} (${access.description})"
        }

        private fun joinLabel(join: JoinClause): String = when (join.type) {
            JoinType.Left -> "Left"
            JoinType.Right -> "Right"
            JoinType.Full -> "Full"
            JoinType.Inner -> "Inner"
            JoinType.Cross -> "Cross"
        }
    }

    private fun columnLabel(item: SelectItem, index: Int): String =
        item.alias ?: when (val expression = item.expression) {
            is ColumnExpression -> expression.name
            is FunctionExpression -> expression.name.lowercase()
            is CallExpression -> expression.name.lowercase()
            is WindowExpression -> expression.name.lowercase()
            else -> "column${index + 1}"
        }

    private fun collectColumns(expression: Expression): List<ColumnExpression> = when (expression) {
        is ColumnExpression -> listOf(expression)
        is BinaryExpression -> collectColumns(expression.left) + collectColumns(expression.right)
        is UnaryExpression -> collectColumns(expression.operand)
        is FunctionExpression ->
            (expression.argument?.let { collectColumns(it) } ?: emptyList()) +
                (expression.separator?.let { collectColumns(it) } ?: emptyList()) +
                expression.orderBy.flatMap { collectColumns(it.expression) } +
                (expression.filter?.let { collectColumns(it) } ?: emptyList())
        is LiteralExpression -> emptyList()
        is CallExpression -> expression.arguments.flatMap { collectColumns(it) }
        is IsNullExpression -> collectColumns(expression.operand)
        is InExpression -> collectColumns(expression.operand) + expression.values.flatMap { collectColumns(it) }
        is BetweenExpression -> collectColumns(expression.operand) + collectColumns(expression.low) + collectColumns(expression.high)
        is LikeExpression -> collectColumns(expression.operand) + collectColumns(expression.pattern)
        is CaseExpression -> (expression.operand?.let { collectColumns(it) } ?: emptyList()) +
            expression.branches.flatMap { collectColumns(it.condition) + collectColumns(it.result) } +
            (expression.elseExpression?.let { collectColumns(it) } ?: emptyList())
        is ScalarSubqueryExpression -> emptyList()
        is ExistsExpression -> emptyList()
        is InSubqueryExpression -> collectColumns(expression.operand)
        is CastExpression -> collectColumns(expression.operand)
        is CollateExpression -> collectColumns(expression.operand)
        is WindowExpression ->
            (expression.argument?.let { collectColumns(it) } ?: emptyList()) +
                (expression.offset?.let { collectColumns(it) } ?: emptyList()) +
                (expression.defaultValue?.let { collectColumns(it) } ?: emptyList()) +
                (expression.frame?.start?.offset?.let { collectColumns(it) } ?: emptyList()) +
                (expression.frame?.end?.offset?.let { collectColumns(it) } ?: emptyList()) +
                expression.partitionBy.flatMap { collectColumns(it) } +
                expression.orderBy.flatMap { collectColumns(it.expression) }
        is NextValueExpression, is CurrentValueExpression, is MergeActionExpression, is ParameterExpression -> emptyList()
    }

    private fun containsAggregate(projection: kestrel.sql.ast.Projection): Boolean = when (projection) {
        is StarProjection -> false
        is ExpressionProjection -> projection.items.any { containsAggregate(it.expression) }
    }

    private fun containsAggregate(expression: Expression): Boolean = when (expression) {
        is FunctionExpression -> true
        is BinaryExpression -> containsAggregate(expression.left) || containsAggregate(expression.right)
        is UnaryExpression -> containsAggregate(expression.operand)
        is CallExpression -> expression.arguments.any { containsAggregate(it) }
        is IsNullExpression -> containsAggregate(expression.operand)
        is InExpression -> containsAggregate(expression.operand) || expression.values.any { containsAggregate(it) }
        is BetweenExpression ->
            containsAggregate(expression.operand) || containsAggregate(expression.low) || containsAggregate(expression.high)
        is LikeExpression -> containsAggregate(expression.operand) || containsAggregate(expression.pattern)
        is CaseExpression ->
            (expression.operand?.let { containsAggregate(it) } ?: false) ||
                expression.branches.any { containsAggregate(it.condition) || containsAggregate(it.result) } ||
                (expression.elseExpression?.let { containsAggregate(it) } ?: false)
        is CastExpression -> containsAggregate(expression.operand)
        is CollateExpression -> containsAggregate(expression.operand)
        // A window function never triggers GROUP BY collapsing on its own - it is deliberately not
        // recursed into here, so an aggregate nested inside its OVER clause (invalid SQL) is instead
        // caught uniformly at evaluation time by the same guard that rejects any bare aggregate
        // outside GROUP BY (see Evaluator.evaluate's FunctionExpression branch).
        is WindowExpression -> false
        else -> false
    }

    /** Window functions are only legal in the SELECT list and ORDER BY - never WHERE/GROUP BY/HAVING/ON. */
    private fun containsWindowFunction(expression: Expression): Boolean = when (expression) {
        is WindowExpression -> true
        is BinaryExpression -> containsWindowFunction(expression.left) || containsWindowFunction(expression.right)
        is UnaryExpression -> containsWindowFunction(expression.operand)
        is CallExpression -> expression.arguments.any { containsWindowFunction(it) }
        is IsNullExpression -> containsWindowFunction(expression.operand)
        is InExpression -> containsWindowFunction(expression.operand) || expression.values.any { containsWindowFunction(it) }
        is BetweenExpression ->
            containsWindowFunction(expression.operand) || containsWindowFunction(expression.low) || containsWindowFunction(expression.high)
        is LikeExpression -> containsWindowFunction(expression.operand) || containsWindowFunction(expression.pattern)
        is CaseExpression ->
            (expression.operand?.let { containsWindowFunction(it) } ?: false) ||
                expression.branches.any { containsWindowFunction(it.condition) || containsWindowFunction(it.result) } ||
                (expression.elseExpression?.let { containsWindowFunction(it) } ?: false)
        is CastExpression -> containsWindowFunction(expression.operand)
        is CollateExpression -> containsWindowFunction(expression.operand)
        else -> false
    }

    /** Every distinct window-function call reachable from [expression] (nested inside arithmetic, CASE, ...). */
    private fun collectWindowExpressions(expression: Expression): List<WindowExpression> = when (expression) {
        is WindowExpression -> listOf(expression)
        is BinaryExpression -> collectWindowExpressions(expression.left) + collectWindowExpressions(expression.right)
        is UnaryExpression -> collectWindowExpressions(expression.operand)
        is CallExpression -> expression.arguments.flatMap { collectWindowExpressions(it) }
        is IsNullExpression -> collectWindowExpressions(expression.operand)
        is InExpression -> collectWindowExpressions(expression.operand) + expression.values.flatMap { collectWindowExpressions(it) }
        is BetweenExpression ->
            collectWindowExpressions(expression.operand) + collectWindowExpressions(expression.low) + collectWindowExpressions(expression.high)
        is LikeExpression -> collectWindowExpressions(expression.operand) + collectWindowExpressions(expression.pattern)
        is CaseExpression ->
            (expression.operand?.let { collectWindowExpressions(it) } ?: emptyList()) +
                expression.branches.flatMap { collectWindowExpressions(it.condition) + collectWindowExpressions(it.result) } +
                (expression.elseExpression?.let { collectWindowExpressions(it) } ?: emptyList())
        is CastExpression -> collectWindowExpressions(expression.operand)
        is CollateExpression -> collectWindowExpressions(expression.operand)
        else -> emptyList()
    }

    private fun isTrue(value: Value): Boolean = value is Value.BooleanValue && value.value

    private fun describe(expression: ColumnExpression): String =
        expression.qualifier?.let { "$it.${expression.name}" } ?: expression.name

    private fun describeExpression(expression: Expression): String = when (expression) {
        is ColumnExpression -> describe(expression)
        is LiteralExpression -> literalText(expression.value)
        is FunctionExpression -> "${expression.name}(${describeFunctionArguments(expression)})${describeFilterClause(expression)}"
        is BinaryExpression -> "${describeExpression(expression.left)} ${expression.operator.symbol} ${describeExpression(expression.right)}"
        is UnaryExpression -> "${expression.operator.symbol} ${describeExpression(expression.operand)}"
        is CallExpression -> "${expression.name}(${expression.arguments.joinToString(", ") { describeExpression(it) }})"
        is IsNullExpression -> "${describeExpression(expression.operand)} IS ${if (expression.negated) "NOT " else ""}NULL"
        is InExpression ->
            "${describeExpression(expression.operand)} ${if (expression.negated) "NOT " else ""}IN (${expression.values.joinToString(", ") { describeExpression(it) }})"
        is BetweenExpression ->
            "${describeExpression(expression.operand)} ${if (expression.negated) "NOT " else ""}BETWEEN ${describeExpression(expression.low)} AND ${describeExpression(expression.high)}"
        is LikeExpression ->
            "${describeExpression(expression.operand)} ${if (expression.negated) "NOT " else ""}LIKE ${describeExpression(expression.pattern)}"
        is CaseExpression -> buildString {
            append("CASE ")
            expression.operand?.let { append(describeExpression(it)); append(' ') }
            expression.branches.forEach { append("WHEN ${describeExpression(it.condition)} THEN ${describeExpression(it.result)} ") }
            expression.elseExpression?.let { append("ELSE ${describeExpression(it)} ") }
            append("END")
        }
        is ScalarSubqueryExpression -> "(subquery)"
        is ExistsExpression -> "EXISTS (subquery)"
        is InSubqueryExpression ->
            "${describeExpression(expression.operand)} ${if (expression.negated) "NOT " else ""}IN (subquery)"
        is CastExpression ->
            "CAST(${describeExpression(expression.operand)} AS ${expression.targetType.displayWith(expression.precision, expression.scale)})"
        is CollateExpression -> "${describeExpression(expression.operand)} COLLATE ${expression.collation.sqlName}"
        is WindowExpression ->
            "${expression.name}(${if (expression.star) "*" else expression.argument?.let { describeExpression(it) } ?: ""}) OVER (...)"
        is NextValueExpression -> "NEXTVAL('${expression.sequenceName}')"
        is CurrentValueExpression -> "CURRVAL('${expression.sequenceName}')"
        is MergeActionExpression -> "MERGE_ACTION()"
        is ParameterExpression -> "\$${expression.index}"
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
