package kestrel.catalog

import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
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
import kestrel.sql.ast.FromItem
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.InSubqueryExpression
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.JoinClause
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.Projection
import kestrel.sql.ast.QueryStatement
import kestrel.sql.ast.ScalarSubqueryExpression
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.SetOperationStatement
import kestrel.sql.ast.StarProjection
import kestrel.sql.ast.SubquerySource
import kestrel.sql.ast.TableSource
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.WindowExpression
import kestrel.sql.ast.WithQuery

internal object DependencyAnalysis {
    fun referencesRelation(query: QueryStatement, name: String): Boolean = when (query) {
        is SelectStatement -> referencesInSelect(query, name)
        is SetOperationStatement -> referencesRelation(query.left, name) || referencesRelation(query.right, name)
        is WithQuery -> query.ctes.any { referencesInCte(it, name) } || referencesRelation(query.body, name)
    }

    private fun referencesInCte(cte: CommonTableExpression, name: String): Boolean =
        referencesRelation(cte.query, name)

    private fun referencesInSelect(select: SelectStatement, name: String): Boolean {
        if (referencesFromItem(select.from, name)) return true
        if (select.joins.any { referencesJoin(it, name) }) return true
        if (select.where?.let { referencesInExpression(it, name) } == true) return true
        if (select.groupBy.any { referencesInExpression(it, name) }) return true
        if (select.having?.let { referencesInExpression(it, name) } == true) return true
        if (select.distinctOn.any { referencesInExpression(it, name) }) return true
        return referencesInProjection(select.projection, name)
    }

    private fun referencesJoin(join: JoinClause, name: String): Boolean =
        referencesFromItem(join.from, name) || (join.on?.let { referencesInExpression(it, name) } == true)

    private fun referencesFromItem(item: FromItem, name: String): Boolean = when (item) {
        is TableSource -> item.name.equals(name, ignoreCase = true)
        is SubquerySource -> referencesRelation(item.select, name)
    }

    private fun referencesInProjection(projection: Projection, name: String): Boolean = when (projection) {
        is StarProjection -> false
        is ExpressionProjection -> projection.items.any { referencesInExpression(it.expression, name) }
    }

    private fun referencesInExpression(expression: Expression, name: String): Boolean = when (expression) {
        is ScalarSubqueryExpression -> referencesRelation(expression.select, name)
        is ExistsExpression -> referencesRelation(expression.select, name)
        is InSubqueryExpression ->
            referencesRelation(expression.select, name) || referencesInExpression(expression.operand, name)
        is BinaryExpression -> referencesInExpression(expression.left, name) || referencesInExpression(expression.right, name)
        is UnaryExpression -> referencesInExpression(expression.operand, name)
        is CallExpression -> expression.arguments.any { referencesInExpression(it, name) }
        is IsNullExpression -> referencesInExpression(expression.operand, name)
        is InExpression ->
            referencesInExpression(expression.operand, name) || expression.values.any { referencesInExpression(it, name) }
        is BetweenExpression ->
            referencesInExpression(expression.operand, name) ||
                referencesInExpression(expression.low, name) ||
                referencesInExpression(expression.high, name)
        is LikeExpression ->
            referencesInExpression(expression.operand, name) || referencesInExpression(expression.pattern, name)
        is CaseExpression ->
            (expression.operand?.let { referencesInExpression(it, name) } ?: false) ||
                expression.branches.any {
                    referencesInExpression(it.condition, name) || referencesInExpression(it.result, name)
                } ||
                (expression.elseExpression?.let { referencesInExpression(it, name) } ?: false)
        is CastExpression -> referencesInExpression(expression.operand, name)
        is CollateExpression -> referencesInExpression(expression.operand, name)
        is FunctionExpression ->
            (expression.argument?.let { referencesInExpression(it, name) } ?: false) ||
                (expression.separator?.let { referencesInExpression(it, name) } ?: false) ||
                expression.orderBy.any { referencesInExpression(it.expression, name) } ||
                (expression.filter?.let { referencesInExpression(it, name) } ?: false)
        is WindowExpression ->
            (expression.argument?.let { referencesInExpression(it, name) } ?: false) ||
                (expression.offset?.let { referencesInExpression(it, name) } ?: false) ||
                (expression.defaultValue?.let { referencesInExpression(it, name) } ?: false) ||
                (expression.frame?.start?.let { referencesInFrameBound(it, name) } ?: false) ||
                (expression.frame?.end?.let { referencesInFrameBound(it, name) } ?: false) ||
                expression.partitionBy.any { referencesInExpression(it, name) } ||
                expression.orderBy.any { referencesInExpression(it.expression, name) }
        is ColumnExpression, is LiteralExpression, is NextValueExpression, is CurrentValueExpression,
        is MergeActionExpression, is ParameterExpression -> false
    }

    private fun referencesInFrameBound(bound: FrameBound, name: String): Boolean =
        bound.offset?.let { referencesInExpression(it, name) } ?: false
}
