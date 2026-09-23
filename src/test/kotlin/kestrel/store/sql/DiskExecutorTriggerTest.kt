package kestrel.store.sql

import kestrel.KestrelException
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

class DiskExecutorTriggerTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
            private set
        var executor = DiskExecutor(catalog)
            private set

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult

        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-trigger-data"), tempFile("kestrel-disk-trigger-wal"))

    @Test
    fun afterInsertTriggerRunsBodyForEachInsertedRow() {
        val db = newDatabase()
        db.run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER)")
        db.run("CREATE TABLE audit (order_id INTEGER)")
        db.run(
            "CREATE TRIGGER log_insert AFTER INSERT ON orders FOR EACH ROW " +
                "BEGIN INSERT INTO audit VALUES (NEW.id); END"
        )
        db.run("INSERT INTO orders VALUES (1, 100), (2, 200)")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), db.select("SELECT order_id FROM audit ORDER BY order_id").rows)
    }

    @Test
    fun beforeInsertTriggerCanAbortTheWholeStatementBeforeThisExecutorsOwnConstraintsRun() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE guard (x INTEGER NOT NULL)")
        db.run("CREATE TRIGGER reject_all BEFORE INSERT ON t FOR EACH ROW BEGIN INSERT INTO guard VALUES (NULL); END")
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (1)") }
        assertEquals(0, db.select("SELECT * FROM t").rows.size)
    }

    @Test
    fun beforeTriggerSideEffectsAreRolledBackWhenTheFiringStatementItselfFails() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY CHECK (id > 0))")
        db.run("CREATE TABLE counter (n INTEGER)")
        db.run("INSERT INTO counter VALUES (0)")
        db.run("CREATE TRIGGER bump BEFORE INSERT ON t FOR EACH ROW BEGIN UPDATE counter SET n = n + 1; END")
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (-1)") }
        assertEquals(listOf(listOf(int(0))), db.select("SELECT n FROM counter").rows)
        assertEquals(0, db.select("SELECT * FROM t").rows.size)
    }

    @Test
    fun afterInsertTriggerFailureRollsBackTheOriginalInsertToo() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE audit (order_id INTEGER NOT NULL)")
        db.run("CREATE TRIGGER log_fail AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NULL); END")
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (1)") }
        assertEquals(0, db.select("SELECT * FROM t").rows.size)
        assertEquals(0, db.select("SELECT * FROM audit").rows.size)
    }

    @Test
    fun whenClauseGatesFiringPerRow() {
        val db = newDatabase()
        db.run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER)")
        db.run("CREATE TABLE audit (order_id INTEGER)")
        db.run(
            "CREATE TRIGGER log_big AFTER INSERT ON orders FOR EACH ROW WHEN NEW.amount > 100 " +
                "BEGIN INSERT INTO audit VALUES (NEW.id); END"
        )
        db.run("INSERT INTO orders VALUES (1, 50), (2, 200)")
        assertEquals(listOf(listOf(int(2))), db.select("SELECT order_id FROM audit").rows)
    }

    @Test
    fun updateTriggerSeesBothOldAndNew() {
        val db = newDatabase()
        db.run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        db.run("CREATE TABLE audit (account_id INTEGER, old_balance INTEGER, new_balance INTEGER)")
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run(
            "CREATE TRIGGER log_balance_change AFTER UPDATE ON accounts FOR EACH ROW " +
                "BEGIN INSERT INTO audit VALUES (NEW.id, OLD.balance, NEW.balance); END"
        )
        db.run("UPDATE accounts SET balance = 150 WHERE id = 1")
        assertEquals(listOf(listOf(int(1), int(100), int(150))), db.select("SELECT * FROM audit").rows)
    }

    @Test
    fun updateTriggerFiresForEveryMatchedRowEvenWhenTheValueIsUnchanged() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER)")
        db.run("CREATE TABLE audit (n INTEGER)")
        db.run("INSERT INTO t VALUES (1, 5)")
        db.run("CREATE TRIGGER log AFTER UPDATE ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (1); END")
        db.run("UPDATE t SET x = 5 WHERE id = 1")
        assertEquals(1, db.select("SELECT * FROM audit").rows.size)
    }

    @Test
    fun deleteTriggerSeesOld() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT)")
        db.run("CREATE TABLE audit (label TEXT)")
        db.run("INSERT INTO t VALUES (1, 'gone')")
        db.run("CREATE TRIGGER log_delete AFTER DELETE ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (OLD.label); END")
        db.run("DELETE FROM t WHERE id = 1")
        assertEquals(listOf(listOf(text("gone"))), db.select("SELECT * FROM audit").rows)
    }

    @Test
    fun multipleTriggersOnSameEventFireInCreationOrder() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("CREATE TABLE audit (step TEXT)")
        db.run("CREATE TRIGGER first_trigger AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES ('first'); END")
        db.run("CREATE TRIGGER second_trigger AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES ('second'); END")
        db.run("INSERT INTO t VALUES (1)")
        assertEquals(listOf(listOf(text("first")), listOf(text("second"))), db.select("SELECT * FROM audit").rows)
    }

    @Test
    fun cascadingTriggerFiresATriggerOnADifferentTable() {
        val db = newDatabase()
        db.run("CREATE TABLE a (id INTEGER)")
        db.run("CREATE TABLE b (id INTEGER)")
        db.run("CREATE TABLE c (id INTEGER)")
        db.run("CREATE TRIGGER a_to_b AFTER INSERT ON a FOR EACH ROW BEGIN INSERT INTO b VALUES (NEW.id); END")
        db.run("CREATE TRIGGER b_to_c AFTER INSERT ON b FOR EACH ROW BEGIN INSERT INTO c VALUES (NEW.id); END")
        db.run("INSERT INTO a VALUES (7)")
        assertEquals(listOf(listOf(int(7))), db.select("SELECT * FROM b").rows)
        assertEquals(listOf(listOf(int(7))), db.select("SELECT * FROM c").rows)
    }

    @Test
    fun recursiveTriggerHitsTheDepthGuardInsteadOfLoopingForeverAndRollsBackCleanly() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("CREATE TRIGGER loopy AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO t VALUES (NEW.id + 1); END")
        val exception = assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (1)") }
        assertTrue(exception.message!!.contains("recursion", ignoreCase = true), exception.message!!)
        assertEquals(0, db.select("SELECT * FROM t").rows.size)
    }

    @Test
    fun dropTriggerStopsItFromFiringAndCannotBeDroppedTwice() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("CREATE TABLE audit (id INTEGER)")
        db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        db.run("DROP TRIGGER log")
        db.run("INSERT INTO t VALUES (1)")
        assertEquals(0, db.select("SELECT * FROM audit").rows.size)
        assertFailsWith<KestrelException> { db.run("DROP TRIGGER log") }
    }

    @Test
    fun droppingTheTableDropsItsTriggersToo() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN UPDATE t SET id = id; END")
        db.run("DROP TABLE t")
        assertFailsWith<KestrelException> { db.run("DROP TRIGGER log") }
    }

    @Test
    fun renamingTheTableKeepsItsTriggersFiring() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("CREATE TABLE audit (id INTEGER)")
        db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        db.run("ALTER TABLE t RENAME TO t2")
        db.run("INSERT INTO t2 VALUES (42)")
        assertEquals(listOf(listOf(int(42))), db.select("SELECT * FROM audit").rows)
    }

    @Test
    fun createTriggerRejectsUnknownTable() {
        val db = newDatabase()
        assertFailsWith<KestrelException> {
            db.run("CREATE TRIGGER log AFTER INSERT ON missing FOR EACH ROW BEGIN DELETE FROM missing WHERE 1 = 0; END")
        }
    }

    @Test
    fun createTriggerRejectsDuplicateName() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN DELETE FROM t WHERE 1 = 0; END")
        assertFailsWith<KestrelException> {
            db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN DELETE FROM t WHERE 1 = 0; END")
        }
    }

    @Test
    fun whenClauseRejectsAnUnqualifiedColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW WHEN id > 0 BEGIN DELETE FROM t WHERE 1 = 0; END")
        }
    }

    @Test
    fun whenClauseRejectsNewOnADeleteTrigger() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            db.run("CREATE TRIGGER log BEFORE DELETE ON t FOR EACH ROW WHEN NEW.id > 0 BEGIN DELETE FROM t WHERE 1 = 0; END")
        }
    }

    @Test
    fun whenClauseRejectsOldOnAnInsertTrigger() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            db.run("CREATE TRIGGER log BEFORE INSERT ON t FOR EACH ROW WHEN OLD.id > 0 BEGIN DELETE FROM t WHERE 1 = 0; END")
        }
    }

    @Test
    fun whenClauseAcceptsBothSidesOnAnUpdateTrigger() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("CREATE TRIGGER log BEFORE UPDATE ON t FOR EACH ROW WHEN NEW.id > OLD.id BEGIN DELETE FROM t WHERE 1 = 0; END")
    }

    @Test
    fun whenClauseRejectsAnUnknownColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW WHEN NEW.nope > 0 BEGIN DELETE FROM t WHERE 1 = 0; END")
        }
    }

    @Test
    fun whenClauseRejectsASubquery() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            db.run(
                "CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW WHEN EXISTS (SELECT 1 FROM t) " +
                    "BEGIN DELETE FROM t WHERE 1 = 0; END"
            )
        }
    }

    @Test
    fun bodyStatementRejectsAnUnknownTargetTable() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN DELETE FROM missing WHERE 1 = 0; END")
        }
    }

    @Test
    fun onConflictRowThatLandsWithNoConflictFiresInsertTriggers() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER)")
        db.run("CREATE TABLE audit (id INTEGER)")
        db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        db.run("INSERT INTO t VALUES (1, 1) ON CONFLICT (id) DO NOTHING")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT * FROM audit").rows)
    }

    @Test
    fun onConflictDoNothingFiresNoTriggerAtAllForTheConflictingRow() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER)")
        db.run("INSERT INTO t VALUES (1, 1)")
        db.run("CREATE TABLE audit (id INTEGER)")
        db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        db.run("INSERT INTO t VALUES (1, 99) ON CONFLICT (id) DO NOTHING")
        assertEquals(0, db.select("SELECT * FROM audit").rows.size)
    }

    @Test
    fun onConflictDoUpdateFiresUpdateTriggersNotInsertTriggers() {
        val db = newDatabase()
        db.run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("CREATE TABLE inserts (id INTEGER)")
        db.run("CREATE TABLE updates (id INTEGER, old_balance INTEGER, new_balance INTEGER)")
        db.run("CREATE TRIGGER log_insert AFTER INSERT ON accounts FOR EACH ROW BEGIN INSERT INTO inserts VALUES (NEW.id); END")
        db.run(
            "CREATE TRIGGER log_update AFTER UPDATE ON accounts FOR EACH ROW " +
                "BEGIN INSERT INTO updates VALUES (NEW.id, OLD.balance, NEW.balance); END"
        )
        db.run("INSERT INTO accounts VALUES (1, 999) ON CONFLICT (id) DO UPDATE SET balance = excluded.balance")
        assertEquals(0, db.select("SELECT * FROM inserts").rows.size)
        assertEquals(listOf(listOf(int(1), int(100), int(999))), db.select("SELECT * FROM updates").rows)
    }

    @Test
    fun onConflictBeforeUpdateTriggerCanRejectTheWholeStatement() {
        val db = newDatabase()
        db.run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("CREATE TABLE guard (x INTEGER NOT NULL)")
        db.run("CREATE TRIGGER reject BEFORE UPDATE ON accounts FOR EACH ROW BEGIN INSERT INTO guard VALUES (NULL); END")
        assertFailsWith<KestrelException> {
            db.run("INSERT INTO accounts VALUES (1, 999) ON CONFLICT (id) DO UPDATE SET balance = excluded.balance")
        }
        assertEquals(int(100), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
    }

    @Test
    fun onConflictBeforeUpdateTriggerRejectionSurvivesAReopen() {
        val db = newDatabase()
        db.run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        db.run("INSERT INTO accounts VALUES (1, 100)")
        db.run("CREATE TABLE guard (x INTEGER NOT NULL)")
        db.run("CREATE TRIGGER reject BEFORE UPDATE ON accounts FOR EACH ROW BEGIN INSERT INTO guard VALUES (NULL); END")
        assertFailsWith<KestrelException> {
            db.run("INSERT INTO accounts VALUES (1, 999) ON CONFLICT (id) DO UPDATE SET balance = excluded.balance")
        }
        db.reopen()
        assertEquals(int(100), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals(0, db.select("SELECT * FROM guard").rows.size)
    }

    @Test
    fun onConflictMultiRowStatementFiresTheRightTriggerPerRow() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, hits INTEGER)")
        db.run("INSERT INTO t VALUES (1, 10)")
        db.run("CREATE TABLE inserts (id INTEGER)")
        db.run("CREATE TABLE updates (id INTEGER)")
        db.run("CREATE TRIGGER log_insert AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO inserts VALUES (NEW.id); END")
        db.run("CREATE TRIGGER log_update AFTER UPDATE ON t FOR EACH ROW BEGIN INSERT INTO updates VALUES (NEW.id); END")
        db.run(
            """
            INSERT INTO t VALUES (1, 1), (2, 2)
            ON CONFLICT (id) DO UPDATE SET hits = t.hits + excluded.hits
            """.trimIndent()
        )
        assertEquals(listOf(listOf(int(2))), db.select("SELECT * FROM inserts").rows)
        assertEquals(listOf(listOf(int(1))), db.select("SELECT * FROM updates").rows)
    }

    @Test
    fun bodyStatementInAnInsertTriggerCanOnlySeeNewNotOld() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("CREATE TABLE audit (id INTEGER)")
        db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        db.run("INSERT INTO t VALUES (5)")
        assertEquals(listOf(listOf(int(5))), db.select("SELECT * FROM audit").rows)
        db.run("CREATE TRIGGER bad AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (OLD.id); END")
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (6)") }
    }

    @Test
    fun triggerFiringInsideAnExplicitTransactionParticipatesInItsCommit() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("CREATE TABLE audit (id INTEGER)")
        db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        db.run("BEGIN")
        db.run("INSERT INTO t VALUES (1)")
        db.run("ROLLBACK")
        assertEquals(0, db.select("SELECT * FROM t").rows.size)
        assertEquals(0, db.select("SELECT * FROM audit").rows.size)
    }

    @Test
    fun triggerCascadeAtomicitySurvivesAReopen() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE audit (order_id INTEGER NOT NULL)")
        db.run("CREATE TRIGGER log_fail AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NULL); END")
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (1)") }
        db.reopen()
        assertEquals(0, db.select("SELECT * FROM t").rows.size)
        assertEquals(0, db.select("SELECT * FROM audit").rows.size)
        db.run("DROP TRIGGER log_fail")
        db.run("CREATE TABLE audit2 (id INTEGER)")
        db.run("CREATE TRIGGER log_ok AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit2 VALUES (NEW.id); END")
        db.reopen()
        db.run("INSERT INTO t VALUES (9)")
        assertEquals(listOf(listOf(int(9))), db.select("SELECT * FROM audit2").rows)
    }
}
