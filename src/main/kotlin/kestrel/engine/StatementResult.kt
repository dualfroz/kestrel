package kestrel.engine

import kestrel.storage.Value

sealed interface StatementResult

data class SelectResult(val columns: List<String>, val rows: List<List<Value>>) : StatementResult

data class InsertResult(val count: Int) : StatementResult

data class UpdateResult(val count: Int) : StatementResult

data class DeleteResult(val count: Int) : StatementResult

data class CreateTableResult(val tableName: String) : StatementResult

data class DropTableResult(val tableName: String) : StatementResult

data class CreateIndexResult(val indexName: String) : StatementResult

data class DropIndexResult(val indexName: String) : StatementResult

data class ExplainResult(val plan: List<String>) : StatementResult

data object BeginResult : StatementResult

data object CommitResult : StatementResult

data object RollbackResult : StatementResult
