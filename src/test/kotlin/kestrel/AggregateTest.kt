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
    fun offsetSkipsGroupsAfterOrderingAggregatedResults() {
        seed()
        val result = select("SELECT dept, SUM(amount) AS total FROM sales GROUP BY dept ORDER BY dept ASC LIMIT 1 OFFSET 1")
        assertEquals(listOf(listOf(text("ops"), int(120))), result.rows)
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
    fun orderByMultipleColumnsOnGroupedResults() {
        seed()
        val result = select(
            "SELECT dept, region, SUM(amount) AS total FROM sales GROUP BY dept, region ORDER BY dept ASC, region DESC"
        )
        assertEquals(
            listOf(
                listOf(text("eng"), text("us"), int(200)),
                listOf(text("eng"), text("eu"), int(100)),
                listOf(text("ops"), text("eu"), int(120))
            ),
            result.rows
        )
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

    @Test
    fun stringAggJoinsWithTheGivenSeparatorInRowOrderWhenThereIsNoOrderByClause() {
        run("CREATE TABLE t (label TEXT)")
        run("INSERT INTO t VALUES ('c'), ('a'), ('b')")
        assertEquals(listOf(listOf(text("c, a, b"))), select("SELECT STRING_AGG(label, ', ') FROM t").rows)
    }

    @Test
    fun stringAggOrderByInsideTheCallSortsIndependentlyOfInsertionOrder() {
        run("CREATE TABLE t (label TEXT, rank INTEGER)")
        run("INSERT INTO t VALUES ('c', 3), ('a', 1), ('b', 2)")
        val result = select("SELECT STRING_AGG(label, '-' ORDER BY rank) FROM t")
        assertEquals(listOf(listOf(text("a-b-c"))), result.rows)
    }

    @Test
    fun stringAggOrderByDescendingReversesTheConcatenationOrder() {
        run("CREATE TABLE t (label TEXT, rank INTEGER)")
        run("INSERT INTO t VALUES ('a', 1), ('b', 2), ('c', 3)")
        val result = select("SELECT STRING_AGG(label, '-' ORDER BY rank DESC) FROM t")
        assertEquals(listOf(listOf(text("c-b-a"))), result.rows)
    }

    @Test
    fun stringAggSkipsNullValuesButKeepsNonNullOnesInOrder() {
        run("CREATE TABLE t (label TEXT)")
        run("INSERT INTO t VALUES ('a'), (NULL), ('b')")
        assertEquals(listOf(listOf(text("a, b"))), select("SELECT STRING_AGG(label, ', ') FROM t").rows)
    }

    @Test
    fun stringAggOverOnlyNullValuesIsNull() {
        run("CREATE TABLE t (label TEXT)")
        run("INSERT INTO t VALUES (NULL), (NULL)")
        assertEquals(listOf(listOf(Value.NullValue)), select("SELECT STRING_AGG(label, ', ') FROM t").rows)
    }

    @Test
    fun stringAggOverAnEmptyTableIsNull() {
        run("CREATE TABLE t (label TEXT)")
        assertEquals(listOf(listOf(Value.NullValue)), select("SELECT STRING_AGG(label, ', ') FROM t").rows)
    }

    @Test
    fun stringAggPerGroupWithGroupBy() {
        seed()
        val result = select("SELECT dept, STRING_AGG(region, '/' ORDER BY region) FROM sales GROUP BY dept ORDER BY dept ASC")
        assertEquals(listOf(text("eng"), text("eu/us/us")), result.rows[0])
        assertEquals(listOf(text("ops"), text("eu/eu")), result.rows[1])
    }

    @Test
    fun stringAggRejectsANonTextValueArgument() {
        seed()
        val failure = assertFailsWith<KestrelException> { select("SELECT STRING_AGG(amount, ', ') FROM sales") }
        assertEquals("function STRING_AGG requires a TEXT argument but got INTEGER", failure.message)
    }

    @Test
    fun stringAggRejectsANonTextSeparator() {
        run("CREATE TABLE t (label TEXT)")
        run("INSERT INTO t VALUES ('a')")
        assertFailsWith<KestrelException> { select("SELECT STRING_AGG(label, 1) FROM t") }
    }

    @Test
    fun stringAggCannotBeUsedAsAWindowFunction() {
        assertFailsWith<ParseException> {
            Parser(Lexer("SELECT STRING_AGG(label, ', ') OVER () FROM t").tokenize()).parseProgram()
        }
    }

    @Test
    fun stringAggRequiresBothAValueAndASeparatorArgument() {
        assertFailsWith<ParseException> {
            Parser(Lexer("SELECT STRING_AGG(label) FROM t").tokenize()).parseProgram()
        }
    }

    private fun seedDistinct() {
        run("CREATE TABLE t (dept TEXT, amount INTEGER)")
        run("INSERT INTO t VALUES ('eng', 100), ('eng', 100), ('eng', 200), ('ops', 100), ('ops', NULL)")
    }

    @Test
    fun countDistinctCountsEachDistinctNonNullValueOnce() {
        seedDistinct()
        assertEquals(listOf(listOf(int(4))), select("SELECT COUNT(amount) FROM t").rows)
        assertEquals(listOf(listOf(int(2))), select("SELECT COUNT(DISTINCT amount) FROM t").rows)
    }

    @Test
    fun sumDistinctAddsEachDistinctValueOnce() {
        seedDistinct()
        assertEquals(listOf(listOf(int(500))), select("SELECT SUM(amount) FROM t").rows)
        assertEquals(listOf(listOf(int(300))), select("SELECT SUM(DISTINCT amount) FROM t").rows)
    }

    @Test
    fun avgDistinctAveragesEachDistinctValueOnce() {
        seedDistinct()
        assertEquals(listOf(listOf(real(125.0))), select("SELECT AVG(amount) FROM t").rows)
        assertEquals(listOf(listOf(real(150.0))), select("SELECT AVG(DISTINCT amount) FROM t").rows)
    }

    @Test
    fun minAndMaxDistinctAreUnaffectedByDeduplication() {
        seedDistinct()
        val result = select("SELECT MIN(DISTINCT amount) AS lo, MAX(DISTINCT amount) AS hi FROM t")
        assertEquals(listOf(int(100), int(200)), result.rows[0])
    }

    @Test
    fun countDistinctIgnoresNulls() {
        seedDistinct()
        assertEquals(listOf(listOf(int(0))), select("SELECT COUNT(DISTINCT amount) FROM t WHERE dept = 'nope'").rows)
    }

    @Test
    fun countDistinctPerGroupWithGroupBy() {
        seedDistinct()
        val result = select("SELECT dept, COUNT(DISTINCT amount) FROM t GROUP BY dept ORDER BY dept ASC")
        assertEquals(listOf(listOf(text("eng"), int(2)), listOf(text("ops"), int(1))), result.rows)
    }

    @Test
    fun countDistinctStarAndDistinctOverAWindowAreRejectedAtParseTime() {
        assertFailsWith<ParseException> { Parser(Lexer("SELECT COUNT(DISTINCT *) FROM t").tokenize()).parseProgram() }
        assertFailsWith<ParseException> {
            Parser(Lexer("SELECT SUM(DISTINCT amount) OVER () FROM t").tokenize()).parseProgram()
        }
    }

    @Test
    fun countStarFilterCountsOnlyRowsMatchingThePredicate() {
        seedDistinct()
        assertEquals(listOf(listOf(int(5))), select("SELECT COUNT(*) FROM t").rows)
        assertEquals(listOf(listOf(int(1))), select("SELECT COUNT(*) FILTER (WHERE amount > 100) FROM t").rows)
    }

    @Test
    fun countColumnFilterCombinesNullSkippingWithThePredicate() {
        seedDistinct()
        assertEquals(listOf(listOf(int(3))), select("SELECT COUNT(amount) FILTER (WHERE dept = 'eng') FROM t").rows)
        assertEquals(listOf(listOf(int(1))), select("SELECT COUNT(amount) FILTER (WHERE dept = 'ops') FROM t").rows)
    }

    @Test
    fun sumFilterSumsOnlyRowsMatchingThePredicate() {
        seedDistinct()
        assertEquals(listOf(listOf(int(400))), select("SELECT SUM(amount) FILTER (WHERE dept = 'eng') FROM t").rows)
        assertEquals(listOf(listOf(int(100))), select("SELECT SUM(amount) FILTER (WHERE dept = 'ops') FROM t").rows)
    }

    @Test
    fun avgMinMaxFilterOperateOnlyOnRowsMatchingThePredicate() {
        seedDistinct()
        val result = select(
            "SELECT AVG(amount) FILTER (WHERE amount >= 150) AS a, " +
                "MIN(amount) FILTER (WHERE dept = 'eng') AS lo, " +
                "MAX(amount) FILTER (WHERE dept = 'eng') AS hi FROM t"
        )
        assertEquals(listOf(real(200.0), int(100), int(200)), result.rows[0])
    }

    @Test
    fun filterExcludingEveryRowLeavesCountZeroAndSumAvgMinMaxNull() {
        seedDistinct()
        val result = select(
            "SELECT COUNT(*) FILTER (WHERE dept = 'nope') AS c, " +
                "SUM(amount) FILTER (WHERE dept = 'nope') AS s, " +
                "AVG(amount) FILTER (WHERE dept = 'nope') AS a FROM t"
        )
        assertEquals(listOf(int(0), Value.NullValue, Value.NullValue), result.rows[0])
    }

    @Test
    fun filterCombinesWithDistinct() {
        seedDistinct()
        assertEquals(
            listOf(listOf(int(2))),
            select("SELECT COUNT(DISTINCT amount) FILTER (WHERE dept = 'eng') FROM t").rows
        )
    }

    @Test
    fun filterAppliesIndependentlyPerAggregatePerGroup() {
        seedDistinct()
        val result = select(
            "SELECT dept, COUNT(*) FILTER (WHERE amount > 50) AS matching, COUNT(*) AS total " +
                "FROM t GROUP BY dept ORDER BY dept ASC"
        )
        assertEquals(
            listOf(listOf(text("eng"), int(3), int(3)), listOf(text("ops"), int(1), int(2))),
            result.rows
        )
    }

    @Test
    fun filterIsRejectedOnNonAggregateFunctionsAndOnWindowFunctions() {
        assertFailsWith<ParseException> {
            Parser(Lexer("SELECT UPPER(dept) FILTER (WHERE amount > 0) FROM t").tokenize()).parseProgram()
        }
        assertFailsWith<ParseException> {
            Parser(Lexer("SELECT SUM(amount) FILTER (WHERE amount > 0) OVER () FROM t").tokenize()).parseProgram()
        }
    }
}
