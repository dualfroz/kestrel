package kestrel.store.sql

import kestrel.KestrelException
import kestrel.engine.BeginResult
import kestrel.engine.CommitResult
import kestrel.engine.ReleaseSavepointResult
import kestrel.engine.RollbackResult
import kestrel.engine.RollbackToSavepointResult
import kestrel.engine.SavepointResult
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

/**
 * Exercises `SAVEPOINT`/`RELEASE SAVEPOINT`/`ROLLBACK TO SAVEPOINT` through [DiskExecutor] against a
 * real [DiskCatalog] backed by temp files - see [DiskExecutorTest] for the general end-to-end pattern
 * this reuses. Most scenarios here are ported 1:1 from [kestrel.SavepointTest] (the in-memory engine's
 * equivalent), since the stack semantics ([DiskExecutor.indexOfLastSavepoint] et al.) are identical -
 * the exception is `rollbackToSavepointUndoesCreateTable`, which does not translate: DDL is rejected
 * inside an explicit transaction on this executor entirely (see [DiskExecutorTest]), so there is
 * nothing for a savepoint to roll back there. The tests unique to this file
 * ([fullRollbackAfterASavepointRollbackUndoesEverythingIncludingThePreSavepointWrite],
 * [rollbackToSavepointSurvivesACrashBeforeTheTransactionResolves],
 * [dataWrittenAfterASavepointRollbackSurvivesAReopenAfterCommit]) cover what is actually new on the
 * disk-backed path: unlike [kestrel.engine.Executor]'s whole-catalog snapshot/restore, a savepoint here
 * partially rolls back the storage engine's own WAL transaction via ARIES-style compensation log
 * records (see [kestrel.store.wal.LogRecord.CompensationUpdate]), so only an end-to-end reopen (or
 * simulated crash) test proves the result is actually durable and not just correct in the live
 * process's buffer pool cache.
 */
class DiskExecutorSavepointTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun int(value: Long) = Value.IntegerValue(value)

    private class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
            private set
        var executor = DiskExecutor(catalog)
            private set

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult
        fun rowCount(sql: String): Int = select(sql).rows.size

        /**
         * Closes and reopens the catalog against the same files, simulating a fresh process. Called
         * with an explicit transaction still open (no preceding COMMIT/ROLLBACK), this simulates a
         * crash: the storage engine's own recovery pass on [DiskCatalog.open] is what must resolve it.
         */
        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-savepoint-data"), tempFile("kestrel-disk-savepoint-wal"))

    private fun Database.seed() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 100), (2, 200)")
    }

    @Test
    fun rollbackToSavepointUndoesOnlyTheInsertsAfterIt() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        assertEquals(SavepointResult("beforeFour"), db.run("SAVEPOINT beforeFour").single())
        db.run("INSERT INTO accounts VALUES (4, 400)")
        assertEquals(4, db.rowCount("SELECT * FROM accounts"))
        assertEquals(RollbackToSavepointResult("beforeFour"), db.run("ROLLBACK TO SAVEPOINT beforeFour").single())
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
        assertEquals(CommitResult, db.run("COMMIT").single())
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
    }

    @Test
    fun rollbackToSavepointUndoesUpdatesAndDeletes() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("SAVEPOINT s1")
        db.run("UPDATE accounts SET balance = 0 WHERE id = 1")
        db.run("DELETE FROM accounts WHERE id = 2")
        assertEquals(int(0), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        db.run("ROLLBACK TO SAVEPOINT s1")
        assertEquals(int(100), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals(2, db.rowCount("SELECT * FROM accounts"))
        db.run("ROLLBACK")
    }

    @Test
    fun theSavepointItselfStaysUsableAfterRollingBackToIt() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("ROLLBACK TO SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (4, 400)")
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
        db.run("ROLLBACK TO SAVEPOINT s1")
        assertEquals(2, db.rowCount("SELECT * FROM accounts"))
        db.run("COMMIT")
    }

    @Test
    fun releaseSavepointForgetsItWithoutUndoingAnything() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        assertEquals(ReleaseSavepointResult("s1"), db.run("RELEASE SAVEPOINT s1").single())
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
        assertFailsWith<KestrelException> { db.run("ROLLBACK TO SAVEPOINT s1") }
        db.run("COMMIT")
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
    }

    @Test
    fun releaseSavepointAlsoForgetsLaterNestedSavepoints() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("SAVEPOINT spOuter")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("SAVEPOINT spInner")
        db.run("INSERT INTO accounts VALUES (4, 400)")
        db.run("RELEASE SAVEPOINT spOuter")
        assertFailsWith<KestrelException> { db.run("ROLLBACK TO SAVEPOINT spInner") }
        assertEquals(4, db.rowCount("SELECT * FROM accounts"))
        db.run("COMMIT")
    }

    @Test
    fun reusingASavepointNameShadowsTheOlderOneUntilItIsConsumed() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("SAVEPOINT s")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("SAVEPOINT s")
        db.run("INSERT INTO accounts VALUES (4, 400)")
        db.run("ROLLBACK TO SAVEPOINT s")
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
        db.run("ROLLBACK TO SAVEPOINT s")
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
        db.run("COMMIT")
    }

    @Test
    fun rollbackRestoresIndexStateEvenAcrossASavepoint() {
        val db = newDatabase()
        db.seed()
        db.run("CREATE INDEX idxBalance ON accounts (balance)")
        db.run("BEGIN")
        db.run("SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (3, 100)")
        assertEquals(2, db.rowCount("SELECT * FROM accounts WHERE balance = 100"))
        db.run("ROLLBACK TO SAVEPOINT s1")
        assertEquals(1, db.rowCount("SELECT * FROM accounts WHERE balance = 100"))
        db.run("ROLLBACK")
    }

    @Test
    fun savepointOutsideATransactionIsRejected() {
        val db = newDatabase()
        db.seed()
        assertFailsWith<KestrelException> { db.run("SAVEPOINT s1") }
    }

    @Test
    fun releaseSavepointOutsideATransactionIsRejected() {
        val db = newDatabase()
        db.seed()
        assertFailsWith<KestrelException> { db.run("RELEASE SAVEPOINT s1") }
    }

    @Test
    fun rollbackToSavepointOutsideATransactionIsRejected() {
        val db = newDatabase()
        db.seed()
        assertFailsWith<KestrelException> { db.run("ROLLBACK TO SAVEPOINT s1") }
    }

    @Test
    fun rollbackToAnUnknownSavepointNameIsRejected() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("SAVEPOINT s1")
        assertFailsWith<KestrelException> { db.run("ROLLBACK TO SAVEPOINT nope") }
        db.run("ROLLBACK")
    }

    @Test
    fun releaseAnUnknownSavepointNameIsRejected() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("SAVEPOINT s1")
        assertFailsWith<KestrelException> { db.run("RELEASE SAVEPOINT nope") }
        db.run("ROLLBACK")
    }

    @Test
    fun fullRollbackClearsEveryOutstandingSavepoint() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("ROLLBACK")
        db.run("BEGIN")
        assertFailsWith<KestrelException> { db.run("ROLLBACK TO SAVEPOINT s1") }
        db.run("ROLLBACK")
    }

    @Test
    fun commitClearsEveryOutstandingSavepoint() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("COMMIT")
        db.run("BEGIN")
        assertFailsWith<KestrelException> { db.run("ROLLBACK TO SAVEPOINT s1") }
        db.run("ROLLBACK")
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
    }

    @Test
    fun releaseKeywordAloneWithoutSavepointKeywordIsAccepted() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("RELEASE s1")
        assertEquals(3, db.rowCount("SELECT * FROM accounts"))
        db.run("COMMIT")
    }

    @Test
    fun beginResultTypeIsReturnedForTheEnclosingTransaction() {
        val db = newDatabase()
        db.seed()
        assertEquals(BeginResult, db.run("BEGIN").single())
        db.run("ROLLBACK")
    }

    /**
     * The SQL-level twin of `RecoveryManagerTest.fullRollbackAfterAPartialRollbackDoesNotMisapplyAnAlreadyUndoneBeforeImage` -
     * proves the whole stack (DiskExecutor -> DiskCatalog -> StorageEngine -> TransactionManager ->
     * RecoveryManager) wires the compensation-record undo-of-undo correctly end to end, not just at the
     * page level.
     */
    @Test
    fun fullRollbackAfterASavepointRollbackUndoesEverythingIncludingThePreSavepointWrite() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (4, 400)")
        db.run("ROLLBACK TO SAVEPOINT s1")
        assertEquals(3, db.rowCount("SELECT * FROM accounts"), "the pre-savepoint insert must still be visible in-session")
        assertEquals(RollbackResult, db.run("ROLLBACK").single())
        assertEquals(2, db.rowCount("SELECT * FROM accounts"), "a full rollback must also undo the pre-savepoint insert")
    }

    /**
     * A crash that lands right after `ROLLBACK TO SAVEPOINT` but before the enclosing transaction ever
     * commits or rolls back must still leave the database as if the whole transaction never happened:
     * recovery resolves any still-open transaction with a full rollback regardless of what savepoints
     * it touched along the way. This is the reopen-based proof that
     * [kestrel.store.wal.RecoveryManager.rollbackTo]'s compensation records are genuinely durable, not
     * just correct in the live process's buffer pool.
     */
    @Test
    fun rollbackToSavepointSurvivesACrashBeforeTheTransactionResolves() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (4, 400)")
        db.run("ROLLBACK TO SAVEPOINT s1")
        // No COMMIT, no ROLLBACK: crash right here, mid-transaction.
        db.reopen()

        assertEquals(2, db.rowCount("SELECT * FROM accounts"), "the entire unresolved transaction must be undone by crash recovery")
    }

    /**
     * The durable-happy-path counterpart to [rollbackToSavepointSurvivesACrashBeforeTheTransactionResolves]:
     * a transaction that rolls back to a savepoint and then commits must persist exactly the rows that
     * survived the rollback, including a write made *after* the savepoint rollback - proving the
     * transaction's WAL chain still threads correctly (new writes point back at the compensation
     * record, not at the undone update it replaced) once it resumes.
     */
    @Test
    fun dataWrittenAfterASavepointRollbackSurvivesAReopenAfterCommit() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.run("INSERT INTO accounts VALUES (3, 300)")
        db.run("SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (4, 400)")
        db.run("ROLLBACK TO SAVEPOINT s1")
        db.run("INSERT INTO accounts VALUES (5, 500)")
        db.run("COMMIT")
        db.reopen()

        assertEquals(
            listOf(int(1), int(2), int(3), int(5)),
            db.select("SELECT id FROM accounts ORDER BY id").rows.map { it[0] }
        )
    }
}
