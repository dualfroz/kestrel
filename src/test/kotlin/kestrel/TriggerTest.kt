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
import kotlin.test.assertTrue

class TriggerTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    @Test
    fun afterInsertTriggerRunsBodyForEachInsertedRow() {
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("CREATE TABLE audit (order_id INTEGER)")
        run(
            "CREATE TRIGGER log_insert AFTER INSERT ON orders FOR EACH ROW " +
                "BEGIN INSERT INTO audit VALUES (NEW.id); END"
        )
        run("INSERT INTO orders VALUES (1, 100), (2, 200)")
        assertEquals(
            listOf(listOf(int(1)), listOf(int(2))),
            select("SELECT order_id FROM audit ORDER BY order_id").rows
        )
    }

    @Test
    fun beforeInsertTriggerCanAbortTheWholeStatementBeforeThisEnginesOwnConstraintsRun() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE guard (x INTEGER NOT NULL)")
        run("CREATE TRIGGER reject_all BEFORE INSERT ON t FOR EACH ROW BEGIN INSERT INTO guard VALUES (NULL); END")
        // The row itself is perfectly valid for t's own PRIMARY KEY - only the trigger rejects it.
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (1)") }
        assertEquals(0, select("SELECT * FROM t").rows.size)
    }

    @Test
    fun beforeTriggerSideEffectsAreRolledBackWhenTheFiringStatementItselfFails() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY CHECK (id > 0))")
        run("CREATE TABLE counter (n INTEGER)")
        run("INSERT INTO counter VALUES (0)")
        run("CREATE TRIGGER bump BEFORE INSERT ON t FOR EACH ROW BEGIN UPDATE counter SET n = n + 1; END")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (-1)") }
        assertEquals(listOf(listOf(int(0))), select("SELECT n FROM counter").rows)
        assertEquals(0, select("SELECT * FROM t").rows.size)
    }

    @Test
    fun afterInsertTriggerFailureRollsBackTheOriginalInsertToo() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE audit (order_id INTEGER NOT NULL)")
        run("CREATE TRIGGER log_fail AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NULL); END")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (1)") }
        assertEquals(0, select("SELECT * FROM t").rows.size)
        assertEquals(0, select("SELECT * FROM audit").rows.size)
    }

    @Test
    fun whenClauseGatesFiringPerRow() {
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("CREATE TABLE audit (order_id INTEGER)")
        run(
            "CREATE TRIGGER log_big AFTER INSERT ON orders FOR EACH ROW WHEN NEW.amount > 100 " +
                "BEGIN INSERT INTO audit VALUES (NEW.id); END"
        )
        run("INSERT INTO orders VALUES (1, 50), (2, 200)")
        assertEquals(listOf(listOf(int(2))), select("SELECT order_id FROM audit").rows)
    }

    @Test
    fun updateTriggerSeesBothOldAndNew() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        run("CREATE TABLE audit (account_id INTEGER, old_balance INTEGER, new_balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 100)")
        run(
            "CREATE TRIGGER log_balance_change AFTER UPDATE ON accounts FOR EACH ROW " +
                "BEGIN INSERT INTO audit VALUES (NEW.id, OLD.balance, NEW.balance); END"
        )
        run("UPDATE accounts SET balance = 150 WHERE id = 1")
        assertEquals(listOf(listOf(int(1), int(100), int(150))), select("SELECT * FROM audit").rows)
    }

    @Test
    fun updateTriggerFiresForEveryMatchedRowEvenWhenTheValueIsUnchanged() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER)")
        run("CREATE TABLE audit (n INTEGER)")
        run("INSERT INTO t VALUES (1, 5)")
        run("CREATE TRIGGER log AFTER UPDATE ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (1); END")
        run("UPDATE t SET x = 5 WHERE id = 1")
        assertEquals(1, select("SELECT * FROM audit").rows.size)
    }

    @Test
    fun deleteTriggerSeesOld() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, label TEXT)")
        run("CREATE TABLE audit (label TEXT)")
        run("INSERT INTO t VALUES (1, 'gone')")
        run("CREATE TRIGGER log_delete AFTER DELETE ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (OLD.label); END")
        run("DELETE FROM t WHERE id = 1")
        assertEquals(listOf(listOf(text("gone"))), select("SELECT * FROM audit").rows)
    }

    @Test
    fun multipleTriggersOnSameEventFireInCreationOrder() {
        run("CREATE TABLE t (id INTEGER)")
        run("CREATE TABLE audit (step TEXT)")
        run("CREATE TRIGGER first_trigger AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES ('first'); END")
        run("CREATE TRIGGER second_trigger AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES ('second'); END")
        run("INSERT INTO t VALUES (1)")
        assertEquals(listOf(listOf(text("first")), listOf(text("second"))), select("SELECT * FROM audit").rows)
    }

    @Test
    fun cascadingTriggerFiresATriggerOnADifferentTable() {
        run("CREATE TABLE a (id INTEGER)")
        run("CREATE TABLE b (id INTEGER)")
        run("CREATE TABLE c (id INTEGER)")
        run("CREATE TRIGGER a_to_b AFTER INSERT ON a FOR EACH ROW BEGIN INSERT INTO b VALUES (NEW.id); END")
        run("CREATE TRIGGER b_to_c AFTER INSERT ON b FOR EACH ROW BEGIN INSERT INTO c VALUES (NEW.id); END")
        run("INSERT INTO a VALUES (7)")
        assertEquals(listOf(listOf(int(7))), select("SELECT * FROM b").rows)
        assertEquals(listOf(listOf(int(7))), select("SELECT * FROM c").rows)
    }

    @Test
    fun recursiveTriggerHitsTheDepthGuardInsteadOfLoopingForeverAndRollsBackCleanly() {
        run("CREATE TABLE t (id INTEGER)")
        run("CREATE TRIGGER loopy AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO t VALUES (NEW.id + 1); END")
        val exception = assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (1)") }
        assertTrue(exception.message!!.contains("recursion", ignoreCase = true), exception.message!!)
        assertEquals(0, select("SELECT * FROM t").rows.size)
    }

    @Test
    fun dropTriggerStopsItFromFiringAndCannotBeDroppedTwice() {
        run("CREATE TABLE t (id INTEGER)")
        run("CREATE TABLE audit (id INTEGER)")
        run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        run("DROP TRIGGER log")
        run("INSERT INTO t VALUES (1)")
        assertEquals(0, select("SELECT * FROM audit").rows.size)
        assertFailsWith<KestrelException> { run("DROP TRIGGER log") }
    }

    @Test
    fun droppingTheTableDropsItsTriggersToo() {
        run("CREATE TABLE t (id INTEGER)")
        run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN UPDATE t SET id = id; END")
        run("DROP TABLE t")
        assertFailsWith<KestrelException> { run("DROP TRIGGER log") }
    }

    @Test
    fun renamingTheTableKeepsItsTriggersFiring() {
        run("CREATE TABLE t (id INTEGER)")
        run("CREATE TABLE audit (id INTEGER)")
        run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        run("ALTER TABLE t RENAME TO t2")
        run("INSERT INTO t2 VALUES (42)")
        assertEquals(listOf(listOf(int(42))), select("SELECT * FROM audit").rows)
    }

    @Test
    fun rollbackUndoesCreateTrigger() {
        run("CREATE TABLE t (id INTEGER)")
        run("BEGIN")
        run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN DELETE FROM t WHERE 1 = 0; END")
        run("ROLLBACK")
        assertFailsWith<KestrelException> { run("DROP TRIGGER log") }
    }

    @Test
    fun createTriggerRejectsUnknownTable() {
        assertFailsWith<KestrelException> {
            run("CREATE TRIGGER log AFTER INSERT ON missing FOR EACH ROW BEGIN DELETE FROM missing WHERE 1 = 0; END")
        }
    }

    @Test
    fun createTriggerRejectsDuplicateName() {
        run("CREATE TABLE t (id INTEGER)")
        run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN DELETE FROM t WHERE 1 = 0; END")
        assertFailsWith<KestrelException> {
            run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN DELETE FROM t WHERE 1 = 0; END")
        }
    }

    @Test
    fun whenClauseRejectsAnUnqualifiedColumn() {
        run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW WHEN id > 0 BEGIN DELETE FROM t WHERE 1 = 0; END")
        }
    }

    @Test
    fun whenClauseRejectsNewOnADeleteTrigger() {
        run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            run("CREATE TRIGGER log BEFORE DELETE ON t FOR EACH ROW WHEN NEW.id > 0 BEGIN DELETE FROM t WHERE 1 = 0; END")
        }
    }

    @Test
    fun whenClauseRejectsOldOnAnInsertTrigger() {
        run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            run("CREATE TRIGGER log BEFORE INSERT ON t FOR EACH ROW WHEN OLD.id > 0 BEGIN DELETE FROM t WHERE 1 = 0; END")
        }
    }

    @Test
    fun whenClauseAcceptsBothSidesOnAnUpdateTrigger() {
        run("CREATE TABLE t (id INTEGER)")
        run("CREATE TRIGGER log BEFORE UPDATE ON t FOR EACH ROW WHEN NEW.id > OLD.id BEGIN DELETE FROM t WHERE 1 = 0; END")
    }

    @Test
    fun whenClauseRejectsAnUnknownColumn() {
        run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW WHEN NEW.nope > 0 BEGIN DELETE FROM t WHERE 1 = 0; END")
        }
    }

    @Test
    fun whenClauseRejectsASubquery() {
        run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            run(
                "CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW WHEN EXISTS (SELECT 1 FROM t) " +
                    "BEGIN DELETE FROM t WHERE 1 = 0; END"
            )
        }
    }

    @Test
    fun bodyStatementRejectsAnUnknownTargetTable() {
        run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN DELETE FROM missing WHERE 1 = 0; END")
        }
    }

    @Test
    fun onConflictRowThatLandsWithNoConflictFiresInsertTriggers() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER)")
        run("CREATE TABLE audit (id INTEGER)")
        run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        run("INSERT INTO t VALUES (1, 1) ON CONFLICT (id) DO NOTHING")
        assertEquals(listOf(listOf(int(1))), select("SELECT * FROM audit").rows)
    }

    @Test
    fun onConflictDoNothingFiresNoTriggerAtAllForTheConflictingRow() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER)")
        run("INSERT INTO t VALUES (1, 1)")
        run("CREATE TABLE audit (id INTEGER)")
        run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        run("INSERT INTO t VALUES (1, 99) ON CONFLICT (id) DO NOTHING")
        assertEquals(0, select("SELECT * FROM audit").rows.size)
    }

    @Test
    fun onConflictDoUpdateFiresUpdateTriggersNotInsertTriggers() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 100)")
        run("CREATE TABLE inserts (id INTEGER)")
        run("CREATE TABLE updates (id INTEGER, old_balance INTEGER, new_balance INTEGER)")
        run("CREATE TRIGGER log_insert AFTER INSERT ON accounts FOR EACH ROW BEGIN INSERT INTO inserts VALUES (NEW.id); END")
        run(
            "CREATE TRIGGER log_update AFTER UPDATE ON accounts FOR EACH ROW " +
                "BEGIN INSERT INTO updates VALUES (NEW.id, OLD.balance, NEW.balance); END"
        )
        run("INSERT INTO accounts VALUES (1, 999) ON CONFLICT (id) DO UPDATE SET balance = excluded.balance")
        assertEquals(0, select("SELECT * FROM inserts").rows.size)
        assertEquals(listOf(listOf(int(1), int(100), int(999))), select("SELECT * FROM updates").rows)
    }

    @Test
    fun onConflictBeforeUpdateTriggerCanRejectTheWholeStatement() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 100)")
        run("CREATE TABLE guard (x INTEGER NOT NULL)")
        run("CREATE TRIGGER reject BEFORE UPDATE ON accounts FOR EACH ROW BEGIN INSERT INTO guard VALUES (NULL); END")
        assertFailsWith<KestrelException> {
            run("INSERT INTO accounts VALUES (1, 999) ON CONFLICT (id) DO UPDATE SET balance = excluded.balance")
        }
        assertEquals(int(100), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
    }

    @Test
    fun onConflictMultiRowStatementFiresTheRightTriggerPerRow() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, hits INTEGER)")
        run("INSERT INTO t VALUES (1, 10)")
        run("CREATE TABLE inserts (id INTEGER)")
        run("CREATE TABLE updates (id INTEGER)")
        run("CREATE TRIGGER log_insert AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO inserts VALUES (NEW.id); END")
        run("CREATE TRIGGER log_update AFTER UPDATE ON t FOR EACH ROW BEGIN INSERT INTO updates VALUES (NEW.id); END")
        run(
            """
            INSERT INTO t VALUES (1, 1), (2, 2)
            ON CONFLICT (id) DO UPDATE SET hits = t.hits + excluded.hits
            """.trimIndent()
        )
        assertEquals(listOf(listOf(int(2))), select("SELECT * FROM inserts").rows)
        assertEquals(listOf(listOf(int(1))), select("SELECT * FROM updates").rows)
    }

    @Test
    fun bodyStatementInAnInsertTriggerCanOnlySeeNewNotOld() {
        run("CREATE TABLE t (id INTEGER)")
        run("CREATE TABLE audit (id INTEGER)")
        run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        run("INSERT INTO t VALUES (5)")
        assertEquals(listOf(listOf(int(5))), select("SELECT * FROM audit").rows)
        // OLD is simply not bound for an INSERT trigger, so an OLD.id reference in the body falls
        // through exactly like any other unresolvable column in an INSERT's VALUES list would.
        run("CREATE TRIGGER bad AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (OLD.id); END")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (6)") }
    }
}
