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

class LateralJoinTest {
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
                "(1, 1, 'ada', 300), (2, 1, 'linus', 250), (3, 1, 'grace', 400), " +
                "(4, 2, 'sam', 150), (5, 2, 'kay', 200)"
        )
    }

    @Test
    fun lateralJoinComputesTopOnePerGroupWhichAPlainJoinCannotExpress() {
        seed()
        val result = select(
            "SELECT d.name, top.name, top.salary FROM departments d " +
                "JOIN LATERAL (SELECT e.name, e.salary FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE " +
                "ORDER BY d.name ASC"
        )
        assertEquals(
            listOf(
                listOf(text("eng"), text("grace"), int(400)),
                listOf(text("sales"), text("kay"), int(200))
            ),
            result.rows
        )
    }

    @Test
    fun lateralJoinWithLimitGreaterThanOneReturnsMultipleRowsPerLeftRow() {
        seed()
        val result = select(
            "SELECT d.name, top.name FROM departments d " +
                "JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 2) AS top ON TRUE " +
                "ORDER BY d.name ASC, top.name ASC"
        )
        assertEquals(
            listOf(
                listOf(text("eng"), text("ada")),
                listOf(text("eng"), text("grace")),
                listOf(text("sales"), text("kay")),
                listOf(text("sales"), text("sam"))
            ),
            result.rows
        )
    }

    @Test
    fun innerJoinLateralDropsGroupsWithNoMatchingRows() {
        seed()
        run("INSERT INTO departments VALUES (3, 'empty')")
        val result = select(
            "SELECT d.name FROM departments d " +
                "JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id LIMIT 1) AS top ON TRUE"
        )
        assertTrue(result.rows.none { it == listOf(text("empty")) })
        assertEquals(2, result.rows.size)
    }

    @Test
    fun leftJoinLateralKeepsGroupsWithNoMatchingRowsAsNull() {
        seed()
        run("INSERT INTO departments VALUES (3, 'empty')")
        val result = select(
            "SELECT d.name, top.name FROM departments d " +
                "LEFT JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE " +
                "ORDER BY d.name ASC"
        )
        assertEquals(
            listOf(
                listOf(text("empty"), Value.NullValue),
                listOf(text("eng"), text("grace")),
                listOf(text("sales"), text("kay"))
            ),
            result.rows
        )
    }

    @Test
    fun leftJoinLateralOverAnEmptyBaseTableProducesNoRowsWithoutRunningTheSubquery() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, deptId INTEGER, name TEXT)")
        val result = select(
            "SELECT d.name, top.name FROM departments d " +
                "LEFT JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id) AS top ON TRUE"
        )
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun chainedLateralJoinsSeeEarlierLateralAliases() {
        seed()
        run("CREATE TABLE dual (x INTEGER)")
        run("INSERT INTO dual VALUES (1)")
        val result = select(
            "SELECT d.name, bonus.amount FROM departments d " +
                "JOIN LATERAL (SELECT e.salary FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE " +
                "JOIN LATERAL (SELECT top.salary / 10 AS amount FROM dual) AS bonus ON TRUE " +
                "ORDER BY d.name ASC"
        )
        assertEquals(listOf(listOf(text("eng"), int(40)), listOf(text("sales"), int(20))), result.rows)
    }

    @Test
    fun lateralSubqueryCannotReferenceATableJoinedLater() {
        seed()
        assertFailsWith<KestrelException> {
            select(
                "SELECT * FROM departments d " +
                    "JOIN LATERAL (SELECT e.name FROM employees e WHERE e.salary = later.salary) AS top ON TRUE " +
                    "JOIN employees later ON later.deptId = d.id"
            )
        }
    }

    @Test
    fun lateralKeywordBeforeAPlainTableIsRejected() {
        seed()
        assertFailsWith<ParseException> {
            select("SELECT * FROM departments d JOIN LATERAL employees ON TRUE")
        }
    }

    @Test
    fun lateralSubqueryWithStarProjectionResolvesColumnsStructurallyFromItsBaseTable() {
        seed()
        // The lateral subquery itself uses `SELECT *`, so its output column names come from
        // [kestrel.engine.SelectRunner.structuralColumnNames] walking the base table's schema in
        // the catalog, not from an executed row - exercised here before any left row exists yet.
        val result = select(
            "SELECT d.name, top.name, top.salary FROM departments d " +
                "JOIN LATERAL (SELECT * FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE " +
                "ORDER BY d.name ASC"
        )
        assertEquals(
            listOf(listOf(text("eng"), text("grace"), int(400)), listOf(text("sales"), text("kay"), int(200))),
            result.rows
        )
    }

    @Test
    fun explainAnnotatesTheJoinAsLateralAndNeverUsesHashJoin() {
        seed()
        val plan = explain(
            "EXPLAIN SELECT d.name, top.name FROM departments d " +
                "JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id) AS top ON top.name = d.name"
        )
        assertTrue(plan.any { it.contains("Lateral") }, "got $plan")
        assertTrue(plan.none { it.contains("Hash Join") }, "got $plan")
    }

    @Test
    fun crossJoinLateralIsEquivalentToPlainJoinLateralOnTrue() {
        seed()
        val cross = select(
            "SELECT d.name, top.name, top.salary FROM departments d " +
                "CROSS JOIN LATERAL (SELECT e.name, e.salary FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ORDER BY d.name ASC"
        )
        val onTrue = select(
            "SELECT d.name, top.name, top.salary FROM departments d " +
                "JOIN LATERAL (SELECT e.name, e.salary FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE ORDER BY d.name ASC"
        )
        assertEquals(onTrue.rows, cross.rows)
    }
}
