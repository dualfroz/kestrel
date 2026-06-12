package kestrel.catalog

import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.CurrentValueExpression
import kestrel.sql.ast.ExistsExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.InSubqueryExpression
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.ScalarSubqueryExpression
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.WindowExpression

/**
 * Walks a CHECK constraint's expression looking for (or renaming) a plain column reference by
 * name. Used by [Catalog] to decide whether dropping a column must also drop a dependent CHECK
 * (`ALTER TABLE ... DROP COLUMN`) and to keep a CHECK in sync when a column is renamed
 * (`ALTER TABLE ... RENAME COLUMN`). The subquery and window-function variants are handled only to
 * keep the `when` exhaustive: `Executor.requireNoSubquery` already rejects any CHECK expression
 * containing a subquery, an aggregate, or a window function at CREATE TABLE time, so those branches
 * are unreachable in practice.
 */
internal object CheckExpressionColumns {
    fun references(expression: Expression, columnName: String): Boolean = when (expression) {
        is ColumnExpression -> expression.name.equals(columnName, ignoreCase = true)
        is LiteralExpression -> false
        is BinaryExpression -> references(expression.left, columnName) || references(expression.right, columnName)
        is UnaryExpression -> references(expression.operand, columnName)
        is CallExpression -> expression.arguments.any { references(it, columnName) }
        is FunctionExpression -> expression.argument?.let { references(it, columnName) } ?: false
        is IsNullExpression -> references(expression.operand, columnName)
        is InExpression -> references(expression.operand, columnName) || expression.values.any { references(it, columnName) }
        is BetweenExpression ->
            references(expression.operand, columnName) ||
                references(expression.low, columnName) ||
                references(expression.high, columnName)
        is LikeExpression -> references(expression.operand, columnName) || references(expression.pattern, columnName)
        is CaseExpression ->
            (expression.operand?.let { references(it, columnName) } ?: false) ||
                expression.branches.any { references(it.condition, columnName) || references(it.result, columnName) } ||
                (expression.elseExpression?.let { references(it, columnName) } ?: false)
        is CastExpression -> references(expression.operand, columnName)
        is CollateExpression -> references(expression.operand, columnName)
        is ScalarSubqueryExpression, is ExistsExpression, is InSubqueryExpression, is WindowExpression,
        is NextValueExpression, is CurrentValueExpression, is MergeActionExpression, is ParameterExpression -> false
    }

    fun rename(expression: Expression, oldName: String, newName: String): Expression = when (expression) {
        is ColumnExpression ->
            if (expression.name.equals(oldName, ignoreCase = true)) expression.copy(name = newName) else expression
        is LiteralExpression -> expression
        is BinaryExpression -> expression.copy(
            left = rename(expression.left, oldName, newName),
            right = rename(expression.right, oldName, newName)
        )
        is UnaryExpression -> expression.copy(operand = rename(expression.operand, oldName, newName))
        is CallExpression -> expression.copy(arguments = expression.arguments.map { rename(it, oldName, newName) })
        is FunctionExpression -> expression.copy(argument = expression.argument?.let { rename(it, oldName, newName) })
        is IsNullExpression -> expression.copy(operand = rename(expression.operand, oldName, newName))
        is InExpression -> expression.copy(
            operand = rename(expression.operand, oldName, newName),
            values = expression.values.map { rename(it, oldName, newName) }
        )
        is BetweenExpression -> expression.copy(
            operand = rename(expression.operand, oldName, newName),
            low = rename(expression.low, oldName, newName),
            high = rename(expression.high, oldName, newName)
        )
        is LikeExpression -> expression.copy(
            operand = rename(expression.operand, oldName, newName),
            pattern = rename(expression.pattern, oldName, newName)
        )
        is CaseExpression -> expression.copy(
            operand = expression.operand?.let { rename(it, oldName, newName) },
            branches = expression.branches.map {
                it.copy(condition = rename(it.condition, oldName, newName), result = rename(it.result, oldName, newName))
            },
            elseExpression = expression.elseExpression?.let { rename(it, oldName, newName) }
        )
        is CastExpression -> expression.copy(operand = rename(expression.operand, oldName, newName))
        is CollateExpression -> expression.copy(operand = rename(expression.operand, oldName, newName))
        is ScalarSubqueryExpression, is ExistsExpression, is InSubqueryExpression, is WindowExpression,
        is NextValueExpression, is CurrentValueExpression, is MergeActionExpression, is ParameterExpression -> expression
    }
}
