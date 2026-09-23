package kestrel.store.sql

import kestrel.KestrelException
import kestrel.engine.StatementResult
import kestrel.engine.TruncateTableResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiskExecutorTruncateTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun int(value: Long) = Value.IntegerValue(value)

    private class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
            private set
        var executor = DiskExecutor(catalog)
            private set

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun exec(sql: String): StatementResult = run(sql).single()
        fun select(sql: String): kestrel.engine.SelectResult = exec(sql) as kestrel.engine.SelectResult

        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-truncate-data"), tempFile("kestrel-disk-truncate-wal"))

    @Test
    fun truncateRemovesEveryRowButKeepsTheTable() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT NOT NULL)")
        db.run("INSERT INTO t VALUES (1, 'a'), (2, 'b')")
        val result = db.exec("TRUNCATE TABLE t") as TruncateTableResult
        assertEquals(listOf("t"), result.tableNames)
        assertEquals(emptyList(), db.select("SELECT * FROM t").rows)
        db.run("INSERT INTO t VALUES (1, 'fresh')")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT id FROM t").rows)
    }

    @Test
    fun multipleTablesInOneStatementAreAllTruncated() {
        val db = newDatabase()
        db.run("CREATE TABLE a (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE b (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO a VALUES (1)")
        db.run("INSERT INTO b VALUES (1)")
        val result = db.exec("TRUNCATE TABLE a, b") as TruncateTableResult
        assertEquals(setOf("a", "b"), result.tableNames.toSet())
        assertEquals(emptyList(), db.select("SELECT * FROM a").rows)
        assertEquals(emptyList(), db.select("SELECT * FROM b").rows)
    }

    @Test
    fun truncatingAnUnknownTableFails() {
        val db = newDatabase()
        assertFailsWith<DiskCatalogException> { db.run("TRUNCATE TABLE nope") }
    }

    @Test
    fun restartIdentityResetsTheOwningSequenceBackToItsStartValue() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('seq'), label TEXT)")
        db.run("INSERT INTO t (label) VALUES ('a'), ('b'), ('c')")
        db.exec("TRUNCATE TABLE t RESTART IDENTITY")
        db.run("INSERT INTO t (label) VALUES ('fresh')")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT id FROM t").rows)
    }

    @Test
    fun continueIdentityIsTheDefaultAndLeavesTheSequencePositionAlone() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('seq'), label TEXT)")
        db.run("INSERT INTO t (label) VALUES ('a'), ('b')")
        db.exec("TRUNCATE TABLE t")
        db.run("INSERT INTO t (label) VALUES ('c')")
        assertEquals(listOf(listOf(int(3))), db.select("SELECT id FROM t").rows)
    }

    @Test
    fun truncateIsRestrictedByDefaultWhenAnotherTableHasAForeignKeyIntoIt() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        db.run("INSERT INTO departments VALUES (1)")
        db.run("INSERT INTO employees VALUES (1, 1)")
        val error = assertFailsWith<DiskCatalogException> { db.run("TRUNCATE TABLE departments") }
        assertTrue(error.message!!.contains("employees"), error.message)
        assertEquals(listOf(listOf(int(1))), db.select("SELECT id FROM departments").rows)
    }

    @Test
    fun truncateCascadeAlsoEmptiesEveryTransitiveReferencer() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        db.run("CREATE TABLE timesheets (id INTEGER PRIMARY KEY, employee_id INTEGER REFERENCES employees(id))")
        db.run("INSERT INTO departments VALUES (1)")
        db.run("INSERT INTO employees VALUES (1, 1)")
        db.run("INSERT INTO timesheets VALUES (1, 1)")
        val result = db.exec("TRUNCATE TABLE departments CASCADE") as TruncateTableResult
        assertEquals(setOf("departments", "employees", "timesheets"), result.tableNames.toSet())
        assertEquals(emptyList(), db.select("SELECT * FROM timesheets").rows)
    }

    @Test
    fun selfReferencingForeignKeyDoesNotBlockTruncatingItsOwnTable() {
        val db = newDatabase()
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, manager_id INTEGER REFERENCES employees(id))")
        db.run("INSERT INTO employees VALUES (1, NULL), (2, 1)")
        db.exec("TRUNCATE TABLE employees")
        assertEquals(emptyList(), db.select("SELECT * FROM employees").rows)
    }

    @Test
    fun truncateNeverFiresRowLevelTriggersUnlikeAWhereLessDelete() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE audit (n INTEGER)")
        db.run("INSERT INTO audit VALUES (0)")
        db.run("CREATE TRIGGER log_delete AFTER DELETE ON t FOR EACH ROW BEGIN UPDATE audit SET n = n + 1; END")
        db.run("INSERT INTO t VALUES (1), (2), (3)")
        db.exec("TRUNCATE TABLE t")
        assertEquals(listOf(listOf(int(0))), db.select("SELECT n FROM audit").rows)
        assertEquals(emptyList(), db.select("SELECT * FROM t").rows)
    }

    @Test
    fun truncateSurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO t VALUES (1), (2)")
        db.exec("TRUNCATE TABLE t")
        db.reopen()
        assertEquals(emptyList(), db.select("SELECT * FROM t").rows)
    }

    @Test
    fun truncateCanRunInsideAnExplicitTransactionAndSharesItsCommit() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO t VALUES (1)")
        db.run("BEGIN")
        db.exec("TRUNCATE TABLE t")
        db.run("COMMIT")
        assertEquals(emptyList(), db.select("SELECT * FROM t").rows)
    }

    @Test
    fun rollbackAfterTruncateInsideAnExplicitTransactionRestoresTheRows() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO t VALUES (1), (2)")
        db.run("BEGIN")
        db.exec("TRUNCATE TABLE t")
        db.run("ROLLBACK")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), db.select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun restartIdentityIsNotUndoneByARollbackUnlikeTheRowTruncationItself() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('seq'), label TEXT)")
        db.run("INSERT INTO t (label) VALUES ('a'), ('b')")
        db.run("BEGIN")
        db.exec("TRUNCATE TABLE t RESTART IDENTITY")
        db.run("ROLLBACK")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), db.select("SELECT id FROM t ORDER BY id").rows)
        val error = assertFailsWith<KestrelException> { db.run("INSERT INTO t (label) VALUES ('c')") }
        assertTrue(error.message!!.contains("id"), error.message)
    }
}
