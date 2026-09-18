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

internal object ParameterSubstitution {

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

    private fun substituteSelect(select: SelectStatement, boundValues: List<Value>): SelectStatement = select.copy(
        from = substituteFromItem(select.from, boundValues),
        joins = select.joins.map { join ->
            join.copy(
                from = substituteFromItem(join.from, boundValues),
                on = join.on?.let { substituteExpression(it, boundValues) }
            )
        },
        where = select.where?.let { substituteExpression(it, boundValues) },
        groupBy = select.groupBy.map { substituteExpression(it, boundValues) },
        groupingSpec = select.groupingSpec?.let { substituteGroupingSpec(it, boundValues) },
        having = select.having?.let { substituteExpression(it, boundValues) },
        projection = substituteProjection(select.projection, boundValues),
        orderBy = select.orderBy?.let { substituteOrderBy(it, boundValues) },
        distinctOn = select.distinctOn.map { substituteExpression(it, boundValues) }
    )

    private fun substituteGroupingSpec(spec: GroupingSpec, boundValues: List<Value>): GroupingSpec = when (spec) {
        is RollupSpec -> spec.copy(expressions = spec.expressions.map { substituteExpression(it, boundValues) })
        is CubeSpec -> spec.copy(expressions = spec.expressions.map { substituteExpression(it, boundValues) })
        is GroupingSetsSpec -> spec.copy(sets = spec.sets.map { set -> set.map { substituteExpression(it, boundValues) } })
    }

    private fun substituteFromItem(item: FromItem, boundValues: List<Value>): FromItem = when (item) {
        is TableSource -> item
        is SubquerySource -> item.copy(select = substituteQuery(item.select, boundValues))
    }

    private fun substituteProjection(projection: Projection, boundValues: List<Value>): Projection = when (projection) {
        StarProjection -> projection
        is ExpressionProjection ->
            projection.copy(items = projection.items.map { it.copy(expression = substituteExpression(it.expression, boundValues)) })
    }

    private fun substituteOrderBy(orderBy: OrderBy, boundValues: List<Value>): OrderBy =
        orderBy.copy(keys = orderBy.keys.map { it.copy(expression = substituteExpression(it.expression, boundValues)) })

    private fun substituteExpression(expression: Expression, boundValues: List<Value>): Expression = when (expression) {
        is ParameterExpression -> LiteralExpression(boundValueAt(expression.index, boundValues))
        is ScalarSubqueryExpression -> expression.copy(select = substituteSelect(expression.select, boundValues))
        is ExistsExpression -> expression.copy(select = substituteSelect(expression.select, boundValues))
        is InSubqueryExpression -> expression.copy(
            operand = substituteExpression(expression.operand, boundValues),
            select = substituteSelect(expression.select, boundValues)
        )
        is BinaryExpression -> expression.copy(
            left = substituteExpression(expression.left, boundValues),
            right = substituteExpression(expression.right, boundValues)
        )
        is UnaryExpression -> expression.copy(operand = substituteExpression(expression.operand, boundValues))
        is CallExpression -> expression.copy(arguments = expression.arguments.map { substituteExpression(it, boundValues) })
        is IsNullExpression -> expression.copy(operand = substituteExpression(expression.operand, boundValues))
        is InExpression -> expression.copy(
            operand = substituteExpression(expression.operand, boundValues),
            values = expression.values.map { substituteExpression(it, boundValues) }
        )
        is BetweenExpression -> expression.copy(
            operand = substituteExpression(expression.operand, boundValues),
            low = substituteExpression(expression.low, boundValues),
            high = substituteExpression(expression.high, boundValues)
        )
        is LikeExpression -> expression.copy(
            operand = substituteExpression(expression.operand, boundValues),
            pattern = substituteExpression(expression.pattern, boundValues)
        )
        is CaseExpression -> expression.copy(
            operand = expression.operand?.let { substituteExpression(it, boundValues) },
            branches = expression.branches.map {
                it.copy(condition = substituteExpression(it.condition, boundValues), result = substituteExpression(it.result, boundValues))
            },
            elseExpression = expression.elseExpression?.let { substituteExpression(it, boundValues) }
        )
        is CastExpression -> expression.copy(operand = substituteExpression(expression.operand, boundValues))
        is CollateExpression -> expression.copy(operand = substituteExpression(expression.operand, boundValues))
        is FunctionExpression -> expression.copy(
            argument = expression.argument?.let { substituteExpression(it, boundValues) },
            separator = expression.separator?.let { substituteExpression(it, boundValues) },
            orderBy = expression.orderBy.map { it.copy(expression = substituteExpression(it.expression, boundValues)) },
            filter = expression.filter?.let { substituteExpression(it, boundValues) }
        )
        is WindowExpression -> expression.copy(
            argument = expression.argument?.let { substituteExpression(it, boundValues) },
            partitionBy = expression.partitionBy.map { substituteExpression(it, boundValues) },
            orderBy = expression.orderBy.map { it.copy(expression = substituteExpression(it.expression, boundValues)) },
            offset = expression.offset?.let { substituteExpression(it, boundValues) },
            defaultValue = expression.defaultValue?.let { substituteExpression(it, boundValues) },
            frame = expression.frame?.let { frame ->
                frame.copy(
                    start = substituteFrameBound(frame.start, boundValues),
                    end = substituteFrameBound(frame.end, boundValues)
                )
            }
        )
        is ColumnExpression, is LiteralExpression, is NextValueExpression, is CurrentValueExpression,
        is MergeActionExpression -> expression
    }

