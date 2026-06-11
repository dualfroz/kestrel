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

class TransactionTest {
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
    fun rollbackUndoesInsert() {
        seed()
        run("BEGIN")
        run("INSERT INTO accounts VALUES (3, 300)")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
        run("ROLLBACK")
        assertEquals(2, rowCount("SELECT * FROM accounts"))
    }

    @Test
    fun commitKeepsChanges() {
        seed()
        run("BEGIN")
        run("INSERT INTO accounts VALUES (3, 300)")
        run("COMMIT")
        assertEquals(3, rowCount("SELECT * FROM accounts"))
    }

    @Test
    fun rollbackUndoesUpdateAndDelete() {
        seed()
        run("BEGIN")
        run("UPDATE accounts SET balance = 0 WHERE id = 1")
        run("DELETE FROM accounts WHERE id = 2")
        assertEquals(Value.IntegerValue(0), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        run("ROLLBACK")
        assertEquals(Value.IntegerValue(100), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals(2, rowCount("SELECT * FROM accounts"))
    }

    @Test
    fun rollbackUndoesCreateTable() {
        seed()
        run("BEGIN")
        run("CREATE TABLE scratch (a INTEGER)")
        run("ROLLBACK")
        assertFailsWith<KestrelException> { run("SELECT * FROM scratch") }
    }

    @Test
    fun rollbackRestoresIndexState() {
        seed()
        run("CREATE INDEX idxBalance ON accounts (balance)")
        run("BEGIN")
        run("INSERT INTO accounts VALUES (3, 100)")
        assertEquals(2, rowCount("SELECT * FROM accounts WHERE balance = 100"))
        run("ROLLBACK")
        assertEquals(1, rowCount("SELECT * FROM accounts WHERE balance = 100"))
    }

    @Test
    fun commitWithoutBeginIsRejected() {
        assertFailsWith<KestrelException> { run("COMMIT") }
    }

    @Test
    fun rollbackWithoutBeginIsRejected() {
        assertFailsWith<KestrelException> { run("ROLLBACK") }
    }

    @Test
    fun nestedBeginIsRejected() {
        run("BEGIN")
        assertFailsWith<KestrelException> { run("BEGIN") }
    }
}
