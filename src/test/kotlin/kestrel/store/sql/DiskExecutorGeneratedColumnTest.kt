package kestrel.store.sql

import kestrel.KestrelException
import kestrel.engine.MergeResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiskExecutorGeneratedColumnTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun int(value: Long) = Value.IntegerValue(value)

    private class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
            private set
        var executor = DiskExecutor(catalog)
            private set

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun exec(sql: String): StatementResult = run(sql).single()
        fun select(sql: String): SelectResult = exec(sql) as SelectResult

        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-generated-data"), tempFile("kestrel-disk-generated-wal"))

    @Test
    fun generatedColumnIsComputedWhenOmittedFromInsert() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        db.run("INSERT INTO t (a) VALUES (5)")
        assertEquals(listOf(listOf(int(5), int(10))), db.select("SELECT a, b FROM t").rows)
    }

    @Test
    fun insertRejectsAnExplicitValueForAGeneratedColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        val error = assertFailsWith<KestrelException> { db.run("INSERT INTO t (a, b) VALUES (5, 99)") }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    @Test
    fun updatingADependencyColumnRecomputesTheGeneratedColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        db.run("INSERT INTO t (a) VALUES (5)")
        db.run("UPDATE t SET a = 9")
        assertEquals(listOf(listOf(int(18))), db.select("SELECT b FROM t").rows)
    }

    @Test
    fun updateRejectsAnExplicitValueForAGeneratedColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        db.run("INSERT INTO t (a) VALUES (5)")
        assertFailsWith<KestrelException> { db.run("UPDATE t SET b = 1") }
    }

    @Test
    fun onConflictDoUpdateRecomputesTheGeneratedColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        db.run("INSERT INTO t (id, a) VALUES (1, 5)")
        db.run("INSERT INTO t (id, a) VALUES (1, 20) ON CONFLICT (id) DO UPDATE SET a = EXCLUDED.a")
        assertEquals(listOf(listOf(int(40))), db.select("SELECT b FROM t").rows)
    }

    @Test
    fun mergeInsertComputesTheGeneratedColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        db.run("CREATE TABLE s (id INTEGER, a INTEGER)")
        db.run("INSERT INTO s VALUES (1, 5)")
        val result = db.exec(
            "MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN INSERT (id, a) VALUES (s.id, s.a)"
        ) as MergeResult
        assertEquals(MergeResult(1, 0, 0), result)
        assertEquals(listOf(listOf(int(10))), db.select("SELECT b FROM t").rows)
    }

    @Test
    fun mergeUpdateRecomputesTheGeneratedColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        db.run("CREATE TABLE s (id INTEGER, a INTEGER)")
        db.run("INSERT INTO t (id, a) VALUES (1, 5)")
        db.run("INSERT INTO s VALUES (1, 9)")
        db.exec("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = s.a")
        assertEquals(listOf(listOf(int(18))), db.select("SELECT b FROM t").rows)
    }

    @Test
    fun rejectsGeneratedExpressionReferencingANonexistentColumn() {
        val db = newDatabase()
        val error = assertFailsWith<KestrelException> {
            db.run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (missing) STORED)")
        }
        assertTrue(error.message!!.contains("no such column"))
    }

    @Test
    fun rejectsAGeneratedColumnReferencingAnotherGeneratedColumn() {
        val db = newDatabase()
        val error = assertFailsWith<KestrelException> {
            db.run(
                "CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED, " +
                    "c INTEGER GENERATED ALWAYS AS (b + 1) STORED)"
            )
        }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    @Test
    fun renameColumnPropagatesIntoADependentGeneratedExpression() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        db.run("ALTER TABLE t RENAME COLUMN a TO a2")
        db.run("INSERT INTO t (a2) VALUES (5)")
        assertEquals(listOf(listOf(int(10))), db.select("SELECT b FROM t").rows)
    }

    @Test
    fun dropColumnRestrictsWhenAGeneratedColumnDependsOnIt() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE t DROP COLUMN a") }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    @Test
    fun dropColumnCascadeRemovesTheDependentGeneratedColumnToo() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED, c TEXT)")
        db.run("INSERT INTO t (a, c) VALUES (5, 'x')")
        db.run("ALTER TABLE t DROP COLUMN a CASCADE")
        val result = db.select("SELECT * FROM t")
        assertEquals(listOf("c"), result.columns)
        assertEquals(listOf(listOf(Value.TextValue("x"))), result.rows)
    }

    @Test
    fun generatedColumnSurvivesAReopen() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        db.run("INSERT INTO t (a) VALUES (5)")
        db.reopen()
        assertEquals(listOf(listOf(int(5), int(10))), db.select("SELECT a, b FROM t").rows)
        db.run("INSERT INTO t (a) VALUES (7)")
        assertEquals(listOf(listOf(int(14))), db.select("SELECT b FROM t WHERE a = 7").rows)
    }

    @Test
    fun alterTableAddColumnGeneratedBackfillsEachExistingRowFromItsOwnOtherColumns() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER)")
        db.run("INSERT INTO t (a) VALUES (1), (2), (3)")
        db.run("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (a * 2) STORED")
        assertEquals(
            listOf(listOf(int(1), int(2)), listOf(int(2), int(4)), listOf(int(3), int(6))),
            db.select("SELECT a, b FROM t ORDER BY a ASC").rows
        )
    }

    @Test
    fun afterAlterTableAddColumnGeneratedAnInsertStillRejectsAnExplicitValue() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER)")
        db.run("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (a * 2) STORED")
        val error = assertFailsWith<KestrelException> { db.run("INSERT INTO t (a, b) VALUES (5, 99)") }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    @Test
    fun alterTableAddColumnGeneratedNotNullSkipsTheDefaultRequirementOnANonEmptyTable() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER)")
        db.run("INSERT INTO t (a) VALUES (1), (2)")
        db.run("ALTER TABLE t ADD COLUMN b INTEGER NOT NULL GENERATED ALWAYS AS (a * 2) STORED")
        assertEquals(listOf(listOf(int(2)), listOf(int(4))), db.select("SELECT b FROM t ORDER BY a ASC").rows)
    }

    @Test
    fun alterTableAddColumnGeneratedNotNullRejectsARowWhoseBackfilledValueIsNull() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER)")
        db.run("INSERT INTO t (a) VALUES (NULL)")
        val error = assertFailsWith<KestrelException> {
            db.run("ALTER TABLE t ADD COLUMN b INTEGER NOT NULL GENERATED ALWAYS AS (a) STORED")
        }
        assertTrue(error.message!!.contains("NOT NULL"))
    }

    @Test
    fun alterTableAddColumnGeneratedRejectsReferencingAnAlreadyExistingGeneratedColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        val error = assertFailsWith<KestrelException> {
            db.run("ALTER TABLE t ADD COLUMN c INTEGER GENERATED ALWAYS AS (b + 1) STORED")
        }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    @Test
    fun alterTableAddColumnGeneratedSurvivesAReopenAndBackfilledRowsKeepTheirValue() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER)")
        db.run("INSERT INTO t (a) VALUES (5)")
        db.run("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (a * 2) STORED")
        db.reopen()
        assertEquals(listOf(listOf(int(5), int(10))), db.select("SELECT a, b FROM t").rows)
        db.run("INSERT INTO t (a) VALUES (7)")
        assertEquals(listOf(listOf(int(14))), db.select("SELECT b FROM t WHERE a = 7").rows)
    }
}
