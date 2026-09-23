package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.DeallocateResult
import kestrel.engine.DeleteResult
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.PrepareResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.UpdateResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreparedStatementTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun seedAccounts() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 'ada', 100), (2, 'linus', 200), (3, 'grace', 300)")
    }

    @Test
    fun prepareReturnsItsNameAndExecuteRunsAParameterizedSelect() {
        seedAccounts()
        assertEquals(PrepareResult("byId"), run("PREPARE byId AS SELECT name, balance FROM accounts WHERE id = \$1").single())
        val result = select("EXECUTE byId(2)")
        assertEquals(listOf("name", "balance"), result.columns)
        assertEquals(listOf(listOf(Value.TextValue("linus"), Value.IntegerValue(200))), result.rows)
    }

    @Test
    fun theSamePreparedStatementCanBeExecutedRepeatedlyWithDifferentArguments() {
        seedAccounts()
        run("PREPARE byId AS SELECT name FROM accounts WHERE id = \$1")
        assertEquals(listOf(listOf(Value.TextValue("ada"))), select("EXECUTE byId(1)").rows)
        assertEquals(listOf(listOf(Value.TextValue("grace"))), select("EXECUTE byId(3)").rows)
    }

    @Test
    fun executeSubstitutesMultipleParametersPositionally() {
        seedAccounts()
        run("PREPARE inRange AS SELECT name FROM accounts WHERE balance >= \$1 AND balance <= \$2 ORDER BY balance")
        assertEquals(
            listOf(listOf(Value.TextValue("linus")), listOf(Value.TextValue("grace"))),
            select("EXECUTE inRange(150, 350)").rows
        )
    }

    @Test
    fun prepareCanWrapInsertAndExecuteInsertsARealRow() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER)")
        run("PREPARE addAccount AS INSERT INTO accounts VALUES (\$1, \$2, \$3)")
        assertEquals(InsertResult(1), run("EXECUTE addAccount(1, 'ada', 100)").single())
        assertEquals(
            listOf(listOf(Value.IntegerValue(1), Value.TextValue("ada"), Value.IntegerValue(100))),
            select("SELECT * FROM accounts").rows
        )
        assertEquals(InsertResult(1), run("EXECUTE addAccount(2, 'linus', 200)").single())
        assertEquals(2, select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun prepareCanWrapUpdate() {
        seedAccounts()
        run("PREPARE credit AS UPDATE accounts SET balance = balance + \$1 WHERE id = \$2")
        assertEquals(UpdateResult(1), run("EXECUTE credit(50, 1)").single())
        assertEquals(listOf(listOf(Value.IntegerValue(150))), select("SELECT balance FROM accounts WHERE id = 1").rows)
    }

    @Test
    fun prepareCanWrapDelete() {
        seedAccounts()
        run("PREPARE removeAccount AS DELETE FROM accounts WHERE id = \$1")
        assertEquals(DeleteResult(1), run("EXECUTE removeAccount(2)").single())
        assertEquals(2, select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun prepareCanWrapMergeAndAParameterReachesTheMatchedUpdateAssignment() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER)")
        run("CREATE TABLE staging (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 'ada', 100)")
        run("INSERT INTO staging VALUES (1, 'ada', 500)")
        run(
            "PREPARE topUp AS MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = staging.balance + \$1"
        )
        run("EXECUTE topUp(10)")
        assertEquals(listOf(listOf(Value.IntegerValue(510))), select("SELECT balance FROM accounts WHERE id = 1").rows)
        run("EXECUTE topUp(20)")
        assertEquals(listOf(listOf(Value.IntegerValue(520))), select("SELECT balance FROM accounts WHERE id = 1").rows)
    }

    @Test
    fun prepareCanWrapMergeAndAParameterReachesTheNotMatchedInsertValues() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER)")
        run("CREATE TABLE staging (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER)")
        run("INSERT INTO staging VALUES (1, 'ada', 500)")
        run(
            "PREPARE seedFromStaging AS MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN NOT MATCHED THEN INSERT VALUES (staging.id, staging.name, staging.balance + \$1)"
        )
        run("EXECUTE seedFromStaging(10)")
        assertEquals(
            listOf(listOf(Value.IntegerValue(1), Value.TextValue("ada"), Value.IntegerValue(510))),
            select("SELECT * FROM accounts").rows
        )
    }

    @Test
    fun prepareCanWrapMergeAndAParameterInTheOnConditionControlsWhetherARowMatches() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER)")
        run("CREATE TABLE staging (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 'ada', 100)")
        run("INSERT INTO staging VALUES (1, 'ada', 500)")
        run(
            "PREPARE conditionalTopUp AS MERGE INTO accounts USING staging " +
                "ON accounts.id = staging.id AND staging.balance > \$1 " +
                "WHEN MATCHED THEN UPDATE SET balance = staging.balance"
        )
        run("EXECUTE conditionalTopUp(1000)")
        assertEquals(listOf(listOf(Value.IntegerValue(100))), select("SELECT balance FROM accounts WHERE id = 1").rows)
        run("EXECUTE conditionalTopUp(10)")
        assertEquals(listOf(listOf(Value.IntegerValue(500))), select("SELECT balance FROM accounts WHERE id = 1").rows)
    }

    @Test
    fun executeWithTheWrongNumberOfArgumentsFailsWithAClearMessage() {
        seedAccounts()
        run("PREPARE byId AS SELECT name FROM accounts WHERE id = \$1")
        val tooMany = assertFailsWith<KestrelException> { run("EXECUTE byId(1, 2)") }
        assertTrue(tooMany.message!!.contains("expects 1 parameter"), tooMany.message)
        assertFailsWith<KestrelException> { run("EXECUTE byId()") }
    }

    @Test
    fun executeOfAnUnknownNameFails() {
        assertFailsWith<KestrelException> { run("EXECUTE nope(1)") }
    }

    @Test
    fun preparingTheSameNameTwiceFails() {
        seedAccounts()
        run("PREPARE byId AS SELECT name FROM accounts WHERE id = \$1")
        assertFailsWith<KestrelException> { run("PREPARE byId AS SELECT balance FROM accounts WHERE id = \$1") }
    }

    @Test
    fun deallocateRemovesAPreparedStatementSoExecuteNoLongerFindsIt() {
        seedAccounts()
        run("PREPARE byId AS SELECT name FROM accounts WHERE id = \$1")
        assertEquals(DeallocateResult("byId", all = false), run("DEALLOCATE byId").single())
        assertFailsWith<KestrelException> { run("EXECUTE byId(1)") }
    }

    @Test
    fun deallocatingAnUnknownNameFails() {
        assertFailsWith<KestrelException> { run("DEALLOCATE nope") }
    }

    @Test
    fun deallocateAllRemovesEveryPreparedStatement() {
        seedAccounts()
        run("PREPARE p1 AS SELECT name FROM accounts WHERE id = \$1")
        run("PREPARE p2 AS SELECT balance FROM accounts WHERE id = \$1")
        assertEquals(DeallocateResult(null, all = true), run("DEALLOCATE ALL").single())
        assertFailsWith<KestrelException> { run("EXECUTE p1(1)") }
        assertFailsWith<KestrelException> { run("EXECUTE p2(1)") }
    }

    @Test
    fun aNameCanBeReparedAfterBeingDeallocated() {
        seedAccounts()
        run("PREPARE byId AS SELECT name FROM accounts WHERE id = \$1")
        run("DEALLOCATE byId")
        run("PREPARE byId AS SELECT balance FROM accounts WHERE id = \$1")
        assertEquals(listOf(listOf(Value.IntegerValue(100))), select("EXECUTE byId(1)").rows)
    }

    @Test
    fun preparedStatementsAreNotUndoneByRollback() {
        seedAccounts()
        run("BEGIN")
        run("PREPARE byId AS SELECT name FROM accounts WHERE id = \$1")
        run("ROLLBACK")
        assertEquals(listOf(listOf(Value.TextValue("ada"))), select("EXECUTE byId(1)").rows)
    }

    @Test
    fun executeArgumentsMustBeConstantNotColumnReferences() {
        seedAccounts()
        run("PREPARE byId AS SELECT name FROM accounts WHERE id = \$1")
        assertFailsWith<KestrelException> { run("EXECUTE byId(id)") }
    }

    @Test
    fun aParameterInTheSelectListDoesNotNeedToAppearInGroupBy() {
        seedAccounts()
        run("PREPARE report AS SELECT \$1, COUNT(*) FROM accounts GROUP BY name")
        val result = select("EXECUTE report('label')")
        assertEquals(3, result.rows.size)
        result.rows.forEach { row -> assertEquals(Value.TextValue("label"), row[0]) }
    }

    @Test
    fun aParameterTypedDirectlyOutsidePrepareParsesButOnlyFailsAtEvaluationTime() {
        seedAccounts()
        val exception = assertFailsWith<KestrelException> { run("SELECT \$1, COUNT(*) FROM accounts GROUP BY name") }
        assertTrue(exception.message!!.contains("parameter reference"), exception.message)
    }
}
