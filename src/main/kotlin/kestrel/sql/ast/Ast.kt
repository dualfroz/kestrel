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
    /** `CREATE TEMP TABLE`/`CREATE TEMPORARY TABLE` - see [kestrel.catalog.TableSchema.temporary]. */
    val temporary: Boolean = false
) : Statement

enum class DropMode { Restrict, Cascade }

data class DropTableStatement(val tableName: String, val mode: DropMode = DropMode.Restrict) : Statement

/**
 * `TRUNCATE TABLE t1 [, t2, ...] [RESTART IDENTITY | CONTINUE IDENTITY] [CASCADE | RESTRICT]`.
 * Unlike a WHERE-less DELETE, TRUNCATE never fires row-level triggers and never runs a FOREIGN KEY's
 * own ON DELETE action row by row - see [kestrel.catalog.Catalog.truncateTables]/
 * [kestrel.store.sql.DiskCatalog.truncateTables] for why [mode] means something structurally
 * different here than it does for [DropTableStatement]/[DropViewStatement].
 */
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

/**
 * `ALTER TABLE t ALTER [COLUMN] col TYPE newtype [USING expression]` (Postgres syntax). [using],
 * when given, is evaluated per existing row against that row's OLD values (exactly like an UPDATE
 * SET assignment) and the result coerced into [newType]/[precision]/[scale]; when omitted it
 * defaults to a bare reference to [columnName] itself - "recompute this column as its own old
 * value, coerced to the new type" - so a widening change (INTEGER -> DECIMAL, say) needs no
 * explicit `USING` at all, matching Postgres' own default behavior.
 */
data class ChangeColumnType(
    val columnName: String,
    val newType: ColumnType,
    val precision: Int? = null,
    val scale: Int? = null,
    val using: Expression? = null
) : AlterTableAction

/**
 * `ALTER TABLE t ADD CONSTRAINT name CHECK (...) | UNIQUE (...) | FOREIGN KEY (...) REFERENCES ...` -
 * the constraint's own name lives inside [AddedCheck.check]/[AddedUnique.unique]/[AddedForeignKey.foreignKey]
 * (each already carries a `name: String?`, shared with the identical table-level `CONSTRAINT name` clause
 * a `CREATE TABLE` can declare inline), not as a separate field here.
 */
sealed interface AddedConstraint
data class AddedCheck(val check: CheckConstraint) : AddedConstraint
data class AddedUnique(val unique: UniqueConstraint) : AddedConstraint
data class AddedForeignKey(val foreignKey: ForeignKeyConstraint) : AddedConstraint

data class AddConstraint(val constraint: AddedConstraint) : AlterTableAction

/** `ALTER TABLE t DROP CONSTRAINT name [RESTRICT | CASCADE]` - see [kestrel.catalog.Catalog.dropConstraint]. */
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

/** Empty [targetColumns] means "any unique or primary key violation" (bare `ON CONFLICT DO ...`), matching Postgres. */
data class OnConflictClause(val targetColumns: List<String>, val action: ConflictAction)

sealed interface ConflictAction

data object ConflictDoNothing : ConflictAction

data class ConflictDoUpdate(val assignments: List<Assignment>, val where: Expression?) : ConflictAction

/**
 * The statement kinds [PrepareStatement] can wrap: a SELECT/set-operation/CTE ([QueryStatement])
 * or a data-mutating [InsertStatement]/[UpdateStatement]/[DeleteStatement]/[MergeStatement] - the
 * same "any DML or query, never DDL/transaction control" boundary Postgres' own PREPARE draws.
 * Declared as its own sealed interface (rather than checking `is QueryStatement || is InsertStatement
 * || ...` by hand at every call site) so the compiler enforces [ParameterSubstitution]'s `when` stays
 * exhaustive as new statement kinds are added.
 */
sealed interface PreparableStatement : Statement

sealed interface QueryStatement : Statement, PreparableStatement

