package kestrel.catalog

import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.CommonTableExpression
import kestrel.sql.ast.CubeSpec
import kestrel.sql.ast.CurrentValueExpression
import kestrel.sql.ast.ExistsExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.FrameBound
import kestrel.sql.ast.FromItem
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.GroupingSetsSpec
import kestrel.sql.ast.GroupingSpec
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.InSubqueryExpression
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.Projection
import kestrel.sql.ast.QueryStatement
import kestrel.sql.ast.RollupSpec
import kestrel.sql.ast.ScalarSubqueryExpression
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.SetOperationStatement
import kestrel.sql.ast.StarProjection
import kestrel.sql.ast.SubquerySource
import kestrel.sql.ast.TableSource
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.WindowExpression
import kestrel.sql.ast.WithQuery

/**
 * Rewrites every [TableSource] named [oldName] to [newName] throughout a view's defining query -
 * FROM/JOIN targets, derived tables, CTE bodies, set-operation branches, and every subquery
 * expression (scalar/EXISTS/IN) nested inside WHERE/HAVING/projection/GROUP BY/ORDER BY. Used by
 * [Catalog] to keep views working after `ALTER TABLE ... RENAME TO`.
 *
 * A CTE whose own name equals [oldName] shadows the real table for the rest of that WITH clause
 * (same rule SQL itself uses to resolve the name): once such a CTE is seen, later CTEs and the
 * body are left untouched, because a bare reference to that name from there on means the CTE, not
 * the table being renamed - renaming it there would silently change what the query means.
 */
internal object RelationRename {
    fun rename(query: QueryStatement, oldName: String, newName: String): QueryStatement = when (query) {
        is SelectStatement -> renameInSelect(query, oldName, newName)
        is SetOperationStatement -> query.copy(
            left = rename(query.left, oldName, newName),
            right = rename(query.right, oldName, newName)
        )
        is WithQuery -> renameInWith(query, oldName, newName)
    }

    private fun renameInWith(query: WithQuery, oldName: String, newName: String): WithQuery {
        val newCtes = mutableListOf<CommonTableExpression>()
        var shadowed = false
        for (cte in query.ctes) {
            newCtes.add(if (shadowed) cte else cte.copy(query = rename(cte.query, oldName, newName)))
            if (cte.name.equals(oldName, ignoreCase = true)) shadowed = true
        }
        val newBody = if (shadowed) query.body else rename(query.body, oldName, newName)
        return query.copy(ctes = newCtes, body = newBody)
    }

    private fun renameInSelect(select: SelectStatement, oldName: String, newName: String): SelectStatement =
        select.copy(
            from = renameFromItem(select.from, oldName, newName),
            joins = select.joins.map { join ->
                join.copy(
                    from = renameFromItem(join.from, oldName, newName),
                    on = join.on?.let { renameInExpression(it, oldName, newName) }
                )
            },
            where = select.where?.let { renameInExpression(it, oldName, newName) },
            groupBy = select.groupBy.map { renameInExpression(it, oldName, newName) },
            groupingSpec = select.groupingSpec?.let { renameInGroupingSpec(it, oldName, newName) },
            having = select.having?.let { renameInExpression(it, oldName, newName) },
            projection = renameInProjection(select.projection, oldName, newName),
            orderBy = select.orderBy?.let { orderBy ->
                orderBy.copy(keys = orderBy.keys.map { it.copy(expression = renameInExpression(it.expression, oldName, newName)) })
            },
            distinctOn = select.distinctOn.map { renameInExpression(it, oldName, newName) }
        )

    /**
     * `groupBy` itself only ever carries the union of a [GroupingSpec]'s expressions (see
     * [SelectStatement.groupBy]'s doc), never the spec's own per-set structure - so renaming that
     * union alone would leave a stale table name buried inside a subquery nested in
     * [RollupSpec]/[CubeSpec]/[GroupingSetsSpec], the one place [GroupAggregation] actually reads for
     * execution. Kept in exact lockstep with `groupBy`'s own rewrite above.
     */
    private fun renameInGroupingSpec(spec: GroupingSpec, oldName: String, newName: String): GroupingSpec = when (spec) {
        is RollupSpec -> spec.copy(expressions = spec.expressions.map { renameInExpression(it, oldName, newName) })
        is CubeSpec -> spec.copy(expressions = spec.expressions.map { renameInExpression(it, oldName, newName) })
        is GroupingSetsSpec -> spec.copy(sets = spec.sets.map { set -> set.map { renameInExpression(it, oldName, newName) } })
    }

