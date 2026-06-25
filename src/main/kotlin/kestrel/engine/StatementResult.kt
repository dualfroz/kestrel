package kestrel.engine

import kestrel.storage.Value

sealed interface StatementResult

data class SelectResult(val columns: List<String>, val rows: List<List<Value>>) : StatementResult

data class InsertResult(val count: Int, val returning: SelectResult? = null) : StatementResult

data class UpdateResult(val count: Int, val returning: SelectResult? = null) : StatementResult

data class DeleteResult(val count: Int, val returning: SelectResult? = null) : StatementResult

data class CreateTableResult(val tableName: String) : StatementResult

data class DropTableResult(val tableName: String) : StatementResult

/** [tableNames] is every table actually truncated - the statement's own list plus, under CASCADE, every transitive FOREIGN KEY referencer added to it. */
data class TruncateTableResult(val tableNames: List<String>) : StatementResult

data class MergeResult(
    val insertedCount: Int,
    val updatedCount: Int,
    val deletedCount: Int,
    val returning: SelectResult? = null
) : StatementResult

data class AlterTableResult(val tableName: String) : StatementResult

data class CreateIndexResult(val indexName: String) : StatementResult

data class DropIndexResult(val indexName: String) : StatementResult

data class CreateViewResult(val viewName: String) : StatementResult

data class DropViewResult(val viewName: String) : StatementResult

data class CreateTriggerResult(val triggerName: String) : StatementResult

data class DropTriggerResult(val triggerName: String) : StatementResult

data class CreateSequenceResult(val sequenceName: String) : StatementResult

data class DropSequenceResult(val sequenceName: String) : StatementResult

data class AlterSequenceResult(val sequenceName: String) : StatementResult

data class ExplainResult(val plan: List<String>) : StatementResult

data object BeginResult : StatementResult

data object CommitResult : StatementResult

data object RollbackResult : StatementResult

data class SavepointResult(val name: String) : StatementResult

data class ReleaseSavepointResult(val name: String) : StatementResult

data class RollbackToSavepointResult(val name: String) : StatementResult

data class PrepareResult(val name: String) : StatementResult

/** [name] is `null` exactly when [all] is `true` (`DEALLOCATE ALL`), mirroring [kestrel.sql.ast.DeallocateStatement]. */
data class DeallocateResult(val name: String?, val all: Boolean) : StatementResult
