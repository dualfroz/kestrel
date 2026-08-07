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

    @Test
    fun aTargetRowMatchedByTwoSourceRowsIsFineWhenOnlyOneClauseActuallyFires() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 5), (1, 999)")
        exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED AND staging.amount > 100 THEN UPDATE SET balance = accounts.balance + staging.amount"
        )
        assertEquals(listOf(listOf(int(1099))), select("SELECT balance FROM accounts").rows)
    }

    @Test
    fun anUnqualifiedColumnPresentOnBothSidesIsAmbiguousInOnCondition() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER)")
        run("CREATE TABLE s (id INTEGER, v INTEGER)")
        val error = assertFailsWith<KestrelException> {
            run("MERGE INTO t USING s ON id = s.id WHEN MATCHED THEN UPDATE SET v = 1")
        }
        assertTrue(error.message!!.contains("ambiguous"), error.message)
    }

    @Test
    fun aQualifierMatchingNeitherSideFails() {
        setUpAccountsAndStaging()
        val error = assertFailsWith<KestrelException> {
            run("MERGE INTO accounts USING staging ON bogus.id = staging.id WHEN MATCHED THEN DELETE")
        }
        assertTrue(error.message!!.contains("no such table or alias"), error.message)
    }

    @Test
    fun aWhenNotMatchedClauseCannotReferenceTheTargetTable() {
        setUpAccountsAndStaging()
        val error = assertFailsWith<KestrelException> {
            run(
                "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                    "WHEN NOT MATCHED AND accounts.balance > 0 THEN INSERT (id, balance) VALUES (staging.id, 1)"
            )
        }
        assertTrue(error.message!!.contains("WHEN NOT MATCHED"), error.message)
    }

    @Test
    fun columnValidationRunsUpFrontEvenWhenNoSourceRowEverReachesTheBadClause() {
        setUpAccountsAndStaging()
        val error = assertFailsWith<KestrelException> {
            run(
                "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                    "WHEN NOT MATCHED THEN INSERT (id, nope) VALUES (staging.id, 1)"
            )
        }
        assertTrue(error.message!!.contains("no such column"), error.message)
    }

    @Test
    fun theTargetAndSourceCannotShareTheSameNameOrAlias() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE other (id INTEGER)")
        val error = assertFailsWith<KestrelException> {
            run("MERGE INTO t USING other AS t ON t.id = t.id WHEN MATCHED THEN DELETE")
        }
        assertTrue(error.message!!.contains("target and source"), error.message)
    }

    @Test
    fun sourceCanBeADerivedTableSubquery() {
        setUpAccountsAndStaging()
        run("INSERT INTO staging VALUES (1, 10), (1, 20)")
        exec(
            "MERGE INTO accounts USING (SELECT id, SUM(amount) AS total FROM staging GROUP BY id) AS agg " +
                "ON accounts.id = agg.id " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (agg.id, agg.total)"
        )
        assertEquals(listOf(listOf(int(1), int(30))), select("SELECT id, balance FROM accounts").rows)
    }

    @Test
    fun aDefaultKeywordInTheInsertValuesUsesTheColumnDefault() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT DEFAULT 'unlabeled')")
        run("CREATE TABLE s (id INTEGER)")
        run("INSERT INTO s VALUES (1)")
        exec("MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN INSERT (id, label) VALUES (s.id, DEFAULT)")
        assertEquals(listOf(listOf(int(1), text("unlabeled"))), select("SELECT id, label FROM t").rows)
    }

    @Test
    fun anOmittedColumnListInsertsIntoEveryColumnInSchemaOrder() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT)")
        run("CREATE TABLE s (id INTEGER, label TEXT)")
        run("INSERT INTO s VALUES (1, 'a')")
        exec("MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN INSERT VALUES (s.id, s.label)")
        assertEquals(listOf(listOf(int(1), text("a"))), select("SELECT id, label FROM t").rows)
    }

    @Test
    fun aNextvalDefaultStillAdvancesThroughAMergeInsert() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('seq'), label TEXT)")
        run("CREATE TABLE s (label TEXT)")
        run("INSERT INTO s VALUES ('a'), ('b')")
        exec("MERGE INTO t USING s ON t.label = s.label WHEN NOT MATCHED THEN INSERT (label) VALUES (s.label)")
        assertEquals(
            listOf(listOf(int(1), text("a")), listOf(int(2), text("b"))),
            select("SELECT id, label FROM t ORDER BY id").rows
        )
    }

    @Test
    fun notNullViolationOnAMergeInsertFailsAndInsertsNothing() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT NOT NULL)")
        run("CREATE TABLE s (id INTEGER)")
        run("INSERT INTO s VALUES (1)")
        assertFailsWith<KestrelException> {
            run("MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id)")
        }
        assertEquals(emptyList(), select("SELECT * FROM t").rows)
    }

    @Test
    fun aForeignKeyViolationOnAMergeUpdateIsRejected() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        run("CREATE TABLE moves (id INTEGER, new_dept INTEGER)")
        run("INSERT INTO departments VALUES (1)")
        run("INSERT INTO employees VALUES (1, 1)")
        run("INSERT INTO moves VALUES (1, 999)")
        val error = assertFailsWith<KestrelException> {
            run("MERGE INTO employees USING moves ON employees.id = moves.id WHEN MATCHED THEN UPDATE SET dept_id = moves.new_dept")
        }
        assertTrue(error.message!!.contains("FOREIGN KEY"), error.message)
        assertEquals(listOf(listOf(int(1))), select("SELECT dept_id FROM employees").rows)
    }

    @Test
    fun rowTriggersFireForEachMergeDrivenAction() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT)")
        run("CREATE TABLE s (id INTEGER, label TEXT)")
        run("CREATE TABLE log (event TEXT)")
        run("INSERT INTO t VALUES (1, 'old'), (2, 'gone')")
        run("INSERT INTO s VALUES (1, 'new'), (2, 'new'), (3, 'fresh')")
        run("CREATE TRIGGER log_insert AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO log VALUES (CONCAT('insert:', NEW.label)); END")
        run("CREATE TRIGGER log_update AFTER UPDATE ON t FOR EACH ROW BEGIN INSERT INTO log VALUES (CONCAT('update:', NEW.label)); END")
        run("CREATE TRIGGER log_delete AFTER DELETE ON t FOR EACH ROW BEGIN INSERT INTO log VALUES (CONCAT('delete:', OLD.label)); END")
        exec(
            "MERGE INTO t USING s ON t.id = s.id " +
                "WHEN MATCHED AND s.id = 2 THEN DELETE " +
                "WHEN MATCHED THEN UPDATE SET label = s.label " +
                "WHEN NOT MATCHED THEN INSERT (id, label) VALUES (s.id, s.label)"
        )
        assertEquals(
            setOf(text("update:new"), text("delete:gone"), text("insert:fresh")),
            select("SELECT event FROM log").rows.map { it.single() }.toSet()
        )
    }

    @Test
    fun rollingBackATransactionUndoesEveryEffectOfAMerge() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 50), (2, 20)")
        run("BEGIN")
        exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount)"
        )
        run("ROLLBACK")
        assertEquals(listOf(listOf(int(1), int(100))), select("SELECT id, balance FROM accounts").rows)
    }

    @Test
    fun anAliasedTargetAndSourceCanBeReferencedByTheirAlias() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 25)")
        exec(
            "MERGE INTO accounts AS acc USING staging AS s ON acc.id = s.id " +
                "WHEN MATCHED THEN UPDATE SET balance = acc.balance + s.amount"
        )
        assertEquals(listOf(listOf(int(125))), select("SELECT balance FROM accounts").rows)
    }

    @Test
    fun mergeRequiresAtLeastOneWhenClause() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE s (id INTEGER)")
        assertFailsWith<KestrelException> { run("MERGE INTO t USING s ON t.id = s.id") }
    }

    @Test
    fun mergingIntoAnUnknownTableFails() {
        run("CREATE TABLE s (id INTEGER)")
        assertFailsWith<KestrelException> {
            run("MERGE INTO nope USING s ON nope.id = s.id WHEN MATCHED THEN DELETE")
        }
    }

    @Test
    fun returningStarProjectsEveryColumnOfEveryActionedRow() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 10), (2, 5)")
        val result = exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount) " +
                "RETURNING *"
        ) as MergeResult
        assertEquals(listOf("id", "balance"), result.returning!!.columns)
        assertEquals(
            listOf(listOf(int(1), int(110)), listOf(int(2), int(5))),
            result.returning!!.rows
        )
    }

    @Test
    fun returningEvaluatesExpressionsAgainstTheAffectedRow() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 10)")
        val result = exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "RETURNING balance * 2 AS doubled"
        ) as MergeResult
        assertEquals(listOf("doubled"), result.returning!!.columns)
        assertEquals(listOf(listOf(int(220))), result.returning!!.rows)
    }

    @Test
    fun returningIncludesThePreDeletionImageOfADeletedRow() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 0)")
        val result = exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id WHEN MATCHED THEN DELETE RETURNING id, balance"
        ) as MergeResult
        assertEquals(listOf(listOf(int(1), int(100))), result.returning!!.rows)
        assertEquals(emptyList(), select("SELECT * FROM accounts").rows)
    }

    @Test
    fun mergeActionLabelsEachReturnedRowByWhichClauseFired() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT)")
        run("CREATE TABLE s (id INTEGER, label TEXT)")
        run("INSERT INTO t VALUES (1, 'old'), (2, 'gone')")
        run("INSERT INTO s VALUES (1, 'new'), (2, 'new'), (3, 'fresh')")
        val result = exec(
            "MERGE INTO t USING s ON t.id = s.id " +
                "WHEN MATCHED AND s.id = 2 THEN DELETE " +
                "WHEN MATCHED THEN UPDATE SET label = s.label " +
                "WHEN NOT MATCHED THEN INSERT (id, label) VALUES (s.id, s.label) " +
                "RETURNING id, MERGE_ACTION() AS kind"
        ) as MergeResult
        assertEquals(
            setOf(listOf(int(1), text("UPDATE")), listOf(int(2), text("DELETE")), listOf(int(3), text("INSERT"))),
            result.returning!!.rows.toSet()
        )
    }

    @Test
    fun noRowMatchingAnyClauseReturnsAnEmptyReturningResultNotANullOne() {
        setUpAccountsAndStaging()
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 5)")
        val result = exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED AND staging.amount > 1000 THEN UPDATE SET balance = 0 " +
                "RETURNING *"
        ) as MergeResult
        assertEquals(emptyList(), result.returning!!.rows)
    }

    @Test
    fun mergeActionIsRejectedOutsideOfAMergesOwnReturningClause() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        val error = assertFailsWith<KestrelException> { select("SELECT MERGE_ACTION() FROM t") }
        assertTrue(error.message!!.contains("MERGE_ACTION"), error.message)
    }

    @Test
    fun mergeActionInAPlainInsertReturningIsRejectedOnceARowActuallyReturns() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        val error = assertFailsWith<KestrelException> {
            run("INSERT INTO t VALUES (1) RETURNING MERGE_ACTION()")
        }
        assertTrue(error.message!!.contains("MERGE_ACTION"), error.message)
    }

    @Test
    fun returningColumnValidationRunsUpFrontEvenWhenNoSourceRowEverReachesAMatchingClause() {
        setUpAccountsAndStaging()
        val error = assertFailsWith<KestrelException> {
            run(
                "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                    "WHEN MATCHED THEN DELETE RETURNING nope"
            )
        }
        assertTrue(error.message!!.contains("no such column"), error.message)
    }
}