    private fun substituteFrameBound(bound: FrameBound, boundValues: List<Value>): FrameBound =
        bound.copy(offset = bound.offset?.let { substituteExpression(it, boundValues) })

    private fun boundValueAt(index: Int, boundValues: List<Value>): Value =
        boundValues.getOrNull(index - 1) ?: throw KestrelException("no value supplied for parameter \$$index")

    private fun maxIndexInAssignments(assignments: List<Assignment>): Int =
        assignments.maxOfOrNull { it.value?.let(::maxIndexInExpression) ?: 0 } ?: 0

    private fun maxIndexInOnConflict(clause: OnConflictClause): Int = when (val action = clause.action) {
        ConflictDoNothing -> 0
        is ConflictDoUpdate -> maxOf(maxIndexInAssignments(action.assignments), action.where?.let(::maxIndexInExpression) ?: 0)
    }

    private fun maxIndexInWhenClause(clause: MergeWhenClause): Int = when (clause) {
        is MergeMatchedUpdate -> maxOf(
            clause.condition?.let(::maxIndexInExpression) ?: 0,
            maxIndexInAssignments(clause.assignments)
        )
        is MergeMatchedDelete -> clause.condition?.let(::maxIndexInExpression) ?: 0
        is MergeNotMatchedInsert -> maxOf(
            clause.condition?.let(::maxIndexInExpression) ?: 0,
            clause.values.maxOfOrNull { it?.let(::maxIndexInExpression) ?: 0 } ?: 0
        )
    }

    private fun maxIndexInQuery(query: QueryStatement): Int = when (query) {
        is SelectStatement -> maxIndexInSelect(query)
        is SetOperationStatement -> maxOf(
            maxIndexInQuery(query.left),
            maxIndexInQuery(query.right),
            query.orderBy?.let(::maxIndexInOrderBy) ?: 0
        )
        is WithQuery -> maxOf(
            query.ctes.maxOfOrNull { maxIndexInQuery(it.query) } ?: 0,
            maxIndexInQuery(query.body)
        )
    }

    private fun maxIndexInSelect(select: SelectStatement): Int = maxOf(
        maxIndexInFromItem(select.from),
        select.joins.maxOfOrNull { join -> maxOf(maxIndexInFromItem(join.from), join.on?.let(::maxIndexInExpression) ?: 0) } ?: 0,
        select.where?.let(::maxIndexInExpression) ?: 0,
        select.groupBy.maxOfOrNull(::maxIndexInExpression) ?: 0,
        select.groupingSpec?.let(::maxIndexInGroupingSpec) ?: 0,
        select.having?.let(::maxIndexInExpression) ?: 0,
        maxIndexInProjection(select.projection),
        select.orderBy?.let(::maxIndexInOrderBy) ?: 0,
        select.distinctOn.maxOfOrNull(::maxIndexInExpression) ?: 0
    )

