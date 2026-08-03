package kestrel.store.sql

/**
 * The durable, on-disk description of one trigger: its name plus the exact `CREATE TRIGGER ...;`
 * text needed to rebuild its [kestrel.catalog.TriggerDefinition] after a process restart - mirrors
 * [ViewDirectoryEntry]'s own reasoning: a trigger's WHEN clause and body can be arbitrarily deep AST
 * (any INSERT/UPDATE/DELETE, each with its own WHERE/SET/VALUES), and [kestrel.persistence.SqlWriter.
 * triggerText] already knows how to render any of that back to text, so re-parsing it with the normal
 * [kestrel.sql.lexer.Lexer]/[kestrel.sql.parser.Parser] pair on [DiskCatalog.open] reuses
 * already-tested machinery instead of a second, trigger-specific AST codec.
 */
data class TriggerDirectoryEntry(val triggerName: String, val statementText: String)
