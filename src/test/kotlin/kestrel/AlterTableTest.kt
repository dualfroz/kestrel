package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.AlterTableResult
import kestrel.engine.ExplainResult
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.UpdateResult
import kestrel.persistence.SqlWriter
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `ALTER TABLE ... ADD/DROP/RENAME COLUMN` and `DEFAULT` - the engine previously had no way to
 * change a table's shape after `CREATE TABLE` at all, and no way to fill a column omitted from an
 * INSERT column list with anything but NULL.
 */
class AlterTableTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun explain(sql: String): List<String> = (exec(sql) as ExplainResult).plan

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)
    private fun real(value: Double) = Value.RealValue(value)
    private fun decimal(value: String) = Value.DecimalValue(BigDecimal(value))

    // ---- ADD COLUMN ----

    @Test
    fun addColumnAppendsNullToExistingRowsWhenNoDefaultIsGiven() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO t VALUES (1, 'ada')")
        val result = exec("ALTER TABLE t ADD COLUMN age INTEGER")
        assertEquals(AlterTableResult("t"), result)
        assertEquals(listOf(listOf(int(1), text("ada"), Value.NullValue)), select("SELECT * FROM t").rows)
    }

    @Test
    fun addColumnBackfillsExistingRowsWithDefaultValue() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO t VALUES (1, 'ada'), (2, 'linus')")
        run("ALTER TABLE t ADD COLUMN status TEXT DEFAULT 'active'")
        assertEquals(
            listOf(listOf(text("active")), listOf(text("active"))),
            select("SELECT status FROM t ORDER BY id ASC").rows
        )
    }

    @Test
    fun addColumnRejectsNotNullWithoutDefaultWhenTableHasRows() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ADD COLUMN name TEXT NOT NULL") }
        assertTrue(error.message!!.contains("NOT NULL"))
    }

    @Test
    fun addColumnAllowsNotNullWithoutDefaultWhenTableIsEmpty() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("ALTER TABLE t ADD COLUMN name TEXT NOT NULL")
        run("INSERT INTO t VALUES (1, 'ada')")
        assertEquals(listOf(listOf(text("ada"))), select("SELECT name FROM t").rows)
        assertFailsWith<KestrelException> { run("INSERT INTO t (id) VALUES (2)") }
    }

    @Test
    fun addColumnWithNotNullAndDefaultBackfillsAndKeepsEnforcingGoingForward() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        run("ALTER TABLE t ADD COLUMN score INTEGER NOT NULL DEFAULT 0")
        assertEquals(listOf(listOf(int(0))), select("SELECT score FROM t").rows)
        assertFailsWith<KestrelException> { run("INSERT INTO t (id, score) VALUES (2, NULL)") }
    }

    @Test
    fun addColumnRejectsDuplicateColumnName() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ADD COLUMN name TEXT") }
        assertTrue(error.message!!.contains("duplicate column"))
    }

    @Test
    fun addColumnRequiresTableToExist() {
        assertFailsWith<KestrelException> { run("ALTER TABLE missing ADD COLUMN x INTEGER") }
    }

    @Test
    fun newColumnParticipatesInInsertUpdateWhereAndSelect() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO t VALUES (1, 'ada')")
        run("ALTER TABLE t ADD COLUMN age INTEGER DEFAULT 0")
        run("INSERT INTO t VALUES (2, 'linus', 54)")
        run("UPDATE t SET age = 36 WHERE id = 1")
        assertEquals(
            listOf(listOf(text("linus"))),
            select("SELECT name FROM t WHERE age > 40").rows
        )
    }

    @Test
    fun defaultExpressionCanBeAnArithmeticConstantNotJustALiteral() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        run("ALTER TABLE t ADD COLUMN adjustment INTEGER DEFAULT -1 + 10")
        assertEquals(listOf(listOf(int(9))), select("SELECT adjustment FROM t").rows)
    }

    @Test
    fun addColumnRejectsPrimaryKeyReferencesUniqueAndCheckConstraints() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        assertFailsWith<ParseException> { run("ALTER TABLE t ADD COLUMN id2 INTEGER PRIMARY KEY") }
        assertFailsWith<ParseException> { run("ALTER TABLE t ADD COLUMN dept_id INTEGER REFERENCES departments(id)") }
        assertFailsWith<ParseException> { run("ALTER TABLE t ADD COLUMN email TEXT UNIQUE") }
        assertFailsWith<ParseException> { run("ALTER TABLE t ADD COLUMN age INTEGER CHECK (age > 0)") }
    }

    // ---- DROP COLUMN ----

    @Test
    fun dropColumnRemovesColumnFromSchemaAndData() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, bio TEXT)")
        run("INSERT INTO t VALUES (1, 'ada', 'mathematician')")
        run("ALTER TABLE t DROP COLUMN bio")
        val result = select("SELECT * FROM t")
        assertEquals(listOf("id", "name"), result.columns)
        assertEquals(listOf(listOf(int(1), text("ada"))), result.rows)
    }

    @Test
    fun dropColumnRejectsWhenItIsTheOnlyColumn() {
        run("CREATE TABLE t (id INTEGER)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t DROP COLUMN id") }
        assertTrue(error.message!!.contains("no columns"))
    }

    @Test
    fun dropColumnRejectsPrimaryKeyColumnEvenWithCascade() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t DROP COLUMN id CASCADE") }
        assertTrue(error.message!!.contains("PRIMARY KEY"))
    }

