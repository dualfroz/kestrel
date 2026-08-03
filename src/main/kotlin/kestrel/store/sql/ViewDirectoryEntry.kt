package kestrel.store.sql

/**
 * The durable, on-disk description of one view: its name plus the exact `CREATE VIEW ... AS ...;`
 * text needed to rebuild its [kestrel.sql.ast.QueryStatement] after a process restart. Unlike
 * [TableDirectoryEntry] this stores rendered SQL text rather than a structured payload - a view's
 * defining query can be arbitrarily deep AST (joins, subqueries, set operations, CTEs), and
 * [kestrel.persistence.SqlWriter] already knows how to render any of that back to text, so
 * re-parsing that text with the normal [kestrel.sql.lexer.Lexer]/[kestrel.sql.parser.Parser] pair
 * on [DiskCatalog.open] reuses already-tested machinery instead of a second, view-specific AST codec.
 */
data class ViewDirectoryEntry(val viewName: String, val statementText: String)
