package kestrel.store.sql

import kestrel.KestrelException
import kestrel.engine.AlterTableResult
import kestrel.engine.CreateIndexResult
import kestrel.engine.CreateSequenceResult
import kestrel.engine.CreateTableResult
import kestrel.engine.CreateViewResult
import kestrel.engine.DeallocateResult
import kestrel.engine.DeleteResult
import kestrel.engine.DropIndexResult
import kestrel.engine.DropTableResult
import kestrel.engine.DropViewResult
import kestrel.engine.InsertResult
import kestrel.engine.PrepareResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.UpdateResult
import kestrel.sql.ast.Statement
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end tests that parse real SQL text through the same [Lexer]/[Parser]
 * the in-memory engine uses, then run it through [DiskExecutor] against a
 * real [DiskCatalog] backed by temp files - proving the disk storage engine
 * (page/heap/btree/wal/txn) can actually serve SQL, not just its own
 * unit tests.
 */
class DiskExecutorTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun parse(sql: String): List<Statement> = Parser(Lexer(sql).tokenize()).parseProgram()

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private inner class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
            private set
        var executor = DiskExecutor(catalog)
            private set

        fun run(sql: String): List<StatementResult> = parse(sql).map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult

        /** Closes and reopens the catalog against the same files, simulating a fresh process. */
        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-exec-data"), tempFile("kestrel-disk-exec-wal"))

    private fun Database.seedUsers() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER DEFAULT 0)")
        run("INSERT INTO users VALUES (1, 'ada', 36), (2, 'linus', 54), (3, 'grace', 85)")
    }

    @Test
    fun createTableReturnsItsNameAndTheTableStartsEmpty() {
        val db = newDatabase()
        val created = db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)").single()
        assertEquals(CreateTableResult("users"), created)
        assertEquals(SelectResult(listOf("id", "name"), emptyList()), db.select("SELECT * FROM users"))
    }

    @Test
    fun creatingTheSameTableTwiceFails() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        assertFailsWith<DiskCatalogException> { db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)") }
    }

    @Test
    fun insertFillsOmittedColumnsFromDeclaredDefaultAndReportsRowCount() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER DEFAULT 0)")

        val inserted = db.run("INSERT INTO users (id, name) VALUES (1, 'ada')").single()
        assertEquals(InsertResult(1), inserted)

        val result = db.select("SELECT * FROM users")
        assertEquals(listOf(listOf(int(1), text("ada"), int(0))), result.rows)
    }

    @Test
    fun insertValuesDefaultKeywordUsesTheDeclaredDefault() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER DEFAULT 0)")
        db.run("INSERT INTO users VALUES (1, 'ada', DEFAULT)")
        assertEquals(listOf(listOf(int(1), text("ada"), int(0))), db.select("SELECT * FROM users").rows)
    }

    /** Positive coverage lives in [DiskExecutorOnConflictTest] - this table only proves it is no longer a scope-cut. */
    @Test
    fun insertOnConflictDoNothingSkipsAConflictingRowInsteadOfThrowing() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.run("INSERT INTO users VALUES (1, 'ada')")
        val inserted = db.run("INSERT INTO users VALUES (1, 'ignored') ON CONFLICT (id) DO NOTHING").single()
        assertEquals(InsertResult(0), inserted)
        assertEquals(listOf(listOf(int(1), text("ada"))), db.select("SELECT * FROM users").rows)
    }

    /** Positive coverage lives in [DiskExecutorTriggerTest] - this table only proves it is no longer a scope-cut. */
    @Test
    fun createTriggerSucceedsAndFiresOnInsert() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        db.run("CREATE TABLE audit (id INTEGER)")
        db.run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END")
        db.run("INSERT INTO t VALUES (1)")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT * FROM audit").rows)
    }

    /** Full SEQUENCE/NEXTVAL/CURRVAL coverage lives in [DiskExecutorSequenceTest]; this just proves the wiring. */
    @Test
    fun createSequenceSucceedsAndNextvalCanBackAColumnDefault() {
        val db = newDatabase()
        assertEquals(CreateSequenceResult("seq"), db.run("CREATE SEQUENCE seq").single())
        db.run("CREATE TABLE t (id INTEGER DEFAULT NEXTVAL('seq'))")
        db.run("INSERT INTO t (id) VALUES (DEFAULT)")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT id FROM t").rows)
    }

    @Test
    fun nextvalInACheckConstraintIsRejected() {
        val db = newDatabase()
        db.run("CREATE SEQUENCE seq")
        val error = assertFailsWith<DiskExecutorException> {
            db.run("CREATE TABLE t (id INTEGER CHECK (id = NEXTVAL('seq')))")
        }
        assertTrue(error.message!!.contains("not allowed"), error.message!!)
    }

    @Test
    fun selectStarReturnsEveryColumnInSchemaOrder() {
        val db = newDatabase()
        db.seedUsers()
        val result = db.select("SELECT * FROM users")
        assertEquals(listOf("id", "name", "age"), result.columns)
        assertEquals(3, result.rows.size)
        assertTrue(listOf(int(1), text("ada"), int(36)) in result.rows)
    }

    @Test
    fun whereFiltersRowsUsingTheSharedEvaluator() {
        val db = newDatabase()
        db.seedUsers()
        val result = db.select("SELECT name FROM users WHERE age > 40")
        assertEquals(setOf(text("linus"), text("grace")), result.rows.map { it.single() }.toSet())
    }

    @Test
    fun orderByAndLimitApplyAfterFilteringAndBeforeProjection() {
        val db = newDatabase()
        db.seedUsers()
        val result = db.select("SELECT name, age FROM users ORDER BY age DESC LIMIT 2")
        assertEquals(listOf(listOf(text("grace"), int(85)), listOf(text("linus"), int(54))), result.rows)
    }

    @Test
    fun offsetSkipsLeadingRowsWithoutALimit() {
        val db = newDatabase()
        db.seedUsers()
        val result = db.select("SELECT name FROM users ORDER BY age ASC OFFSET 1")
        assertEquals(listOf(listOf(text("linus")), listOf(text("grace"))), result.rows)
    }

    @Test
    fun limitAndOffsetTogetherPageThroughTheOrderedResult() {
        val db = newDatabase()
        db.seedUsers()
        val result = db.select("SELECT name FROM users ORDER BY age ASC LIMIT 1 OFFSET 1")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }

    @Test
    fun offsetWrittenBeforeLimitStillAppliesLimitAfterTheSkip() {
        val db = newDatabase()
        db.seedUsers()
        val result = db.select("SELECT name FROM users ORDER BY age ASC OFFSET 1 LIMIT 1")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }

    @Test
    fun offsetPastTheEndOfTheResultReturnsNoRows() {
        val db = newDatabase()
        db.seedUsers()
        val result = db.select("SELECT name FROM users ORDER BY age ASC OFFSET 100")
        assertEquals(emptyList<List<Value>>(), result.rows)
    }

    @Test
    fun offsetSurvivesCloseAndReopen() {
        val db = newDatabase()
        db.seedUsers()
        db.reopen()
        val result = db.select("SELECT name FROM users ORDER BY age ASC LIMIT 1 OFFSET 1")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }

    @Test
    fun expressionProjectionEvaluatesFunctionsAndUsesAliasesOrGeneratedLabels() {
        val db = newDatabase()
        db.seedUsers()
        val result = db.select("SELECT UPPER(name) AS loud, age + 1 FROM users WHERE id = 1")
        assertEquals(listOf("loud", "column2"), result.columns)
        assertEquals(listOf(listOf(text("ADA"), int(37))), result.rows)
    }

    @Test
    fun updateAppliesExpressionsAgainstTheOldRowAndReportsMatchedCount() {
        val db = newDatabase()
        db.seedUsers()
        val updated = db.run("UPDATE users SET age = age + 1 WHERE age > 40").single()
        assertEquals(UpdateResult(2), updated)
        val ages = db.select("SELECT name, age FROM users ORDER BY name").rows.associate { (it[0] as Value.TextValue).value to it[1] }
        assertEquals(mapOf("ada" to int(36), "linus" to int(55), "grace" to int(86)), ages)
    }

    @Test
    fun updateSetToDefaultKeywordResetsTheColumn() {
        val db = newDatabase()
        db.seedUsers()
        db.run("UPDATE users SET age = DEFAULT WHERE id = 2")
        assertEquals(int(0), db.select("SELECT age FROM users WHERE id = 2").rows.single().single())
    }

    @Test
    fun deleteRemovesMatchingRowsAndReportsCount() {
        val db = newDatabase()
        db.seedUsers()
        val deleted = db.run("DELETE FROM users WHERE age > 40").single()
        assertEquals(DeleteResult(2), deleted)
        assertEquals(listOf(listOf(int(1), text("ada"), int(36))), db.select("SELECT * FROM users").rows)
    }

    @Test
    fun dropTableRemovesItFromTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        val dropped = db.run("DROP TABLE users").single()
        assertEquals(DropTableResult("users"), dropped)
        assertFailsWith<DiskCatalogException> { db.select("SELECT * FROM users") }
    }

    @Test
    fun aFailedStatementInAMultiRowInsertLeavesNoPartialRowsBehind() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        // The second row duplicates the first row's primary key: DiskTable rejects it mid-batch,
        // so the whole INSERT statement (including the already-applied first row) must abort.
        assertFailsWith<DiskTableException> {
            db.run("INSERT INTO users VALUES (1, 'ada'), (1, 'ada-again')")
        }
        assertEquals(emptyList<List<Value>>(), db.select("SELECT * FROM users").rows)
    }

    @Test
    fun notNullViolationOnUpdateAbortsTheWholeStatement() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.run("INSERT INTO users VALUES (1, 'ada'), (2, 'linus')")
        assertFailsWith<DiskTableException> { db.run("UPDATE users SET name = NULL") }
        // Neither row's name was actually cleared, because the failure aborted the transaction.
        assertEquals(setOf(text("ada"), text("linus")), db.select("SELECT name FROM users").rows.map { it.single() }.toSet())
    }

    @Test
    fun integerLiteralIsCoercedToRealForARealColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE readings (id INTEGER PRIMARY KEY, value REAL NOT NULL)")
        db.run("INSERT INTO readings VALUES (1, 5)")
        assertEquals(Value.RealValue(5.0), db.select("SELECT value FROM readings").rows.single().single())
    }

    @Test
    fun textLiteralIntoAnIntegerColumnIsRejectedWithATypeMismatch() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        assertFailsWith<DiskExecutorException> { db.run("INSERT INTO users VALUES ('x', 'ada')") }
    }

    @Test
    fun dataSurvivesClosingAndReopeningTheCatalogInWhatActsAsAFreshProcess() {
        val db = newDatabase()
        db.seedUsers()
        db.run("UPDATE users SET age = 100 WHERE id = 1")
        db.reopen()
        val ages = db.select("SELECT name, age FROM users ORDER BY name").rows
        assertEquals(listOf(listOf(text("ada"), int(100)), listOf(text("grace"), int(85)), listOf(text("linus"), int(54))), ages)
    }

    private fun Database.seedOrders() {
        run("CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("INSERT INTO customers VALUES (1, 'ada'), (2, 'linus'), (3, 'grace')")
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, amount INTEGER NOT NULL)")
        // grace (id 3) has no orders, so LEFT JOIN has something to pad and INNER JOIN has something to drop.
        run("INSERT INTO orders VALUES (1, 1, 100), (2, 1, 50), (3, 2, 200)")
    }

    // Adds an order referencing a customer id that does not exist, so RIGHT/FULL JOIN has an
    // unmatched *right* row to pad with NULLs on top of grace's unmatched left row from seedOrders.
    private fun Database.seedOrdersWithOrphan() {
        seedOrders()
        run("INSERT INTO orders VALUES (4, 99, 999)")
    }

    @Test
    fun rightJoinPadsUnmatchedRightRowsWithNulls() {
        val db = newDatabase()
        db.seedOrdersWithOrphan()
        val result = db.select(
            "SELECT customers.name, orders.amount FROM customers RIGHT JOIN orders ON customers.id = orders.customer_id ORDER BY orders.id"
        )
        assertEquals(
            listOf(
                listOf(text("ada"), int(100)),
                listOf(text("ada"), int(50)),
                listOf(text("linus"), int(200)),
                listOf(Value.NullValue, int(999))
            ),
            result.rows
        )
    }

    @Test
    fun fullJoinKeepsBothUnmatchedLeftAndRightRows() {
        val db = newDatabase()
        db.seedOrdersWithOrphan()
        val result = db.select(
            "SELECT customers.name, orders.amount FROM customers FULL JOIN orders ON customers.id = orders.customer_id"
        )
        assertEquals(
            setOf(
                listOf(text("ada"), int(100)),
                listOf(text("ada"), int(50)),
                listOf(text("linus"), int(200)),
                listOf(text("grace"), Value.NullValue),
                listOf(Value.NullValue, int(999))
            ),
            result.rows.toSet()
        )
        assertEquals(5, result.rows.size)
    }

    @Test
    fun crossJoinProducesTheFullCartesianProductWithNoOnClause() {
        val db = newDatabase()
        db.run("CREATE TABLE colors (name TEXT)")
        db.run("CREATE TABLE sizes (label TEXT)")
        db.run("INSERT INTO colors VALUES ('red'), ('blue')")
        db.run("INSERT INTO sizes VALUES ('s'), ('m')")
        val result = db.select("SELECT colors.name, sizes.label FROM colors CROSS JOIN sizes ORDER BY colors.name, sizes.label")
        assertEquals(
            listOf(
                listOf(text("blue"), text("m")), listOf(text("blue"), text("s")),
                listOf(text("red"), text("m")), listOf(text("red"), text("s"))
            ),
            result.rows
        )
    }

    @Test
    fun rightOuterAndFullOuterJoinSpellingsAreAccepted() {
        val db = newDatabase()
        db.seedOrders()
        val right = db.select(
            "SELECT customers.name FROM customers RIGHT OUTER JOIN orders ON customers.id = orders.customer_id ORDER BY orders.id"
        )
        assertEquals(listOf(listOf(text("ada")), listOf(text("ada")), listOf(text("linus"))), right.rows)
        val full = db.select(
            "SELECT customers.name FROM customers FULL OUTER JOIN orders ON customers.id = orders.customer_id ORDER BY customers.id"
        )
        assertEquals(4, full.rows.size)
    }

    @Test
    fun innerJoinCombinesMatchingRowsAndDropsUnmatchedLeftRows() {
        val db = newDatabase()
        db.seedOrders()
        val result = db.select(
            "SELECT customers.name, orders.amount FROM customers JOIN orders ON customers.id = orders.customer_id ORDER BY orders.id"
        )
        assertEquals(listOf("name", "amount"), result.columns)
        assertEquals(
            listOf(listOf(text("ada"), int(100)), listOf(text("ada"), int(50)), listOf(text("linus"), int(200))),
            result.rows
        )
    }

    @Test
    fun leftJoinPadsUnmatchedLeftRowsWithNulls() {
        val db = newDatabase()
        db.seedOrders()
        val result = db.select(
            "SELECT customers.name, orders.amount FROM customers LEFT JOIN orders ON customers.id = orders.customer_id ORDER BY customers.id, orders.id"
        )
        assertEquals(
            listOf(
                listOf(text("ada"), int(100)),
                listOf(text("ada"), int(50)),
                listOf(text("linus"), int(200)),
                listOf(text("grace"), Value.NullValue)
            ),
            result.rows
        )
    }

    @Test
    fun joinUsesTableAliasesToQualifyColumnsWithTheSameName() {
        val db = newDatabase()
        db.run("CREATE TABLE a (id INTEGER PRIMARY KEY, tag TEXT NOT NULL)")
        db.run("CREATE TABLE b (id INTEGER PRIMARY KEY, tag TEXT NOT NULL)")
        db.run("INSERT INTO a VALUES (1, 'left')")
        db.run("INSERT INTO b VALUES (1, 'right')")
        val result = db.select("SELECT x.tag, y.tag FROM a x JOIN b y ON x.id = y.id")
        assertEquals(listOf(listOf(text("left"), text("right"))), result.rows)
    }

    @Test
    fun anUnqualifiedColumnPresentInBothJoinedTablesIsRejectedAsAmbiguous() {
        val db = newDatabase()
        db.run("CREATE TABLE a (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE b (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO a VALUES (1)")
        db.run("INSERT INTO b VALUES (1)")
        val failure = assertFailsWith<DiskExecutorException> { db.select("SELECT id FROM a JOIN b ON a.id = b.id") }
        assertTrue(failure.message!!.contains("ambiguous"))
    }

    @Test
    fun aDuplicateTableAliasAcrossJoinedSourcesIsRejected() {
        val db = newDatabase()
        db.run("CREATE TABLE a (id INTEGER PRIMARY KEY)")
        val failure = assertFailsWith<DiskExecutorException> { db.select("SELECT * FROM a x JOIN a x ON x.id = x.id") }
        assertTrue(failure.message!!.contains("duplicate table alias"))
    }

    @Test
    fun joiningThreeTablesChainsLeftToRight() {
        val db = newDatabase()
        db.seedOrders()
        db.run("CREATE TABLE shipments (id INTEGER PRIMARY KEY, order_id INTEGER NOT NULL, carrier TEXT NOT NULL)")
        db.run("INSERT INTO shipments VALUES (1, 1, 'ups')")
        val result = db.select(
            "SELECT customers.name, orders.amount, shipments.carrier " +
                "FROM customers JOIN orders ON customers.id = orders.customer_id " +
                "JOIN shipments ON orders.id = shipments.order_id"
        )
        assertEquals(listOf(listOf(text("ada"), int(100), text("ups"))), result.rows)
    }

    @Test
    fun joinAgainstADerivedTableWorks() {
        val db = newDatabase()
        db.run("CREATE TABLE a (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.run("CREATE TABLE b (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO a VALUES (1, 'ada'), (2, 'linus')")
        db.run("INSERT INTO b VALUES (1)")
        val result = db.select("SELECT a.name FROM a JOIN (SELECT id FROM b) sub ON a.id = sub.id")
        assertEquals(listOf(listOf(text("ada"))), result.rows)
    }