    private fun maxIndexInGroupingSpec(spec: GroupingSpec): Int = when (spec) {
        is RollupSpec -> spec.expressions.maxOfOrNull(::maxIndexInExpression) ?: 0
        is CubeSpec -> spec.expressions.maxOfOrNull(::maxIndexInExpression) ?: 0
        is GroupingSetsSpec -> spec.sets.maxOfOrNull { set -> set.maxOfOrNull(::maxIndexInExpression) ?: 0 } ?: 0
    }

    private fun maxIndexInFromItem(item: FromItem): Int = when (item) {
        is TableSource -> 0
        is SubquerySource -> maxIndexInQuery(item.select)
    }

    private fun maxIndexInProjection(projection: Projection): Int = when (projection) {
        StarProjection -> 0
        is ExpressionProjection -> projection.items.maxOfOrNull { maxIndexInExpression(it.expression) } ?: 0
    }

    private fun maxIndexInOrderBy(orderBy: OrderBy): Int = orderBy.keys.maxOfOrNull { maxIndexInExpression(it.expression) } ?: 0

    private fun maxIndexInExpression(expression: Expression): Int = when (expression) {
        is ParameterExpression -> expression.index
        is ScalarSubqueryExpression -> maxIndexInSelect(expression.select)
        is ExistsExpression -> maxIndexInSelect(expression.select)
        is InSubqueryExpression -> maxOf(maxIndexInExpression(expression.operand), maxIndexInSelect(expression.select))
        is BinaryExpression -> maxOf(maxIndexInExpression(expression.left), maxIndexInExpression(expression.right))
        is UnaryExpression -> maxIndexInExpression(expression.operand)
        is CallExpression -> expression.arguments.maxOfOrNull(::maxIndexInExpression) ?: 0
        is IsNullExpression -> maxIndexInExpression(expression.operand)
        is InExpression -> maxOf(
            maxIndexInExpression(expression.operand),
            expression.values.maxOfOrNull(::maxIndexInExpression) ?: 0
        )
        is BetweenExpression -> maxOf(
            maxIndexInExpression(expression.operand),
            maxIndexInExpression(expression.low),
            maxIndexInExpression(expression.high)
        )
        is LikeExpression -> maxOf(maxIndexInExpression(expression.operand), maxIndexInExpression(expression.pattern))
        is CaseExpression -> maxOf(
            expression.operand?.let(::maxIndexInExpression) ?: 0,
            expression.branches.maxOfOrNull { maxOf(maxIndexInExpression(it.condition), maxIndexInExpression(it.result)) } ?: 0,
            expression.elseExpression?.let(::maxIndexInExpression) ?: 0
        )
        is CastExpression -> maxIndexInExpression(expression.operand)
        is CollateExpression -> maxIndexInExpression(expression.operand)
        is FunctionExpression -> maxOf(
            expression.argument?.let(::maxIndexInExpression) ?: 0,
            expression.separator?.let(::maxIndexInExpression) ?: 0,
            expression.orderBy.maxOfOrNull { maxIndexInExpression(it.expression) } ?: 0,
            expression.filter?.let(::maxIndexInExpression) ?: 0
        )
        is WindowExpression -> maxOf(
            expression.argument?.let(::maxIndexInExpression) ?: 0,
            expression.partitionBy.maxOfOrNull(::maxIndexInExpression) ?: 0,
            expression.orderBy.maxOfOrNull { maxIndexInExpression(it.expression) } ?: 0,
            expression.offset?.let(::maxIndexInExpression) ?: 0,
            expression.defaultValue?.let(::maxIndexInExpression) ?: 0,
            expression.frame?.start?.offset?.let(::maxIndexInExpression) ?: 0,
            expression.frame?.end?.offset?.let(::maxIndexInExpression) ?: 0
        )
        is ColumnExpression, is LiteralExpression, is NextValueExpression, is CurrentValueExpression,
        is MergeActionExpression -> 0
    }
}
