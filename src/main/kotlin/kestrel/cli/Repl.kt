package kestrel.cli

import java.io.BufferedReader
import kestrel.KestrelException
import kestrel.LexException
import kestrel.catalog.Catalog
import kestrel.catalog.ForeignKeyAction
import kestrel.catalog.sqlKeyword
import kestrel.engine.AlterSequenceResult
import kestrel.engine.AlterTableResult
import kestrel.engine.BeginResult
import kestrel.engine.CommitResult
import kestrel.engine.CreateIndexResult
import kestrel.engine.CreateSequenceResult
import kestrel.engine.CreateTableResult
import kestrel.engine.CreateTriggerResult
import kestrel.engine.CreateViewResult
import kestrel.engine.DeallocateResult
import kestrel.engine.DeleteResult
import kestrel.engine.DropIndexResult
import kestrel.engine.DropSequenceResult
import kestrel.engine.DropTableResult
import kestrel.engine.DropTriggerResult
import kestrel.engine.DropViewResult
import kestrel.engine.ExplainResult
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.MergeResult
import kestrel.engine.PrepareResult
import kestrel.engine.ReleaseSavepointResult
import kestrel.engine.RollbackResult
import kestrel.engine.RollbackToSavepointResult
import kestrel.engine.SavepointResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.TruncateTableResult
import kestrel.engine.UpdateResult
import kestrel.persistence.Persistence
import kestrel.persistence.SqlWriter
import kestrel.sql.ast.AlterTableStatement
import kestrel.sql.ast.BeginStatement
import kestrel.sql.ast.CommitStatement
import kestrel.sql.ast.CreateIndexStatement
import kestrel.sql.ast.CreateTableStatement
import kestrel.sql.ast.DeleteStatement
import kestrel.sql.ast.DropIndexStatement
import kestrel.sql.ast.DropTableStatement
import kestrel.sql.ast.ExecuteStatement
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.MergeStatement
import kestrel.sql.ast.ReleaseSavepointStatement
import kestrel.sql.ast.RollbackStatement
import kestrel.sql.ast.RollbackToSavepointStatement
import kestrel.sql.ast.SavepointStatement
import kestrel.sql.ast.Statement
import kestrel.sql.ast.TruncateTableStatement
import kestrel.sql.ast.UpdateStatement
import kestrel.sql.lexer.Lexer
import kestrel.storage.displayWith
import kestrel.sql.lexer.Token
import kestrel.sql.lexer.TokenType
import kestrel.sql.parser.Parser
import kestrel.store.sql.DiskCatalog
import kestrel.store.sql.DiskExecutor

