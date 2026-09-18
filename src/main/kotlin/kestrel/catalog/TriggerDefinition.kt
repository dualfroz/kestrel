package kestrel.catalog

import kestrel.sql.ast.Expression
import kestrel.sql.ast.Statement
import kestrel.sql.ast.TriggerEvent
import kestrel.sql.ast.TriggerTiming

data class TriggerDefinition(
    val name: String,
    val timing: TriggerTiming,
    val event: TriggerEvent,
    val tableName: String,
    val whenClause: Expression?,
    val body: List<Statement>
)
