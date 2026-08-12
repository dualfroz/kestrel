package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.DeleteResult
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.UpdateResult
import kestrel.storage.Value
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReturningTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private fun seed() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL)")
        run("INSERT INTO accounts VALUES (1, 'ada', 100)")
    }

    @Test
    fun insertReturningStarReportsTheFullInsertedRow() {
        seed()
        val result = exec("INSERT INTO accounts VALUES (2, 'linus', 200) RETURNING *") as InsertResult
        assertEquals(1, result.count)
        assertEquals(listOf("id", "name", "balance"), result.returning!!.columns)
        assertEquals(listOf(listOf(int(2), text("linus"), int(200))), result.returning!!.rows)
    }

    @Test
    fun insertReturningExpressionListWithAliasProjectsAndLabels() {
        seed()
        val result = exec("INSERT INTO accounts VALUES (2, 'linus', 200) RETURNING id, balance * 2 AS doubled") as InsertResult
        assertEquals(listOf("id", "doubled"), result.returning!!.columns)
        assertEquals(listOf(listOf(int(2), int(400))), result.returning!!.rows)
    }

    @Test
    fun multiRowInsertReturningReportsOneRowPerInsertedRowInOrder() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, tag TEXT NOT NULL)")
        val result = exec("INSERT INTO t VALUES (1, 'a'), (2, 'b'), (3, 'c') RETURNING id") as InsertResult
        assertEquals(listOf(listOf(int(1)), listOf(int(2)), listOf(int(3))), result.returning!!.rows)
    }

    @Test
    fun insertWithoutReturningLeavesReturningNull() {
        seed()
        val result = exec("INSERT INTO accounts VALUES (2, 'linus', 200)") as InsertResult
        assertNull(result.returning)
    }

    @Test
    fun onConflictDoNothingContributesNoRowToReturning() {
        seed()
        val result = exec(
            "INSERT INTO accounts VALUES (1, 'ignored', 999) ON CONFLICT (id) DO NOTHING RETURNING *"
        ) as InsertResult
        assertEquals(0, result.count)
        assertEquals(emptyList(), result.returning!!.rows)
    }

    @Test
    fun onConflictDoUpdateReturnsThePostUpdateRowNotTheProposedOne() {
        seed()
        val result = exec(
            "INSERT INTO accounts VALUES (1, 'ada', 50) ON CONFLICT (id) DO UPDATE SET balance = accounts.balance + excluded.balance RETURNING balance"
        ) as InsertResult
        assertEquals(1, result.count)
        assertEquals(listOf(listOf(int(150))), result.returning!!.rows)
    }

    @Test
    fun onConflictDoUpdateWithFailingWhereContributesNoRowToReturning() {
        seed()
        val result = exec(
            "INSERT INTO accounts VALUES (1, 'ada', 999) ON CONFLICT (id) DO UPDATE SET balance = 999 WHERE excluded.balance < 0 RETURNING *"
        ) as InsertResult
        assertEquals(0, result.count)
        assertEquals(emptyList(), result.returning!!.rows)
    }

    @Test
    fun mixedConflictAndFreshRowsInOneStatementReturnBothKinds() {
        seed()
        val result = exec(
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
        seed()
        run("INSERT INTO accounts VALUES (2, 'linus', 200)")
        val result = exec("UPDATE accounts SET balance = balance + 10 RETURNING id, balance") as UpdateResult
        assertEquals(2, result.count)
        assertEquals(listOf(listOf(int(1), int(110)), listOf(int(2), int(210))), result.returning!!.rows)
    }

    @Test
    fun updateReturningWithWhereOnlyReportsMatchedRows() {
        seed()
        run("INSERT INTO accounts VALUES (2, 'linus', 200)")
        val result = exec("UPDATE accounts SET balance = 0 WHERE id = 2 RETURNING id") as UpdateResult
        assertEquals(listOf(listOf(int(2))), result.returning!!.rows)
    }

    @Test
    fun updateReturningWithNoMatchesReturnsAnEmptyResultNotNull() {
        seed()
        val result = exec("UPDATE accounts SET balance = 0 WHERE id = 999 RETURNING id") as UpdateResult
        assertEquals(0, result.count)
        assertEquals(emptyList(), result.returning!!.rows)
    }

    @Test
    fun deleteReturningReportsThePreDeletionRowValues() {
        seed()
        run("INSERT INTO accounts VALUES (2, 'linus', 200)")
        val result = exec("DELETE FROM accounts WHERE id = 1 RETURNING *") as DeleteResult
        assertEquals(1, result.count)
        assertEquals(listOf(listOf(int(1), text("ada"), int(100))), result.returning!!.rows)
        assertEquals(1, select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun deleteReturningWithNoWhereReturnsEveryDeletedRow() {
        seed()
        run("INSERT INTO accounts VALUES (2, 'linus', 200)")
        val result = exec("DELETE FROM accounts RETURNING id") as DeleteResult
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), result.returning!!.rows)
    }

    @Test
    fun returningRejectsAnUnknownColumnEvenWhenZeroRowsMatch() {
        seed()
        assertFailsWith<KestrelException> {
            exec("UPDATE accounts SET balance = 0 WHERE id = 999 RETURNING nope")
        }
    }

    @Test
    fun returningRejectsAnAggregateFunction() {
        seed()
        assertFailsWith<KestrelException> {
            exec("DELETE FROM accounts WHERE id = 1 RETURNING COUNT(*)")
        }
    }

    @Test
    fun returningIsRejectedInsideATriggerBodyStatement() {
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("CREATE TABLE audit (order_id INTEGER)")
        assertFailsWith<KestrelException> {
            run(
                "CREATE TRIGGER log_insert AFTER INSERT ON orders FOR EACH ROW " +
                    "BEGIN INSERT INTO audit VALUES (NEW.id) RETURNING order_id; END"
            )
        }
    }

    @Test
    fun aTableWithATriggerStillHonoursReturningOnTheOutermostStatement() {
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("CREATE TABLE audit (order_id INTEGER)")
        run(
            "CREATE TRIGGER log_insert AFTER INSERT ON orders FOR EACH ROW " +
                "BEGIN INSERT INTO audit VALUES (NEW.id); END"
        )
        val result = exec("INSERT INTO orders VALUES (1, 100) RETURNING id, amount") as InsertResult
        assertEquals(listOf(listOf(int(1), int(100))), result.returning!!.rows)
        assertEquals(listOf(listOf(int(1))), select("SELECT order_id FROM audit").rows)
    }
}
