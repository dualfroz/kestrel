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

class SequenceTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)

    @Test
    fun nextvalStartsAtOneAndIncrementsByOneByDefault() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(2)), listOf(int(3))), select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun createSequenceOptionsAreHonored() {
        run("CREATE SEQUENCE seq START WITH 100 INCREMENT BY 10")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(100)), listOf(int(110))), select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun descendingSequenceDefaultsToMaxvalueAndCountsDown() {
        run("CREATE SEQUENCE seq INCREMENT BY -1")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(-2)), listOf(int(-1))), select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun exhaustedAscendingSequenceWithoutCycleThrows() {
        run("CREATE SEQUENCE seq MAXVALUE 2")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (NEXTVAL('seq'))") }
    }

    @Test
    fun cyclingAscendingSequenceWrapsToMinvalue() {
        run("CREATE SEQUENCE seq MAXVALUE 2 CYCLE")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(2)), listOf(int(1))), select("SELECT id FROM t").rows)
    }

    @Test
    fun dropSequenceRemovesItAndFurtherNextvalFails() {
        run("CREATE SEQUENCE seq")
        run("DROP SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (NEXTVAL('seq'))") }
    }

    @Test
    fun duplicateCreateSequenceFails() {
        run("CREATE SEQUENCE seq")
        assertFailsWith<KestrelException> { run("CREATE SEQUENCE seq") }
    }

    @Test
    fun droppingAnUnknownSequenceFails() {
        assertFailsWith<KestrelException> { run("DROP SEQUENCE nope") }
    }

    @Test
    fun alterSequenceRestartWithResetsPosition() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq'))")
        run("ALTER SEQUENCE seq RESTART WITH 50")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(2)), listOf(int(50))), select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun bareRestartResetsToOriginalStartValue() {
        run("CREATE SEQUENCE seq START WITH 5")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq'))")
        run("ALTER SEQUENCE seq RESTART")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(5)), listOf(int(5)), listOf(int(6))), select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun alterSequenceIncrementByWithoutRestartPreservesCurrentPosition() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        run("ALTER SEQUENCE seq INCREMENT BY 100")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(101))), select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun alterSequenceRestartValueOutOfNewBoundsFails() {
        run("CREATE SEQUENCE seq MAXVALUE 10")
        assertFailsWith<KestrelException> { run("ALTER SEQUENCE seq RESTART WITH 20") }
    }

    @Test
    fun alteringAnUnknownSequenceFails() {
        assertFailsWith<KestrelException> { run("ALTER SEQUENCE nope INCREMENT BY 2") }
    }

    @Test
    fun currvalReturnsLastNextvalInThisSession() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER, again INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq'), CURRVAL('seq'))")
        assertEquals(listOf(listOf(int(1), int(1))), select("SELECT id, again FROM t").rows)
    }

    @Test
    fun currvalBeforeAnyNextvalInThisSessionFails() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (CURRVAL('seq'))") }
    }

    @Test
    fun alterSequenceInvalidatesRememberedCurrvalForThatSession() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        run("ALTER SEQUENCE seq INCREMENT BY 1")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (CURRVAL('seq'))") }
    }

    @Test
    fun rollbackDoesNotUndoANextvalAdvance() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        run("BEGIN")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        run("ROLLBACK")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        // The rolled-back row never landed, but NEXTVAL's advance to 1 survives - the surviving
        // insert gets 2, not a reused 1.
        assertEquals(listOf(listOf(int(2))), select("SELECT id FROM t").rows)
    }

    @Test
    fun rollbackUndoesCreateSequence() {
        run("BEGIN")
        run("CREATE SEQUENCE seq")
        run("ROLLBACK")
        assertFailsWith<KestrelException> { run("DROP SEQUENCE seq") }
    }

    @Test
    fun rollbackUndoesDropSequence() {
        run("CREATE SEQUENCE seq")
        run("BEGIN")
        run("DROP SEQUENCE seq")
        run("ROLLBACK")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM t").rows)
    }

    @Test
    fun rollbackUndoesAlterSequenceButNotTheNextvalAdvanceMadeBeforeIt() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        run("BEGIN")
        run("ALTER SEQUENCE seq INCREMENT BY 100")
        run("ROLLBACK")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun nextvalAsColumnDefaultActsLikeAnAutoIncrement() {
        run("CREATE SEQUENCE order_ids")
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('order_ids'), item TEXT)")
        run("INSERT INTO orders (item) VALUES ('a'), ('b'), ('c')")
        assertEquals(
            listOf(listOf(int(1), Value.TextValue("a")), listOf(int(2), Value.TextValue("b")), listOf(int(3), Value.TextValue("c"))),
            select("SELECT id, item FROM orders ORDER BY id").rows
        )
    }

    @Test
    fun createTableWithNextvalDefaultRequiresTheSequenceToAlreadyExist() {
        assertFailsWith<KestrelException> {
            run("CREATE TABLE orders (id INTEGER DEFAULT NEXTVAL('nope'))")
        }
    }

    @Test
    fun createTableDoesNotConsumeASequenceValueJustToValidateTheDefault() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE orders (id INTEGER DEFAULT NEXTVAL('seq'))")
        run("INSERT INTO orders (id) VALUES (DEFAULT)")
        // If CREATE TABLE's own validation had already called NEXTVAL once, this would be 2.
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM orders").rows)
    }

    @Test
    fun nextvalDefaultOnANonIntegerColumnFails() {
        run("CREATE SEQUENCE seq")
        assertFailsWith<KestrelException> {
            run("CREATE TABLE orders (id TEXT DEFAULT NEXTVAL('seq'))")
        }
    }

    @Test
    fun currvalAsColumnDefaultIsRejected() {
        run("CREATE SEQUENCE seq")
        assertFailsWith<KestrelException> {
            run("CREATE TABLE orders (id INTEGER DEFAULT CURRVAL('seq'))")
        }
    }

    @Test
    fun addColumnWithNextvalDefaultIsRejected() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            run("ALTER TABLE t ADD COLUMN seq_id INTEGER DEFAULT NEXTVAL('seq')")
        }
    }

    @Test
    fun nextvalIsUsableInUpdateSet() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NULL)")
        run("UPDATE t SET id = NEXTVAL('seq')")
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM t").rows)
    }

    @Test
    fun nextvalInAWhereClauseIsRejected() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (1)")
        assertFailsWith<KestrelException> { run("UPDATE t SET id = 2 WHERE id = NEXTVAL('seq')") }
    }

    @Test
    fun nextvalInACheckConstraintIsRejectedAtCreateTableTime() {
        run("CREATE SEQUENCE seq")
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER CHECK (id = NEXTVAL('seq')))")
        }
    }

    @Test
    fun nextvalInATriggerWhenClauseIsRejected() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<KestrelException> {
            run("CREATE TRIGGER trg BEFORE INSERT ON t FOR EACH ROW WHEN (NEXTVAL('seq') = 1) BEGIN DELETE FROM t; END")
        }
    }

    @Test
    fun createSequenceMinvalueExceedingMaxvalueFails() {
        assertFailsWith<KestrelException> { run("CREATE SEQUENCE seq MINVALUE 10 MAXVALUE 5") }
    }

    @Test
    fun createSequenceIncrementByZeroFails() {
        assertFailsWith<KestrelException> { run("CREATE SEQUENCE seq INCREMENT BY 0") }
    }

    @Test
    fun createSequenceStartOutsideBoundsFails() {
        assertFailsWith<KestrelException> { run("CREATE SEQUENCE seq MINVALUE 10 MAXVALUE 20 START WITH 5") }
    }

    @Test
    fun noMinvalueAndNoMaxvalueOnAlterRecomputeTheDefaultBound() {
        run("CREATE SEQUENCE seq MINVALUE 1 MAXVALUE 3")
        run("ALTER SEQUENCE seq NO MAXVALUE")
        run("CREATE TABLE t (id INTEGER)")
        // Was capped at 3 before the ALTER; NO MAXVALUE lifts it back to the ascending default.
        run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq')), (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(4, select("SELECT id FROM t").rows.size)
    }
}
