package kestrel

import kestrel.sql.ast.Assignment
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.CreateTableStatement
import kestrel.sql.ast.DeleteStatement
import kestrel.sql.ast.DropTableStatement
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.StarProjection
import kestrel.sql.ast.Statement
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UnaryOperator
import kestrel.sql.ast.UpdateStatement
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.ColumnType
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {
    private fun parse(sql: String): List<Statement> = Parser(Lexer(sql).tokenize()).parseProgram()

    private fun parseOne(sql: String): Statement = parse(sql).single()

    private fun intLit(value: Long) = LiteralExpression(Value.IntegerValue(value))

    private fun whereOf(sql: String): Expression = (parseOne(sql) as SelectStatement).where!!

    private fun firstProjection(sql: String): Expression =
        ((parseOne(sql) as SelectStatement).projection as ExpressionProjection).items.first().expression

    @Test
    fun parsesCreateTableWithConstraints() {
        val statement = parseOne("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER)")
        assertTrue(statement is CreateTableStatement)
        assertEquals("users", statement.tableName)
        assertEquals(3, statement.columns.size)
        val id = statement.columns[0]
        assertEquals(ColumnType.Integer, id.type)
        assertTrue(id.primaryKey)
        assertTrue(id.notNull)
        val name = statement.columns[1]
        assertTrue(name.notNull)
        assertTrue(!name.primaryKey)
        assertTrue(!statement.columns[2].notNull)
    }

    @Test
    fun parsesInsertWithoutColumnList() {
        val statement = parseOne("INSERT INTO users VALUES (1, 'ada', 36)")
        assertTrue(statement is InsertStatement)
        assertNull(statement.columns)
        assertEquals(1, statement.rows.size)
        assertEquals(3, statement.rows[0].size)
    }

    @Test
    fun parsesInsertWithColumnListAndMultipleRows() {
        val statement = parseOne("INSERT INTO t (a, b) VALUES (1, 2), (3, 4), (5, 6)") as InsertStatement
        assertEquals(listOf("a", "b"), statement.columns)
        assertEquals(3, statement.rows.size)
    }

    @Test
    fun parsesSelectStar() {
        val statement = parseOne("SELECT * FROM users") as SelectStatement
        assertEquals(StarProjection, statement.projection)
        assertEquals("users", statement.tableName)
        assertNull(statement.where)
        assertNull(statement.orderBy)
        assertNull(statement.limit)
    }

    @Test
    fun parsesSelectWithAliasWhereOrderLimit() {
        val statement = parseOne(
            "SELECT name AS who, age FROM users WHERE age > 40 ORDER BY age DESC LIMIT 5"
        ) as SelectStatement
        val projection = statement.projection as ExpressionProjection
        assertEquals("who", projection.items[0].alias)
        assertEquals(true, statement.orderBy!!.descending)
        assertEquals(5, statement.limit)
    }

    @Test
    fun parsesDeleteAndDropAndUpdate() {
        assertTrue(parseOne("DELETE FROM t WHERE id = 1") is DeleteStatement)
        assertTrue(parseOne("DROP TABLE t") is DropTableStatement)
        val update = parseOne("UPDATE t SET a = 1, b = a + 2 WHERE id = 3") as UpdateStatement
        assertEquals(2, update.assignments.size)
        assertEquals(Assignment("a", intLit(1)), update.assignments[0])
    }

    @Test
    fun multipleStatementsAreSeparatedBySemicolons() {
        val statements = parse("CREATE TABLE t (a INTEGER); DROP TABLE t;")
        assertEquals(2, statements.size)
    }

    @Test
    fun arithmeticHonoursPrecedence() {
        val expression = firstProjection("SELECT 1 + 2 * 3 FROM t")
        val expected = BinaryExpression(
            BinaryOperator.Add,
            intLit(1),
            BinaryExpression(BinaryOperator.Multiply, intLit(2), intLit(3))
        )
        assertEquals(expected, expression)
    }

    @Test
    fun notBindsTighterThanAnd() {
        val expression = whereOf("SELECT 1 FROM t WHERE NOT a AND b")
        val expected = BinaryExpression(
            BinaryOperator.And,
            UnaryExpression(UnaryOperator.Not, ColumnExpression("a")),
            ColumnExpression("b")
        )
        assertEquals(expected, expression)
    }

    @Test
    fun andBindsTighterThanOr() {
        val expression = whereOf("SELECT 1 FROM t WHERE a OR b AND c")
        val expected = BinaryExpression(
            BinaryOperator.Or,
            ColumnExpression("a"),
            BinaryExpression(BinaryOperator.And, ColumnExpression("b"), ColumnExpression("c"))
        )
        assertEquals(expected, expression)
    }

    @Test
    fun parenthesesOverridePrecedence() {
        val expression = firstProjection("SELECT (1 + 2) * 3 FROM t")
        val expected = BinaryExpression(
            BinaryOperator.Multiply,
            BinaryExpression(BinaryOperator.Add, intLit(1), intLit(2)),
            intLit(3)
        )
        assertEquals(expected, expression)
    }

    @Test
    fun reportsMissingProjectionWithPosition() {
        val exception = assertFailsWith<ParseException> { parse("SELECT FROM t") }
        assertEquals(1, exception.line)
        assertEquals(8, exception.column)
    }

    @Test
    fun reportsMissingClosingParen() {
        assertFailsWith<ParseException> { parse("CREATE TABLE t (a INTEGER") }
    }

    @Test
    fun reportsUnknownStatement() {
        assertFailsWith<ParseException> { parse("EXPLAIN users") }
    }
}
