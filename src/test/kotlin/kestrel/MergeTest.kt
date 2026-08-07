package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.MergeResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MergeTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)

    private fun text(value: String) = Value.TextValue(value)

    private fun setUpAccountsAndStaging() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER NOT NULL)")
        run("CREATE TABLE staging (id INTEGER, amount INTEGER)")
    }

    @Test
    fun matchedRowIsUpdated() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 50)")
        val result = exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount"
        ) as MergeResult
        assertEquals(MergeResult(0, 1, 0), result)
        assertEquals(listOf(listOf(int(150))), select("SELECT balance FROM accounts").rows)
    }

    @Test
    fun unmatchedRowIsInserted() {
        setUpAccountsAndStaging()
        run("INSERT INTO staging VALUES (7, 30)")
        val result = exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount)"
        ) as MergeResult
        assertEquals(MergeResult(1, 0, 0), result)
        assertEquals(listOf(listOf(int(7), int(30))), select("SELECT id, balance FROM accounts").rows)
    }

    @Test
    fun matchedRowIsDeleted() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 0)")
        val result = exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id WHEN MATCHED THEN DELETE"
        ) as MergeResult
        assertEquals(MergeResult(0, 0, 1), result)
        assertEquals(emptyList(), select("SELECT * FROM accounts").rows)
    }

    @Test
    fun aMultiRowMergeInsertsUpdatesAndDeletesInOneStatement() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100), (2, 200)")
        run("INSERT INTO staging VALUES (1, 10), (2, -999), (3, 5)")
        val result = exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED AND accounts.balance + staging.amount < 0 THEN DELETE " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount)"
        ) as MergeResult
        assertEquals(MergeResult(1, 1, 1), result)
        assertEquals(
            listOf(listOf(int(1), int(110)), listOf(int(3), int(5))),
            select("SELECT id, balance FROM accounts ORDER BY id").rows
        )
    }

    @Test
    fun firstSatisfiedWhenMatchedClauseWinsInWrittenOrder() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 5)")
        exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED AND staging.amount > 100 THEN DELETE " +
                "WHEN MATCHED AND staging.amount > 0 THEN UPDATE SET balance = 1 " +
                "WHEN MATCHED THEN UPDATE SET balance = 2"
        )
        assertEquals(listOf(listOf(int(1))), select("SELECT balance FROM accounts").rows)
    }

    @Test
    fun firstSatisfiedWhenNotMatchedClauseWinsInWrittenOrder() {
        setUpAccountsAndStaging()
        run("INSERT INTO staging VALUES (1, 500)")
        exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN NOT MATCHED AND staging.amount > 1000 THEN INSERT (id, balance) VALUES (staging.id, 1) " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, 2)"
        )
        assertEquals(listOf(listOf(int(1), int(2))), select("SELECT id, balance FROM accounts").rows)
    }

    @Test
    fun noSatisfiedWhenClauseLeavesTheRowUntouched() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 5)")
        val result = exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED AND staging.amount > 1000 THEN UPDATE SET balance = 0"
        ) as MergeResult
        assertEquals(MergeResult(0, 0, 0), result)
        assertEquals(listOf(listOf(int(100))), select("SELECT balance FROM accounts").rows)
    }

    @Test
    fun anUnmatchedSourceRowWithNoSatisfiedNotMatchedClauseInsertsNothing() {
        setUpAccountsAndStaging()
        run("INSERT INTO staging VALUES (1, 5)")
        val result = exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN NOT MATCHED AND staging.amount > 1000 THEN INSERT (id, balance) VALUES (staging.id, staging.amount)"
        ) as MergeResult
        assertEquals(MergeResult(0, 0, 0), result)
        assertEquals(emptyList(), select("SELECT * FROM accounts").rows)
    }

    @Test
    fun aTargetRowMatchedByTwoSourceRowsCannotBeAffectedTwice() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 5), (1, 10)")
        val error = assertFailsWith<KestrelException> {
            run(
                "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                    "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount"
            )
        }
        assertTrue(error.message!!.contains("a second time"), error.message)
    }

