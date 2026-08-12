package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SavepointTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun rowCount(sql: String): Int = select(sql).rows.size

    private fun seed() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 100), (2, 200)")
    }

    @Test
    fun rollbackToSavepointUndoesOnlyTheInsertsAfterIt() {
        seed()
        run("BEGIN")
        run("INSERT INTO accounts VALUES (3, 300)")
        run("SAVEPOINT beforeFour")
        run("INSERT INTO accounts VALUES (4, 400)")
        assertEquals(4, rowCount("SELECT * FROM accounts"))
        run("ROLLBACK TO SAVEPOINT beforeFour")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
        run("COMMIT")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
    }

    @Test
    fun rollbackToSavepointUndoesUpdatesAndDeletes() {
        seed()
        run("BEGIN")
        run("SAVEPOINT s1")
        run("UPDATE accounts SET balance = 0 WHERE id = 1")
        run("DELETE FROM accounts WHERE id = 2")
        assertEquals(Value.IntegerValue(0), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        run("ROLLBACK TO SAVEPOINT s1")
        assertEquals(Value.IntegerValue(100), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals(2, rowCount("SELECT * FROM accounts"))
        run("ROLLBACK")
    }

    @Test
    fun rollbackToSavepointUndoesCreateTable() {
        seed()
        run("BEGIN")
        run("SAVEPOINT s1")
        run("CREATE TABLE scratch (a INTEGER)")
        run("ROLLBACK TO SAVEPOINT s1")
        assertFailsWith<KestrelException> { run("SELECT * FROM scratch") }
        run("ROLLBACK")
    }

    @Test
    fun theSavepointItselfStaysUsableAfterRollingBackToIt() {
        seed()
        run("BEGIN")
        run("SAVEPOINT s1")
        run("INSERT INTO accounts VALUES (3, 300)")
        run("ROLLBACK TO SAVEPOINT s1")
        run("INSERT INTO accounts VALUES (4, 400)")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
        run("ROLLBACK TO SAVEPOINT s1")
        assertEquals(2, rowCount("SELECT * FROM accounts"))
        run("COMMIT")
    }

    @Test
    fun releaseSavepointForgetsItWithoutUndoingAnything() {
        seed()
        run("BEGIN")
        run("SAVEPOINT s1")
        run("INSERT INTO accounts VALUES (3, 300)")
        run("RELEASE SAVEPOINT s1")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
        assertFailsWith<KestrelException> { run("ROLLBACK TO SAVEPOINT s1") }
        run("COMMIT")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
    }

    @Test
    fun releaseSavepointAlsoForgetsLaterNestedSavepoints() {
        seed()
        run("BEGIN")
        run("SAVEPOINT spOuter")
        run("INSERT INTO accounts VALUES (3, 300)")
        run("SAVEPOINT spInner")
        run("INSERT INTO accounts VALUES (4, 400)")
        run("RELEASE SAVEPOINT spOuter")
        assertFailsWith<KestrelException> { run("ROLLBACK TO SAVEPOINT spInner") }
        assertEquals(4, rowCount("SELECT * FROM accounts"))
        run("COMMIT")
    }

    @Test
    fun reusingASavepointNameShadowsTheOlderOneUntilItIsConsumed() {
        seed()
        run("BEGIN")
        run("SAVEPOINT s")
        run("INSERT INTO accounts VALUES (3, 300)")
        run("SAVEPOINT s")
        run("INSERT INTO accounts VALUES (4, 400)")
        run("ROLLBACK TO SAVEPOINT s")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
        run("ROLLBACK TO SAVEPOINT s")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
        run("COMMIT")
    }

    @Test
    fun rollbackRestoresIndexStateEvenAcrossASavepoint() {
        seed()
        run("CREATE INDEX idxBalance ON accounts (balance)")
        run("BEGIN")
        run("SAVEPOINT s1")
        run("INSERT INTO accounts VALUES (3, 100)")
        assertEquals(2, rowCount("SELECT * FROM accounts WHERE balance = 100"))
        run("ROLLBACK TO SAVEPOINT s1")
        assertEquals(1, rowCount("SELECT * FROM accounts WHERE balance = 100"))
        run("ROLLBACK")
    }

    @Test
    fun savepointOutsideATransactionIsRejected() {
        seed()
        assertFailsWith<KestrelException> { run("SAVEPOINT s1") }
    }

    @Test
    fun releaseSavepointOutsideATransactionIsRejected() {
        seed()
        assertFailsWith<KestrelException> { run("RELEASE SAVEPOINT s1") }
    }

    @Test
    fun rollbackToSavepointOutsideATransactionIsRejected() {
        seed()
        assertFailsWith<KestrelException> { run("ROLLBACK TO SAVEPOINT s1") }
    }

    @Test
    fun rollbackToAnUnknownSavepointNameIsRejected() {
        seed()
        run("BEGIN")
        run("SAVEPOINT s1")
        assertFailsWith<KestrelException> { run("ROLLBACK TO SAVEPOINT nope") }
        run("ROLLBACK")
    }

    @Test
    fun releaseAnUnknownSavepointNameIsRejected() {
        seed()
        run("BEGIN")
        run("SAVEPOINT s1")
        assertFailsWith<KestrelException> { run("RELEASE SAVEPOINT nope") }
        run("ROLLBACK")
    }

    @Test
    fun fullRollbackClearsEveryOutstandingSavepoint() {
        seed()
        run("BEGIN")
        run("SAVEPOINT s1")
        run("INSERT INTO accounts VALUES (3, 300)")
        run("ROLLBACK")
        run("BEGIN")
        assertFailsWith<KestrelException> { run("ROLLBACK TO SAVEPOINT s1") }
        run("ROLLBACK")
    }

    @Test
    fun commitClearsEveryOutstandingSavepoint() {
        seed()
        run("BEGIN")
        run("SAVEPOINT s1")
        run("INSERT INTO accounts VALUES (3, 300)")
        run("COMMIT")
        run("BEGIN")
        assertFailsWith<KestrelException> { run("ROLLBACK TO SAVEPOINT s1") }
        run("ROLLBACK")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
    }

    @Test
    fun releaseKeywordAloneWithoutSavepointKeywordIsAccepted() {
        seed()
        run("BEGIN")
        run("SAVEPOINT s1")
        run("INSERT INTO accounts VALUES (3, 300)")
        run("RELEASE s1")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
        run("COMMIT")
    }
}
