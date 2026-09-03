package kestrel.store.sql

import kestrel.KestrelException
import kestrel.engine.AlterTableResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `ALTER TABLE t ALTER COLUMN col TYPE ...` on [DiskExecutor] - mirrors
 * [kestrel.AlterTableTest]'s in-memory `ChangeColumnType` cases. Built on [DiskTable.rewriteRows]
 * exactly like `ADD COLUMN` (see [DiskCatalog.changeColumnType]'s own doc): every row's stored
 * bytes are rewritten in place under a single WAL transaction, so a NOT NULL/CHECK failure
 * discovered partway through a multi-row rewrite aborts the whole transaction rather than leaving
 * some rows converted and others not - the disk-specific angle [kestrel.AlterTableTest] cannot
 * exercise at all, since the in-memory engine has no WAL to roll back.
 */
class DiskExecutorChangeColumnTypeTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)
    private fun real(value: Double) = Value.RealValue(value)
    private fun decimal(value: String) = Value.DecimalValue(BigDecimal(value))

    private class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
            private set
        var executor = DiskExecutor(catalog)
            private set

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun exec(sql: String): StatementResult = run(sql).single()
        fun select(sql: String): SelectResult = exec(sql) as SelectResult

        /** Closes and reopens the catalog against the same files, simulating a fresh process. */
        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-alter-type-data"), tempFile("kestrel-disk-alter-type-wal"))

    @Test
    fun changeColumnTypeWideningIntegerToRealCoercesExistingRowsWithNoExplicitUsing() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, score INTEGER)")
        db.run("INSERT INTO t VALUES (1, 10), (2, 20)")
        val result = db.exec("ALTER TABLE t ALTER COLUMN score TYPE REAL")
        assertEquals(AlterTableResult("t"), result)
        assertEquals(listOf(listOf(real(10.0)), listOf(real(20.0))), db.select("SELECT score FROM t ORDER BY id ASC").rows)
    }

    @Test
    fun changeColumnTypeWideningIntegerToDecimalCoercesExistingRows() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER)")
        db.run("INSERT INTO t VALUES (1, 42)")
        db.exec("ALTER TABLE t ALTER COLUMN amount TYPE DECIMAL(10, 2)")
        assertEquals(listOf(listOf(decimal("42.00"))), db.select("SELECT amount FROM t").rows)
    }

    @Test
    fun changeColumnTypeWithUsingConvertsTextToIntegerViaCast() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, code TEXT)")
        db.run("INSERT INTO t VALUES (1, '100'), (2, '200')")
        db.exec("ALTER TABLE t ALTER COLUMN code TYPE INTEGER USING CAST(code AS INTEGER)")
        assertEquals(listOf(listOf(int(100)), listOf(int(200))), db.select("SELECT code FROM t ORDER BY id ASC").rows)
    }

    @Test
    fun changeColumnTypeWithUsingCanReferenceTheRowsOtherColumns() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, price INTEGER, quantity INTEGER)")
        db.run("INSERT INTO t VALUES (1, 10, 3)")
        db.exec("ALTER TABLE t ALTER COLUMN price TYPE INTEGER USING price * quantity")
        assertEquals(listOf(listOf(int(30))), db.select("SELECT price FROM t").rows)
    }

    @Test
    fun changeColumnTypeRejectsImplicitCoercionWhenTypesAreIncompatibleAndNoUsingIsGiven() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, code TEXT)")
        db.run("INSERT INTO t VALUES (1, 'abc')")
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE t ALTER COLUMN code TYPE INTEGER") }
        assertTrue(error.message!!.contains("type mismatch"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsUnknownColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE t ALTER COLUMN missing TYPE TEXT") }
        assertTrue(error.message!!.contains("no such column"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsGeneratedColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, price INTEGER, doubled INTEGER GENERATED ALWAYS AS (price * 2) STORED)")
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE t ALTER COLUMN doubled TYPE REAL") }
        assertTrue(error.message!!.contains("GENERATED"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsPrimaryKeyColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE t ALTER COLUMN id TYPE TEXT") }
        assertTrue(error.message!!.contains("PRIMARY KEY"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsColumnUsedByItsOwnForeignKey() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER, FOREIGN KEY (dept_id) REFERENCES departments (id))")
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE employees ALTER COLUMN dept_id TYPE TEXT") }
        assertTrue(error.message!!.contains("FOREIGN KEY"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsColumnReferencedByAnotherTablesForeignKey() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY, code TEXT, CONSTRAINT uq_code UNIQUE (code))")
        db.run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_code TEXT, " +
                "FOREIGN KEY (dept_code) REFERENCES departments (code))"
        )
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE departments ALTER COLUMN code TYPE INTEGER") }
        assertTrue(error.message!!.contains("employees"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsColumnUsedByAUniqueConstraint() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT, CONSTRAINT uq_email UNIQUE (email))")
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE t ALTER COLUMN email TYPE TEXT") }
        assertTrue(error.message!!.contains("UNIQUE"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsColumnWithASecondaryIndex() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("CREATE INDEX idx_name ON t (name)")
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE t ALTER COLUMN name TYPE TEXT") }
        assertTrue(error.message!!.contains("idx_name"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsWhenUsingProducesNullForANotNullColumnAndLeavesTheTableUnchanged() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, code TEXT NOT NULL)")
        db.run("INSERT INTO t VALUES (1, '100'), (2, '')")
        val error = assertFailsWith<KestrelException> {
            db.run("ALTER TABLE t ALTER COLUMN code TYPE INTEGER USING CASE WHEN code = '' THEN NULL ELSE CAST(code AS INTEGER) END")
        }
        assertTrue(error.message!!.contains("NOT NULL"), error.message!!)
        assertEquals(listOf(listOf(text("100")), listOf(text(""))), db.select("SELECT code FROM t ORDER BY id ASC").rows)
    }

    /**
     * The disk-specific proof [kestrel.AlterTableTest] cannot give: row 1's converted value (20)
     * would already have been written to its heap page by [DiskTable.rewriteRows] before row 2's
     * CHECK violation is discovered and thrown - if the surrounding transaction were not aborted,
     * row 1 would be left silently converted while row 2 was not. Asserting BOTH rows still hold
     * their ORIGINAL, unconverted values after the failure is the proof that [DiskCatalog.changeColumnType]'s
     * `catch { engine.abort(txn) }` really unwinds a partial per-row rewrite already applied to disk.
     */
    @Test
    fun changeColumnTypeRevalidatesACheckConstraintAgainstTheNewValuesAndLeavesTheTableUnchangedOnFailure() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER, CHECK (amount <= 100))")
        db.run("INSERT INTO t VALUES (1, 10), (2, 60)")
        val error = assertFailsWith<KestrelException> {
            db.run("ALTER TABLE t ALTER COLUMN amount TYPE INTEGER USING amount * 2")
        }
        assertTrue(error.message!!.contains("CHECK constraint"), error.message!!)
        assertEquals(
            listOf(listOf(int(1), int(10)), listOf(int(2), int(60))),
            db.select("SELECT id, amount FROM t ORDER BY id ASC").rows
        )
    }

    @Test
    fun changeColumnTypeSucceedsAndAppliesUsingToEveryRowWhenAllSatisfyAnExistingCheckConstraint() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER, CHECK (amount <= 100))")
        db.run("INSERT INTO t VALUES (1, 10), (2, 20)")
        db.exec("ALTER TABLE t ALTER COLUMN amount TYPE INTEGER USING amount * 2")
        assertEquals(listOf(listOf(int(20)), listOf(int(40))), db.select("SELECT amount FROM t ORDER BY id ASC").rows)
    }

    @Test
    fun changeColumnTypeRejectsAnIncompatibleDefaultExpressionUpFront() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'pending')")
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE t ALTER COLUMN status TYPE INTEGER USING 0") }
        assertTrue(error.message!!.contains("DEFAULT"), error.message!!)
    }

    @Test
    fun changeColumnTypeAcceptsAnOptionalColumnKeyword() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER)")
        db.run("INSERT INTO t VALUES (1, 5)")
        db.exec("ALTER TABLE t ALTER amount TYPE REAL")
        assertEquals(listOf(listOf(real(5.0))), db.select("SELECT amount FROM t").rows)
    }

    /** The type change - and the converted data - must survive a full process close/reopen, not just live in the still-open transaction's page cache. */
    @Test
    fun changeColumnTypeSurvivesReopen() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER)")
        db.run("INSERT INTO t VALUES (1, 42)")
        db.exec("ALTER TABLE t ALTER COLUMN amount TYPE DECIMAL(10, 2)")
        db.reopen()
        assertEquals(listOf(listOf(decimal("42.00"))), db.select("SELECT amount FROM t").rows)
        val error = assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (2, 'not-a-number')") }
        assertTrue(error.message!!.contains("type mismatch"), error.message!!)
    }

    @Test
    fun changeColumnTypePreservesNotNullAndStillEnforcesItGoingForward() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)")
        db.run("INSERT INTO t VALUES (1, 10)")
        db.exec("ALTER TABLE t ALTER COLUMN amount TYPE REAL")
        val error = assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (2, NULL)") }
        assertTrue(error.message!!.contains("NOT NULL"), error.message!!)
    }
}
