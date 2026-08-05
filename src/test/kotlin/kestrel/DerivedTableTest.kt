package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DerivedTableTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private fun seedUsers() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER, dept TEXT)")
        run(
            "INSERT INTO users VALUES " +
                "(1, 'ada', 36, 'eng'), (2, 'linus', 54, 'eng'), (3, 'grace', 85, 'navy'), (4, 'alan', 41, NULL)"
        )
    }

    @Test
    fun selectsFromASimpleDerivedTable() {
        seedUsers()
        val result = select("SELECT s.name FROM (SELECT id, name FROM users WHERE age >= 40) AS s ORDER BY s.id ASC")
        assertEquals(listOf(text("linus"), text("grace"), text("alan")), result.rows.map { it.single() })
    }

    @Test
    fun derivedTableOverAnAggregateQueryWorks() {
        seedUsers()
        val result = select(
            "SELECT d.dept, d.n FROM (SELECT dept, COUNT(*) AS n FROM users GROUP BY dept) AS d " +
                "WHERE d.n > 1 ORDER BY d.dept ASC"
        )
        assertEquals(listOf(listOf(text("eng"), int(2))), result.rows)
    }

    @Test
    fun derivedTableCanBeJoinedWithARealTable() {
        seedUsers()
        run("CREATE TABLE bonuses (dept TEXT, amount INTEGER)")
        run("INSERT INTO bonuses VALUES ('eng', 1000), ('navy', 500)")
        val result = select(
            "SELECT d.dept, b.amount FROM (SELECT dept, COUNT(*) AS n FROM users GROUP BY dept) AS d " +
                "JOIN bonuses AS b ON d.dept = b.dept ORDER BY d.dept ASC"
        )
        assertEquals(listOf(listOf(text("eng"), int(1000)), listOf(text("navy"), int(500))), result.rows)
    }

    @Test
    fun twoDerivedTablesCanBeJoinedTogether() {
        seedUsers()
        val result = select(
            "SELECT a.dept, a.n FROM (SELECT dept, COUNT(*) AS n FROM users GROUP BY dept) AS a " +
                "JOIN (SELECT dept, COUNT(*) AS n FROM users GROUP BY dept) AS b ON a.dept = b.dept " +
                "WHERE a.n = b.n ORDER BY a.dept ASC"
        )
        assertEquals(listOf(listOf(text("eng"), int(2)), listOf(text("navy"), int(1))), result.rows)
    }

    @Test
    fun derivedTableColumnsAreScopedToItsAliasNotTheUnderlyingTableName() {
        seedUsers()
        assertFailsWith<KestrelException> { run("SELECT users.name FROM (SELECT id, name FROM users) AS s") }
    }

    @Test
    fun derivedTableIsReEvaluatedEachQueryReflectingLiveData() {
        seedUsers()
        val before = select("SELECT COUNT(*) AS n FROM (SELECT id FROM users WHERE dept = 'eng') AS e")
        assertEquals(listOf(listOf(int(2))), before.rows)
        run("INSERT INTO users VALUES (5, 'margaret', 61, 'eng')")
        val after = select("SELECT COUNT(*) AS n FROM (SELECT id FROM users WHERE dept = 'eng') AS e")
        assertEquals(listOf(listOf(int(3))), after.rows)
    }

    @Test
    fun derivedTableCanContainACorrelatedSubqueryOfItsOwn() {
        seedUsers()
        val result = select(
            "SELECT s.name FROM (SELECT id, name, dept FROM users u WHERE age > " +
                "(SELECT AVG(age) FROM users u2 WHERE u2.dept = u.dept)) AS s ORDER BY s.id ASC"
        )
        assertEquals(listOf(text("linus")), result.rows.map { it.single() })
    }

    @Test
    fun explainDescribesADerivedTableScan() {
        seedUsers()
        val explain = run("EXPLAIN SELECT s.name FROM (SELECT id, name FROM users) AS s").single() as kestrel.engine.ExplainResult
        assertEquals(true, explain.plan.any { it.contains("Seq Scan on s") })
    }

    @Test
    fun creatingAViewFromADerivedTableWorks() {
        seedUsers()
        run("CREATE VIEW dept_counts AS SELECT d.dept, d.n FROM (SELECT dept, COUNT(*) AS n FROM users GROUP BY dept) AS d")
        val result = select("SELECT dept, n FROM dept_counts WHERE n > 1")
        assertEquals(listOf(listOf(text("eng"), int(2))), result.rows)
    }
}
