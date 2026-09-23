package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.UpdateResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CheckConstraintTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    @Test
    fun columnLevelCheckAcceptsSatisfyingRows() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        assertEquals(InsertResult(1), exec("INSERT INTO t VALUES (1, 30)"))
    }

    @Test
    fun columnLevelCheckRejectsViolatingRows() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (1, -5)") }
        assertTrue(error.message!!.contains("CHECK"))
    }

    @Test
    fun checkPassesWhenExpressionIsUnknownBecauseOfNull() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        assertEquals(InsertResult(1), exec("INSERT INTO t VALUES (1, NULL)"))
    }

    @Test
    fun tableLevelCheckCanSpanMultipleColumns() {
        run("CREATE TABLE ranges (id INTEGER PRIMARY KEY, low INTEGER, high INTEGER, CHECK (low <= high))")
        assertEquals(InsertResult(1), exec("INSERT INTO ranges VALUES (1, 1, 10)"))
        assertFailsWith<KestrelException> { run("INSERT INTO ranges VALUES (2, 10, 1)") }
    }

    @Test
    fun multipleCheckConstraintsAllMustBeSatisfied() {
        run(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age >= 0), " +
                "score INTEGER CHECK (score BETWEEN 0 AND 100))"
        )
        assertEquals(InsertResult(1), exec("INSERT INTO t VALUES (1, 30, 90)"))
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (2, 30, 150)") }
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (3, -1, 90)") }
    }

    @Test
    fun checkIsEnforcedOnUpdateToo() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        run("INSERT INTO t VALUES (1, 30)")
        assertFailsWith<KestrelException> { run("UPDATE t SET age = -1 WHERE id = 1") }
        assertEquals(UpdateResult(1), exec("UPDATE t SET age = 40 WHERE id = 1"))
    }

    @Test
    fun checkRejectsAggregateFunctions() {
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER CHECK (SUM(amount) > 0))")
        }
    }

    @Test
    fun checkRejectsSubqueries() {
        run("CREATE TABLE other (id INTEGER PRIMARY KEY)")
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER PRIMARY KEY CHECK (id IN (SELECT id FROM other)))")
        }
    }

    @Test
    fun checkRejectsUnknownColumn() {
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER PRIMARY KEY, CHECK (nope > 0))")
        }
    }

    @Test
    fun checkCanReferenceStringAndCallExpressions() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT CHECK (LENGTH(name) > 0))")
        assertEquals(InsertResult(1), exec("INSERT INTO t VALUES (1, 'ada')"))
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (2, '')") }
    }

    @Test
    fun checkConstraintSurvivesRollback() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        run("BEGIN")
        run("INSERT INTO t VALUES (1, 30)")
        run("ROLLBACK")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (1, -5)") }
        assertEquals(0, select("SELECT * FROM t").rows.size)
    }
}
