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

