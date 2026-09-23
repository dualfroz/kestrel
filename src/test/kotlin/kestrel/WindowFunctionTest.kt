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

class WindowFunctionTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun explain(sql: String): List<String> = (run(sql).single() as ExplainResult).plan

    private fun text(value: String) = Value.TextValue(value)
    private fun int(value: Long) = Value.IntegerValue(value)

    private fun seed() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, deptId INTEGER, name TEXT, salary INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng'), (2, 'sales')")
        run(
            "INSERT INTO employees VALUES " +
                "(1, 1, 'ada', 400), (2, 1, 'linus', 400), (3, 1, 'grace', 300), " +
                "(4, 2, 'sam', 200), (5, 2, 'kay', 200), (6, 2, 'meg', 100)"
        )
    }

    @Test
    fun rowNumberAssignsSequentialNumbersWithinEachPartition() {
        seed()
        val result = select(
            "SELECT id, ROW_NUMBER() OVER (PARTITION BY deptId ORDER BY salary DESC) AS rn " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(1)), listOf(int(2), int(2)), listOf(int(3), int(3)),
                listOf(int(4), int(1)), listOf(int(5), int(2)), listOf(int(6), int(3))
            ),
            result.rows
        )
    }

    @Test
    fun rowNumberWithoutOverOrderByFallsBackToPartitionArrivalOrder() {
        seed()
        val result = select("SELECT id, ROW_NUMBER() OVER (PARTITION BY deptId) AS rn FROM employees ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(int(1), int(1)), listOf(int(2), int(2)), listOf(int(3), int(3)),
                listOf(int(4), int(1)), listOf(int(5), int(2)), listOf(int(6), int(3))
            ),
            result.rows
        )
    }

    @Test
    fun rankLeavesGapsAfterTiesButDenseRankDoesNot() {
        seed()
        val result = select(
            "SELECT id, RANK() OVER (PARTITION BY deptId ORDER BY salary DESC) AS r, " +
                "DENSE_RANK() OVER (PARTITION BY deptId ORDER BY salary DESC) AS dr " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(1), int(1)),
                listOf(int(2), int(1), int(1)),
                listOf(int(3), int(3), int(2)),
                listOf(int(4), int(1), int(1)),
                listOf(int(5), int(1), int(1)),
                listOf(int(6), int(3), int(2))
            ),
            result.rows
        )
    }

    @Test
    fun rankWithoutPartitionOrdersTheWholeResultSet() {
        seed()
        val result = select("SELECT id, RANK() OVER (ORDER BY salary DESC) AS r FROM employees ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(int(1), int(1)), listOf(int(2), int(1)), listOf(int(3), int(3)),
                listOf(int(4), int(4)), listOf(int(5), int(4)), listOf(int(6), int(6))
            ),
            result.rows
        )
    }

    @Test
    fun aggregateWindowWithoutOrderByRepeatsTheWholePartitionTotalOnEveryRow() {
        seed()
        val result = select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId) AS deptTotal FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(1100)), listOf(int(2), int(1100)), listOf(int(3), int(1100)),
                listOf(int(4), int(500)), listOf(int(5), int(500)), listOf(int(6), int(500))
            ),
            result.rows
        )
    }

    @Test
    fun aggregateWindowWithOrderByRunsCumulativelyAndTiesSharePeerGroupTotal() {
        seed()
        val result = select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY salary DESC) AS running " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(800)), listOf(int(2), int(800)), listOf(int(3), int(1100)),
                listOf(int(4), int(400)), listOf(int(5), int(400)), listOf(int(6), int(500))
            ),
            result.rows
        )
    }

    @Test
    fun aggregateWindowWithUniqueOrderKeyProducesARunningTotal() {
        seed()
        val result = select("SELECT id, SUM(salary) OVER (ORDER BY id) AS running FROM employees ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(int(1), int(400)), listOf(int(2), int(800)), listOf(int(3), int(1100)),
                listOf(int(4), int(1300)), listOf(int(5), int(1500)), listOf(int(6), int(1600))
            ),
            result.rows
        )
    }

    @Test
    fun countStarOverPartitionCountsRowsRegardlessOfNulls() {
        seed()
        run("INSERT INTO employees VALUES (7, 3, 'unpaid', NULL)")
        val result = select(
            "SELECT COUNT(*) OVER (PARTITION BY deptId) AS n, COUNT(salary) OVER (PARTITION BY deptId) AS paid " +
                "FROM employees WHERE deptId = 3"
        )
        assertEquals(listOf(listOf(int(1), int(0))), result.rows)
    }

    @Test
    fun windowFunctionOverAJoinPartitionsByTheJoinedColumn() {
        seed()
        val result = select(
            "SELECT e.name, d.name AS dept, " +
                "ROW_NUMBER() OVER (PARTITION BY d.name ORDER BY e.salary DESC) AS rn " +
                "FROM employees e JOIN departments d ON e.deptId = d.id " +
                "WHERE d.name = 'eng' ORDER BY rn ASC"
        )
        assertEquals(
            listOf(
                listOf(text("ada"), text("eng"), int(1)),
                listOf(text("linus"), text("eng"), int(2)),
                listOf(text("grace"), text("eng"), int(3))
            ),
            result.rows
        )
    }

    @Test
    fun orderByCanReferenceAWindowFunctionByItsProjectionAlias() {
        seed()
        val result = select(
            "SELECT id, RANK() OVER (ORDER BY salary DESC) AS r FROM employees ORDER BY r ASC, id ASC"
        )
        assertEquals((1..6).map { it.toLong() }, result.rows.map { (it[0] as Value.IntegerValue).value })
    }

    @Test
    fun windowFunctionIsRejectedInWhere() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT id FROM employees WHERE ROW_NUMBER() OVER (ORDER BY id) = 1")
        }
        assertTrue(error.message!!.contains("WHERE"), error.message!!)
    }

    @Test
    fun windowFunctionIsRejectedInGroupBy() {
        seed()
        assertFailsWith<KestrelException> {
            run("SELECT COUNT(*) FROM employees GROUP BY ROW_NUMBER() OVER (ORDER BY id)")
        }
    }

    @Test
    fun windowFunctionIsRejectedInHaving() {
        seed()
        assertFailsWith<KestrelException> {
            run("SELECT deptId, COUNT(*) FROM employees GROUP BY deptId HAVING ROW_NUMBER() OVER (ORDER BY deptId) > 1")
        }
    }

    @Test
    fun windowFunctionCannotBeCombinedWithGroupByInTheSameQuery() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT deptId, COUNT(*), ROW_NUMBER() OVER (ORDER BY deptId) FROM employees GROUP BY deptId")
        }
        assertTrue(error.message!!.contains("combined"), error.message!!)
    }

    @Test
    fun rankRequiresAnOrderByInsideOver() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT RANK() OVER (PARTITION BY deptId) FROM employees")
        }
        assertTrue(error.message!!.contains("ORDER BY"), error.message!!)
    }

    @Test
    fun denseRankRequiresAnOrderByInsideOver() {
        seed()
        assertFailsWith<KestrelException> {
            run("SELECT DENSE_RANK() OVER (PARTITION BY deptId) FROM employees")
        }
    }

    @Test
    fun nestedWindowFunctionsAreRejected() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT SUM(salary) OVER (ORDER BY ROW_NUMBER() OVER (ORDER BY id)) FROM employees")
        }
        assertTrue(error.message!!.contains("nested"), error.message!!)
    }

    @Test
    fun explainDoesNotFailOnAQueryWithAWindowFunction() {
        seed()
        val plan = explain("EXPLAIN SELECT id, ROW_NUMBER() OVER (PARTITION BY deptId ORDER BY salary DESC) FROM employees")
        assertTrue(plan.isNotEmpty())
    }

    @Test
    fun selectStarWithAWindowFunctionOnlyInOrderByDoesNotLeakTheExtraColumn() {
        seed()
        val result = select("SELECT * FROM departments ORDER BY ROW_NUMBER() OVER (ORDER BY id) DESC")
        assertEquals(listOf("id", "name"), result.columns)
        assertEquals(listOf(listOf(int(2), text("sales")), listOf(int(1), text("eng"))), result.rows)
    }

    @Test
    fun lagReturnsThePreviousRowsValueWithinEachPartitionAndNullAtTheEdge() {
        seed()
        val result = select(
            "SELECT id, LAG(salary) OVER (PARTITION BY deptId ORDER BY salary DESC) AS prev " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(null, 400L, 400L, null, 200L, 200L),
            result.rows.map { (it[1] as? Value.IntegerValue)?.value }
        )
    }

    @Test
    fun leadReturnsTheNextRowsValueWithinEachPartitionAndNullAtTheEdge() {
        seed()
        val result = select(
            "SELECT id, LEAD(salary) OVER (PARTITION BY deptId ORDER BY salary DESC) AS next " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(400L, 300L, null, 200L, 100L, null),
            result.rows.map { (it[1] as? Value.IntegerValue)?.value }
        )
    }

    @Test
    fun lagWithAnExplicitOffsetSkipsMultipleRows() {
        seed()
        val result = select(
            "SELECT id, LAG(salary, 2) OVER (PARTITION BY deptId ORDER BY salary DESC) AS prev2 " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(null, null, 400L, null, null, 200L),
            result.rows.map { (it[1] as? Value.IntegerValue)?.value }
        )
    }

    @Test
    fun lagPastThePartitionEdgeFallsBackToTheDefaultExpressionInsteadOfNull() {
        seed()
        val result = select(
            "SELECT id, LAG(salary, 1, 0) OVER (PARTITION BY deptId ORDER BY salary DESC) AS prev " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(0L, 400L, 400L, 0L, 200L, 200L),
            result.rows.map { (it[1] as Value.IntegerValue).value }
        )
    }

    @Test
    fun lagOffsetMustNotBeNegative() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT LAG(salary, -1) OVER (ORDER BY id) FROM employees")
        }
        assertTrue(error.message!!.contains("negative"), error.message!!)
    }

    @Test
    fun lagOffsetMustBeAnInteger() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT LAG(salary, 1.5) OVER (ORDER BY id) FROM employees")
        }
        assertTrue(error.message!!.contains("integer"), error.message!!)
    }

    @Test
    fun firstValueReturnsTheFirstRowsValueForEveryRowInThePartition() {
        seed()
        val result = select(
            "SELECT id, FIRST_VALUE(salary) OVER (PARTITION BY deptId ORDER BY salary DESC) AS top " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(400L, 400L, 400L, 200L, 200L, 200L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun lastValueSharesOneValuePerPeerGroupRatherThanTheWholePartition() {
        seed()
        val result = select(
            "SELECT id, LAST_VALUE(salary) OVER (PARTITION BY deptId ORDER BY salary DESC) AS lv " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(400L, 400L, 300L, 200L, 200L, 100L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun lastValueWithoutOverOrderByUsesTheWholePartitionsLastRow() {
        seed()
        val result = select(
            "SELECT id, LAST_VALUE(salary) OVER (PARTITION BY deptId) AS lv FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(300L, 300L, 300L, 100L, 100L, 100L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun ntileSplitsEachPartitionIntoAsEvenBucketsAsPossible() {
        seed()
        val result = select(
            "SELECT id, NTILE(2) OVER (PARTITION BY deptId ORDER BY salary DESC) AS bucket " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(1L, 1L, 2L, 1L, 1L, 2L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun ntileWithMoreBucketsThanRowsLeavesTheHighestBucketsUnused() {
        seed()
        val result = select(
            "SELECT id, NTILE(5) OVER (PARTITION BY deptId ORDER BY salary DESC) AS bucket " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun ntileBucketCountMustBePositive() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT NTILE(0) OVER (ORDER BY id) FROM employees")
        }
        assertTrue(error.message!!.contains("positive"), error.message!!)
    }

    @Test
    fun ntileBucketCountMustBeAnInteger() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT NTILE(1.5) OVER (ORDER BY id) FROM employees")
        }
        assertTrue(error.message!!.contains("integer"), error.message!!)
    }

    @Test
    fun windowFunctionNestedInsideLagsOffsetIsRejected() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT LAG(salary, ROW_NUMBER() OVER (ORDER BY id)) OVER (ORDER BY id) FROM employees")
        }
        assertTrue(error.message!!.contains("nested"), error.message!!)
    }

    @Test
    fun explicitRowsFrameProducesAMovingAverageInsteadOfARunningTotal() {
        seed()
        val result = select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS s " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(400L, 800L, 700L, 200L, 400L, 300L),
            result.rows.map { (it[1] as Value.IntegerValue).value }
        )
    }

    @Test
    fun rowsBetweenCurrentRowAndFollowingLooksForward() {
        seed()
        val result = select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN CURRENT ROW AND 1 FOLLOWING) AS s " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(800L, 700L, 300L, 400L, 300L, 100L),
            result.rows.map { (it[1] as Value.IntegerValue).value }
        )
    }

    @Test
    fun rowsBetweenUnboundedPrecedingAndCurrentRowMatchesTheImplicitDefaultOnAUniqueOrderKey() {
        seed()
        val explicit = select(
            "SELECT id, SUM(salary) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s " +
                "FROM employees ORDER BY id ASC"
        )
        val implicit = select("SELECT id, SUM(salary) OVER (ORDER BY id) AS s FROM employees ORDER BY id ASC")
        assertEquals(implicit.rows, explicit.rows)
    }

    @Test
    fun frameEntirelyPastThePartitionEdgeAggregatesToNullAndCountsZero() {
        seed()
        val result = select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 5 FOLLOWING AND 9 FOLLOWING) AS s, " +
                "COUNT(*) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 5 FOLLOWING AND 9 FOLLOWING) AS c " +
                "FROM employees WHERE deptId = 1 ORDER BY id ASC"
        )
        assertEquals(
            listOf(listOf(int(1), Value.NullValue, int(0)), listOf(int(2), Value.NullValue, int(0)), listOf(int(3), Value.NullValue, int(0))),
            result.rows
        )
    }

    @Test
    fun firstValueAndLastValueRespectAnExplicitFrameInsteadOfThePeerGroupDefault() {
        seed()
        val result = select(
            "SELECT id, " +
                "FIRST_VALUE(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS f, " +
                "LAST_VALUE(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN CURRENT ROW AND 1 FOLLOWING) AS l " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(400), int(400)),
                listOf(int(2), int(400), int(300)),
                listOf(int(3), int(400), int(300)),
                listOf(int(4), int(200), int(200)),
                listOf(int(5), int(200), int(100)),
                listOf(int(6), int(200), int(100))
            ),
            result.rows
        )
    }

    @Test
    fun rowsFrameShorthandWithoutBetweenEndsAtCurrentRow() {
        seed()
        val explicit = select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS 1 PRECEDING) AS s " +
                "FROM employees ORDER BY id ASC"
        )
        val spelledOut = select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS s " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(spelledOut.rows, explicit.rows)
    }

    @Test
    fun rowsFrameOffsetMustNotBeNegative() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN -1 PRECEDING AND CURRENT ROW) FROM employees")
        }
        assertTrue(error.message!!.contains("negative"), error.message!!)
    }

    @Test
    fun rowsFrameOffsetMustBeAnInteger() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN 1.5 PRECEDING AND CURRENT ROW) FROM employees")
        }
        assertTrue(error.message!!.contains("integer"), error.message!!)
    }

    @Test
    fun rowsFrameIsRejectedOnRankingAndOffsetWindowFunctions() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT ROW_NUMBER() OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM employees")
        }
        assertTrue(error.message!!.contains("frame"), error.message!!)
    }

    @Test
    fun windowFunctionNestedInsideAFrameOffsetIsRejected() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run(
                "SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN ROW_NUMBER() OVER (ORDER BY id) PRECEDING AND CURRENT ROW) " +
                    "FROM employees"
            )
        }
        assertTrue(error.message!!.contains("nested"), error.message!!)
    }

    @Test
    fun explicitFrameSurvivesViewDefinitionRoundTrip() {
        seed()
        run(
            "CREATE VIEW movingTotals AS SELECT id, " +
                "SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS s " +
                "FROM employees"
        )
        val result = select("SELECT id, s FROM movingTotals ORDER BY id ASC")
        assertEquals(
            listOf(400L, 800L, 700L, 200L, 400L, 300L),
            result.rows.map { (it[1] as Value.IntegerValue).value }
        )
    }

    private fun seedSales() {
        run("CREATE TABLE sales (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("INSERT INTO sales VALUES (1, 10), (2, 20), (3, 20), (4, 30), (5, 50)")
    }

    @Test
    fun rangeBetweenPrecedingAndFollowingIncludesRowsWithinValueDistanceRegardlessOfPhysicalRowCount() {
        seedSales()
        val result = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(50L, 80L, 80L, 70L, 50L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rangeFrameGivesTheSameRowSetRegardlessOfOrderDirectionForASymmetricWindow() {
        seedSales()
        val ascending = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount ASC RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        val descending = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount DESC RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals(ascending.rows, descending.rows)
    }

    @Test
    fun rangeBetweenPrecedingAndFollowingOnADescendingOrderByFlipsWhichDirectionIsPreceding() {
        seedSales()
        val result = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount DESC RANGE BETWEEN 15 PRECEDING AND 5 FOLLOWING) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(50L, 70L, 70L, 30L, 50L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rangeCurrentRowExpandsToTheWholePeerGroupUnlikeRowsCurrentRow() {
        seedSales()
        val range = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        val rows = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(10L, 50L, 50L, 80L, 130L), range.rows.map { (it[1] as Value.IntegerValue).value })
        assertEquals(listOf(10L, 30L, 50L, 80L, 130L), rows.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rangeFrameMeasuresDistanceOnADecimalColumn() {
        run("CREATE TABLE prices (id INTEGER PRIMARY KEY, price DECIMAL(10, 2))")
        run("INSERT INTO prices VALUES (1, 1.00), (2, 1.40), (3, 1.50), (4, 2.00)")
        val result = select(
            "SELECT id, COUNT(*) OVER (ORDER BY price RANGE BETWEEN 0.5 PRECEDING AND 0.5 FOLLOWING) AS c " +
                "FROM prices ORDER BY id ASC"
        )
        assertEquals(listOf(3L, 3L, 4L, 2L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rangeFrameMeasuresDistanceInDaysOnADateColumn() {
        run("CREATE TABLE events (id INTEGER PRIMARY KEY, day DATE)")
        run(
            "INSERT INTO events VALUES " +
                "(1, CAST('2024-01-01' AS DATE)), (2, CAST('2024-01-02' AS DATE)), " +
                "(3, CAST('2024-01-05' AS DATE)), (4, CAST('2024-01-06' AS DATE)), (5, CAST('2024-01-10' AS DATE))"
        )
        val result = select(
            "SELECT id, COUNT(*) OVER (ORDER BY day RANGE BETWEEN 2 PRECEDING AND 2 FOLLOWING) AS c " +
                "FROM events ORDER BY id ASC"
        )
        assertEquals(listOf(2L, 2L, 2L, 2L, 1L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rangeFrameEntirelyPastThePartitionEdgeAggregatesToNullAndCountsZero() {
        seedSales()
        val result = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount RANGE BETWEEN 100 FOLLOWING AND 200 FOLLOWING) AS s, " +
                "COUNT(*) OVER (ORDER BY amount RANGE BETWEEN 100 FOLLOWING AND 200 FOLLOWING) AS c " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals(
            (1L..5L).map { listOf(int(it), Value.NullValue, int(0)) },
            result.rows
        )
    }

    @Test
    fun rangeFrameWithAPrecedingBoundRequiresExactlyOneOrderByColumn() {
        seedSales()
        val error = assertFailsWith<KestrelException> {
            run(
                "SELECT SUM(amount) OVER (ORDER BY amount, id RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING) " +
                    "FROM sales"
            )
        }
        assertTrue(error.message!!.contains("exactly one ORDER BY column"), error.message!!)
    }

    @Test
    fun rangeFrameWithAPrecedingBoundRequiresANumericOrDateOrderColumn() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT SUM(salary) OVER (ORDER BY name RANGE BETWEEN 1 PRECEDING AND CURRENT ROW) FROM employees")
        }
        assertTrue(error.message!!.contains("numeric or DATE"), error.message!!)
    }

    @Test
    fun rangeFrameOffsetMustBeNumeric() {
        seedSales()
        val error = assertFailsWith<KestrelException> {
            run("SELECT SUM(amount) OVER (ORDER BY amount RANGE BETWEEN 'x' PRECEDING AND CURRENT ROW) FROM sales")
        }
        assertTrue(error.message!!.contains("numeric"), error.message!!)
    }

    @Test
    fun rangeFrameOffsetMustNotBeNegative() {
        seedSales()
        val error = assertFailsWith<KestrelException> {
            run("SELECT SUM(amount) OVER (ORDER BY amount RANGE BETWEEN -1 PRECEDING AND CURRENT ROW) FROM sales")
        }
        assertTrue(error.message!!.contains("negative"), error.message!!)
    }

    @Test
    fun rangeFrameSurvivesViewDefinitionRoundTrip() {
        seedSales()
        run(
            "CREATE VIEW valueTotals AS SELECT id, " +
                "SUM(amount) OVER (ORDER BY amount RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING) AS s " +
                "FROM sales"
        )
        val result = select("SELECT id, s FROM valueTotals ORDER BY id ASC")
        assertEquals(listOf(50L, 80L, 80L, 70L, 50L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun excludeCurrentRowRemovesOnlyTheCurrentRowFromTheWholePartitionFrame() {
        seedSales()
        val result = select(
            "SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE CURRENT ROW" +
                ") AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(120L, 110L, 110L, 100L, 80L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun excludeGroupRemovesTheWholePeerGroupIncludingTheCurrentRow() {
        seedSales()
        val result = select(
            "SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE GROUP" +
                ") AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(120L, 90L, 90L, 100L, 80L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun excludeTiesRemovesOnlyTheOtherTiedRowsAndKeepsTheCurrentRow() {
        seedSales()
        val result = select(
            "SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE TIES" +
                ") AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(130L, 110L, 110L, 130L, 130L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun excludeCurrentRowInteractsWithABoundedFrameInsteadOfOnlyTheWholePartition() {
        seedSales()
        val result = select(
            "SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING EXCLUDE CURRENT ROW" +
                ") AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(20L, 30L, 50L, 70L, 30L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun excludeNoOthersExplicitlyWrittenOutBehavesLikeNoExcludeClauseAtAll() {
        seedSales()
        val explicit = select(
            "SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE NO OTHERS" +
                ") AS s FROM sales ORDER BY id ASC"
        )
        val implicit = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals(implicit.rows, explicit.rows)
    }

    @Test
    fun excludeGroupWithNoOrderByTreatsTheWholePartitionAsOnePeerGroup() {
        seedSales()
        val result = select(
            "SELECT id, COUNT(*) OVER (ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE GROUP) AS c " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(0L, 0L, 0L, 0L, 0L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun excludeClauseSurvivesViewDefinitionRoundTrip() {
        seedSales()
        run(
            "CREATE VIEW excludingSelf AS SELECT id, " +
                "SUM(amount) OVER (ORDER BY amount ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE CURRENT ROW) AS s " +
                "FROM sales"
        )
        val result = select("SELECT id, s FROM excludingSelf ORDER BY id ASC")
        assertEquals(listOf(120L, 110L, 110L, 100L, 80L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun groupsBetweenPrecedingAndFollowingCountsPeerGroupsNotPhysicalRows() {
        seedSales()
        val groups = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        val rows = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(50L, 80L, 80L, 120L, 80L), groups.rows.map { (it[1] as Value.IntegerValue).value })
        assertEquals(listOf(30L, 50L, 70L, 100L, 80L), rows.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun groupsFrameBehavesExactlyLikeRowsFrameWhenNoRowsAreTied() {
        run("CREATE TABLE prices (id INTEGER PRIMARY KEY, price DECIMAL(10, 2))")
        run("INSERT INTO prices VALUES (1, 1.00), (2, 1.40), (3, 1.50), (4, 2.00)")
        val groups = select(
            "SELECT id, COUNT(*) OVER (ORDER BY price GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS c " +
                "FROM prices ORDER BY id ASC"
        )
        val rows = select(
            "SELECT id, COUNT(*) OVER (ORDER BY price ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS c " +
                "FROM prices ORDER BY id ASC"
        )
        assertEquals(rows.rows, groups.rows)
    }

    @Test
    fun groupsCurrentRowExpandsToTheWholePeerGroupJustLikeRangeCurrentRow() {
        seedSales()
        val groups = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        val range = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        val rows = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(10L, 50L, 50L, 80L, 130L), groups.rows.map { (it[1] as Value.IntegerValue).value })
        assertEquals(groups.rows, range.rows)
        assertEquals(listOf(10L, 30L, 50L, 80L, 130L), rows.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun groupsFrameWorksWithAMultiColumnNonNumericOrderByUnlikeRange() {
        seed()
        val result = select(
            "SELECT id, COUNT(*) OVER (ORDER BY deptId, name GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS c " +
                "FROM employees ORDER BY deptId ASC, name ASC"
        )
        assertEquals(
            listOf(int(1), int(3), int(2), int(5), int(6), int(4)),
            result.rows.map { it[0] }
        )
        assertEquals(listOf(2L, 3L, 3L, 3L, 3L, 2L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun groupsFrameEntirelyPastThePartitionEdgeAggregatesToNullAndCountsZero() {
        seedSales()
        val result = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN 100 FOLLOWING AND 200 FOLLOWING) AS s, " +
                "COUNT(*) OVER (ORDER BY amount GROUPS BETWEEN 100 FOLLOWING AND 200 FOLLOWING) AS c " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals((1L..5L).map { listOf(int(it), Value.NullValue, int(0)) }, result.rows)
    }

    @Test
    fun groupsFrameOffsetMustBeAnInteger() {
        seedSales()
        val error = assertFailsWith<KestrelException> {
            run("SELECT SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN 1.5 PRECEDING AND CURRENT ROW) FROM sales")
        }
        assertTrue(error.message!!.contains("integer"), error.message!!)
    }

    @Test
    fun groupsFrameOffsetMustNotBeNegative() {
        seedSales()
        val error = assertFailsWith<KestrelException> {
            run("SELECT SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN -1 PRECEDING AND CURRENT ROW) FROM sales")
        }
        assertTrue(error.message!!.contains("negative"), error.message!!)
    }

    @Test
    fun groupsFrameWithUnboundedPrecedingAndFollowingCoversTheWholePartitionJustLikeRowsAndRange() {
        seedSales()
        val groups = select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS s " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(130L, 130L, 130L, 130L, 130L), groups.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun groupsFrameIsRejectedOnRankingAndOffsetWindowFunctions() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("SELECT ROW_NUMBER() OVER (ORDER BY id GROUPS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM employees")
        }
        assertTrue(error.message!!.contains("GROUPS"), error.message!!)
    }

    @Test
    fun excludeGroupCombinesWithAGroupsFrameJustLikeItDoesWithRange() {
        seedSales()
        val groups = select(
            "SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount GROUPS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE GROUP" +
                ") AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(120L, 90L, 90L, 100L, 80L), groups.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun nestedWindowFunctionInAGroupsFrameOffsetIsRejected() {
        seedSales()
        val error = assertFailsWith<KestrelException> {
            run(
                "SELECT SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN ROW_NUMBER() OVER (ORDER BY amount) PRECEDING AND CURRENT ROW) " +
                    "FROM sales"
            )
        }
        assertTrue(error.message!!.contains("nested"), error.message!!)
    }

    @Test
    fun groupsFrameSurvivesViewDefinitionRoundTrip() {
        seedSales()
        run(
            "CREATE VIEW groupTotals AS SELECT id, " +
                "SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS s " +
                "FROM sales"
        )
        val result = select("SELECT id, s FROM groupTotals ORDER BY id ASC")
        assertEquals(listOf(50L, 80L, 80L, 120L, 80L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }
}
