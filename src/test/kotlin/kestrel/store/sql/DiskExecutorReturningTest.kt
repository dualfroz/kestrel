package kestrel.store.sql

import kestrel.KestrelException
import kestrel.engine.DeleteResult
import kestrel.engine.InsertResult
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
import kotlin.test.assertNull

class DiskExecutorReturningTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

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

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-returning-data"), tempFile("kestrel-disk-returning-wal"))

    private fun Database.seed() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL)")
        run("INSERT INTO accounts VALUES (1, 'ada', 100)")
    }

    @Test
    fun insertReturningStarReportsTheFullInsertedRow() {
        val db = newDatabase()
        db.seed()
        val result = db.exec("INSERT INTO accounts VALUES (2, 'linus', 200) RETURNING *") as InsertResult
        assertEquals(1, result.count)
        assertEquals(listOf("id", "name", "balance"), result.returning!!.columns)
        assertEquals(listOf(listOf(int(2), text("linus"), int(200))), result.returning!!.rows)
    }

    @Test
    fun insertReturningExpressionListWithAliasProjectsAndLabels() {
        val db = newDatabase()
        db.seed()
        val result = db.exec("INSERT INTO accounts VALUES (2, 'linus', 200) RETURNING id, balance * 2 AS doubled") as InsertResult
        assertEquals(listOf("id", "doubled"), result.returning!!.columns)
        assertEquals(listOf(listOf(int(2), int(400))), result.returning!!.rows)
    }

    @Test
    fun insertWithoutReturningLeavesReturningNull() {
        val db = newDatabase()
        db.seed()
        val result = db.exec("INSERT INTO accounts VALUES (2, 'linus', 200)") as InsertResult
        assertNull(result.returning)
    }

    @Test
    fun onConflictDoNothingContributesNoRowToReturning() {
        val db = newDatabase()
        db.seed()
        val result = db.exec(
            "INSERT INTO accounts VALUES (1, 'ignored', 999) ON CONFLICT (id) DO NOTHING RETURNING *"
        ) as InsertResult
        assertEquals(0, result.count)
        assertEquals(emptyList(), result.returning!!.rows)
    }

    @Test
    fun onConflictDoUpdateReturnsThePostUpdateRowNotTheProposedOne() {
        val db = newDatabase()
        db.seed()
        val result = db.exec(
            "INSERT INTO accounts VALUES (1, 'ada', 50) ON CONFLICT (id) DO UPDATE SET balance = accounts.balance + excluded.balance RETURNING balance"
        ) as InsertResult
        assertEquals(1, result.count)
        assertEquals(listOf(listOf(int(150))), result.returning!!.rows)
    }

    @Test
    fun mixedConflictAndFreshRowsInOneStatementReturnBothKinds() {
        val db = newDatabase()
        db.seed()
        val result = db.exec(
            "INSERT INTO accounts VALUES (1, 'ada', 5), (2, 'linus', 200) " +
                "ON CONFLICT (id) DO UPDATE SET balance = accounts.balance + excluded.balance RETURNING id, balance"
        ) as InsertResult
        assertEquals(
            listOf(listOf(int(1), int(105)), listOf(int(2), int(200))),
            result.returning!!.rows
        )
    }

    @Test
    fun updateReturningReportsThePostUpdateValuesForEveryMatchedRow() {
        val db = newDatabase()
        db.seed()
        db.run("INSERT INTO accounts VALUES (2, 'linus', 200)")
        val result = db.exec("UPDATE accounts SET balance = balance + 10 RETURNING id, balance") as UpdateResult
        assertEquals(2, result.count)
        assertEquals(listOf(listOf(int(1), int(110)), listOf(int(2), int(210))), result.returning!!.rows)
    }

    @Test
    fun updateReturningWithNoMatchesReturnsAnEmptyResultNotNull() {
        val db = newDatabase()
        db.seed()
        val result = db.exec("UPDATE accounts SET balance = 0 WHERE id = 999 RETURNING id") as UpdateResult
        assertEquals(0, result.count)
        assertEquals(emptyList(), result.returning!!.rows)
    }

    @Test
    fun deleteReturningReportsThePreDeletionRowValues() {
        val db = newDatabase()
        db.seed()
        db.run("INSERT INTO accounts VALUES (2, 'linus', 200)")
        val result = db.exec("DELETE FROM accounts WHERE id = 1 RETURNING *") as DeleteResult
        assertEquals(1, result.count)
        assertEquals(listOf(listOf(int(1), text("ada"), int(100))), result.returning!!.rows)
        assertEquals(1, db.select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun returningRejectsAnUnknownColumnEvenWhenZeroRowsMatch() {
        val db = newDatabase()
        db.seed()
        assertFailsWith<KestrelException> {
            db.exec("UPDATE accounts SET balance = 0 WHERE id = 999 RETURNING nope")
        }
    }

    @Test
    fun returningRejectsAnAggregateFunction() {
        val db = newDatabase()
        db.seed()
        assertFailsWith<KestrelException> {
            db.exec("DELETE FROM accounts WHERE id = 1 RETURNING COUNT(*)")
        }
    }

    @Test
    fun returningIsRejectedInsideATriggerBodyStatement() {
        val db = newDatabase()
        db.run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER)")
        db.run("CREATE TABLE audit (order_id INTEGER)")
        assertFailsWith<KestrelException> {
            db.run(
                "CREATE TRIGGER log_insert AFTER INSERT ON orders FOR EACH ROW " +
                    "BEGIN INSERT INTO audit VALUES (NEW.id) RETURNING order_id; END"
            )
        }
    }

    @Test
    fun aTableWithATriggerStillHonoursReturningOnTheOutermostStatement() {
        val db = newDatabase()
        db.run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER)")
        db.run("CREATE TABLE audit (order_id INTEGER)")
        db.run(
            "CREATE TRIGGER log_insert AFTER INSERT ON orders FOR EACH ROW " +
                "BEGIN INSERT INTO audit VALUES (NEW.id); END"
        )
        val result = db.exec("INSERT INTO orders VALUES (1, 100) RETURNING id, amount") as InsertResult
        assertEquals(listOf(listOf(int(1), int(100))), result.returning!!.rows)
        assertEquals(listOf(listOf(int(1))), db.select("SELECT order_id FROM audit").rows)
    }

    @Test
    fun returningSurvivesAReopen() {
        val db = newDatabase()
        db.seed()
        val inserted = db.exec("INSERT INTO accounts VALUES (2, 'linus', 200) RETURNING *") as InsertResult
        assertEquals(listOf(listOf(int(2), text("linus"), int(200))), inserted.returning!!.rows)
        db.reopen()
        val deleted = db.exec("DELETE FROM accounts WHERE id = 2 RETURNING id") as DeleteResult
        assertEquals(listOf(listOf(int(2))), deleted.returning!!.rows)
        assertEquals(1, db.select("SELECT * FROM accounts").rows.size)
    }
}
