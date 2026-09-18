package kestrel.sql.ast

import kestrel.catalog.CheckConstraint
import kestrel.catalog.Column
import kestrel.catalog.ForeignKeyConstraint
import kestrel.catalog.UniqueConstraint
import kestrel.storage.Collation
import kestrel.storage.ColumnType
import kestrel.storage.Value

sealed interface Statement

data class CreateTableStatement(
    val tableName: String,
    val columns: List<Column>,
    val foreignKeys: List<ForeignKeyConstraint> = emptyList(),
    val checks: List<CheckConstraint> = emptyList(),
    val uniqueConstraints: List<UniqueConstraint> = emptyList(),
    val compositePrimaryKey: List<String> = emptyList(),
    val temporary: Boolean = false
) : Statement

enum class DropMode { Restrict, Cascade }

data class DropTableStatement(val tableName: String, val mode: DropMode = DropMode.Restrict) : Statement

data class TruncateTableStatement(
    val tableNames: List<String>,
    val restartIdentity: Boolean = false,
    val mode: DropMode = DropMode.Restrict
) : Statement

data class CreateIndexStatement(
    val indexName: String,
    val tableName: String,
    val columnNames: List<String>,
    val unique: Boolean = false
) : Statement

data class DropIndexStatement(val indexName: String) : Statement

sealed interface AlterTableAction

data class AddColumn(val column: Column) : AlterTableAction

data class DropColumn(val columnName: String, val mode: DropMode = DropMode.Restrict) : AlterTableAction

data class RenameColumn(val oldName: String, val newName: String) : AlterTableAction

data class RenameTable(val newName: String) : AlterTableAction

data class ChangeColumnType(
    val columnName: String,
    val newType: ColumnType,
    val precision: Int? = null,
    val scale: Int? = null,
    val using: Expression? = null
) : AlterTableAction

sealed interface AddedConstraint
data class AddedCheck(val check: CheckConstraint) : AddedConstraint
data class AddedUnique(val unique: UniqueConstraint) : AddedConstraint
data class AddedForeignKey(val foreignKey: ForeignKeyConstraint) : AddedConstraint

data class AddConstraint(val constraint: AddedConstraint) : AlterTableAction

data class DropConstraint(val name: String, val mode: DropMode = DropMode.Restrict) : AlterTableAction

data class AlterTableStatement(val tableName: String, val action: AlterTableAction) : Statement

data class CreateViewStatement(
    val viewName: String,
    val query: QueryStatement,
    val orReplace: Boolean
) : Statement

data class DropViewStatement(val viewName: String, val mode: DropMode = DropMode.Restrict) : Statement

data class InsertStatement(
    val tableName: String,
    val columns: List<String>?,
    val rows: List<List<Expression?>>,
    val onConflict: OnConflictClause? = null,
    val returning: Projection? = null
) : PreparableStatement

data class OnConflictClause(val targetColumns: List<String>, val action: ConflictAction)

sealed interface ConflictAction

data object ConflictDoNothing : ConflictAction

data class ConflictDoUpdate(val assignments: List<Assignment>, val where: Expression?) : ConflictAction

sealed interface PreparableStatement : Statement

sealed interface QueryStatement : Statement, PreparableStatement

data class SelectStatement(
    val projection: Projection,
    val from: FromItem,
    val joins: List<JoinClause> = emptyList(),
    val where: Expression? = null,
    val groupBy: List<Expression> = emptyList(),
    val groupingSpec: GroupingSpec? = null,
    val having: Expression? = null,
    val orderBy: OrderBy? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val distinct: Boolean = false,
    val distinctOn: List<Expression> = emptyList()
) : QueryStatement

sealed interface GroupingSpec

data class RollupSpec(val expressions: List<Expression>) : GroupingSpec

data class CubeSpec(val expressions: List<Expression>) : GroupingSpec

data class GroupingSetsSpec(val sets: List<List<Expression>>) : GroupingSpec

enum class SetOperator { Union, UnionAll, Intersect, IntersectAll, Except, ExceptAll }

data class SetOperationStatement(
    val operator: SetOperator,
    val left: QueryStatement,
    val right: QueryStatement,
    val orderBy: OrderBy? = null,
    val limit: Int? = null,
    val offset: Int? = null
) : QueryStatement

data class CommonTableExpression(
    val name: String,
    val columns: List<String>?,
    val query: QueryStatement
)

data class WithQuery(
    val recursive: Boolean,
    val ctes: List<CommonTableExpression>,
    val body: QueryStatement
) : QueryStatement

sealed interface FromItem

data class TableSource(val name: String, val alias: String? = null) : FromItem

data class SubquerySource(val select: QueryStatement, val alias: String, val lateral: Boolean = false) : FromItem

data class DeleteStatement(
    val tableName: String,
    val where: Expression?,
    val returning: Projection? = null
) : PreparableStatement

data class UpdateStatement(
    val tableName: String,
    val assignments: List<Assignment>,
    val where: Expression?,
    val returning: Projection? = null
) : PreparableStatement

data class MergeStatement(
    val targetTable: String,
    val targetAlias: String?,
    val source: FromItem,
    val onCondition: Expression,
    val whenClauses: List<MergeWhenClause>,
    val returning: Projection? = null
) : PreparableStatement

sealed interface MergeWhenClause {
    val condition: Expression?
}

data class MergeMatchedUpdate(override val condition: Expression?, val assignments: List<Assignment>) : MergeWhenClause

data class MergeMatchedDelete(override val condition: Expression?) : MergeWhenClause

data class MergeNotMatchedInsert(override val condition: Expression?, val columns: List<String>?, val values: List<Expression?>) : MergeWhenClause