data class SelectStatement(
    val projection: Projection,
    val from: FromItem,
    val joins: List<JoinClause> = emptyList(),
    val where: Expression? = null,
    /** For [RollupSpec]/[CubeSpec]/[GroupingSetsSpec], this is the deduplicated union of every expression across every one of [groupingSpec]'s grouping sets (order = first appearance) - NOT the flat comma list SQL text would suggest. Every existing consumer that only needs "which expressions are legal grouping columns" (validation, dependency analysis, rename propagation, the plain-`GROUP BY a, b` write-back path) reads this field and needs no awareness of [groupingSpec] at all; only [kestrel.engine.GroupAggregation], which needs the actual per-set breakdown, reads [groupingSpec]. */
    val groupBy: List<Expression> = emptyList(),
    val groupingSpec: GroupingSpec? = null,
    val having: Expression? = null,
    val orderBy: OrderBy? = null,
    val limit: Int? = null,
    /** `LIMIT`/`OFFSET` may be written in either order (matching Postgres) - applied together, after ORDER BY/DISTINCT dedup and before projection, skipping this many rows before [limit] (if any) caps what remains. */
    val offset: Int? = null,
    /**
     * `true` for both `SELECT DISTINCT ...` and `SELECT DISTINCT ON (...) ...` - `false` (the
     * default, also written explicitly as `SELECT ALL ...`) means every source row that survives
     * WHERE/GROUP BY/HAVING produces its own output row. See [distinctOn] for what distinguishes
     * the two `true` forms from each other.
     */
    val distinct: Boolean = false,
    /**
     * Empty for plain `SELECT DISTINCT` (dedup by the *entire* output row, evaluated after
     * projection - see [kestrel.engine.SelectRunner]'s `Plan.projectRows`). Non-empty for
     * `SELECT DISTINCT ON (expr, ...) ...`: keeps only the first source row per distinct
     * combination of these expressions, evaluated the same way `GROUP BY` expressions are
     * (pre-projection, against the joined/filtered row) - never resolved against a `SELECT`-list
     * alias, matching Postgres' own grammar where `DISTINCT ON (...)` is parsed before the select
     * list even exists. "First" means first in `ORDER BY` order when one is given (which is why the
     * parser requires `ORDER BY`, if present at all, to start with exactly these expressions - the
     * whole point of `DISTINCT ON` is picking a deterministic row per group), otherwise first in
     * scan/join order. Deliberately rejected outright when combined with `GROUP BY` or aggregates -
     * unlike plain [distinct], there is no unsurprising way to define "first row of a group" once
     * the underlying rows have already been collapsed into aggregate groups.
     */
    val distinctOn: List<Expression> = emptyList()
) : QueryStatement

/**
 * Distinguishes `GROUP BY ROLLUP (...)` / `CUBE (...)` / `GROUPING SETS (...)` from an ordinary
 * `GROUP BY a, b, ...` (represented by `groupingSpec == null`, the only case before this existed).
 * [kestrel.engine.GroupingSetExpansion] turns any of these into the concrete list of grouping sets
 * [kestrel.engine.GroupAggregation] actually groups by - one full pass over the source rows per set,
 * unioned together, so a single query reports subtotals (and, for [Rollup]/[Cube]/an explicit `()`
 * element, a grand total) alongside whatever detail rows survive. Mixing plain columns with
 * ROLLUP/CUBE in the same `GROUP BY` list (SQL allows this, producing a cross product of sets) is a
 * scope cut - the parser only accepts one of these forms standing alone as the entire clause.
 */
sealed interface GroupingSpec

data class RollupSpec(val expressions: List<Expression>) : GroupingSpec

data class CubeSpec(val expressions: List<Expression>) : GroupingSpec

/** Each element of [sets] is one grouping set as written; an empty inner list is the `()` grand-total set. */
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

/**
 * `MERGE INTO target [AS targetAlias] USING source ON onCondition whenClauses...`. [source] is the
 * same [FromItem] a `FROM`/`USING`-shaped clause anywhere else in this grammar produces (a bare
 * table name or a derived-table subquery, carrying its own alias), reused rather than inventing a
 * MERGE-specific source type. [whenClauses] preserves the exact order they were written in - see
 * [kestrel.engine.Executor]'s executeMerge for why that order (not a MATCHED-then-NOT-MATCHED split)
 * is what standard MERGE semantics actually require: for a given source row, at most one category
 * (MATCHED or NOT MATCHED) can ever apply, so evaluating the whole list in written order and
 * skipping clauses of the other category naturally reproduces "first matching WHEN clause wins".
 * Use from inside a trigger body is deliberately unsupported (the parser's trigger-body grammar only
 * ever accepts INSERT/UPDATE/DELETE, a structural scope cut, not a runtime check). [returning], when
 * present, projects one result row per action MERGE actually took (insert/update/delete), in the
 * order those actions ran - see [kestrel.engine.Executor.evaluateMergeReturning].
 */
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

