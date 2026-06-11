package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.ExplainResult
import kestrel.engine.Executor
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JoinTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun explain(sql: String): List<String> = (run(sql).single() as ExplainResult).plan

    private fun text(value: String) = Value.TextValue(value)
    private fun int(value: Long) = Value.IntegerValue(value)

    private fun seed() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("INSERT INTO users VALUES (1, 'ada'), (2, 'linus'), (3, 'grace')")
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, userId INTEGER, total INTEGER)")
        run("INSERT INTO orders VALUES (10, 1, 100), (11, 1, 40), (12, 2, 70)")
    }

    @Test
    fun innerJoinMatchesOnCondition() {
        seed()
        val result = select(
            "SELECT u.name, o.total FROM users u INNER JOIN orders o ON u.id = o.userId ORDER BY o.total ASC"
        )
        assertEquals(listOf("name", "total"), result.columns)
        assertEquals(
            listOf(listOf(text("ada"), int(40)), listOf(text("linus"), int(70)), listOf(text("ada"), int(100))),
            result.rows
        )
    }

    @Test
    fun leftJoinKeepsUnmatchedRowsWithNulls() {
        seed()
        val result = select(
            "SELECT u.name, o.total FROM users u LEFT JOIN orders o ON u.id = o.userId ORDER BY o.total ASC"
        )
        assertEquals(listOf(text("grace"), Value.NullValue), result.rows[0])
        assertEquals(listOf(text("ada"), int(40)), result.rows[1])
        assertEquals(listOf(text("linus"), int(70)), result.rows[2])
        assertEquals(listOf(text("ada"), int(100)), result.rows[3])
    }

    @Test
    fun hashJoinAndNestedLoopProduceSameResult() {
        seed()
        val hashed = select(
            "SELECT u.name, o.total FROM users u JOIN orders o ON u.id = o.userId ORDER BY o.total ASC"
        ).rows
        val nested = select(
            "SELECT u.name, o.total FROM users u JOIN orders o ON u.id >= o.userId AND u.id <= o.userId ORDER BY o.total ASC"
        ).rows
        assertEquals(hashed, nested)
    }

    @Test
    fun plannerUsesHashJoinForEquality() {
        seed()
        val plan = explain("EXPLAIN SELECT u.name FROM users u JOIN orders o ON u.id = o.userId")
        assertTrue(plan.any { it.contains("Hash Join") }, "got $plan")
    }

    @Test
    fun plannerUsesNestedLoopForInequality() {
        seed()
        val plan = explain("EXPLAIN SELECT u.name FROM users u JOIN orders o ON u.id < o.userId")
        assertTrue(plan.any { it.contains("Nested Loop") }, "got $plan")
    }

    @Test
    fun threeTableJoinResolvesColumns() {
        seed()
        run("CREATE TABLE items (orderId INTEGER, label TEXT)")
        run("INSERT INTO items VALUES (10, 'book'), (11, 'pen'), (12, 'lamp')")
        val result = select(
            "SELECT u.name, i.label FROM users u " +
                "JOIN orders o ON u.id = o.userId " +
                "JOIN items i ON o.id = i.orderId ORDER BY i.label ASC"
        )
        assertEquals(
            listOf(listOf(text("ada"), text("book")), listOf(text("linus"), text("lamp")), listOf(text("ada"), text("pen"))),
            result.rows
        )
    }

    @Test
    fun unqualifiedColumnResolvesAcrossJoin() {
        seed()
        val result = select("SELECT name, total FROM users u JOIN orders o ON u.id = o.userId WHERE total > 50 ORDER BY total ASC")
        assertEquals(listOf(listOf(text("linus"), int(70)), listOf(text("ada"), int(100))), result.rows)
    }

    @Test
    fun ambiguousColumnIsRejected() {
        seed()
        assertFailsWith<KestrelException> {
            select("SELECT id FROM users u JOIN orders o ON u.id = o.userId")
        }
    }
}