    private fun renameFromItem(item: FromItem, oldName: String, newName: String): FromItem = when (item) {
        is TableSource -> if (item.name.equals(oldName, ignoreCase = true)) item.copy(name = newName) else item
        is SubquerySource -> item.copy(select = rename(item.select, oldName, newName))
    }

    private fun renameInProjection(projection: Projection, oldName: String, newName: String): Projection = when (projection) {
        is StarProjection -> projection
        is ExpressionProjection ->
            projection.copy(items = projection.items.map { it.copy(expression = renameInExpression(it.expression, oldName, newName)) })
    }

    private fun renameInExpression(expression: Expression, oldName: String, newName: String): Expression = when (expression) {
        is ScalarSubqueryExpression -> expression.copy(select = renameInSelect(expression.select, oldName, newName))
        is ExistsExpression -> expression.copy(select = renameInSelect(expression.select, oldName, newName))
        is InSubqueryExpression -> expression.copy(
            operand = renameInExpression(expression.operand, oldName, newName),
            select = renameInSelect(expression.select, oldName, newName)
        )
        is BinaryExpression -> expression.copy(
            left = renameInExpression(expression.left, oldName, newName),
            right = renameInExpression(expression.right, oldName, newName)
        )
        is UnaryExpression -> expression.copy(operand = renameInExpression(expression.operand, oldName, newName))
        is CallExpression -> expression.copy(arguments = expression.arguments.map { renameInExpression(it, oldName, newName) })
        is IsNullExpression -> expression.copy(operand = renameInExpression(expression.operand, oldName, newName))
        is InExpression -> expression.copy(
            operand = renameInExpression(expression.operand, oldName, newName),
            values = expression.values.map { renameInExpression(it, oldName, newName) }
        )
        is BetweenExpression -> expression.copy(
            operand = renameInExpression(expression.operand, oldName, newName),
            low = renameInExpression(expression.low, oldName, newName),
            high = renameInExpression(expression.high, oldName, newName)
        )
        is LikeExpression -> expression.copy(
            operand = renameInExpression(expression.operand, oldName, newName),
            pattern = renameInExpression(expression.pattern, oldName, newName)
        )
        is CaseExpression -> expression.copy(
            operand = expression.operand?.let { renameInExpression(it, oldName, newName) },
            branches = expression.branches.map {
                it.copy(
                    condition = renameInExpression(it.condition, oldName, newName),
                    result = renameInExpression(it.result, oldName, newName)
                )
            },
            elseExpression = expression.elseExpression?.let { renameInExpression(it, oldName, newName) }
        )
        is CastExpression -> expression.copy(operand = renameInExpression(expression.operand, oldName, newName))
        is CollateExpression -> expression.copy(operand = renameInExpression(expression.operand, oldName, newName))
        is FunctionExpression -> expression.copy(
            argument = expression.argument?.let { renameInExpression(it, oldName, newName) },
            separator = expression.separator?.let { renameInExpression(it, oldName, newName) },
            orderBy = expression.orderBy.map { it.copy(expression = renameInExpression(it.expression, oldName, newName)) },
            filter = expression.filter?.let { renameInExpression(it, oldName, newName) }
        )
        is WindowExpression -> expression.copy(
            argument = expression.argument?.let { renameInExpression(it, oldName, newName) },
            partitionBy = expression.partitionBy.map { renameInExpression(it, oldName, newName) },
            orderBy = expression.orderBy.map { it.copy(expression = renameInExpression(it.expression, oldName, newName)) },
            offset = expression.offset?.let { renameInExpression(it, oldName, newName) },
            defaultValue = expression.defaultValue?.let { renameInExpression(it, oldName, newName) },
            frame = expression.frame?.let { frame ->
                frame.copy(
                    start = renameInFrameBound(frame.start, oldName, newName),
                    end = renameInFrameBound(frame.end, oldName, newName)
                )
            }
        )
        is ColumnExpression, is LiteralExpression, is NextValueExpression, is CurrentValueExpression,
        is MergeActionExpression, is ParameterExpression -> expression
    }

    private fun renameInFrameBound(bound: FrameBound, oldName: String, newName: String): FrameBound =
        bound.copy(offset = bound.offset?.let { renameInExpression(it, oldName, newName) })
}
