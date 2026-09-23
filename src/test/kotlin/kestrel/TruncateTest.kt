package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.TruncateTableResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TruncateTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)

    @Test
    fun truncateRemovesEveryRowButKeepsTheTableAndItsSchema() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT NOT NULL)")
        run("INSERT INTO t VALUES (1, 'a'), (2, 'b')")
        val result = exec("TRUNCATE TABLE t") as TruncateTableResult
        assertEquals(listOf("t"), result.tableNames)
        assertEquals(emptyList(), select("SELECT * FROM t").rows)
        run("INSERT INTO t VALUES (1, 'fresh')")
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM t").rows)
    }

    @Test
    fun tableKeywordIsOptional() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        exec("TRUNCATE t")
        assertEquals(emptyList(), select("SELECT * FROM t").rows)
    }

    @Test
    fun multipleTablesInOneStatementAreAllTruncated() {
        run("CREATE TABLE a (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE b (id INTEGER PRIMARY KEY)")
        run("INSERT INTO a VALUES (1)")
        run("INSERT INTO b VALUES (1)")
        val result = exec("TRUNCATE TABLE a, b") as TruncateTableResult
        assertEquals(setOf("a", "b"), result.tableNames.toSet())
        assertEquals(emptyList(), select("SELECT * FROM a").rows)
        assertEquals(emptyList(), select("SELECT * FROM b").rows)
    }

    @Test
    fun truncatingAnUnknownTableFails() {
        assertFailsWith<KestrelException> { run("TRUNCATE TABLE nope") }
    }

    @Test
    fun truncatingAViewFailsWithAViewSpecificMessage() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("CREATE VIEW v AS SELECT * FROM t")
        val error = assertFailsWith<KestrelException> { run("TRUNCATE TABLE v") }
        assertTrue(error.message!!.contains("view"), error.message)
    }

    @Test
    fun continueIdentityIsTheDefaultAndLeavesTheSequencePositionAlone() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('seq'), label TEXT)")
        run("INSERT INTO t (label) VALUES ('a'), ('b')")
        exec("TRUNCATE TABLE t")
        run("INSERT INTO t (label) VALUES ('c')")
        assertEquals(listOf(listOf(int(3))), select("SELECT id FROM t").rows)
    }

    @Test
    fun continueIdentityIsAlsoTheResultOfWritingItOutExplicitly() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('seq'), label TEXT)")
        run("INSERT INTO t (label) VALUES ('a')")
        exec("TRUNCATE TABLE t CONTINUE IDENTITY")
        run("INSERT INTO t (label) VALUES ('b')")
        assertEquals(listOf(listOf(int(2))), select("SELECT id FROM t").rows)
    }

    @Test
    fun restartIdentityResetsTheOwningSequenceBackToItsStartValue() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('seq'), label TEXT)")
        run("INSERT INTO t (label) VALUES ('a'), ('b'), ('c')")
        exec("TRUNCATE TABLE t RESTART IDENTITY")
        run("INSERT INTO t (label) VALUES ('fresh')")
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM t").rows)
    }

    @Test
    fun restartIdentityIgnoresAColumnWhoseDefaultIsNotNextval() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT DEFAULT 'x')")
        run("INSERT INTO t VALUES (1, 'a')")
        exec("TRUNCATE TABLE t RESTART IDENTITY")
        run("INSERT INTO t VALUES (2, DEFAULT)")
        assertEquals(listOf(listOf(int(2))), select("SELECT id FROM t").rows)
    }

    @Test
    fun truncateIsRestrictedByDefaultWhenAnotherTableHasAForeignKeyIntoIt() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("INSERT INTO employees VALUES (1, 1)")
        val error = assertFailsWith<KestrelException> { run("TRUNCATE TABLE departments") }
        assertTrue(error.message!!.contains("employees"), error.message)
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM departments").rows)
    }

    @Test
    fun truncateRestrictionAppliesEvenWhenTheForeignKeyDeclaresOnDeleteCascade() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id) ON DELETE CASCADE)")
        run("INSERT INTO departments VALUES (1)")
        run("INSERT INTO employees VALUES (1, 1)")
        assertFailsWith<KestrelException> { run("TRUNCATE TABLE departments") }
    }

    @Test
    fun truncatingBothTablesInTheSameStatementNeedsNoCascade() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        run("INSERT INTO departments VALUES (1)")
        run("INSERT INTO employees VALUES (1, 1)")
        val result = exec("TRUNCATE TABLE departments, employees") as TruncateTableResult
        assertEquals(setOf("departments", "employees"), result.tableNames.toSet())
    }

    @Test
    fun truncateCascadeAlsoEmptiesEveryTransitiveReferencer() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        run("CREATE TABLE timesheets (id INTEGER PRIMARY KEY, employee_id INTEGER REFERENCES employees(id))")
        run("INSERT INTO departments VALUES (1)")
        run("INSERT INTO employees VALUES (1, 1)")
        run("INSERT INTO timesheets VALUES (1, 1)")
        val result = exec("TRUNCATE TABLE departments CASCADE") as TruncateTableResult
        assertEquals(setOf("departments", "employees", "timesheets"), result.tableNames.toSet())
        assertEquals(emptyList(), select("SELECT * FROM timesheets").rows)
    }

    @Test
    fun selfReferencingForeignKeyDoesNotBlockTruncatingItsOwnTable() {
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, manager_id INTEGER REFERENCES employees(id))")
        run("INSERT INTO employees VALUES (1, NULL), (2, 1)")
        exec("TRUNCATE TABLE employees")
        assertEquals(emptyList(), select("SELECT * FROM employees").rows)
    }

    @Test
    fun truncateNeverFiresRowLevelTriggersUnlikeAWhereLessDelete() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE audit (n INTEGER)")
        run("INSERT INTO audit VALUES (0)")
        run("CREATE TRIGGER log_delete AFTER DELETE ON t FOR EACH ROW BEGIN UPDATE audit SET n = n + 1; END")
        run("INSERT INTO t VALUES (1), (2), (3)")
        exec("TRUNCATE TABLE t")
        assertEquals(listOf(listOf(int(0))), select("SELECT n FROM audit").rows)
        assertEquals(emptyList(), select("SELECT * FROM t").rows)
    }

    @Test
    fun rollbackAfterTruncateRestoresTheRows() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1), (2)")
        run("BEGIN")
        exec("TRUNCATE TABLE t")
        run("ROLLBACK")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), select("SELECT id FROM t").rows)
    }

    @Test
    fun rollbackAfterTruncateRestartIdentityAlsoRestoresTheSequencePosition() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('seq'), label TEXT)")
        run("INSERT INTO t (label) VALUES ('a'), ('b')")
        run("BEGIN")
        exec("TRUNCATE TABLE t RESTART IDENTITY")
        run("ROLLBACK")
        run("INSERT INTO t (label) VALUES ('c')")
        assertEquals(listOf(int(1), int(2), int(3)), select("SELECT id FROM t").rows.map { it.single() })
    }

    @Test
    fun truncateInsideATransactionCanStillBeCommitted() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        run("BEGIN")
        exec("TRUNCATE TABLE t")
        run("COMMIT")
        assertEquals(emptyList(), select("SELECT * FROM t").rows)
    }
}
