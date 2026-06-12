package kestrel.catalog

import kestrel.sql.ast.Expression
import kestrel.sql.ast.Statement
import kestrel.sql.ast.TriggerEvent
import kestrel.sql.ast.TriggerTiming

/**
 * `body` only ever holds INSERT/UPDATE/DELETE statements (enforced by the parser) - see
 * [kestrel.sql.ast.CreateTriggerStatement] for why. `tableName` is kept in sync with
 * [Catalog.renameTable] the same way [IndexDefinition.tableName] is; it is NOT rewritten when a
 * column referenced by [whenClause] or [body] is renamed (unlike CHECK constraints, which use
 * [CheckExpressionColumns.rename]) - a documented scope-cut, since [body] statements can each
 * target a different table and no expression-rewrite utility for arbitrary nested statements
 * exists yet. A rename of a column such a reference depends on simply surfaces as "no such column"
 * the next time the trigger fires.
 */
data class TriggerDefinition(
    val name: String,
    val timing: TriggerTiming,
    val event: TriggerEvent,
    val tableName: String,
    val whenClause: Expression?,
    val body: List<Statement>
)
