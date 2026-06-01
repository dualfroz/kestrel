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

class AggregateTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun real(value: Double) = Value.RealValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private fun seed() {
        run("CREATE TABLE sales (dept TEXT, region TEXT, amount INTEGER)")
        run(
            "INSERT INTO sales VALUES " +
                "('eng', 'eu', 100), ('eng', 'us', 200), ('eng', 'us', NULL), " +
                "('ops', 'eu', 50), ('ops', 'eu', 70)"
        )
    }

    @Test
    fun countStarCountsAllRows() {
        seed()
        assertEquals(listOf(listOf(int(5))), select("SELECT COUNT(*) FROM sales").rows)
    }

    @Test
    fun countColumnIgnoresNulls() {
        seed()
        assertEquals(listOf(listOf(int(4))), select("SELECT COUNT(amount) FROM sales").rows)
    }

    @Test
    fun sumAndAvgIgnoreNulls() {
        seed()
        val result = select("SELECT SUM(amount) AS total, AVG(amount) AS mean FROM sales")
        assertEquals(listOf("total", "mean"), result.columns)
        assertEquals(listOf(int(420), real(105.0)), result.rows[0])
    }

    @Test
    fun minAndMaxIgnoreNulls() {
        seed()
        val result = select("SELECT MIN(amount) AS lo, MAX(amount) AS hi FROM sales")
        assertEquals(listOf(int(50), int(200)), result.rows[0])
    }

    @Test
    fun groupBySingleColumn() {
        seed()
        val result = select("SELECT dept, SUM(amount) AS total FROM sales GROUP BY dept ORDER BY dept ASC")
        assertEquals(listOf(text("eng"), int(300)), result.rows[0])
        assertEquals(listOf(text("ops"), int(120)), result.rows[1])
    }

    @Test
    fun groupByMultipleColumns() {
        seed()
        val result = select(
            "SELECT dept, region, COUNT(*) AS n FROM sales GROUP BY dept, region ORDER BY dept ASC"
        )
        assertEquals(listOf(text("eng"), text("eu"), int(1)), result.rows[0])
        assertEquals(listOf(text("eng"), text("us"), int(2)), result.rows[1])
        assertEquals(listOf(text("ops"), text("eu"), int(2)), result.rows[2])
    }

    @Test
    fun havingFiltersGroups() {
        seed()
        val result = select("SELECT dept, SUM(amount) AS total FROM sales GROUP BY dept HAVING SUM(amount) > 200")
        assertEquals(listOf(listOf(text("eng"), int(300))), result.rows)
    }

    @Test
    fun sumOfAllNullsIsNull() {
        run("CREATE TABLE t (x INTEGER)")
        run("INSERT INTO t VALUES (NULL), (NULL)")
        assertEquals(listOf(listOf(Value.NullValue)), select("SELECT SUM(x) FROM t").rows)
    }

    @Test
    fun countStarOnEmptyTableIsZero() {
        run("CREATE TABLE t (x INTEGER)")
        assertEquals(listOf(listOf(int(0))), select("SELECT COUNT(*) FROM t").rows)
    }

    @Test
    fun bareColumnOutsideGroupByIsRejected() {
        seed()
        assertFailsWith<KestrelException> { select("SELECT region, SUM(amount) FROM sales GROUP BY dept") }
    }

    @Test
    fun orderByProjectionAlias() {
        seed()
        val result = select("SELECT dept, SUM(amount) AS total FROM sales GROUP BY dept ORDER BY total DESC")
        assertEquals(listOf(text("eng"), int(300)), result.rows[0])
        assertEquals(listOf(text("ops"), int(120)), result.rows[1])
    }

    @Test
    fun aggregateExpressionArithmetic() {
        seed()
        val result = select("SELECT dept, SUM(amount) + COUNT(*) AS blended FROM sales GROUP BY dept ORDER BY dept ASC")
        assertEquals(listOf(text("eng"), int(303)), result.rows[0])
        assertEquals(listOf(text("ops"), int(122)), result.rows[1])
    }
}
