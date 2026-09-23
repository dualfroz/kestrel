package kestrel.store.sql

import kestrel.KestrelException
import kestrel.engine.AlterSequenceResult
import kestrel.engine.CreateSequenceResult
import kestrel.engine.DropSequenceResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.ast.Statement
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DiskExecutorSequenceTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun parse(sql: String): List<Statement> = Parser(Lexer(sql).tokenize()).parseProgram()

    private fun int(value: Long) = Value.IntegerValue(value)

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

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-seq-data"), tempFile("kestrel-disk-seq-wal"))

    @Test
    fun createSequenceReturnsItsNameAndNextvalStartsAtOneByDefault() {
        val db = newDatabase()
        assertEquals(CreateSequenceResult("seq"), db.run("CREATE SEQUENCE seq").single())
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(2)), listOf(int(3))), db.select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun createSequenceOptionsAreHonored() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq START WITH 100 INCREMENT BY 10")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(100)), listOf(int(110))), db.select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun descendingSequenceDefaultsToMaxvalueAndCountsDown() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq INCREMENT BY -1")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(-2)), listOf(int(-1))), db.select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun exhaustedAscendingSequenceWithoutCycleThrows() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq MAXVALUE 2")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (NEXTVAL('seq'))") }
    }

    @Test
    fun cyclingAscendingSequenceWrapsToMinvalue() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq MAXVALUE 2 CYCLE")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(2)), listOf(int(1))), db.select("SELECT id FROM t").rows)
    }

    @Test
    fun dropSequenceReturnsItsNameAndFurtherNextvalFails() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        assertEquals(DropSequenceResult("seq"), db.run("DROP SEQUENCE seq").single())
        db.run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<DiskCatalogException> { db.run("INSERT INTO t VALUES (NEXTVAL('seq'))") }
    }

    @Test
    fun duplicateCreateSequenceFails() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        assertFailsWith<DiskCatalogException> { db.run("CREATE SEQUENCE seq") }
    }

    @Test
    fun droppingAnUnknownSequenceFails() {
        val db = newDatabase()
        assertFailsWith<DiskCatalogException> { db.run("DROP SEQUENCE nope") }
    }

    @Test
    fun alterSequenceReturnsItsNameAndRestartWithResetsPosition() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(AlterSequenceResult("seq"), db.run("ALTER SEQUENCE seq RESTART WITH 50").single())
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(2)), listOf(int(50))), db.select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun bareRestartResetsToOriginalStartValue() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq START WITH 5")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq'))")
        db.run("ALTER SEQUENCE seq RESTART")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(5)), listOf(int(5)), listOf(int(6))), db.select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun alterSequenceIncrementByWithoutRestartPreservesCurrentPosition() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        db.run("ALTER SEQUENCE seq INCREMENT BY 100")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(101))), db.select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun alterSequenceRestartValueOutOfNewBoundsFails() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq MAXVALUE 10")
        assertFailsWith<DiskExecutorException> { db.run("ALTER SEQUENCE seq RESTART WITH 20") }
    }

    @Test
    fun alteringAnUnknownSequenceFails() {
        val db = newDatabase()
        assertFailsWith<DiskCatalogException> { db.run("ALTER SEQUENCE nope INCREMENT BY 2") }
    }

    @Test
    fun currvalReturnsLastNextvalInThisSession() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER, again INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'), CURRVAL('seq'))")
        assertEquals(listOf(listOf(int(1), int(1))), db.select("SELECT id, again FROM t").rows)
    }

    @Test
    fun currvalBeforeAnyNextvalInThisSessionFails() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<DiskExecutorException> { db.run("INSERT INTO t VALUES (CURRVAL('seq'))") }
    }

    @Test
    fun currvalIsScopedPerExecutorInstanceNotPerCatalog() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        val secondExecutor = DiskExecutor(db.catalog)
        assertFailsWith<DiskExecutorException> {
            parse("INSERT INTO t VALUES (CURRVAL('seq'))").map { secondExecutor.execute(it) }
        }
    }

    @Test
    fun alterSequenceInvalidatesRememberedCurrvalForThatSession() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        db.run("ALTER SEQUENCE seq INCREMENT BY 1")
        assertFailsWith<DiskExecutorException> { db.run("INSERT INTO t VALUES (CURRVAL('seq'))") }
    }

    @Test
    fun rollbackDoesNotUndoANextvalAdvance() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("BEGIN")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        db.run("ROLLBACK")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(2))), db.select("SELECT id FROM t").rows)
    }

    @Test
    fun createSequenceIsRejectedInsideAnExplicitTransaction() {
        val db = newDatabase()
        db.run("BEGIN")
        assertFailsWith<DiskExecutorException> { db.run("CREATE SEQUENCE seq") }
    }

    @Test
    fun dropSequenceIsRejectedInsideAnExplicitTransaction() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("BEGIN")
        assertFailsWith<DiskExecutorException> { db.run("DROP SEQUENCE seq") }
    }

    @Test
    fun alterSequenceIsRejectedInsideAnExplicitTransaction() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("BEGIN")
        assertFailsWith<DiskExecutorException> { db.run("ALTER SEQUENCE seq INCREMENT BY 2") }
    }

    @Test
    fun nextvalAsColumnDefaultActsLikeAnAutoIncrement() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE order_ids")
        db.run("CREATE TABLE orders (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('order_ids'), item TEXT)")
        db.run("INSERT INTO orders (item) VALUES ('a'), ('b'), ('c')")
        assertEquals(
            listOf(listOf(int(1), Value.TextValue("a")), listOf(int(2), Value.TextValue("b")), listOf(int(3), Value.TextValue("c"))),
            db.select("SELECT id, item FROM orders ORDER BY id").rows
        )
    }

    @Test
    fun createTableWithNextvalDefaultRequiresTheSequenceToAlreadyExist() {
        val db = newDatabase()
        assertFailsWith<DiskCatalogException> { db.run("CREATE TABLE orders (id INTEGER DEFAULT NEXTVAL('nope'))") }
    }

    @Test
    fun createTableDoesNotConsumeASequenceValueJustToValidateTheDefault() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE orders (id INTEGER DEFAULT NEXTVAL('seq'))")
        db.run("INSERT INTO orders (id) VALUES (DEFAULT)")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT id FROM orders").rows)
    }

    @Test
    fun nextvalDefaultOnANonIntegerColumnFails() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        assertFailsWith<DiskExecutorException> { db.run("CREATE TABLE orders (id TEXT DEFAULT NEXTVAL('seq'))") }
    }

    @Test
    fun currvalAsColumnDefaultIsRejected() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        assertFailsWith<DiskExecutorException> { db.run("CREATE TABLE orders (id INTEGER DEFAULT CURRVAL('seq'))") }
    }

    @Test
    fun addColumnWithNextvalDefaultIsRejected() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        assertFailsWith<DiskExecutorException> { db.run("ALTER TABLE t ADD COLUMN seq_id INTEGER DEFAULT NEXTVAL('seq')") }
    }

    @Test
    fun nextvalIsUsableInUpdateSet() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NULL)")
        db.run("UPDATE t SET id = NEXTVAL('seq')")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT id FROM t").rows)
    }

    @Test
    fun nextvalInAWhereClauseIsRejected() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (1)")
        assertFailsWith<KestrelException> { db.run("UPDATE t SET id = 2 WHERE id = NEXTVAL('seq')") }
    }

    @Test
    fun nextvalInACheckConstraintIsRejectedAtCreateTableTime() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        assertFailsWith<DiskExecutorException> { db.run("CREATE TABLE t (id INTEGER CHECK (id = NEXTVAL('seq')))") }
    }

    @Test
    fun createSequenceMinvalueExceedingMaxvalueFails() {
        val db = newDatabase()
        assertFailsWith<DiskExecutorException> { db.run("CREATE SEQUENCE seq MINVALUE 10 MAXVALUE 5") }
    }

    @Test
    fun createSequenceIncrementByZeroFails() {
        val db = newDatabase()
        assertFailsWith<DiskExecutorException> { db.run("CREATE SEQUENCE seq INCREMENT BY 0") }
    }

    @Test
    fun createSequenceStartOutsideBoundsFails() {
        val db = newDatabase()
        assertFailsWith<DiskExecutorException> { db.run("CREATE SEQUENCE seq MINVALUE 10 MAXVALUE 20 START WITH 5") }
    }

    @Test
    fun noMinvalueAndNoMaxvalueOnAlterRecomputeTheDefaultBound() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq MINVALUE 1 MAXVALUE 3")
        db.run("ALTER SEQUENCE seq NO MAXVALUE")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq')), (NEXTVAL('seq')), (NEXTVAL('seq'))")
        assertEquals(4, db.select("SELECT id FROM t").rows.size)
    }

    @Test
    fun sequencePositionSurvivesAReopen() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq'))")
        db.reopen()
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(2)), listOf(int(3))), db.select("SELECT id FROM t ORDER BY id").rows)
    }

    @Test
    fun alterSequenceRestartPositionSurvivesAReopen() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        db.run("ALTER SEQUENCE seq RESTART WITH 1000")
        db.reopen()
        db.run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(int(1)), listOf(int(1000))), db.select("SELECT id FROM t ORDER BY id").rows)
    }
}