/** WHEN MATCHED [AND condition] THEN UPDATE SET assignments... - `assignments` targets are always unqualified target columns, matching plain UPDATE. */
data class MergeMatchedUpdate(override val condition: Expression?, val assignments: List<Assignment>) : MergeWhenClause

/** WHEN MATCHED [AND condition] THEN DELETE */
data class MergeMatchedDelete(override val condition: Expression?) : MergeWhenClause

/**
 * WHEN NOT MATCHED [AND condition] THEN INSERT [(columns)] VALUES (values). `columns == null` means
 * every target column in schema order, exactly like a column-list-less plain INSERT. Unlike an
 * ordinary INSERT, [values] is always exactly one row (MERGE inserts at most one row per
 * unmatched source row) and can only reference the source side - there is no target row yet.
 */
data class MergeNotMatchedInsert(override val condition: Expression?, val columns: List<String>?, val values: List<Expression?>) : MergeWhenClause

data class ExplainStatement(val select: QueryStatement) : Statement

enum class TriggerTiming { Before, After }

enum class TriggerEvent { Insert, Update, Delete }

/**
 * `body` is restricted by the parser to INSERT/UPDATE/DELETE statements (matching SQLite's own
 * trigger-body grammar) - those are the only statement kinds with a side effect worth firing, and
 * the only ones [kestrel.engine.Executor] knows how to run with a [kestrel.engine.TriggerRowContext]
 * bound. `whenClause`, when present, is evaluated once per candidate row before [body] runs at all;
 * a row for which it is false or NULL never fires this trigger.
 */
data class CreateTriggerStatement(
    val triggerName: String,
    val timing: TriggerTiming,
    val event: TriggerEvent,
    val tableName: String,
    val whenClause: Expression?,
    val body: List<Statement>
) : Statement

data class DropTriggerStatement(val triggerName: String) : Statement

/**
 * `incrementBy`/`minValue`/`maxValue`/`startValue` are already fully resolved by the parser's
 * defaulting rules (see `Parser.parseSequenceOptions`) - `null` only ever means "not written by the
 * user", never "no bound"; [kestrel.engine.Executor.executeCreateSequence] is what turns a `null`
 * into Postgres' own ascending/descending default (MINVALUE 1 / MAXVALUE Long.MAX_VALUE and the
 * reverse for a negative [incrementBy]) and validates the combination.
 */
data class CreateSequenceStatement(
    val sequenceName: String,
    val incrementBy: Long? = null,
    val minValue: Long? = null,
    val maxValue: Long? = null,
    val startValue: Long? = null,
    val cycle: Boolean = false
) : Statement

data class DropSequenceStatement(val sequenceName: String) : Statement

/**
 * Every field besides [sequenceName] is "not mentioned in this ALTER" when `null`/`false` -
 * [clearMinValue]/[clearMaxValue] (Postgres' `NO MINVALUE`/`NO MAXVALUE`) are tracked separately
 * from [minValue]/[maxValue] because, unlike CREATE SEQUENCE, "not mentioned" and "explicitly
 * cleared back to the type default" are different instructions on an ALTER: the former leaves
 * today's bound untouched, the latter recomputes it from [incrementBy]'s sign exactly like a fresh
 * CREATE SEQUENCE with no bound written at all would. [restart] with a `null` [restartWith] is a
 * bare `RESTART` (reset to the sequence's original `START WITH`, unaffected by any earlier ALTER);
 * [restart] `false` means no RESTART clause at all, leaving the counter wherever NEXTVAL last left it.
 */
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

/**
 * `PREPARE name AS <target>` - parses and stores [target] under [name] without running it.
 * [kestrel.engine.Executor] keeps prepared statements in a session-scoped registry OUTSIDE
 * [kestrel.catalog.Catalog] (unlike CREATE VIEW/TRIGGER/SEQUENCE) - a deliberate choice, not an
 * oversight: they carry no data of their own (only an AST plus, at EXECUTE time, caller-supplied
 * argument values), so BEGIN/ROLLBACK's catalog snapshot/restore has nothing meaningful to undo for
 * them, and a name PREPARE-d before a transaction started should not vanish just because that
 * transaction later rolled back. See [kestrel.engine.ParameterSubstitution] for how `$1`, `$2`, ...
 * inside [target] are resolved once an EXECUTE actually supplies values.
 */
