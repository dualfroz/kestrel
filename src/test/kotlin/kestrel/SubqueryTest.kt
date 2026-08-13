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

class SubqueryTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private fun seed() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL, budget INTEGER)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, name TEXT NOT NULL, department_id INTEGER, salary INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng', 1000), (2, 'sales', 500), (3, 'ops', 0)")
        run(
            "INSERT INTO employees VALUES " +
                "(1, 'ada', 1, 120), (2, 'linus', 1, 90), (3, 'grace', 2, 80), (4, 'guido', 2, 70)"
        )
    }

    @Test
    fun scalarSubqueryComparesAgainstAggregateOfAnotherQuery() {
        seed()
        val result = select("SELECT name FROM employees WHERE salary > (SELECT AVG(salary) FROM employees) ORDER BY name")
        assertEquals(listOf(listOf(text("ada"))), result.rows)
    }

    @Test
    fun scalarSubqueryReturningNoRowsEvaluatesToNull() {
        seed()
        val result = select("SELECT (SELECT id FROM departments WHERE name = 'nope') FROM departments WHERE id = 1")
        assertEquals(listOf(listOf(Value.NullValue)), result.rows)
    }

    @Test
    fun scalarSubqueryReturningMultipleRowsFails() {
        seed()
        assertFailsWith<KestrelException> {
            select("SELECT (SELECT salary FROM employees) FROM departments WHERE id = 1")
        }
    }

    @Test
    fun scalarSubqueryReturningMultipleColumnsFails() {
        seed()
        assertFailsWith<KestrelException> {
            select("SELECT (SELECT id, name FROM departments) FROM departments WHERE id = 1")
        }
    }

    @Test
    fun correlatedScalarSubqueryInProjectionCountsPerRow() {
        seed()
        val result = select(
            "SELECT d.name, (SELECT COUNT(*) FROM employees e WHERE e.department_id = d.id) " +
                "FROM departments d ORDER BY d.id ASC"
        )
        assertEquals(
            listOf(
                listOf(text("eng"), int(2)),
                listOf(text("sales"), int(2)),
                listOf(text("ops"), int(0))
            ),
            result.rows
        )
    }

    @Test
    fun correlatedScalarSubqueryInWhereComparesAgainstOwnGroupAverage() {
        seed()
        val result = select(
            "SELECT name FROM employees e WHERE e.salary > " +
                "(SELECT AVG(salary) FROM employees e2 WHERE e2.department_id = e.department_id) ORDER BY name ASC"
        )
        assertEquals(listOf(listOf(text("ada")), listOf(text("grace"))), result.rows)
    }

    @Test
    fun inSubqueryMatchesUncorrelatedResultSet() {
        seed()
        val result = select("SELECT name FROM departments WHERE id IN (SELECT department_id FROM employees) ORDER BY name ASC")
        assertEquals(listOf(listOf(text("eng")), listOf(text("sales"))), result.rows)
    }

    @Test
    fun notInSubqueryExcludesMatchingRows() {
        seed()
        val result = select("SELECT name FROM departments WHERE id NOT IN (SELECT department_id FROM employees)")
        assertEquals(listOf(listOf(text("ops"))), result.rows)
    }

    @Test
    fun notInSubqueryWithNullInResultSetExcludesEverythingByThreeValuedLogic() {
        run("CREATE TABLE d (id INTEGER)")
        run("CREATE TABLE e (dept INTEGER)")
        run("INSERT INTO d VALUES (1), (2)")
        run("INSERT INTO e VALUES (1), (NULL)")
        val result = select("SELECT id FROM d WHERE id NOT IN (SELECT dept FROM e)")
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun inSubqueryRequiresExactlyOneColumn() {
        seed()
        assertFailsWith<KestrelException> {
            select("SELECT name FROM departments WHERE id IN (SELECT id, name FROM departments)")
        }
    }

    @Test
    fun existsWithoutCorrelationChecksWhetherSubqueryHasAnyRows() {
        seed()
        val result = select("SELECT name FROM departments WHERE EXISTS (SELECT 1 FROM employees) ORDER BY name ASC")
        assertEquals(listOf(listOf(text("eng")), listOf(text("ops")), listOf(text("sales"))), result.rows)
    }

    @Test
    fun correlatedExistsFindsDepartmentsWithEmployees() {
        seed()
        val result = select(
            "SELECT name FROM departments d WHERE EXISTS " +
                "(SELECT 1 FROM employees e WHERE e.department_id = d.id) ORDER BY name ASC"
        )
        assertEquals(listOf(listOf(text("eng")), listOf(text("sales"))), result.rows)
    }

    @Test
    fun correlatedNotExistsFindsDepartmentsWithoutEmployees() {
        seed()
        val result = select(
            "SELECT name FROM departments d WHERE NOT EXISTS " +
                "(SELECT 1 FROM employees e WHERE e.department_id = d.id)"
        )
        assertEquals(listOf(listOf(text("ops"))), result.rows)
    }

    @Test
    fun correlatedScalarSubqueryUsableInUpdateSet() {
        seed()
        run("CREATE TABLE dept_stats (id INTEGER PRIMARY KEY, headcount INTEGER)")
        run("INSERT INTO dept_stats VALUES (1, 0), (2, 0), (3, 0)")
        run(
            "UPDATE dept_stats SET headcount = " +
                "(SELECT COUNT(*) FROM employees WHERE employees.department_id = dept_stats.id)"
        )
        val result = select("SELECT headcount FROM dept_stats ORDER BY id ASC")
        assertEquals(listOf(listOf(int(2)), listOf(int(2)), listOf(int(0))), result.rows)
    }

    @Test
    fun inSubqueryUsableInDeleteWhereClause() {
        seed()
        run("INSERT INTO employees VALUES (5, 'temp', 3, 10)")
        run("DELETE FROM employees WHERE department_id IN (SELECT id FROM departments WHERE budget = 0)")
        val result = select("SELECT name FROM employees ORDER BY name ASC")
        assertEquals(
            listOf(listOf(text("ada")), listOf(text("grace")), listOf(text("guido")), listOf(text("linus"))),
            result.rows
        )
    }

    @Test
    fun scalarSubqueryUsableAsInsertValue() {
        seed()
        run("CREATE TABLE summary (id INTEGER PRIMARY KEY, employee_count INTEGER)")
        run("INSERT INTO summary VALUES (1, (SELECT COUNT(*) FROM employees))")
        val result = select("SELECT employee_count FROM summary")
        assertEquals(listOf(listOf(int(4))), result.rows)
    }

    @Test
    fun correlatedScalarSubqueryInUpdateSetRoundTripsThroughSqlWriter() {
        seed()
        run("CREATE TABLE dept_stats (id INTEGER PRIMARY KEY, headcount INTEGER)")
        run("INSERT INTO dept_stats VALUES (1, 0), (2, 0), (3, 0)")
        val statement = Parser(
            Lexer(
                "UPDATE dept_stats SET headcount = " +
                    "(SELECT COUNT(*) FROM employees WHERE employees.department_id = dept_stats.id)"
            ).tokenize()
        ).parseProgram().single()
        val rendered = kestrel.persistence.SqlWriter.write(statement)
        val reparsed = Parser(Lexer(rendered).tokenize()).parseProgram().single()
        executor.execute(reparsed)
        val result = select("SELECT headcount FROM dept_stats ORDER BY id ASC")
        assertEquals(listOf(listOf(int(2)), listOf(int(2)), listOf(int(0))), result.rows)
    }

    @Test
    fun inSubqueryInDeleteWhereRoundTripsThroughSqlWriter() {
        seed()
        run("INSERT INTO employees VALUES (5, 'temp', 3, 10)")
        val statement = Parser(
            Lexer("DELETE FROM employees WHERE department_id IN (SELECT id FROM departments WHERE budget = 0)").tokenize()
        ).parseProgram().single()
        val rendered = kestrel.persistence.SqlWriter.write(statement)
        val reparsed = Parser(Lexer(rendered).tokenize()).parseProgram().single()
        executor.execute(reparsed)
        val result = select("SELECT name FROM employees ORDER BY name ASC")
        assertEquals(
            listOf(listOf(text("ada")), listOf(text("grace")), listOf(text("guido")), listOf(text("linus"))),
            result.rows
        )
    }

    @Test
    fun aggregateOverUncorrelatedScalarSubqueryEvaluatesItPerRow() {
        seed()
        val result = select("SELECT SUM((SELECT budget FROM departments WHERE id = 1)) FROM employees")
        assertEquals(listOf(listOf(int(1000 * 4))), result.rows)
    }

    @Test
    fun existsResultCannotBeUsedAsAnArithmeticOperand() {
        seed()
        assertFailsWith<KestrelException> {
            select("SELECT 1 + EXISTS (SELECT 1 FROM employees) FROM departments WHERE id = 1")
        }
    }
}
