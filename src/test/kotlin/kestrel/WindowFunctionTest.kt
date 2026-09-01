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

    /**
     * eng (deptId 1): ada/linus tie at 400 (ada inserted first), grace at 300.
     * sales (deptId 2): sam/kay tie at 200 (sam inserted first), meg at 100.
     * Salaries also happen to be in strict descending order by id across the whole table, which
     * keeps the no-PARTITION-BY tests below easy to eyeball.
     */
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