data class PrepareStatement(val name: String, val target: PreparableStatement) : Statement

/**
 * `EXECUTE name(arg, arg, ...)` (or `EXECUTE name` for a statement with no parameters) -
 * [kestrel.engine.Executor.resolveExecute] looks [name] up, evaluates each [arguments] expression
 * as a constant (no column/row context exists to resolve against), and substitutes the results for
 * every [ParameterExpression] in the prepared statement before it actually runs - so whatever this
 * resolves to is always one of [PreparableStatement]'s own concrete kinds, never `EXECUTE` itself.
 */
data class ExecuteStatement(val name: String, val arguments: List<Expression> = emptyList()) : Statement

/** `DEALLOCATE name` removes one prepared statement; `DEALLOCATE ALL` ([all] `true`, [name] `null`) removes every one. */
data class DeallocateStatement(val name: String?, val all: Boolean = false) : Statement

data object BeginStatement : Statement

data object CommitStatement : Statement

data object RollbackStatement : Statement

data class SavepointStatement(val name: String) : Statement

data class ReleaseSavepointStatement(val name: String) : Statement

data class RollbackToSavepointStatement(val name: String) : Statement

/** `value == null` means the assignment was written as `col = DEFAULT` - the column's declared DEFAULT (or NULL without one), evaluated fresh per row rather than carrying a literal at parse time. */
data class Assignment(val columnName: String, val value: Expression?)

enum class JoinType { Inner, Left, Right, Full, Cross }

/** [on] is null exactly for [JoinType.Cross] - a CROSS JOIN has no condition, matching every pairing. */
data class JoinClause(
    val type: JoinType,
    val from: FromItem,
    val on: Expression?
)

sealed interface Projection

data object StarProjection : Projection

data class ExpressionProjection(val items: List<SelectItem>) : Projection

data class SelectItem(val expression: Expression, val alias: String?)

/**
 * [nullsFirst] is `null` when the SQL text had no explicit `NULLS FIRST`/`NULLS LAST` - every
 * comparator consuming this (see [kestrel.engine.compareOrderKeyValues]) then falls back to
 * `!descending`, preserving this engine's original convention (NULL as the smallest value
 * regardless of direction) from before this syntax existed. `true`/`false` here always means an
 * explicit clause was present, independent of [descending].
 */
data class OrderKey(val expression: Expression, val descending: Boolean, val nullsFirst: Boolean? = null)

data class OrderBy(val keys: List<OrderKey>)

sealed interface Expression

data class LiteralExpression(val value: Value) : Expression

data class ColumnExpression(val name: String, val qualifier: String? = null) : Expression

/**
 * [separator] and [orderBy] exist only for `STRING_AGG(value, separator [ORDER BY ...])` - every
 * other aggregate (COUNT/SUM/AVG/MIN/MAX) leaves both null/empty. Kept on the shared node rather than
 * a dedicated `StringAggExpression` variant so STRING_AGG rides every existing [FunctionExpression]
 * validator (GROUP BY membership, CHECK/DEFAULT aggregate bans, subquery bans, ...) for free instead
 * of needing a twelfth near-parallel `when` branch across the codebase.
 *
 * [distinct] is `COUNT(DISTINCT x)`/`SUM(DISTINCT x)`/`AVG(DISTINCT x)`/`MIN(DISTINCT x)`/
 * `MAX(DISTINCT x)` - never true together with [star] or alongside [separator] (the parser rejects
 * `DISTINCT` on STRING_AGG's own call shape and on `*`), and never true on a windowed aggregate (the
 * parser rejects `DISTINCT ... OVER (...)` outright, matching Postgres - there is no per-row windowed
 * notion of "distinct so far").
 *
 * [filter] is `aggregate_name(args) FILTER (WHERE condition)` - a per-row predicate evaluated against
 * the same group the aggregate would otherwise see in full, restricting which rows reach the aggregate
 * without needing a separate `CASE WHEN ... END` inside every argument. Never set on STRING_AGG (its
 * own call shape returns before this field would ever be populated) or on a windowed aggregate (the
 * parser rejects `FILTER` together with `OVER`, matching how [distinct] is rejected there too - there
 * is no per-row windowed notion of a group to filter).
 */
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

