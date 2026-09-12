package kestrel.store.sql

import kestrel.engine.BeginResult
import kestrel.engine.CommitResult
import kestrel.engine.RollbackResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.ast.Statement
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
 * Exercises `BEGIN`/`COMMIT`/`ROLLBACK` through [DiskExecutor], parsing real SQL text through the
 * same [Lexer]/[Parser] the in-memory engine uses - see [DiskExecutorTest] for that general pattern.
 * Unlike [kestrel.TransactionTest] (the in-memory engine's equivalent, backed by a whole-catalog
 * snapshot/restore that also undoes DDL), an explicit transaction here is the storage engine's own
 * WAL/2PL transaction shared across statements via [DiskExecutor.execute] until `COMMIT`/`ROLLBACK`
 * (see [DiskExecutor.executeBegin]) - so it can only wrap DML/SELECT, survives a process restart once
 * committed, and actually holds locks across the gap between two SQL statements, which
 * [locksAcquiredByAnOpenTransactionBlockAConcurrentWriterUntilItEnds] proves end to end.
 */
class DiskTransactionTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun parse(sql: String): List<Statement> = Parser(Lexer(sql).tokenize()).parseProgram()

    private fun int(value: Long) = Value.IntegerValue(value)

    private class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
            private set
        var executor = DiskExecutor(catalog)
            private set

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult

        /** A second executor sharing this database's still-open [catalog] - a second concurrent session. */
        fun secondExecutor(): DiskExecutor = DiskExecutor(catalog)

        /** Closes and reopens the catalog against the same files, simulating a fresh process. */
        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-txn-data"), tempFile("kestrel-disk-txn-wal"))

    private fun Database.seedAccounts() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 100), (2, 200)")
    }

    private fun Database.balanceOf(id: Long): Long =
        (select("SELECT balance FROM accounts WHERE id = $id").rows.single().single() as Value.IntegerValue).value

    private fun Database.rowCount(sql: String): Int = select(sql).rows.size

    @Test
    fun beginReturnsBeginResultAndCommitAppliesEveryStatementInBetween() {
        val db = newDatabase()
        db.seedAccounts()

        assertEquals(BeginResult, db.run("BEGIN").single())
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("UPDATE accounts SET balance = 0 WHERE id = 1")
        db.run("DELETE FROM accounts WHERE id = 2")
        assertEquals(CommitResult, db.run("COMMIT").single())

        assertEquals(2, db.rowCount("SELECT * FROM accounts"))
        assertEquals(0L, db.balanceOf(1))
    }

    @Test
    fun rollbackUndoesEveryStatementSinceBegin() {
        val db = newDatabase()
        db.seedAccounts()

        db.run("BEGIN")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("UPDATE accounts SET balance = 0 WHERE id = 1")
        db.run("DELETE FROM accounts WHERE id = 2")
        assertEquals(RollbackResult, db.run("ROLLBACK").single())

        assertEquals(2, db.rowCount("SELECT * FROM accounts"))
        assertEquals(100L, db.balanceOf(1))
        assertEquals(200L, db.balanceOf(2))
    }

    @Test
    fun laterStatementsInTheSameTransactionSeeEarlierUncommittedWrites() {
        val db = newDatabase()
        db.seedAccounts()

        db.run("BEGIN")
        db.run("UPDATE accounts SET balance = 999 WHERE id = 1")
        assertEquals(999L, db.balanceOf(1))
        db.run("ROLLBACK")
    }

    @Test
    fun commitSurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.seedAccounts()

        db.run("BEGIN")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("UPDATE accounts SET balance = 1000 WHERE id = 2")
        db.run("COMMIT")

        db.reopen()
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
        assertEquals(1000L, db.balanceOf(2))
    }

    @Test
    fun commitWithoutBeginIsRejected() {
        val db = newDatabase()
        db.seedAccounts()
        assertFailsWith<DiskExecutorException> { db.run("COMMIT") }
    }

    @Test
    fun rollbackWithoutBeginIsRejected() {
        val db = newDatabase()
        db.seedAccounts()
        assertFailsWith<DiskExecutorException> { db.run("ROLLBACK") }
    }

    @Test
    fun nestedBeginIsRejected() {
        val db = newDatabase()
        db.seedAccounts()
        db.run("BEGIN")
        assertFailsWith<DiskExecutorException> { db.run("BEGIN") }
        db.run("ROLLBACK")
    }

    @Test
    fun ddlInsideAnOpenTransactionIsRejectedButTheTransactionStaysUsable() {
        val db = newDatabase()
        db.seedAccounts()

        db.run("BEGIN")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        assertFailsWith<DiskExecutorException> { db.run("CREATE TABLE scratch (a INTEGER)") }
        assertFailsWith<DiskExecutorException> { db.run("CREATE INDEX idx ON accounts (balance)") }
        assertFailsWith<DiskExecutorException> { db.run("ALTER TABLE accounts ADD COLUMN note TEXT") }

        // The rejected DDL must not have corrupted or silently closed the still-open transaction.
        assertEquals(CommitResult, db.run("COMMIT").single())
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
    }

    @Test
    fun everyStatementAutoCommitsImmediatelyOutsideAnExplicitTransaction() {
        val db = newDatabase()
        db.seedAccounts()

        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("UPDATE accounts SET balance = 0 WHERE id = 1")

        // Neither statement opened a BEGIN, so each must already be durable on its own - reopening
        // the catalog (a fresh process against the same files) has to see both without a COMMIT.
        db.reopen()
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
        assertEquals(0L, db.balanceOf(1))
    }

    @Test
    fun inTransactionReflectsWhetherABeginIsCurrentlyOpen() {
        val db = newDatabase()
        db.seedAccounts()
        assertTrue(!db.executor.inTransaction)
        db.run("BEGIN")
        assertTrue(db.executor.inTransaction)
        db.run("COMMIT")
        assertTrue(!db.executor.inTransaction)
    }

    /**
     * The core new guarantee this session adds: two SQL statements inside one `BEGIN` share a single
     * disk transaction, so the exclusive lock taken by the first `UPDATE` (see
     * [kestrel.store.engine.TransactionalHeap], locked on the table's header page) is still held while
     * a second, concurrent session's `UPDATE` on the very same table blocks on it - across the gap
     * between statement 1 and statement 2, not just for the duration of a single statement. Before
     * this session's change, `DiskExecutor` had no notion of a transaction spanning more than one
     * statement, so this scenario could never even arise: every statement committed (and released its
     * locks) the instant it finished.
     */
    @Test
    fun locksAcquiredByAnOpenTransactionBlockAConcurrentWriterUntilItEnds() {
        val db = newDatabase()
        db.seedAccounts()
        val other = db.secondExecutor()

        db.run("BEGIN")
        db.run("UPDATE accounts SET balance = 999 WHERE id = 1")

        val blockedUpdateFinished = java.util.concurrent.atomic.AtomicBoolean(false)
        val thread = Thread {
            other.execute(parse("UPDATE accounts SET balance = balance + 1 WHERE id = 1").single())
            blockedUpdateFinished.set(true)
        }
        thread.start()
        Thread.sleep(200)
        assertTrue(!blockedUpdateFinished.get(), "a concurrent writer must block while the open transaction still holds its lock")

        db.run("COMMIT")
        thread.join(2000)
        assertTrue(blockedUpdateFinished.get(), "the concurrent writer must proceed once the transaction commits")
        assertEquals(1000L, db.balanceOf(1))
    }
}
