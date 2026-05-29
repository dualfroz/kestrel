package kestrel.sql.ast

import kestrel.catalog.Column
import kestrel.storage.Value

sealed interface Statement

data class CreateTableStatement(val tableName: String, val columns: List<Column>) : Statement

data class DropTableStatement(val tableName: String) : Statement

data class CreateIndexStatement(
    val indexName: String,
    val tableName: String,
    val columnName: String
) : Statement

data class DropIndexStatement(val indexName: String) : Statement

data class InsertStatement(
    val tableName: String,
    val columns: List<String>?,
    val rows: List<List<Expression>>
) : Statement

data class SelectStatement(
    val projection: Projection,
    val tableName: String,
    val tableAlias: String? = null,
    val joins: List<JoinClause> = emptyList(),
    val where: Expression? = null,
    val groupBy: List<Expression> = emptyList(),
    val having: Expression? = null,
    val orderBy: OrderBy? = null,
    val limit: Int? = null
) : Statement

data class DeleteStatement(val tableName: String, val where: Expression?) : Statement

data class UpdateStatement(
    val tableName: String,
    val assignments: List<Assignment>,
    val where: Expression?
) : Statement

data class ExplainStatement(val select: SelectStatement) : Statement

data object BeginStatement : Statement

data object CommitStatement : Statement

data object RollbackStatement : Statement

data class Assignment(val columnName: String, val value: Expression)

enum class JoinType { Inner, Left }

data class JoinClause(
    val type: JoinType,
    val tableName: String,
    val alias: String?,
    val on: Expression
)

sealed interface Projection

data object StarProjection : Projection

data class ExpressionProjection(val items: List<SelectItem>) : Projection

data class SelectItem(val expression: Expression, val alias: String?)

data class OrderBy(val expression: Expression, val descending: Boolean)

sealed interface Expression

data class LiteralExpression(val value: Value) : Expression

data class ColumnExpression(val name: String, val qualifier: String? = null) : Expression

data class FunctionExpression(
    val name: String,
    val argument: Expression?,
    val star: Boolean
) : Expression

data class BinaryExpression(
    val operator: BinaryOperator,
    val left: Expression,
    val right: Expression
) : Expression

data class UnaryExpression(val operator: UnaryOperator, val operand: Expression) : Expression

enum class BinaryOperator(val symbol: String) {
    Add("+"), Subtract("-"), Multiply("*"), Divide("/"),
    Equal("="), NotEqual("<>"), Less("<"), LessEqual("<="),
    Greater(">"), GreaterEqual(">="), And("AND"), Or("OR")
}

enum class UnaryOperator(val symbol: String) {
    Negate("-"), Not("NOT")
}