/**
 * `NEXTVAL('seq')`/`CURRVAL('seq')`. Deliberately their own [Expression] variants rather than
 * folding into [CallExpression] like every other function call - [sequenceName] is a name resolved
 * once at parse time (the parser requires the argument to literally be a string literal, not an
 * arbitrary expression), never a per-row value, which keeps every existing [CallExpression]-shaped
 * validator (`Executor.validateConstantDefault`, `CheckExpressionColumns`, `GroupAggregation`, ...)
 * untouched by NEXTVAL's side effect instead of needing a special case bolted onto each one. See
 * [kestrel.engine.Executor.executeInsertInner]/`executeUpdateInner` for where these two are actually
 * allowed to evaluate - CHECK constraints, trigger `WHEN` clauses, and SELECT/WHERE all reject them.
 */
data class NextValueExpression(val sequenceName: String) : Expression

data class CurrentValueExpression(val sequenceName: String) : Expression

/**
 * `MERGE_ACTION()` - takes no arguments (the parser rejects any), so unlike [CallExpression] it
 * carries no per-call state and is a `data object` rather than a `data class`. Only meaningful inside
 * a [MergeStatement]'s own [MergeStatement.returning] projection, where it evaluates to `'INSERT'`,
 * `'UPDATE'`, or `'DELETE'` depending on which action a given RETURNING row came from - see
 * [kestrel.engine.Executor.evaluateMergeReturning] for where that per-row action is actually bound.
 * Every other [Expression] consumer (CHECK constraints, WHERE, trigger bodies, plain INSERT/UPDATE/
 * DELETE RETURNING, ...) treats it as structurally harmless - no column reference to check, nothing
 * to rename, nothing to depend on - exactly like [NextValueExpression]/[CurrentValueExpression] do,
 * and instead rejects it the same way they reject NEXTVAL/CURRVAL: by never handing the
 * [kestrel.engine.Evaluator] that runs it the per-row action string it needs, so evaluation itself
 * throws.
 */
data object MergeActionExpression : Expression

/**
 * `$1`, `$2`, ... - a positional parameter placeholder, 1-based to match the SQL text. Only ever
 * produced by the parser inside a [PrepareStatement]'s [PrepareStatement.target] (or, harmlessly,
 * anywhere else an [Expression] is grammatically legal, since the parser has no context at parse
 * time to reject it elsewhere) - [kestrel.engine.ParameterSubstitution] replaces every occurrence
 * with a [LiteralExpression] before the surrounding statement actually runs, so by evaluation time
 * ([kestrel.engine.Evaluator]) one reaching [kestrel.engine.Evaluator.evaluate] unsubstituted is
 * always a usage error (typed outside of PREPARE/EXECUTE entirely) and throws there, exactly like
 * [MergeActionExpression] throws when evaluated outside a MERGE's own RETURNING.
 */
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

/** Where a [FrameBound] sits relative to the current row; ordered exactly as SQL requires a frame's start to precede its end. */
enum class FrameBoundType { UnboundedPreceding, Preceding, CurrentRow, Following, UnboundedFollowing }

/** `UNBOUNDED PRECEDING`/`CURRENT ROW`/`UNBOUNDED FOLLOWING` carry no [offset]; `N PRECEDING`/`N FOLLOWING` evaluate it per row. */
data class FrameBound(val type: FrameBoundType, val offset: Expression? = null)

/**
 * [Rows] counts physical row positions (`ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING` = the row before
 * and after, regardless of ties). [Range] measures distance in the value of a single ORDER BY column
 * (numeric or DATE) - `RANGE BETWEEN 5 PRECEDING AND 5 FOLLOWING` on `ORDER BY amount` includes every
 * row whose `amount` is within 5 of the current row's, ties included, however many physical rows that
 * is. [Groups] counts whole peer groups (ties on the OVER's ORDER BY) instead of rows or values -
 * `GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING` includes every row in the peer group before the
 * current one, the current peer group, and the one after, however many rows each group has. Unlike
 * [Range] it works with any ORDER BY (zero, one, or many columns, any type), not just a single
 * numeric/DATE column.
 */