data class ExplainStatement(val select: QueryStatement) : Statement

enum class TriggerTiming { Before, After }

enum class TriggerEvent { Insert, Update, Delete }

data class CreateTriggerStatement(
    val triggerName: String,
    val timing: TriggerTiming,
    val event: TriggerEvent,
    val tableName: String,
    val whenClause: Expression?,
    val body: List<Statement>
) : Statement

data class DropTriggerStatement(val triggerName: String) : Statement

data class CreateSequenceStatement(
    val sequenceName: String,
    val incrementBy: Long? = null,
    val minValue: Long? = null,
    val maxValue: Long? = null,
    val startValue: Long? = null,
    val cycle: Boolean = false
) : Statement

data class DropSequenceStatement(val sequenceName: String) : Statement

data class AlterSequenceStatement(
    val sequenceName: String,
    val incrementBy: Long? = null,
    val minValue: Long? = null,
    val clearMinValue: Boolean = false,
    val maxValue: Long? = null,
    val clearMaxValue: Boolean = false,
    val restart: Boolean = false,
    val restartWith: Long? = null,
    val cycle: Boolean? = null
) : Statement

data class PrepareStatement(val name: String, val target: PreparableStatement) : Statement

data class ExecuteStatement(val name: String, val arguments: List<Expression> = emptyList()) : Statement

data class DeallocateStatement(val name: String?, val all: Boolean = false) : Statement

data object BeginStatement : Statement

data object CommitStatement : Statement

data object RollbackStatement : Statement

data class SavepointStatement(val name: String) : Statement

data class ReleaseSavepointStatement(val name: String) : Statement

data class RollbackToSavepointStatement(val name: String) : Statement

data class Assignment(val columnName: String, val value: Expression?)

enum class JoinType { Inner, Left, Right, Full, Cross }

data class JoinClause(
    val type: JoinType,
    val from: FromItem,
    val on: Expression?
)

sealed interface Projection

data object StarProjection : Projection

data class ExpressionProjection(val items: List<SelectItem>) : Projection

data class SelectItem(val expression: Expression, val alias: String?)

data class OrderKey(val expression: Expression, val descending: Boolean, val nullsFirst: Boolean? = null)

data class OrderBy(val keys: List<OrderKey>)

sealed interface Expression

data class LiteralExpression(val value: Value) : Expression

data class ColumnExpression(val name: String, val qualifier: String? = null) : Expression

data class FunctionExpression(
    val name: String,
    val argument: Expression?,
    val star: Boolean,
    val separator: Expression? = null,
    val orderBy: List<OrderKey> = emptyList(),
    val distinct: Boolean = false,
    val filter: Expression? = null
) : Expression

data class CallExpression(val name: String, val arguments: List<Expression>) : Expression

data class NextValueExpression(val sequenceName: String) : Expression

data class CurrentValueExpression(val sequenceName: String) : Expression

data object MergeActionExpression : Expression

data class ParameterExpression(val index: Int) : Expression

data class IsNullExpression(val operand: Expression, val negated: Boolean) : Expression

data class InExpression(val operand: Expression, val values: List<Expression>, val negated: Boolean) : Expression

data class BetweenExpression(
    val operand: Expression,
    val low: Expression,
    val high: Expression,
    val negated: Boolean
) : Expression

data class LikeExpression(val operand: Expression, val pattern: Expression, val negated: Boolean) : Expression

data class CaseExpression(
    val operand: Expression?,
    val branches: List<CaseBranch>,
    val elseExpression: Expression?
) : Expression

data class CaseBranch(val condition: Expression, val result: Expression)

data class ScalarSubqueryExpression(val select: SelectStatement) : Expression

data class ExistsExpression(val select: SelectStatement) : Expression

data class InSubqueryExpression(
    val operand: Expression,
    val select: SelectStatement,
    val negated: Boolean
) : Expression

data class CastExpression(
    val operand: Expression,
    val targetType: ColumnType,
    val precision: Int? = null,
    val scale: Int? = null
) : Expression

enum class WindowFunctionKind { RowNumber, Rank, DenseRank, Aggregate, Lag, Lead, FirstValue, LastValue, Ntile }

enum class FrameBoundType { UnboundedPreceding, Preceding, CurrentRow, Following, UnboundedFollowing }

data class FrameBound(val type: FrameBoundType, val offset: Expression? = null)

enum class FrameUnit { Rows, Range, Groups }

enum class FrameExclude { NoOthers, CurrentRow, Group, Ties }

data class WindowFrame(val unit: FrameUnit, val start: FrameBound, val end: FrameBound, val exclude: FrameExclude = FrameExclude.NoOthers)

data class WindowExpression(
    val name: String,
    val kind: WindowFunctionKind,
    val argument: Expression?,
    val star: Boolean,
    val partitionBy: List<Expression>,
    val orderBy: List<OrderKey>,
    val offset: Expression? = null,
    val defaultValue: Expression? = null,
    val frame: WindowFrame? = null
) : Expression

data class BinaryExpression(
    val operator: BinaryOperator,
    val left: Expression,
    val right: Expression
) : Expression

data class UnaryExpression(val operator: UnaryOperator, val operand: Expression) : Expression

data class CollateExpression(val operand: Expression, val collation: Collation) : Expression

enum class BinaryOperator(val symbol: String) {
    Add("+"), Subtract("-"), Multiply("*"), Divide("/"),
    Equal("="), NotEqual("<>"), Less("<"), LessEqual("<="),
    Greater(">"), GreaterEqual(">="), And("AND"), Or("OR")
}

enum class UnaryOperator(val symbol: String) {
    Negate("-"), Not("NOT")
}
