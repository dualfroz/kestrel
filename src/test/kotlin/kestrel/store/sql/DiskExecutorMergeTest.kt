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

/**
 * `MERGE INTO ... USING ... ON ... WHEN [NOT] MATCHED ...` through [DiskExecutor] against a real
 * [DiskCatalog] backed by temp files - see [DiskExecutorTest] for the general end-to-end pattern this
 * reuses, and [kestrel.MergeTest] for the in-memory engine's equivalent scenarios, ported 1:1 wherever
 * they translate (every one of them does; the disk-backed engine has no scope cut left for MERGE).
 * [mergeSurvivesClosingAndReopeningTheCatalog] has no in-memory equivalent - it exercises the one thing
 * only the disk-backed engine's persistence layer can demonstrate.
 */
class DiskExecutorMergeTest {
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

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-merge-data"), tempFile("kestrel-disk-merge-wal"))

    private fun Database.setUpAccountsAndStaging() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER NOT NULL)")
        run("CREATE TABLE staging (id INTEGER, amount INTEGER)")
    }

    @Test
    fun matchedRowIsUpdated() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 50)")
        val result = db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount"
        ) as MergeResult
        assertEquals(MergeResult(0, 1, 0), result)
        assertEquals(listOf(listOf(int(150))), db.select("SELECT balance FROM accounts").rows)
    }

    @Test
    fun unmatchedRowIsInserted() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO staging VALUES (7, 30)")
        val result = db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount)"
        ) as MergeResult
        assertEquals(MergeResult(1, 0, 0), result)
        assertEquals(listOf(listOf(int(7), int(30))), db.select("SELECT id, balance FROM accounts").rows)
    }

    @Test
    fun matchedRowIsDeleted() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 0)")
        val result = db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id WHEN MATCHED THEN DELETE"
        ) as MergeResult
        assertEquals(MergeResult(0, 0, 1), result)
        assertEquals(emptyList(), db.select("SELECT * FROM accounts").rows)
    }

    @Test
    fun aMultiRowMergeInsertsUpdatesAndDeletesInOneStatement() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100), (2, 200)")
        db.run("INSERT INTO staging VALUES (1, 10), (2, -999), (3, 5)")
        val result = db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED AND accounts.balance + staging.amount < 0 THEN DELETE " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount)"
        ) as MergeResult
        assertEquals(MergeResult(1, 1, 1), result)
        assertEquals(
            listOf(listOf(int(1), int(110)), listOf(int(3), int(5))),
            db.select("SELECT id, balance FROM accounts ORDER BY id").rows
        )
    }

    @Test
    fun firstSatisfiedWhenMatchedClauseWinsInWrittenOrder() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 5)")
        db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED AND staging.amount > 100 THEN DELETE " +
                "WHEN MATCHED AND staging.amount > 0 THEN UPDATE SET balance = 1 " +
                "WHEN MATCHED THEN UPDATE SET balance = 2"
        )
        assertEquals(listOf(listOf(int(1))), db.select("SELECT balance FROM accounts").rows)
    }

    @Test
    fun firstSatisfiedWhenNotMatchedClauseWinsInWrittenOrder() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO staging VALUES (1, 500)")
        db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN NOT MATCHED AND staging.amount > 1000 THEN INSERT (id, balance) VALUES (staging.id, 1) " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, 2)"
        )
        assertEquals(listOf(listOf(int(1), int(2))), db.select("SELECT id, balance FROM accounts").rows)
    }

    @Test
    fun noSatisfiedWhenClauseLeavesTheRowUntouched() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 5)")
        val result = db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED AND staging.amount > 1000 THEN UPDATE SET balance = 0"
        ) as MergeResult
        assertEquals(MergeResult(0, 0, 0), result)
        assertEquals(listOf(listOf(int(100))), db.select("SELECT balance FROM accounts").rows)
    }