enum class FrameUnit { Rows, Range, Groups }

/**
 * `EXCLUDE` narrows a frame after its [FrameBound]s are resolved, by removing rows tied to the
 * current row on the OVER's ORDER BY. [NoOthers] (the default - no `EXCLUDE` clause at all) removes
 * nothing. [CurrentRow] removes only the current row itself. [Group] removes the current row's
 * entire peer group, including the current row. [Ties] removes every *other* row in the peer group
 * but keeps the current row - the one exclusion mode where the current row's own value always still
 * counts. With no ORDER BY the whole partition is one peer group, matching the same convention
 * already used for the frame's own `CURRENT ROW` bound and for the implicit default frame.
 */
enum class FrameExclude { NoOthers, CurrentRow, Group, Ties }

/** `ROWS|RANGE BETWEEN <start> AND <end> [EXCLUDE ...]`. */
data class WindowFrame(val unit: FrameUnit, val start: FrameBound, val end: FrameBound, val exclude: FrameExclude = FrameExclude.NoOthers)

/**
 * `name(...) OVER (PARTITION BY ... ORDER BY ... [ROWS BETWEEN ... AND ...])`. [RowNumber]/[Rank]/
 * [DenseRank] never take an argument (always `argument == null && star == false`); [Aggregate]
 * reuses the same COUNT/SUM/AVG/MIN/MAX vocabulary as [FunctionExpression] (including COUNT(*) via
 * [star]) but computes per row over a partition/frame instead of collapsing the whole group into
 * one row. [Lag]/[Lead] read [argument] from a row [offset] rows before/after the current one
 * (default 1), falling back to [defaultValue] (or NULL) past the partition's edge - both are `null`
 * for every other kind. [FirstValue]/[LastValue] read [argument] from the first/last row of the
 * current frame; [Ntile] treats [argument] as the bucket count and ignores [offset]/[defaultValue].
 * [frame] is only meaningful for [Aggregate]/[FirstValue]/[LastValue] - `null` means the default SQL
 * frame (`RANGE UNBOUNDED PRECEDING AND CURRENT ROW` with an ORDER BY, the whole partition without
 * one); every other kind rejects an explicit [frame] outright since it would not change their result.
 */
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

/**
 * `expr COLLATE name` (SQL:1999) - overrides the text-comparison sequence used for [operand]
 * wherever it participates in a comparison (`=`/`<>`/`<`/`<=`/`>`/`>=`, `IN`, `BETWEEN`, a
 * searched-`CASE`'s operand match) - see [kestrel.engine.Evaluator]'s own comparison helpers, the
 * only place a [Collation] other than [Collation.Binary] changes behavior this way. Evaluating
 * [operand] itself is unaffected ([kestrel.engine.Evaluator.evaluate] just recurses into it) -
 * COLLATE only ever changes how two already-evaluated values are ordered against each other, never
 * what value an expression itself produces. Always wins over a column's own declared COLLATE (a
 * `col TYPE COLLATE name` on its [kestrel.catalog.Column], resolved automatically for a bare
 * [ColumnExpression] with no [CollateExpression] of its own via
 * [kestrel.engine.ColumnResolver.declaredCollation]) - explicit beats declared, exactly like
 * SQLite. `ORDER BY`/window `OVER (... ORDER BY ...)` (see [kestrel.engine.compareOrderKeyValues])
 * only ever honor an explicit [CollateExpression] on the sort key, never a bare column's declared
 * COLLATE - a documented, narrower scope cut than before: sorting a `COLLATE NOCASE` column still
 * needs `ORDER BY col COLLATE NOCASE` spelled out, only comparisons pick the declared one up for
 * free.
 */
data class CollateExpression(val operand: Expression, val collation: Collation) : Expression

enum class BinaryOperator(val symbol: String) {
    Add("+"), Subtract("-"), Multiply("*"), Divide("/"),
    Equal("="), NotEqual("<>"), Less("<"), LessEqual("<="),
    Greater(">"), GreaterEqual(">="), And("AND"), Or("OR")
}

enum class UnaryOperator(val symbol: String) {
    Negate("-"), Not("NOT")
}