class Repl(
    private val output: Appendable = System.out,
    private val errorOutput: Appendable = System.err,
    private val persistence: Persistence? = null,
    private val diskCatalog: DiskCatalog? = null
) {
    private val catalog = Catalog()
    private val executor = Executor(catalog)
    private val diskExecutor = diskCatalog?.let { DiskExecutor(it) }
    private val pending = StringBuilder()
    private var pendingCommit: MutableList<String>? = null
    private val savepointMarks = mutableListOf<Pair<String, Int>>()
    private var replaying = false

    init {
        require(persistence == null || diskCatalog == null) {
            "Repl cannot combine text-based Persistence with a disk-backed DiskCatalog - pick one storage backend"
        }
        if (persistence != null) {
            val restored = persistence.load()
            if (restored.isNotBlank()) {
                replaying = true
                executeBuffer(restored)
                replaying = false
            }
        }
    }

    fun run(input: BufferedReader) {
        while (true) {
            val line = input.readLine() ?: break
            if (handleLine(line)) {
                finish()
                return
            }
        }
        flushPending()
        finish()
    }

    private fun finish() {
        if (diskExecutor != null) {
            if (diskExecutor.inTransaction) diskExecutor.execute(RollbackStatement)
            diskCatalog?.close()
            return
        }
        if (executor.inTransaction) executor.execute(RollbackStatement)
        pendingCommit = null
        persistence?.checkpoint(SqlWriter.dump(catalog))
    }

    private fun handleLine(line: String): Boolean {
        val trimmed = line.trim()
        if (pending.isBlank() && trimmed.startsWith(".")) {
            return handleMetaCommand(trimmed)
        }
        if (pending.isBlank() && trimmed.isEmpty()) return false
        pending.append(line).append('\n')
        maybeExecute()
        return false
    }

    private fun maybeExecute() {
        val tokens = try {
            Lexer(pending.toString()).tokenize()
        } catch (exception: LexException) {
            if (exception.incomplete) return
            printError(exception.message)
            pending.setLength(0)
            return
        }
        val meaningful = tokens.dropLast(1)
        if (meaningful.isNotEmpty() && meaningful.last().type == TokenType.Semicolon && !endsInsideOpenBlock(meaningful)) {
            executeBuffer(pending.toString())
            pending.setLength(0)
        }
    }

    private fun endsInsideOpenBlock(tokens: List<Token>): Boolean {
        var depth = 0
        for (index in tokens.indices) {
            when (tokens[index].type) {
                TokenType.Case -> depth++
                TokenType.Begin -> {
                    val previous = tokens.getOrNull(index - 1)?.type
                    if (previous != null && previous != TokenType.Semicolon) depth++
                }
                TokenType.End -> if (depth > 0) depth--
                else -> Unit
            }
        }
        return depth > 0
    }

    private fun flushPending() {
        if (pending.isNotBlank()) {
            executeBuffer(pending.toString())
            pending.setLength(0)
        }
    }

    private fun executeBuffer(sql: String) {
        val statements = try {
            Parser(Lexer(sql).tokenize()).parseProgram()
        } catch (exception: KestrelException) {
            printError(exception.message)
            return
        }
        for (statement in statements) {
            try {
                val effective = if (diskExecutor == null && statement is ExecuteStatement) {
                    executor.resolveExecute(statement)
                } else {
                    statement
                }
                val temporaryTargets = if (diskExecutor == null) temporaryTableTargets(effective) else emptySet()
                val result = if (diskExecutor != null) diskExecutor.execute(statement) else executor.execute(effective)
                printResult(result)
                if (diskExecutor == null && !replaying) recordForPersistence(effective, temporaryTargets)
            } catch (exception: KestrelException) {
                printError(exception.message)
            }
        }
    }

    private fun temporaryTableTargets(statement: Statement): Set<String> = when (statement) {
        is CreateTableStatement -> if (statement.temporary) setOf(statement.tableName) else emptySet()
        is DropTableStatement -> temporaryOf(statement.tableName)
        is TruncateTableStatement -> statement.tableNames.filter { isTemporaryTable(it) }.toSet()
        is AlterTableStatement -> temporaryOf(statement.tableName)
        is CreateIndexStatement -> temporaryOf(statement.tableName)
        is DropIndexStatement -> catalog.indexDefinitions()
            .firstOrNull { it.name.equals(statement.indexName, ignoreCase = true) }
            ?.let { temporaryOf(it.tableName) } ?: emptySet()
        is InsertStatement -> temporaryOf(statement.tableName)
        is UpdateStatement -> temporaryOf(statement.tableName)
        is DeleteStatement -> temporaryOf(statement.tableName)
        is MergeStatement -> temporaryOf(statement.targetTable)
        else -> emptySet()
    }

    private fun isTemporaryTable(tableName: String): Boolean = catalog.tableOrNull(tableName)?.schema?.temporary == true

    private fun temporaryOf(tableName: String): Set<String> = if (isTemporaryTable(tableName)) setOf(tableName) else emptySet()

    private fun recordForPersistence(statement: Statement, temporaryTargets: Set<String>) {
        when (statement) {
            is BeginStatement -> {
                pendingCommit = mutableListOf()
                savepointMarks.clear()
            }
            is CommitStatement -> {
                pendingCommit?.let { persistence?.appendWal(it) }
                pendingCommit = null
                savepointMarks.clear()
            }
            is RollbackStatement -> {
                pendingCommit = null
                savepointMarks.clear()
            }
            is SavepointStatement -> {
                pendingCommit?.let { savepointMarks.add(statement.name to it.size) }
            }
            is ReleaseSavepointStatement -> {
                val index = savepointMarks.indexOfLast { it.first.equals(statement.name, ignoreCase = true) }
                while (savepointMarks.size > index) savepointMarks.removeAt(savepointMarks.size - 1)
            }
            is RollbackToSavepointStatement -> {
                val index = savepointMarks.indexOfLast { it.first.equals(statement.name, ignoreCase = true) }
                val mark = savepointMarks[index].second
                pendingCommit?.let { buffer -> while (buffer.size > mark) buffer.removeAt(buffer.size - 1) }
                while (savepointMarks.size > index + 1) savepointMarks.removeAt(savepointMarks.size - 1)
            }
            is TruncateTableStatement -> {
                val persistedNames = statement.tableNames.filterNot { it in temporaryTargets }
                if (persistedNames.isNotEmpty()) {
                    appendToWal(SqlWriter.write(statement.copy(tableNames = persistedNames)))
                }
            }
            else -> if (temporaryTargets.isEmpty() && SqlWriter.canPersist(statement)) {
                appendToWal(SqlWriter.write(statement))
            }
        }
    }

    private fun appendToWal(sql: String) {
        val buffer = pendingCommit
        if (buffer != null) buffer.add(sql) else persistence?.appendWal(listOf(sql))
    }

    private fun printResult(result: StatementResult) {
        if (replaying) return
        when (result) {
            is SelectResult -> output.appendLine(TableRenderer.render(result))
            is InsertResult -> {
                output.appendLine("${rowsWord(result.count)} inserted")
                result.returning?.let { output.appendLine(TableRenderer.render(it)) }
            }
            is UpdateResult -> {
                output.appendLine("${rowsWord(result.count)} updated")
                result.returning?.let { output.appendLine(TableRenderer.render(it)) }
            }
            is DeleteResult -> {
                output.appendLine("${rowsWord(result.count)} deleted")
                result.returning?.let { output.appendLine(TableRenderer.render(it)) }
            }
            is CreateTableResult -> output.appendLine("Table ${result.tableName} created")
            is DropTableResult -> output.appendLine("Table ${result.tableName} dropped")
            is TruncateTableResult -> output.appendLine("Table(s) ${result.tableNames.joinToString(", ")} truncated")
            is MergeResult -> {
                output.appendLine(
                    "${rowsWord(result.insertedCount)} inserted, ${rowsWord(result.updatedCount)} updated, " +
                        "${rowsWord(result.deletedCount)} deleted"
                )
                result.returning?.let { output.appendLine(TableRenderer.render(it)) }
            }
            is AlterTableResult -> output.appendLine("Table ${result.tableName} altered")
            is CreateIndexResult -> output.appendLine("Index ${result.indexName} created")
            is DropIndexResult -> output.appendLine("Index ${result.indexName} dropped")
            is CreateViewResult -> output.appendLine("View ${result.viewName} created")
            is DropViewResult -> output.appendLine("View ${result.viewName} dropped")
            is CreateTriggerResult -> output.appendLine("Trigger ${result.triggerName} created")
            is DropTriggerResult -> output.appendLine("Trigger ${result.triggerName} dropped")
            is CreateSequenceResult -> output.appendLine("Sequence ${result.sequenceName} created")
            is DropSequenceResult -> output.appendLine("Sequence ${result.sequenceName} dropped")
            is AlterSequenceResult -> output.appendLine("Sequence ${result.sequenceName} altered")
            is ExplainResult -> result.plan.forEach { output.appendLine(it) }
            BeginResult -> output.appendLine("Transaction started")
            CommitResult -> output.appendLine("Transaction committed")
            RollbackResult -> output.appendLine("Transaction rolled back")
            is SavepointResult -> output.appendLine("Savepoint ${result.name} created")
            is ReleaseSavepointResult -> output.appendLine("Savepoint ${result.name} released")
            is RollbackToSavepointResult -> output.appendLine("Rolled back to savepoint ${result.name}")
            is PrepareResult -> output.appendLine("Prepared statement ${result.name} created")
            is DeallocateResult ->
                output.appendLine(if (result.all) "All prepared statements deallocated" else "Prepared statement ${result.name} deallocated")
        }
    }

    private fun handleMetaCommand(command: String): Boolean {
        val parts = command.split(Regex("\\s+"), limit = 2)
        when (parts[0]) {
            ".exit", ".quit" -> return true
            ".tables" -> printNames(tableNames(), "(no tables)")
            ".indexes" -> printNames(indexNames(), "(no indexes)")
            ".views" -> printNames(viewNames(), "(no views)")
            ".triggers" -> printNames(triggerNames(), "(no triggers)")
            ".sequences" -> printNames(sequenceNames(), "(no sequences)")
            ".prepared" -> printNames(
                diskExecutor?.preparedStatementNames ?: executor.preparedStatementNames,
                "(no prepared statements)"
            )
            ".schema" -> {
                if (parts.size < 2 || parts[1].isBlank()) {
                    printError("usage: .schema <table>")
                } else {
                    printSchema(parts[1].trim())
                }
            }
            else -> printError("unknown command: ${parts[0]}")
        }
        return false
    }

    private fun printNames(names: List<String>, emptyMessage: String) {
        if (names.isEmpty()) output.appendLine(emptyMessage) else names.forEach { output.appendLine(it) }
    }

    private fun tableNames(): List<String> = diskCatalog?.tableNames ?: catalog.tableNames
    private fun viewNames(): List<String> = diskCatalog?.viewNames ?: catalog.viewNames
    private fun triggerNames(): List<String> = diskCatalog?.triggerNames ?: catalog.triggerNames
    private fun sequenceNames(): List<String> = diskCatalog?.sequenceNames ?: catalog.sequenceNames

    private fun indexNames(): List<String> {
        val disk = diskCatalog ?: return catalog.indexNames
        return disk.tableNames.flatMap { disk.table(it).secondaryIndexes.map { index -> index.name } }
    }

    private fun indexLines(tableName: String): List<String> {
        val disk = diskCatalog
        if (disk != null) {
            val table = disk.tableOrNull(tableName) ?: return emptyList()
            return table.secondaryIndexes.map { index ->
                val uniqueKeyword = if (index.unique) "UNIQUE " else ""
                "CREATE ${uniqueKeyword}INDEX ${index.name} ON $tableName (${index.columnNames.joinToString(", ")});"
            }
        }
        return catalog.indexDefinitions()
            .filter { it.tableName.equals(tableName, ignoreCase = true) }
            .map { index ->
                val uniqueKeyword = if (index.unique) "UNIQUE " else ""
                "CREATE ${uniqueKeyword}INDEX ${index.name} ON $tableName (${index.columnDisplayName});"
            }
    }

    private fun triggerLines(tableName: String): List<String> {
        val definitions = diskCatalog?.triggerDefinitions() ?: catalog.triggerDefinitions()
        return definitions
            .filter { it.tableName.equals(tableName, ignoreCase = true) }
            .map { SqlWriter.triggerText(it) }
    }

    private fun printSchema(tableName: String) {
        val schema = diskCatalog?.tableOrNull(tableName)?.schema
            ?: diskCatalog?.temporaryTableOrNull(tableName)?.schema
            ?: catalog.tableOrNull(tableName)?.schema
        if (schema == null) {
            printError("no such table: $tableName")
            return
        }
        val builder = StringBuilder()
        builder.appendLine("CREATE TABLE ${schema.name} (")
        val lines = mutableListOf<String>()
        schema.columns.forEach { column ->
            val constraints = buildString {
                if (column.primaryKey) append(" PRIMARY KEY")
                if (column.notNull && !column.primaryKey) append(" NOT NULL")
                column.default?.let { append(" DEFAULT ${SqlWriter.expressionText(it)}") }
            }
            lines.add("  ${column.name} ${column.type.displayWith(column.precision, column.scale)}$constraints")
        }
        if (schema.compositePrimaryKey.isNotEmpty()) {
            lines.add("  PRIMARY KEY (${schema.compositePrimaryKey.joinToString(", ")})")
        }
        schema.foreignKeys.forEach { fk ->
            val onDelete = if (fk.onDelete != ForeignKeyAction.NoAction) " ON DELETE ${fk.onDelete.sqlKeyword}" else ""
            val onUpdate = if (fk.onUpdate != ForeignKeyAction.NoAction) " ON UPDATE ${fk.onUpdate.sqlKeyword}" else ""
            lines.add(
                "  FOREIGN KEY (${fk.columns.joinToString(", ")}) REFERENCES ${fk.referencedTable} " +
                    "(${fk.referencedColumns.joinToString(", ")})$onDelete$onUpdate"
            )
        }
        schema.checks.forEach { check ->
            lines.add("  CHECK (${SqlWriter.expressionText(check.expression)})")
        }
        schema.uniqueConstraints.forEach { unique ->
            lines.add("  UNIQUE (${unique.columns.joinToString(", ")})")
        }
        builder.append(lines.joinToString(",\n"))
        builder.append("\n);")
        output.appendLine(builder.toString())
        indexLines(schema.name).forEach { output.appendLine(it) }
        triggerLines(schema.name).forEach { output.appendLine(it) }
    }

    private fun printError(message: String?) {
        errorOutput.appendLine("Error: ${message ?: "unknown error"}")
    }

    private fun rowsWord(count: Int): String = if (count == 1) "1 row" else "$count rows"
}
