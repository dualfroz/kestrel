package kestrel.persistence

import java.math.BigDecimal
import kestrel.catalog.Catalog
import kestrel.catalog.Column
import kestrel.sql.ast.Assignment
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.CreateIndexStatement
import kestrel.sql.ast.CreateTableStatement
import kestrel.sql.ast.DeleteStatement
import kestrel.sql.ast.DropIndexStatement
import kestrel.sql.ast.DropTableStatement
import kestrel.sql.ast.Expression
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.Statement
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UpdateStatement
import kestrel.storage.Value

object SqlWriter {
    fun canPersist(statement: Statement): Boolean = statement is CreateTableStatement ||
        statement is DropTableStatement ||
        statement is CreateIndexStatement ||
        statement is DropIndexStatement ||
        statement is InsertStatement ||
        statement is UpdateStatement ||
        statement is DeleteStatement

    fun write(statement: Statement): String = when (statement) {
        is CreateTableStatement -> "CREATE TABLE ${statement.tableName} (" +
            statement.columns.joinToString(", ") { columnDefinition(it) } + ");"
        is DropTableStatement -> "DROP TABLE ${statement.tableName};"
        is CreateIndexStatement ->
            "CREATE INDEX ${statement.indexName} ON ${statement.tableName} (${statement.columnName});"
        is DropIndexStatement -> "DROP INDEX ${statement.indexName};"
        is InsertStatement -> writeInsert(statement)
        is UpdateStatement -> writeUpdate(statement)
        is DeleteStatement -> writeDelete(statement)
        else -> throw IllegalArgumentException("statement is not persistable")
    }

    fun dump(catalog: Catalog): String {
        val builder = StringBuilder()
        for (tableName in catalog.tableNames) {
            val table = catalog.table(tableName)
            builder.append("CREATE TABLE ${table.schema.name} (")
            builder.append(table.schema.columns.joinToString(", ") { columnDefinition(it) })
            builder.append(");\n")
            for (row in table.rows) {
                builder.append("INSERT INTO ${table.schema.name} VALUES (")
                builder.append(row.values.joinToString(", ") { literal(it) })
                builder.append(");\n")
            }
        }
        for (definition in catalog.indexDefinitions()) {
            builder.append("CREATE INDEX ${definition.name} ON ${definition.tableName} (${definition.columnName});\n")
        }
        return builder.toString()
    }

    private fun writeInsert(statement: InsertStatement): String {
        val columns = statement.columns?.let { " (${it.joinToString(", ")})" } ?: ""
        val rows = statement.rows.joinToString(", ") { row ->
            "(" + row.joinToString(", ") { expression(it) } + ")"
        }
        return "INSERT INTO ${statement.tableName}$columns VALUES $rows;"
    }

    private fun writeUpdate(statement: UpdateStatement): String {
        val assignments = statement.assignments.joinToString(", ") { assignment(it) }
        val where = statement.where?.let { " WHERE ${expression(it)}" } ?: ""
        return "UPDATE ${statement.tableName} SET $assignments$where;"
    }

    private fun writeDelete(statement: DeleteStatement): String {
        val where = statement.where?.let { " WHERE ${expression(it)}" } ?: ""
        return "DELETE FROM ${statement.tableName}$where;"
    }

    private fun assignment(assignment: Assignment): String =
        "${assignment.columnName} = ${expression(assignment.value)}"

    private fun columnDefinition(column: Column): String {
        val constraint = when {
            column.primaryKey -> " PRIMARY KEY"
            column.notNull -> " NOT NULL"
            else -> ""
        }
        return "${column.name} ${column.type.displayName}$constraint"
    }

    private fun expression(expression: Expression): String = when (expression) {
        is LiteralExpression -> literal(expression.value)
        is ColumnExpression -> expression.qualifier?.let { "$it.${expression.name}" } ?: expression.name
        is FunctionExpression ->
            "${expression.name}(${if (expression.star) "*" else expression.argument?.let { expression(it) } ?: ""})"
        is BinaryExpression ->
            "(${expression(expression.left)} ${expression.operator.symbol} ${expression(expression.right)})"
        is UnaryExpression -> "(${expression.operator.symbol} ${expression(expression.operand)})"
    }

    private fun literal(value: Value): String = when (value) {
        is Value.IntegerValue -> value.value.toString()
        is Value.RealValue -> realText(value.value)
        is Value.TextValue -> "'" + value.value.replace("'", "''") + "'"
        is Value.BooleanValue -> value.value.toString()
        is Value.NullValue -> "NULL"
    }

    private fun realText(value: Double): String {
        val plain = value.toString()
        return if (plain.contains('E') || plain.contains('e')) BigDecimal(value).toPlainString() else plain
    }
}
