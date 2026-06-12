package kestrel.catalog

import kestrel.sql.ast.QueryStatement

data class ViewDefinition(val name: String, val query: QueryStatement)
