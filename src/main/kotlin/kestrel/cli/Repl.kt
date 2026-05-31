package kestrel.cli

import java.io.BufferedReader
import kestrel.KestrelException
import kestrel.LexException
import kestrel.catalog.Catalog
import kestrel.engine.BeginResult
import kestrel.engine.CommitResult
import kestrel.engine.CreateIndexResult
import kestrel.engine.CreateTableResult
import kestrel.engine.DeleteResult
import kestrel.engine.DropIndexResult
import kestrel.engine.DropTableResult
import kestrel.engine.ExplainResult
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.RollbackResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.UpdateResult
import kestrel.persistence.Persistence
import kestrel.persistence.SqlWriter
import kestrel.sql.ast.BeginStatement
import kestrel.sql.ast.CommitStatement
import kestrel.sql.ast.RollbackStatement
import kestrel.sql.ast.Statement
import kestrel.sql.lexer.Lexer
import kestrel.sql.lexer.TokenType
import kestrel.sql.parser.Parser

class Repl(
    private val output: Appendable = System.out,
    private val errorOutput: Appendable = System.err,
    private val persistence: Persistence? = null
) {
    private val catalog = Catalog()
    private val executor = Executor(catalog)
    private val pending = StringBuilder()
    private var pendingCommit: MutableList<String>? = null
    private var replaying = false

    init {
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
        if (meaningful.isNotEmpty() && meaningful.last().type == TokenType.Semicolon) {
            executeBuffer(pending.toString())
            pending.setLength(0)
        }
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
                val result = executor.execute(statement)
                printResult(result)
                if (!replaying) recordForPersistence(statement)
            } catch (exception: KestrelException) {
                printError(exception.message)
            }
        }
    }

    private fun recordForPersistence(statement: Statement) {
        when (statement) {
            is BeginStatement -> pendingCommit = mutableListOf()
            is CommitStatement -> {
                pendingCommit?.let { persistence?.appendWal(it) }
                pendingCommit = null
            }
            is RollbackStatement -> pendingCommit = null
            else -> if (SqlWriter.canPersist(statement)) {
                val sql = SqlWriter.write(statement)
                val buffer = pendingCommit
                if (buffer != null) buffer.add(sql) else persistence?.appendWal(listOf(sql))
            }
        }
    }

    private fun printResult(result: StatementResult) {
        if (replaying) return
        when (result) {
            is SelectResult -> output.appendLine(TableRenderer.render(result))
            is InsertResult -> output.appendLine("${rowsWord(result.count)} inserted")
            is UpdateResult -> output.appendLine("${rowsWord(result.count)} updated")
            is DeleteResult -> output.appendLine("${rowsWord(result.count)} deleted")
            is CreateTableResult -> output.appendLine("Table ${result.tableName} created")
            is DropTableResult -> output.appendLine("Table ${result.tableName} dropped")
            is CreateIndexResult -> output.appendLine("Index ${result.indexName} created")
            is DropIndexResult -> output.appendLine("Index ${result.indexName} dropped")
            is ExplainResult -> result.plan.forEach { output.appendLine(it) }
            BeginResult -> output.appendLine("Transaction started")
            CommitResult -> output.appendLine("Transaction committed")
            RollbackResult -> output.appendLine("Transaction rolled back")
        }
    }

    private fun handleMetaCommand(command: String): Boolean {
        val parts = command.split(Regex("\\s+"), limit = 2)
        when (parts[0]) {
            ".exit", ".quit" -> return true
            ".tables" -> {
                val names = catalog.tableNames
                if (names.isEmpty()) output.appendLine("(no tables)") else names.forEach { output.appendLine(it) }
            }
            ".indexes" -> {
                val names = catalog.indexNames
                if (names.isEmpty()) output.appendLine("(no indexes)") else names.forEach { output.appendLine(it) }
            }
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

    private fun printSchema(tableName: String) {
        val table = catalog.tableOrNull(tableName)
        if (table == null) {
            printError("no such table: $tableName")
            return
        }
        val schema = table.schema
        val builder = StringBuilder()
        builder.appendLine("CREATE TABLE ${schema.name} (")
        schema.columns.forEachIndexed { index, column ->
            val constraints = buildString {
                if (column.primaryKey) append(" PRIMARY KEY")
                if (column.notNull && !column.primaryKey) append(" NOT NULL")
            }
            val comma = if (index < schema.columns.size - 1) "," else ""
            builder.appendLine("  ${column.name} ${column.type.displayName}$constraints$comma")
        }
        builder.append(");")
        output.appendLine(builder.toString())
    }

    private fun printError(message: String?) {
        errorOutput.appendLine("Error: ${message ?: "unknown error"}")
    }

    private fun rowsWord(count: Int): String = if (count == 1) "1 row" else "$count rows"
}
