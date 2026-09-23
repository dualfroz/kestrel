package kestrel.store.sql

import kestrel.KestrelException
import kestrel.engine.CommitResult
import kestrel.engine.CreateTableResult
import kestrel.engine.DeleteResult
import kestrel.engine.DropTableResult
import kestrel.engine.InsertResult
import kestrel.engine.RollbackResult
import kestrel.engine.RollbackToSavepointResult
import kestrel.engine.SavepointResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.UpdateResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiskExecutorTemporaryTableTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
            private set
        var executor = DiskExecutor(catalog)
            private set

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult
        fun rowCount(sql: String): Int = select(sql).rows.size

        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-temp-data"), tempFile("kestrel-disk-temp-wal"))

    @Test
    fun createTempTableStartsEmptyAndSupportsFullCrud() {
        val db = newDatabase()
        assertEquals(
            CreateTableResult("scratch"),
            db.run("CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY, note TEXT)").single()
        )
        assertEquals(SelectResult(listOf("id", "note"), emptyList()), db.select("SELECT * FROM scratch"))

        assertEquals(InsertResult(2, null), db.run("INSERT INTO scratch VALUES (1, 'a'), (2, 'b')").single())
        assertEquals(2, db.rowCount("SELECT * FROM scratch"))

        assertEquals(UpdateResult(1, null), db.run("UPDATE scratch SET note = 'z' WHERE id = 2").single())
        assertEquals(listOf(int(2), text("z")), db.select("SELECT * FROM scratch WHERE id = 2").rows.single())

        assertEquals(DeleteResult(1, null), db.run("DELETE FROM scratch WHERE id = 1").single())
        assertEquals(1, db.rowCount("SELECT * FROM scratch"))

        assertEquals(DropTableResult("scratch"), db.run("DROP TABLE scratch").single())
        assertFailsWith<KestrelException> { db.select("SELECT * FROM scratch") }
    }

    @Test
    fun createTemporaryTableKeywordIsAnAliasForTemp() {
        val db = newDatabase()
        db.run("CREATE TEMPORARY TABLE scratch (id INTEGER)")
        db.run("INSERT INTO scratch VALUES (1)")
        assertEquals(1, db.rowCount("SELECT * FROM scratch"))
    }

    @Test
    fun aTempTableNeverGetsADirectoryEntrySoItDoesNotSurviveAReopen() {
        val db = newDatabase()
        db.run("CREATE TABLE regular (id INTEGER PRIMARY KEY)")
        db.run("CREATE TEMP TABLE scratch (id INTEGER)")
        db.run("INSERT INTO regular VALUES (1)")
        db.run("INSERT INTO scratch VALUES (99)")
        assertEquals(setOf("regular", "scratch"), db.catalog.tableNames.toSet())

        db.reopen()

        assertEquals(listOf("regular"), db.catalog.tableNames)
        assertEquals(1, db.rowCount("SELECT * FROM regular"))
        assertFailsWith<KestrelException> { db.select("SELECT * FROM scratch") }
    }

    @Test
    fun tableNamesListsBothRegularAndTemporaryTables() {
        val db = newDatabase()
        db.run("CREATE TABLE regular (id INTEGER)")
        db.run("CREATE TEMP TABLE scratch (id INTEGER)")
        assertEquals(setOf("regular", "scratch"), db.catalog.tableNames.toSet())
    }

    @Test
    fun aTemporaryTableCannotShadowOrCollideWithAnExistingTableOrView() {
        val db = newDatabase()
        db.run("CREATE TABLE regular (id INTEGER)")
        assertFailsWith<KestrelException> { db.run("CREATE TEMP TABLE regular (id INTEGER)") }

        db.run("CREATE TEMP TABLE scratch (id INTEGER)")
        assertFailsWith<KestrelException> { db.run("CREATE TABLE scratch (id INTEGER)") }
        assertFailsWith<KestrelException> { db.run("CREATE TEMP TABLE scratch (id INTEGER)") }

        db.run("CREATE VIEW a_view AS SELECT * FROM regular")
        assertFailsWith<KestrelException> { db.run("CREATE TEMP TABLE a_view (id INTEGER)") }
    }

    @Test
    fun notNullPrimaryKeyUniqueAndCheckAreAllEnforcedOnATemporaryTable() {
        val db = newDatabase()
        db.run(
            "CREATE TEMP TABLE scratch (" +
                "id INTEGER PRIMARY KEY, sku TEXT UNIQUE, qty INTEGER NOT NULL, CHECK (qty >= 0))"
        )
        db.run("INSERT INTO scratch VALUES (1, 'a', 5)")

        assertFailsWith<KestrelException> { db.run("INSERT INTO scratch VALUES (1, 'b', 1)") }
        assertFailsWith<KestrelException> { db.run("INSERT INTO scratch VALUES (2, 'a', 1)") }
        assertFailsWith<KestrelException> { db.run("INSERT INTO scratch (id, sku) VALUES (3, 'c')") }
        assertFailsWith<KestrelException> { db.run("INSERT INTO scratch VALUES (4, 'd', -1)") }

        assertEquals(1, db.rowCount("SELECT * FROM scratch"))
    }

    @Test
    fun defaultAndGeneratedColumnsAreEvaluatedOnATemporaryTable() {
        val db = newDatabase()
        db.run(
            "CREATE TEMP TABLE scratch (" +
                "id INTEGER, price INTEGER DEFAULT 10, doubled INTEGER GENERATED ALWAYS AS (price * 2) STORED)"
        )
        db.run("INSERT INTO scratch (id) VALUES (1)")
        assertEquals(listOf(int(1), int(10), int(20)), db.select("SELECT * FROM scratch").rows.single())

        assertFailsWith<KestrelException> { db.run("INSERT INTO scratch (id, doubled) VALUES (2, 99)") }
    }

    @Test
    fun onConflictOnATemporaryTableIsRejected() {
        val db = newDatabase()
        db.run("CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO scratch VALUES (1)")
        assertFailsWith<KestrelException> { db.run("INSERT INTO scratch VALUES (1) ON CONFLICT (id) DO NOTHING") }
    }

    @Test
    fun foreignKeyOnATemporaryTableIsRejectedAtCreateTime() {
        val db = newDatabase()
        db.run("CREATE TABLE parent (id INTEGER PRIMARY KEY)")
        assertFailsWith<KestrelException> {
            db.run("CREATE TEMP TABLE child (id INTEGER, parent_id INTEGER REFERENCES parent(id))")
        }
    }

    @Test
    fun createIndexAndCreateTriggerOnATemporaryTableAreRejected() {
        val db = newDatabase()
        db.run("CREATE TEMP TABLE scratch (id INTEGER)")
        assertFailsWith<KestrelException> { db.run("CREATE INDEX scratch_idx ON scratch(id)") }
        assertFailsWith<KestrelException> {
            db.run("CREATE TRIGGER scratch_trg AFTER INSERT ON scratch FOR EACH ROW INSERT INTO scratch VALUES (0)")
        }
    }

    @Test
    fun aTemporaryTableJoinsAndCorrelatesWithARealDiskTable() {
        val db = newDatabase()
        db.run("CREATE TABLE orders (id INTEGER PRIMARY KEY, total INTEGER)")
        db.run("INSERT INTO orders VALUES (1, 100), (2, 200)")
        db.run("CREATE TEMP TABLE flagged (order_id INTEGER)")
        db.run("INSERT INTO flagged VALUES (2)")

        val joined = db.select(
            "SELECT orders.id, orders.total FROM orders JOIN flagged ON orders.id = flagged.order_id"
        )
        assertEquals(listOf(listOf(int(2), int(200))), joined.rows)

        val correlated = db.select(
            "SELECT id FROM orders WHERE EXISTS (SELECT 1 FROM flagged WHERE flagged.order_id = orders.id)"
        )
        assertEquals(listOf(listOf(int(2))), correlated.rows)
    }

    @Test
    fun rollbackUndoesWritesToATemporaryTableEvenThoughItNeverTouchesTheWal() {
        val db = newDatabase()
        db.run("CREATE TEMP TABLE scratch (id INTEGER)")
        db.run("INSERT INTO scratch VALUES (1)")

        db.run("BEGIN")
        db.run("INSERT INTO scratch VALUES (2), (3)")
        db.run("DELETE FROM scratch WHERE id = 1")
        assertEquals(2, db.rowCount("SELECT * FROM scratch"))
        assertEquals(RollbackResult, db.run("ROLLBACK").single())

        assertEquals(1, db.rowCount("SELECT * FROM scratch"))
        assertEquals(listOf(int(1)), db.select("SELECT * FROM scratch").rows.single())
    }

    @Test
    fun commitKeepsWritesToATemporaryTable() {
        val db = newDatabase()
        db.run("CREATE TEMP TABLE scratch (id INTEGER)")
        db.run("BEGIN")
        db.run("INSERT INTO scratch VALUES (1)")
        assertEquals(CommitResult, db.run("COMMIT").single())
        assertEquals(1, db.rowCount("SELECT * FROM scratch"))
    }

    @Test
    fun rollbackToSavepointUndoesOnlyTemporaryTableWritesAfterIt() {
        val db = newDatabase()
        db.run("CREATE TEMP TABLE scratch (id INTEGER)")
        db.run("BEGIN")
        db.run("INSERT INTO scratch VALUES (1)")
        assertEquals(SavepointResult("mark"), db.run("SAVEPOINT mark").single())
        db.run("INSERT INTO scratch VALUES (2)")
        assertEquals(2, db.rowCount("SELECT * FROM scratch"))

        assertEquals(RollbackToSavepointResult("mark"), db.run("ROLLBACK TO SAVEPOINT mark").single())
        assertEquals(1, db.rowCount("SELECT * FROM scratch"))
        assertEquals(CommitResult, db.run("COMMIT").single())
        assertEquals(1, db.rowCount("SELECT * FROM scratch"))
    }

    @Test
    fun rollbackAlsoUndoesARealDiskTableWriteInTheSameTransactionAsATemporaryTableWrite() {
        val db = newDatabase()
        db.run("CREATE TABLE regular (id INTEGER PRIMARY KEY)")
        db.run("CREATE TEMP TABLE scratch (id INTEGER)")
        db.run("BEGIN")
        db.run("INSERT INTO regular VALUES (1)")
        db.run("INSERT INTO scratch VALUES (1)")
        db.run("ROLLBACK")
        assertEquals(0, db.rowCount("SELECT * FROM regular"))
        assertEquals(0, db.rowCount("SELECT * FROM scratch"))
    }

    @Test
    fun returningWorksOnATemporaryTable() {
        val db = newDatabase()
        db.run("CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY, note TEXT)")
        val inserted = db.run("INSERT INTO scratch VALUES (1, 'a') RETURNING id, note").single() as InsertResult
        assertEquals(SelectResult(listOf("id", "note"), listOf(listOf(int(1), text("a")))), inserted.returning)

        val updated = db.run("UPDATE scratch SET note = 'b' WHERE id = 1 RETURNING note").single() as UpdateResult
        assertEquals(SelectResult(listOf("note"), listOf(listOf(text("b")))), updated.returning)

        val deleted = db.run("DELETE FROM scratch WHERE id = 1 RETURNING id").single() as DeleteResult
        assertEquals(SelectResult(listOf("id"), listOf(listOf(int(1)))), deleted.returning)
    }

    @Test
    fun dropTableRemovesATemporaryTableWithoutAffectingCatalogTables() {
        val db = newDatabase()
        db.run("CREATE TABLE regular (id INTEGER)")
        db.run("CREATE TEMP TABLE scratch (id INTEGER)")
        db.run("DROP TABLE scratch")
        assertEquals(listOf("regular"), db.catalog.tableNames)
        assertFalse(db.catalog.tableNames.contains("scratch"))
        assertTrue(db.catalog.tableOrNull("regular") != null)
    }
}
