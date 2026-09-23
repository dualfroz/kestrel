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

    @Test
    fun anUnmatchedSourceRowWithNoSatisfiedNotMatchedClauseInsertsNothing() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO staging VALUES (1, 5)")
        val result = db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN NOT MATCHED AND staging.amount > 1000 THEN INSERT (id, balance) VALUES (staging.id, staging.amount)"
        ) as MergeResult
        assertEquals(MergeResult(0, 0, 0), result)
        assertEquals(emptyList(), db.select("SELECT * FROM accounts").rows)
    }

    @Test
    fun aTargetRowMatchedByTwoSourceRowsCannotBeAffectedTwice() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 5), (1, 10)")
        val error = assertFailsWith<KestrelException> {
            db.run(
                "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                    "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount"
            )
        }
        assertTrue(error.message!!.contains("a second time"), error.message)
    }

    @Test
    fun aTargetRowMatchedByTwoSourceRowsIsFineWhenOnlyOneClauseActuallyFires() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 5), (1, 999)")
        db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED AND staging.amount > 100 THEN UPDATE SET balance = accounts.balance + staging.amount"
        )
        assertEquals(listOf(listOf(int(1099))), db.select("SELECT balance FROM accounts").rows)
    }

    @Test
    fun anUnqualifiedColumnPresentOnBothSidesIsAmbiguousInOnCondition() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, v INTEGER)")
        db.run("CREATE TABLE s (id INTEGER, v INTEGER)")
        val error = assertFailsWith<KestrelException> {
            db.run("MERGE INTO t USING s ON id = s.id WHEN MATCHED THEN UPDATE SET v = 1")
        }
        assertTrue(error.message!!.contains("ambiguous"), error.message)
    }

    @Test
    fun aQualifierMatchingNeitherSideFails() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        val error = assertFailsWith<KestrelException> {
            db.run("MERGE INTO accounts USING staging ON bogus.id = staging.id WHEN MATCHED THEN DELETE")
        }
        assertTrue(error.message!!.contains("no such table or alias"), error.message)
    }

    @Test
    fun aWhenNotMatchedClauseCannotReferenceTheTargetTable() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        val error = assertFailsWith<KestrelException> {
            db.run(
                "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                    "WHEN NOT MATCHED AND accounts.balance > 0 THEN INSERT (id, balance) VALUES (staging.id, 1)"
            )
        }
        assertTrue(error.message!!.contains("WHEN NOT MATCHED"), error.message)
    }

    @Test
    fun columnValidationRunsUpFrontEvenWhenNoSourceRowEverReachesTheBadClause() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        val error = assertFailsWith<KestrelException> {
            db.run(
                "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                    "WHEN NOT MATCHED THEN INSERT (id, nope) VALUES (staging.id, 1)"
            )
        }
        assertTrue(error.message!!.contains("no such column"), error.message)
    }

    @Test
    fun theTargetAndSourceCannotShareTheSameNameOrAlias() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE other (id INTEGER)")
        val error = assertFailsWith<KestrelException> {
            db.run("MERGE INTO t USING other AS t ON t.id = t.id WHEN MATCHED THEN DELETE")
        }
        assertTrue(error.message!!.contains("target and source"), error.message)
    }

    @Test
    fun sourceCanBeADerivedTableSubquery() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO staging VALUES (1, 10), (1, 20)")
        db.exec(
            "MERGE INTO accounts USING (SELECT id, SUM(amount) AS total FROM staging GROUP BY id) AS agg " +
                "ON accounts.id = agg.id " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (agg.id, agg.total)"
        )
        assertEquals(listOf(listOf(int(1), int(30))), db.select("SELECT id, balance FROM accounts").rows)
    }

    @Test
    fun aDefaultKeywordInTheInsertValuesUsesTheColumnDefault() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT DEFAULT 'unlabeled')")
        db.run("CREATE TABLE s (id INTEGER)")
        db.run("INSERT INTO s VALUES (1)")
        db.exec("MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN INSERT (id, label) VALUES (s.id, DEFAULT)")
        assertEquals(listOf(listOf(int(1), text("unlabeled"))), db.select("SELECT id, label FROM t").rows)
    }

    @Test
    fun anOmittedColumnListInsertsIntoEveryColumnInSchemaOrder() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT)")
        db.run("CREATE TABLE s (id INTEGER, label TEXT)")
        db.run("INSERT INTO s VALUES (1, 'a')")
        db.exec("MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN INSERT VALUES (s.id, s.label)")
        assertEquals(listOf(listOf(int(1), text("a"))), db.select("SELECT id, label FROM t").rows)
    }

    @Test
    fun aNextvalDefaultStillAdvancesThroughAMergeInsert() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('seq'), label TEXT)")
        db.run("CREATE TABLE s (label TEXT)")
        db.run("INSERT INTO s VALUES ('a'), ('b')")
        db.exec("MERGE INTO t USING s ON t.label = s.label WHEN NOT MATCHED THEN INSERT (label) VALUES (s.label)")
        assertEquals(
            listOf(listOf(int(1), text("a")), listOf(int(2), text("b"))),
            db.select("SELECT id, label FROM t ORDER BY id").rows
        )
    }

    @Test
    fun notNullViolationOnAMergeInsertFailsAndInsertsNothing() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT NOT NULL)")
        db.run("CREATE TABLE s (id INTEGER)")
        db.run("INSERT INTO s VALUES (1)")
        assertFailsWith<KestrelException> {
            db.run("MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id)")
        }
        assertEquals(emptyList(), db.select("SELECT * FROM t").rows)
    }

    @Test
    fun aForeignKeyViolationOnAMergeUpdateIsRejected() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        db.run("CREATE TABLE moves (id INTEGER, new_dept INTEGER)")
        db.run("INSERT INTO departments VALUES (1)")
        db.run("INSERT INTO employees VALUES (1, 1)")
        db.run("INSERT INTO moves VALUES (1, 999)")
        val error = assertFailsWith<KestrelException> {
            db.run("MERGE INTO employees USING moves ON employees.id = moves.id WHEN MATCHED THEN UPDATE SET dept_id = moves.new_dept")
        }
        assertTrue(error.message!!.contains("FOREIGN KEY"), error.message)
        assertEquals(listOf(listOf(int(1))), db.select("SELECT dept_id FROM employees").rows)
    }

    @Test
    fun rowTriggersFireForEachMergeDrivenAction() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT)")
        db.run("CREATE TABLE s (id INTEGER, label TEXT)")
        db.run("CREATE TABLE log (event TEXT)")
        db.run("INSERT INTO t VALUES (1, 'old'), (2, 'gone')")
        db.run("INSERT INTO s VALUES (1, 'new'), (2, 'new'), (3, 'fresh')")
        db.run("CREATE TRIGGER log_insert AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO log VALUES (CONCAT('insert:', NEW.label)); END")
        db.run("CREATE TRIGGER log_update AFTER UPDATE ON t FOR EACH ROW BEGIN INSERT INTO log VALUES (CONCAT('update:', NEW.label)); END")
        db.run("CREATE TRIGGER log_delete AFTER DELETE ON t FOR EACH ROW BEGIN INSERT INTO log VALUES (CONCAT('delete:', OLD.label)); END")
        db.exec(
            "MERGE INTO t USING s ON t.id = s.id " +
                "WHEN MATCHED AND s.id = 2 THEN DELETE " +
                "WHEN MATCHED THEN UPDATE SET label = s.label " +
                "WHEN NOT MATCHED THEN INSERT (id, label) VALUES (s.id, s.label)"
        )
        assertEquals(
            setOf(text("update:new"), text("delete:gone"), text("insert:fresh")),
            db.select("SELECT event FROM log").rows.map { it.single() }.toSet()
        )
    }

    @Test
    fun rollingBackATransactionUndoesEveryEffectOfAMerge() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 50), (2, 20)")
        db.run("BEGIN")
        db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount)"
        )
        db.run("ROLLBACK")
        assertEquals(listOf(listOf(int(1), int(100))), db.select("SELECT id, balance FROM accounts").rows)
    }

    @Test
    fun anAliasedTargetAndSourceCanBeReferencedByTheirAlias() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 25)")
        db.exec(
            "MERGE INTO accounts AS acc USING staging AS s ON acc.id = s.id " +
                "WHEN MATCHED THEN UPDATE SET balance = acc.balance + s.amount"
        )
        assertEquals(listOf(listOf(int(125))), db.select("SELECT balance FROM accounts").rows)
    }

    @Test
    fun mergeRequiresAtLeastOneWhenClause() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE s (id INTEGER)")
        assertFailsWith<KestrelException> { db.run("MERGE INTO t USING s ON t.id = s.id") }
    }

    @Test
    fun mergingIntoAnUnknownTableFails() {
        val db = newDatabase()
        db.run("CREATE TABLE s (id INTEGER)")
        assertFailsWith<KestrelException> {
            db.run("MERGE INTO nope USING s ON nope.id = s.id WHEN MATCHED THEN DELETE")
        }
    }

    @Test
    fun mergeSurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 50), (2, 20)")
        db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount)"
        )
        db.reopen()
        assertEquals(
            listOf(listOf(int(1), int(150)), listOf(int(2), int(20))),
            db.select("SELECT id, balance FROM accounts ORDER BY id").rows
        )
    }

    @Test
    fun returningStarProjectsEveryColumnOfEveryActionedRow() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 10), (2, 5)")
        val result = db.exec(
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
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 10)")
        val result = db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "RETURNING balance * 2 AS doubled"
        ) as MergeResult
        assertEquals(listOf("doubled"), result.returning!!.columns)
        assertEquals(listOf(listOf(int(220))), result.returning!!.rows)
    }

    @Test
    fun returningIncludesThePreDeletionImageOfADeletedRow() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 0)")
        val result = db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id WHEN MATCHED THEN DELETE RETURNING id, balance"
        ) as MergeResult
        assertEquals(listOf(listOf(int(1), int(100))), result.returning!!.rows)
        assertEquals(emptyList(), db.select("SELECT * FROM accounts").rows)
    }

    @Test
    fun mergeActionLabelsEachReturnedRowByWhichClauseFired() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT)")
        db.run("CREATE TABLE s (id INTEGER, label TEXT)")
        db.run("INSERT INTO t VALUES (1, 'old'), (2, 'gone')")
        db.run("INSERT INTO s VALUES (1, 'new'), (2, 'new'), (3, 'fresh')")
        val result = db.exec(
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
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 5)")
        val result = db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED AND staging.amount > 1000 THEN UPDATE SET balance = 0 " +
                "RETURNING *"
        ) as MergeResult
        assertEquals(emptyList(), result.returning!!.rows)
    }

    @Test
    fun mergeActionIsRejectedOutsideOfAMergesOwnReturningClause() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO t VALUES (1)")
        val error = assertFailsWith<KestrelException> { db.select("SELECT MERGE_ACTION() FROM t") }
        assertTrue(error.message!!.contains("MERGE_ACTION"), error.message)
    }

    @Test
    fun returningColumnValidationRunsUpFrontEvenWhenNoSourceRowEverReachesAMatchingClause() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        val error = assertFailsWith<KestrelException> {
            db.run(
                "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                    "WHEN MATCHED THEN DELETE RETURNING nope"
            )
        }
        assertTrue(error.message!!.contains("no such column"), error.message)
    }

    @Test
    fun returningSurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.setUpAccountsAndStaging()
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("INSERT INTO staging VALUES (1, 50)")
        val beforeReopen = db.exec(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "RETURNING id, balance"
        ) as MergeResult
        assertEquals(listOf(listOf(int(1), int(150))), beforeReopen.returning!!.rows)
        db.reopen()
        assertEquals(listOf(listOf(int(1), int(150))), db.select("SELECT id, balance FROM accounts").rows)
    }
}
