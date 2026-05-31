package kestrel.engine

import kestrel.KestrelException
import kestrel.catalog.Catalog
import kestrel.catalog.CatalogSnapshot
import kestrel.catalog.Column
import kestrel.catalog.IndexDefinition
import kestrel.catalog.TableSchema
import kestrel.sql.ast.BeginStatement
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.CommitStatement
import kestrel.sql.ast.CreateIndexStatement
import kestrel.sql.ast.CreateTableStatement
import kestrel.sql.ast.DeleteStatement
import kestrel.sql.ast.DropIndexStatement
import kestrel.sql.ast.DropTableStatement
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExplainStatement
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.RollbackStatement
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.Statement
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UpdateStatement
import kestrel.storage.ColumnType
import kestrel.storage.Row
import kestrel.storage.Value
import kestrel.storage.typeName

class Executor(private val catalog: Catalog) {
    private val selectRunner = SelectRunner(catalog)
    private var transactionSnapshot: CatalogSnapshot? = null

    val inTransaction: Boolean get() = transactionSnapshot != null

    fun execute(statement: Statement): StatementResult = when (statement) {
        is CreateTableStatement -> executeCreateTable(statement)
        is DropTableStatement -> executeDropTable(statement)
        is CreateIndexStatement -> executeCreateIndex(statement)
        is DropIndexStatement -> executeDropIndex(statement)
        is InsertStatement -> executeInsert(statement)
        is SelectStatement -> selectRunner.run(statement)
        is DeleteStatement -> executeDelete(statement)
        is UpdateStatement -> executeUpdate(statement)
        is ExplainStatement -> ExplainResult(selectRunner.explain(statement.select))
        is BeginStatement -> executeBegin()
        is CommitStatement -> executeCommit()
        is RollbackStatement -> executeRollback()
    }

    private fun executeBegin(): StatementResult {
        if (inTransaction) throw KestrelException("a transaction is already in progress")
        transactionSnapshot = catalog.snapshot()
        return BeginResult
    }

    private fun executeCommit(): StatementResult {
        if (!inTransaction) throw KestrelException("no transaction is in progress")
        transactionSnapshot = null
        return CommitResult
    }

    private fun executeRollback(): StatementResult {
        val snapshot = transactionSnapshot ?: throw KestrelException("no transaction is in progress")
        catalog.restore(snapshot)
        transactionSnapshot = null
        return RollbackResult
    }

    private fun executeCreateTable(statement: CreateTableStatement): StatementResult {
        if (statement.columns.count { it.primaryKey } > 1) {
            throw KestrelException("table '${statement.tableName}' declares more than one primary key")
        }
        val seen = mutableSetOf<String>()
        for (column in statement.columns) {
            if (!seen.add(column.name.lowercase())) {
                throw KestrelException("duplicate column name: ${column.name}")
            }
        }
        catalog.createTable(TableSchema(statement.tableName, statement.columns))
        return CreateTableResult(statement.tableName)
    }

    private fun executeDropTable(statement: DropTableStatement): StatementResult {
        catalog.dropTable(statement.tableName)
        return DropTableResult(statement.tableName)
    }

    private fun executeCreateIndex(statement: CreateIndexStatement): StatementResult {
        catalog.createIndex(IndexDefinition(statement.indexName, statement.tableName, statement.columnName))
        return CreateIndexResult(statement.indexName)
    }

    private fun executeDropIndex(statement: DropIndexStatement): StatementResult {
        catalog.dropIndex(statement.indexName)
        return DropIndexResult(statement.indexName)
    }

    private fun executeInsert(statement: InsertStatement): StatementResult {
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        val targetIndices = resolveInsertTargets(schema, statement.columns)
        val evaluator = Evaluator()
        val newRows = statement.rows.map { rawRow ->
            if (rawRow.size != targetIndices.size) {
                throw KestrelException(
                    "table ${schema.name} expects ${targetIndices.size} values but ${rawRow.size} were supplied"
                )
            }
            val values = MutableList<Value>(schema.columns.size) { Value.NullValue }
            for ((position, expression) in rawRow.withIndex()) {
                val columnIndex = targetIndices[position]
                values[columnIndex] = coerceForColumn(evaluator.evaluate(expression), schema.columns[columnIndex])
            }
            validateNotNull(schema, values)
            Row(values)
        }
        enforcePrimaryKeyUniqueness(schema, table.rows + newRows)
        table.rows.addAll(newRows)
        catalog.reindexTable(schema.name)
        return InsertResult(newRows.size)
    }

