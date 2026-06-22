package kestrel.engine

import kestrel.KestrelException
import kestrel.sql.ast.Assignment
import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.ConflictAction
import kestrel.sql.ast.ConflictDoNothing
import kestrel.sql.ast.ConflictDoUpdate
import kestrel.sql.ast.CubeSpec
import kestrel.sql.ast.CurrentValueExpression
import kestrel.sql.ast.DeleteStatement
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
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.MergeMatchedDelete
import kestrel.sql.ast.MergeMatchedUpdate
import kestrel.sql.ast.MergeNotMatchedInsert
import kestrel.sql.ast.MergeStatement
import kestrel.sql.ast.MergeWhenClause
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.OnConflictClause
import kestrel.sql.ast.OrderBy
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.PreparableStatement
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
import kestrel.sql.ast.UpdateStatement
import kestrel.sql.ast.WindowExpression
import kestrel.sql.ast.WithQuery
import kestrel.storage.Value

/**
 * Resolves `$1`, `$2`, ... [ParameterExpression] placeholders inside a [PreparableStatement] -
 * the only place they are ever actually turned into real values. Walks exactly the shape
 * [kestrel.catalog.RelationRename]/[kestrel.catalog.DependencyAnalysis] already walk for a view's
 * defining query (FROM/JOIN targets, derived tables, CTE bodies, set-operation branches, every
 * subquery nested in WHERE/HAVING/projection/GROUP BY/ORDER BY/window frames), plus the parts only
 * a top-level DML statement has that a view's body never does: INSERT's VALUES rows and ON CONFLICT
 * clause, UPDATE's SET assignments, and MERGE's USING source/ON condition/WHEN clauses - since a
 * prepared statement can be any [PreparableStatement] kind, not just a SELECT.
 * [kestrel.engine.Executor.resolveExecute] is the only caller.
 */
internal object ParameterSubstitution {

    /** Highest 1-based parameter index anywhere in [statement] - 0 if it has none. EXECUTE must supply exactly this many arguments. */
    fun parameterCount(statement: PreparableStatement): Int = when (statement) {
        is QueryStatement -> maxIndexInQuery(statement)
        is InsertStatement -> maxOf(
            statement.rows.maxOfOrNull { row -> row.maxOfOrNull { it?.let(::maxIndexInExpression) ?: 0 } ?: 0 } ?: 0,
            statement.onConflict?.let(::maxIndexInOnConflict) ?: 0,
            statement.returning?.let(::maxIndexInProjection) ?: 0
        )
        is UpdateStatement -> maxOf(
            statement.assignments.maxOfOrNull { it.value?.let(::maxIndexInExpression) ?: 0 } ?: 0,
            statement.where?.let(::maxIndexInExpression) ?: 0,
            statement.returning?.let(::maxIndexInProjection) ?: 0
        )
        is DeleteStatement -> maxOf(
            statement.where?.let(::maxIndexInExpression) ?: 0,
            statement.returning?.let(::maxIndexInProjection) ?: 0
        )
        is MergeStatement -> maxOf(
            maxIndexInFromItem(statement.source),
            maxIndexInExpression(statement.onCondition),
            statement.whenClauses.maxOfOrNull(::maxIndexInWhenClause) ?: 0,
            statement.returning?.let(::maxIndexInProjection) ?: 0
        )
    }

    fun substitute(statement: PreparableStatement, boundValues: List<Value>): PreparableStatement = when (statement) {
        is QueryStatement -> substituteQuery(statement, boundValues)
        is InsertStatement -> statement.copy(
            rows = statement.rows.map { row -> row.map { it?.let { expression -> substituteExpression(expression, boundValues) } } },
            onConflict = statement.onConflict?.let { substituteOnConflict(it, boundValues) },
            returning = statement.returning?.let { substituteProjection(it, boundValues) }
        )
        is UpdateStatement -> statement.copy(
            assignments = substituteAssignments(statement.assignments, boundValues),
            where = statement.where?.let { substituteExpression(it, boundValues) },
            returning = statement.returning?.let { substituteProjection(it, boundValues) }
        )
        is DeleteStatement -> statement.copy(
            where = statement.where?.let { substituteExpression(it, boundValues) },
            returning = statement.returning?.let { substituteProjection(it, boundValues) }
        )
        is MergeStatement -> statement.copy(
            source = substituteFromItem(statement.source, boundValues),
            onCondition = substituteExpression(statement.onCondition, boundValues),
            whenClauses = statement.whenClauses.map { substituteWhenClause(it, boundValues) },
            returning = statement.returning?.let { substituteProjection(it, boundValues) }
        )
    }

    private fun substituteAssignments(assignments: List<Assignment>, boundValues: List<Value>): List<Assignment> =
        assignments.map { it.copy(value = it.value?.let { value -> substituteExpression(value, boundValues) }) }

    private fun substituteOnConflict(clause: OnConflictClause, boundValues: List<Value>): OnConflictClause =
        clause.copy(action = substituteConflictAction(clause.action, boundValues))

    private fun substituteConflictAction(action: ConflictAction, boundValues: List<Value>): ConflictAction = when (action) {
        ConflictDoNothing -> action
        is ConflictDoUpdate -> action.copy(
            assignments = substituteAssignments(action.assignments, boundValues),
            where = action.where?.let { substituteExpression(it, boundValues) }
        )
    }

    private fun substituteWhenClause(clause: MergeWhenClause, boundValues: List<Value>): MergeWhenClause = when (clause) {
        is MergeMatchedUpdate -> clause.copy(
            condition = clause.condition?.let { substituteExpression(it, boundValues) },
            assignments = substituteAssignments(clause.assignments, boundValues)
        )
        is MergeMatchedDelete -> clause.copy(condition = clause.condition?.let { substituteExpression(it, boundValues) })
        is MergeNotMatchedInsert -> clause.copy(
            condition = clause.condition?.let { substituteExpression(it, boundValues) },
            values = clause.values.map { it?.let { expression -> substituteExpression(expression, boundValues) } }
        )
    }

    private fun substituteQuery(query: QueryStatement, boundValues: List<Value>): QueryStatement = when (query) {
        is SelectStatement -> substituteSelect(query, boundValues)
        is SetOperationStatement -> query.copy(
            left = substituteQuery(query.left, boundValues),
            right = substituteQuery(query.right, boundValues),
            orderBy = query.orderBy?.let { substituteOrderBy(it, boundValues) }
        )
        is WithQuery -> query.copy(
            ctes = query.ctes.map { it.copy(query = substituteQuery(it.query, boundValues)) },
            body = substituteQuery(query.body, boundValues)
        )
    }

