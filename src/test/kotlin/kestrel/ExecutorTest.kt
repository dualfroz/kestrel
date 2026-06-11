package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.DeleteResult
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.UpdateResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExecutorTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun seedUsers() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER)")
        run("INSERT INTO users VALUES (1, 'ada', 36), (2, 'linus', 54), (3, 'grace', 85)")
    }

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    @Test
    fun insertReportsRowCount() {
        run("CREATE TABLE t (a INTEGER)")
        val inserted = run("INSERT INTO t VALUES (1), (2), (3)").single()
        assertEquals(InsertResult(3), inserted)
    }

    @Test
    fun selectStarReturnsAllRows() {
        seedUsers()
        val result = select("SELECT * FROM users")
        assertEquals(listOf("id", "name", "age"), result.columns)
        assertEquals(3, result.rows.size)
        assertEquals(listOf(int(1), text("ada"), int(36)), result.rows[0])
    }

    @Test
    fun whereFiltersRows() {
        seedUsers()
        val result = select("SELECT name FROM users WHERE age > 40")
        assertEquals(listOf(listOf(text("linus")), listOf(text("grace"))), result.rows)
    }

    @Test
    fun orderByDescendingSortsRows() {
        seedUsers()
        val result = select("SELECT name, age FROM users WHERE age > 40 ORDER BY age DESC")
        assertEquals(listOf(text("grace"), int(85)), result.rows[0])
        assertEquals(listOf(text("linus"), int(54)), result.rows[1])
    }

    @Test
    fun orderByAscendingSortsRows() {
        seedUsers()
        val result = select("SELECT name FROM users ORDER BY age ASC")
        assertEquals(listOf(listOf(text("ada")), listOf(text("linus")), listOf(text("grace"))), result.rows)
    }

    @Test
    fun limitCapsResultSize() {
        seedUsers()
        val result = select("SELECT name FROM users ORDER BY age DESC LIMIT 2")
        assertEquals(2, result.rows.size)
        assertEquals(listOf(text("grace")), result.rows[0])
    }

    @Test
    fun projectionSupportsExpressionsAndAliases() {
        seedUsers()
        val result = select("SELECT name AS who, age + 1 AS nextYear FROM users WHERE id = 1")
        assertEquals(listOf("who", "nextYear"), result.columns)
        assertEquals(listOf(text("ada"), int(37)), result.rows[0])
    }

    @Test
    fun updateMutatesMatchingRows() {
        seedUsers()
        val updated = run("UPDATE users SET age = age + 10 WHERE name = 'ada'").single()
        assertEquals(UpdateResult(1), updated)
        val result = select("SELECT age FROM users WHERE id = 1")
        assertEquals(listOf(int(46)), result.rows[0])
    }

    @Test
    fun deleteRemovesMatchingRows() {
        seedUsers()
        val deleted = run("DELETE FROM users WHERE age < 50").single()
        assertEquals(DeleteResult(1), deleted)
        assertEquals(2, select("SELECT * FROM users").rows.size)
    }

    @Test
    fun deleteWithoutWhereClearsTable() {
        seedUsers()
        assertEquals(DeleteResult(3), run("DELETE FROM users").single())
        assertEquals(0, select("SELECT * FROM users").rows.size)
    }

    @Test
    fun notNullViolationIsRejected() {
        run("CREATE TABLE t (id INTEGER, name TEXT NOT NULL)")
        assertFailsWith<KestrelException> { run("INSERT INTO t (id) VALUES (1)") }
    }

    @Test
    fun primaryKeyUniquenessIsEnforced() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (1)") }
    }

    @Test
    fun updateCausingDuplicatePrimaryKeyIsRejected() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1), (2)")
        assertFailsWith<KestrelException> { run("UPDATE t SET id = 2 WHERE id = 1") }
    }

    @Test
    fun typeMismatchIsRejected() {
        run("CREATE TABLE t (age INTEGER)")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES ('old')") }
    }

    @Test
    fun integerIsCoercedIntoRealColumn() {
        run("CREATE TABLE measurements (value REAL)")
        run("INSERT INTO measurements VALUES (5)")
        val result = select("SELECT value FROM measurements")
        assertEquals(listOf(Value.RealValue(5.0)), result.rows[0])
    }

    @Test
    fun nullInWhereIsNotIncluded() {
        run("CREATE TABLE t (id INTEGER, score INTEGER)")
        run("INSERT INTO t VALUES (1, 10), (2, NULL)")
        val result = select("SELECT id FROM t WHERE score > 5")
        assertEquals(listOf(listOf(int(1))), result.rows)
    }

    @Test
    fun explicitColumnsLeaveOthersNull() {
        run("CREATE TABLE t (id INTEGER, note TEXT)")
        run("INSERT INTO t (id) VALUES (7)")
        val result = select("SELECT id, note FROM t")
        assertEquals(listOf(int(7), Value.NullValue), result.rows[0])
    }

    @Test
    fun duplicateColumnNameIsRejected() {
        assertFailsWith<KestrelException> { run("CREATE TABLE t (a INTEGER, a TEXT)") }
    }

    @Test
    fun droppedTableCannotBeQueried() {
        run("CREATE TABLE t (a INTEGER)")
        run("DROP TABLE t")
        assertFailsWith<KestrelException> { run("SELECT * FROM t") }
    }

    @Test
    fun unknownColumnInProjectionFailsOnEmptyTable() {
        run("CREATE TABLE t (a INTEGER)")
        assertFailsWith<KestrelException> { run("SELECT missing FROM t") }
    }

    @Test
    fun creatingExistingTableIsRejected() {
        run("CREATE TABLE t (a INTEGER)")
        assertFailsWith<KestrelException> { run("CREATE TABLE t (b INTEGER)") }
    }

    @Test
    fun booleanColumnRoundTrips() {
        run("CREATE TABLE flags (name TEXT, active BOOLEAN)")
        run("INSERT INTO flags VALUES ('beta', true), ('legacy', false)")
        val result = select("SELECT name FROM flags WHERE active = true")
        assertEquals(listOf(listOf(text("beta"))), result.rows)
    }

    @Test
    fun compoundWhereUsesThreeValuedLogic() {
        seedUsers()
        val result = select("SELECT name FROM users WHERE age > 40 AND age < 80")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }
}