    private fun resolveInsertTargets(schema: TableSchema, columns: List<String>?): List<Int> {
        if (columns == null) return schema.columns.indices.toList()
        return columns.map { columnName ->
            schema.columnIndex(columnName)
                ?: throw KestrelException("no such column: $columnName in table ${schema.name}")
        }
    }

    private fun executeDelete(statement: DeleteStatement): StatementResult {
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        if (statement.where == null) {
            val removed = table.rows.size
            table.rows.clear()
            catalog.reindexTable(schema.name)
            return DeleteResult(removed)
        }
        checkColumns(statement.where, schema)
        val kept = mutableListOf<Row>()
        var deleted = 0
        for (row in table.rows) {
            if (isTrue(Evaluator(schema, row).evaluate(statement.where))) {
                deleted++
            } else {
                kept.add(row)
            }
        }
        table.rows.clear()
        table.rows.addAll(kept)
        catalog.reindexTable(schema.name)
        return DeleteResult(deleted)
    }

    private fun executeUpdate(statement: UpdateStatement): StatementResult {
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        val assignments = statement.assignments.map { assignment ->
            val index = schema.columnIndex(assignment.columnName)
                ?: throw KestrelException("no such column: ${assignment.columnName}")
            checkColumns(assignment.value, schema)
            index to assignment.value
        }
        statement.where?.let { checkColumns(it, schema) }

        var updated = 0
        val newRows = table.rows.map { row ->
            val matches = statement.where == null || isTrue(Evaluator(schema, row).evaluate(statement.where))
            if (!matches) return@map row
            val values = row.values.toMutableList()
            val evaluator = Evaluator(schema, row)
            for ((index, expression) in assignments) {
                values[index] = coerceForColumn(evaluator.evaluate(expression), schema.columns[index])
            }
            validateNotNull(schema, values)
            updated++
            Row(values)
        }
        enforcePrimaryKeyUniqueness(schema, newRows)
        table.rows.clear()
        table.rows.addAll(newRows)
        catalog.reindexTable(schema.name)
        return UpdateResult(updated)
    }

    private fun coerceForColumn(value: Value, column: Column): Value {
        if (value is Value.NullValue) return value
        return when (column.type) {
            ColumnType.Integer -> value as? Value.IntegerValue ?: throw typeMismatch(column, value)
            ColumnType.Text -> value as? Value.TextValue ?: throw typeMismatch(column, value)
            ColumnType.Boolean -> value as? Value.BooleanValue ?: throw typeMismatch(column, value)
            ColumnType.Real -> when (value) {
                is Value.RealValue -> value
                is Value.IntegerValue -> Value.RealValue(value.value.toDouble())
                else -> throw typeMismatch(column, value)
            }
        }
    }

    private fun typeMismatch(column: Column, value: Value): KestrelException =
        KestrelException("type mismatch: column ${column.name} expects ${column.type.displayName} but got ${value.typeName}")

    private fun validateNotNull(schema: TableSchema, values: List<Value>) {
        for ((index, column) in schema.columns.withIndex()) {
            if (column.notNull && values[index] is Value.NullValue) {
                throw KestrelException("NOT NULL constraint failed: ${schema.name}.${column.name}")
            }
        }
    }

    private fun enforcePrimaryKeyUniqueness(schema: TableSchema, rows: List<Row>) {
        val primaryKeyIndex = schema.primaryKeyIndex ?: return
        val seen = HashSet<Value>()
        for (row in rows) {
            val key = row.values[primaryKeyIndex]
            if (!seen.add(key)) {
                throw KestrelException("UNIQUE constraint failed: ${schema.name}.${schema.columns[primaryKeyIndex].name}")
            }
        }
    }

    private fun checkColumns(expression: Expression, schema: TableSchema) {
        when (expression) {
            is ColumnExpression -> if (schema.columnIndex(expression.name) == null) {
                throw KestrelException("no such column: ${expression.name}")
            }
            is BinaryExpression -> {
                checkColumns(expression.left, schema)
                checkColumns(expression.right, schema)
            }
            is UnaryExpression -> checkColumns(expression.operand, schema)
            is FunctionExpression -> throw KestrelException("aggregate functions are not allowed here")
            is LiteralExpression -> Unit
        }
    }

    private fun isTrue(value: Value): Boolean = value is Value.BooleanValue && value.value
}
