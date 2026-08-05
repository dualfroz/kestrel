package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.ExplainResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DistinctTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private fun seed() {
        run("CREATE TABLE sales (dept TEXT, region TEXT, amount INTEGER)")
        run(
            "INSERT INTO sales VALUES " +
                "('eng', 'eu', 100), ('eng', 'us', 200), ('eng', 'eu', 100), " +
                "('ops', 'eu', 50), ('ops', 'eu', 70), ('ops', 'eu', 50)"
        )
    }

    @Test
    fun distinctRemovesDuplicateOutputRows() {
        seed()
        val result = select("SELECT DISTINCT dept FROM sales ORDER BY dept")
        assertEquals(listOf(listOf(text("eng")), listOf(text("ops"))), result.rows)
    }

    @Test
    fun distinctConsidersTheWholeProjectedRow() {
        seed()
        val result = select("SELECT DISTINCT dept, region FROM sales ORDER BY dept, region")
        assertEquals(
            listOf(listOf(text("eng"), text("eu")), listOf(text("eng"), text("us")), listOf(text("ops"), text("eu"))),
            result.rows
        )
    }

    @Test
    fun selectAllIsTheDefaultAndKeepsDuplicates() {
        seed()
        assertEquals(6, select("SELECT ALL dept FROM sales").rows.size)
        assertEquals(6, select("SELECT dept FROM sales").rows.size)
    }

    @Test
    fun distinctStarDedupsOnEveryColumn() {
        seed()
        val result = select("SELECT DISTINCT * FROM sales ORDER BY dept, region, amount")
        assertEquals(4, result.rows.size)
    }

    @Test
    fun distinctWithLimitCountsDistinctRowsNotSourceRows() {
        seed()
        val result = select("SELECT DISTINCT dept FROM sales ORDER BY dept LIMIT 1")
        assertEquals(listOf(listOf(text("eng"))), result.rows)
    }

    @Test
    fun distinctWithOffsetSkipsDistinctRowsNotSourceRows() {
        seed()
        val result = select("SELECT DISTINCT dept FROM sales ORDER BY dept LIMIT 1 OFFSET 1")
        assertEquals(listOf(listOf(text("ops"))), result.rows)
    }

    @Test
    fun distinctCombinesWithGroupBy() {
        run("CREATE TABLE t (a INTEGER, b INTEGER)")
        run("INSERT INTO t VALUES (1, 10), (1, 20), (2, 10)")
        // GROUP BY a, b produces three distinct groups; DISTINCT on the projected `a` alone
        // collapses the two groups sharing a=1 down to one output row.
        val result = select("SELECT DISTINCT a FROM t GROUP BY a, b ORDER BY a")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), result.rows)
    }

    @Test
    fun distinctOnKeepsFirstRowPerGroupInOrderByOrder() {
        seed()
        val result = select("SELECT DISTINCT ON (dept) dept, amount FROM sales ORDER BY dept, amount DESC")
        assertEquals(listOf(listOf(text("eng"), int(200)), listOf(text("ops"), int(70))), result.rows)
    }

    @Test
    fun distinctOnWithoutOrderByUsesScanOrder() {
        run("CREATE TABLE t (k TEXT, v INTEGER)")
        run("INSERT INTO t VALUES ('a', 1), ('a', 2), ('b', 3)")
        val result = select("SELECT DISTINCT ON (k) k, v FROM t")
        assertEquals(listOf(listOf(text("a"), int(1)), listOf(text("b"), int(3))), result.rows)
    }

    @Test
    fun distinctOnStarProjectsEveryColumnOfTheKeptRow() {
        seed()
        val result = select("SELECT DISTINCT ON (dept) * FROM sales ORDER BY dept, amount DESC")
        assertEquals(listOf("dept", "region", "amount"), result.columns)
        assertEquals(listOf(text("eng"), text("us"), int(200)), result.rows[0])
        assertEquals(listOf(text("ops"), text("eu"), int(70)), result.rows[1])
    }

    @Test
    fun distinctOnRequiresOrderByToStartWithItsOwnExpressions() {
        seed()
        assertFailsWith<KestrelException> {
            select("SELECT DISTINCT ON (dept) dept, amount FROM sales ORDER BY amount")
        }
    }

    @Test
    fun distinctOnPermitsAnOrderByThatOnlyMatchesItsLeadingExpressions() {
        seed()
        val result = select("SELECT DISTINCT ON (dept) dept, amount FROM sales ORDER BY dept, region, amount DESC")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun distinctOnWithNoOrderByAtAllIsAllowed() {
        seed()
        val result = select("SELECT DISTINCT ON (dept) dept FROM sales")
        assertEquals(2, result.rows.size)
    }

    @Test
    fun distinctOnRejectsGroupBy() {
        seed()
        assertFailsWith<KestrelException> {
            select("SELECT DISTINCT ON (dept) dept, SUM(amount) FROM sales GROUP BY dept")
        }
    }

    @Test
    fun distinctOnRejectsAnUnknownColumn() {
        seed()
        assertFailsWith<KestrelException> {
            select("SELECT DISTINCT ON (nope) dept FROM sales")
        }
    }

    @Test
    fun distinctOnRejectsAWindowFunction() {
        seed()
        assertFailsWith<KestrelException> {
            select("SELECT DISTINCT ON (ROW_NUMBER() OVER (ORDER BY amount)) dept FROM sales")
        }
    }

    @Test
    fun explainShowsUniqueForDistinct() {
        seed()
        val plan = (run("EXPLAIN SELECT DISTINCT dept FROM sales").single() as ExplainResult).plan
        assertEquals("Unique", plan.first())
    }

    @Test
    fun explainShowsUniqueDistinctOnForDistinctOn() {
        seed()
        val plan = (run("EXPLAIN SELECT DISTINCT ON (dept) dept FROM sales ORDER BY dept").single() as ExplainResult).plan
        assertEquals("Unique (DISTINCT ON)", plan.first())
    }

    @Test
    fun explainShowsLimitOutermostAndOffsetJustInside() {
        seed()
        val plan = (run("EXPLAIN SELECT dept FROM sales ORDER BY dept LIMIT 5 OFFSET 2").single() as ExplainResult).plan
        assertEquals("Limit 5", plan[0])
        assertEquals("  Offset 2", plan[1])
    }
}
