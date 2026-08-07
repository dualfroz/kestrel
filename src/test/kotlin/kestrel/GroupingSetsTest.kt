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
import kotlin.test.assertTrue

/**
 * `GROUP BY ROLLUP (...)` / `CUBE (...)` / `GROUPING SETS (...)` and the `GROUPING(...)` function -
 * a single query reporting subtotals (and a grand total) at several levels at once instead of a
 * separate `UNION ALL` of differently-grouped queries. See [kestrel.sql.ast.GroupingSpec] and
 * [kestrel.engine.GroupingSetExpansion] for how the sets are computed and
 * [kestrel.engine.GroupAggregation] for how a collapsed column reads as NULL outside an aggregate.
 */
class GroupingSetsTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)
    private val NULL = Value.NullValue

    private fun seed() {
        run("CREATE TABLE sales (dept TEXT, region TEXT, amount INTEGER)")
        run(
            "INSERT INTO sales VALUES " +
                "('eng', 'eu', 100), ('eng', 'us', 200), ('ops', 'eu', 50), ('ops', 'eu', 70)"
        )
    }

    @Test
    fun rollupProducesDetailSubtotalsAndAGrandTotal() {
        seed()
        val result = select(
            "SELECT dept, region, SUM(amount) AS total, GROUPING(dept) AS gd, GROUPING(region) AS gr " +
                "FROM sales GROUP BY ROLLUP (dept, region) " +
                "ORDER BY GROUPING(dept), dept, GROUPING(region), region"
        )
        assertEquals(listOf("dept", "region", "total", "gd", "gr"), result.columns)
        assertEquals(
            listOf(
                listOf(text("eng"), text("eu"), int(100), int(0), int(0)),
                listOf(text("eng"), text("us"), int(200), int(0), int(0)),
                listOf(text("eng"), NULL, int(300), int(0), int(1)),
                listOf(text("ops"), text("eu"), int(120), int(0), int(0)),
                listOf(text("ops"), NULL, int(120), int(0), int(1)),
                listOf(NULL, NULL, int(420), int(1), int(1))
            ),
            result.rows
        )
    }

    @Test
    fun cubeProducesEverySubsetOfGroupingColumns() {
        seed()
        val result = select(
            "SELECT dept, region, SUM(amount) AS total " +
                "FROM sales GROUP BY CUBE (dept, region) " +
                "ORDER BY GROUPING(dept), GROUPING(region), dept, region"
        )
        assertEquals(
            listOf(
                listOf(text("eng"), text("eu"), int(100)),
                listOf(text("eng"), text("us"), int(200)),
                listOf(text("ops"), text("eu"), int(120)),
                listOf(text("eng"), NULL, int(300)),
                listOf(text("ops"), NULL, int(120)),
                listOf(NULL, text("eu"), int(220)),
                listOf(NULL, text("us"), int(200)),
                listOf(NULL, NULL, int(420))
            ),
            result.rows
        )
    }

    @Test
    fun explicitGroupingSetsSkipsTheFullDetailSetWhenNotListed() {
        seed()
        val result = select(
            "SELECT dept, region, SUM(amount) AS total " +
                "FROM sales GROUP BY GROUPING SETS ((dept), (region), ()) " +
                "ORDER BY GROUPING(dept), GROUPING(region), dept, region"
        )
        assertEquals(
            listOf(
                listOf(text("eng"), NULL, int(300)),
                listOf(text("ops"), NULL, int(120)),
                listOf(NULL, text("eu"), int(220)),
                listOf(NULL, text("us"), int(200)),
                listOf(NULL, NULL, int(420))
            ),
            result.rows
        )
    }

    @Test
    fun havingFiltersAcrossEverySetAtOnce() {
        seed()
        val result = select(
            "SELECT dept, SUM(amount) AS total FROM sales GROUP BY ROLLUP (dept) " +
                "HAVING SUM(amount) > 150 ORDER BY GROUPING(dept), dept"
        )
        assertEquals(listOf(listOf(text("eng"), int(300)), listOf(NULL, int(420))), result.rows)
    }

    @Test
    fun plainGroupByAlwaysReportsGroupingAsZero() {
        seed()
        val result = select("SELECT dept, GROUPING(dept) FROM sales GROUP BY dept ORDER BY dept")
        assertEquals(listOf(listOf(text("eng"), int(0)), listOf(text("ops"), int(0))), result.rows)
    }

    @Test
    fun aCollapsedColumnInsideCaseEvaluatesAsNull() {
        seed()
        val result = select(
            "SELECT CASE WHEN dept IS NULL THEN 'ALL DEPARTMENTS' ELSE dept END AS label, SUM(amount) AS total " +
                "FROM sales GROUP BY ROLLUP (dept) ORDER BY GROUPING(dept), dept"
        )
        assertEquals(
            listOf(
                listOf(text("eng"), int(300)),
                listOf(text("ops"), int(120)),
                listOf(text("ALL DEPARTMENTS"), int(420))
            ),
            result.rows
        )
    }

    @Test
    fun aggregatesInsideTheSameQuerySeeRealPerRowValuesRegardlessOfCollapsedColumns() {
        seed()
        // COUNT(*) on the grand-total row must count every row (4), not be tripped up by dept/region
        // reading as NULL there - the nulling only ever applies outside an aggregate's own argument.
        val result = select(
            "SELECT dept, COUNT(*) AS n FROM sales GROUP BY ROLLUP (dept) ORDER BY GROUPING(dept), dept"
        )
        assertEquals(
            listOf(listOf(text("eng"), int(2)), listOf(text("ops"), int(2)), listOf(NULL, int(4))),
            result.rows
        )
    }

    @Test
    fun rollupOverAnEmptyTableStillProducesOneGrandTotalRow() {
        run("CREATE TABLE empty_sales (dept TEXT, amount INTEGER)")
        val result = select("SELECT dept, COUNT(*), SUM(amount) FROM empty_sales GROUP BY ROLLUP (dept)")
        assertEquals(listOf(listOf(NULL, int(0), NULL)), result.rows)
    }

    @Test
    fun groupingFunctionOutsideAGroupedQueryIsRejected() {
        seed()
        val failure = assertFailsWith<KestrelException> { select("SELECT GROUPING(dept) FROM sales") }
        assertTrue(failure.message!!.contains("GROUP BY"), failure.message)
    }

    @Test
    fun groupingFunctionArgumentMustItselfBeAGroupingColumn() {
        seed()
        val failure = assertFailsWith<KestrelException> {
            select("SELECT GROUPING(amount) FROM sales GROUP BY ROLLUP (dept)")
        }
        assertTrue(failure.message!!.contains("must appear in GROUP BY"), failure.message)
    }

    @Test
    fun groupingFunctionRequiresAtLeastOneArgument() {
        seed()
        val failure = assertFailsWith<KestrelException> {
            select("SELECT GROUPING() FROM sales GROUP BY ROLLUP (dept)")
        }
        assertTrue(failure.message!!.contains("at least one argument"), failure.message)
    }

    @Test
    fun cubeRejectsTooManyColumns() {
        val columns = (1..17).joinToString(", ") { "c$it" }
        run("CREATE TABLE wide (${(1..17).joinToString(", ") { "c$it INTEGER" }})")
        val failure = assertFailsWith<KestrelException> {
            select("SELECT COUNT(*) FROM wide GROUP BY CUBE ($columns)")
        }
        assertTrue(failure.message!!.contains("CUBE supports at most"), failure.message)
    }

    @Test
    fun explainDescribesTheNumberOfGroupingSets() {
        seed()
        val plan = (run("EXPLAIN SELECT dept, region, SUM(amount) FROM sales GROUP BY CUBE (dept, region)").single()
            as kestrel.engine.ExplainResult).plan
        assertTrue(plan.any { it.contains("GroupAggregate") && it.contains("4 grouping sets") }, plan.toString())
    }

    @Test
    fun explainOnAPlainGroupByDoesNotMentionGroupingSets() {
        seed()
        val plan = (run("EXPLAIN SELECT dept, SUM(amount) FROM sales GROUP BY dept").single()
            as kestrel.engine.ExplainResult).plan
        assertTrue(plan.any { it == "GroupAggregate keys: dept" }, plan.toString())
    }
}
