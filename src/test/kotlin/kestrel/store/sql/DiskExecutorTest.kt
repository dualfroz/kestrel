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

    private fun Database.seedDerivedTableUsers() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER, dept TEXT)")
        run(
            "INSERT INTO users VALUES " +
                "(1, 'ada', 36, 'eng'), (2, 'linus', 54, 'eng'), (3, 'grace', 85, 'navy'), (4, 'alan', 41, NULL)"
        )
    }

    @Test
    fun selectsFromASimpleDerivedTable() {
        val db = newDatabase()
        db.seedDerivedTableUsers()
        val result = db.select("SELECT s.name FROM (SELECT id, name FROM users WHERE age >= 40) AS s ORDER BY s.id ASC")
        assertEquals(listOf(text("linus"), text("grace"), text("alan")), result.rows.map { it.single() })
    }

    @Test
    fun derivedTableOverAnAggregateQueryWorks() {
        val db = newDatabase()
        db.seedDerivedTableUsers()
        val result = db.select(
            "SELECT d.dept, d.n FROM (SELECT dept, COUNT(*) AS n FROM users GROUP BY dept) AS d " +
                "WHERE d.n > 1 ORDER BY d.dept ASC"
        )
        assertEquals(listOf(listOf(text("eng"), int(2))), result.rows)
    }

    @Test
    fun twoDerivedTablesCanBeJoinedTogether() {
        val db = newDatabase()
        db.seedDerivedTableUsers()
        val result = db.select(
            "SELECT a.dept, a.n FROM (SELECT dept, COUNT(*) AS n FROM users GROUP BY dept) AS a " +
                "JOIN (SELECT dept, COUNT(*) AS n FROM users GROUP BY dept) AS b ON a.dept = b.dept " +
                "WHERE a.n = b.n ORDER BY a.dept ASC"
        )
        assertEquals(listOf(listOf(text("eng"), int(2)), listOf(text("navy"), int(1))), result.rows)
    }

    @Test
    fun derivedTableColumnsAreScopedToItsAliasNotTheUnderlyingTableName() {
        val db = newDatabase()
        db.seedDerivedTableUsers()
        assertFailsWith<DiskExecutorException> { db.run("SELECT users.name FROM (SELECT id, name FROM users) AS s") }
    }

    @Test
    fun derivedTableIsReEvaluatedEachQueryReflectingLiveData() {
        val db = newDatabase()
        db.seedDerivedTableUsers()
        val before = db.select("SELECT COUNT(*) AS n FROM (SELECT id FROM users WHERE dept = 'eng') AS e")
        assertEquals(listOf(listOf(int(2))), before.rows)
        db.run("INSERT INTO users VALUES (5, 'margaret', 61, 'eng')")
        val after = db.select("SELECT COUNT(*) AS n FROM (SELECT id FROM users WHERE dept = 'eng') AS e")
        assertEquals(listOf(listOf(int(3))), after.rows)
    }

    @Test
    fun derivedTableCanContainACorrelatedSubqueryOfItsOwn() {
        val db = newDatabase()
        db.seedDerivedTableUsers()
        val result = db.select(
            "SELECT s.name FROM (SELECT id, name, dept FROM users u WHERE age > " +
                "(SELECT AVG(age) FROM users u2 WHERE u2.dept = u.dept)) AS s ORDER BY s.id ASC"
        )
        assertEquals(listOf(text("linus")), result.rows.map { it.single() })
    }

    @Test
    fun creatingAViewFromADerivedTableWorks() {
        val db = newDatabase()
        db.seedDerivedTableUsers()
        db.run("CREATE VIEW dept_counts AS SELECT d.dept, d.n FROM (SELECT dept, COUNT(*) AS n FROM users GROUP BY dept) AS d")
        val result = db.select("SELECT dept, n FROM dept_counts WHERE n > 1")
        assertEquals(listOf(listOf(text("eng"), int(2))), result.rows)
    }

    private fun Database.seedDepartmentsAndSalaries() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, deptId INTEGER, name TEXT, salary INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng'), (2, 'sales')")
        run(
            "INSERT INTO employees VALUES " +
                "(1, 1, 'ada', 300), (2, 1, 'linus', 250), (3, 1, 'grace', 400), " +
                "(4, 2, 'sam', 150), (5, 2, 'kay', 200)"
        )
    }

    @Test
    fun lateralJoinComputesTopOnePerGroupWhichAPlainJoinCannotExpress() {
        val db = newDatabase()
        db.seedDepartmentsAndSalaries()
        val result = db.select(
            "SELECT d.name, top.name, top.salary FROM departments d " +
                "JOIN LATERAL (SELECT e.name, e.salary FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE " +
                "ORDER BY d.name ASC"
        )
        assertEquals(
            listOf(
                listOf(text("eng"), text("grace"), int(400)),
                listOf(text("sales"), text("kay"), int(200))
            ),
            result.rows
        )
    }

    @Test
    fun lateralJoinWithLimitGreaterThanOneReturnsMultipleRowsPerLeftRow() {
        val db = newDatabase()
        db.seedDepartmentsAndSalaries()
        val result = db.select(
            "SELECT d.name, top.name FROM departments d " +
                "JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 2) AS top ON TRUE " +
                "ORDER BY d.name ASC, top.name ASC"
        )
        assertEquals(
            listOf(
                listOf(text("eng"), text("ada")),
                listOf(text("eng"), text("grace")),
                listOf(text("sales"), text("kay")),
                listOf(text("sales"), text("sam"))
            ),
            result.rows
        )
    }

    @Test
    fun innerJoinLateralDropsGroupsWithNoMatchingRows() {
        val db = newDatabase()
        db.seedDepartmentsAndSalaries()
        db.run("INSERT INTO departments VALUES (3, 'empty')")
        val result = db.select(
            "SELECT d.name FROM departments d " +
                "JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id LIMIT 1) AS top ON TRUE"
        )
        assertTrue(result.rows.none { it == listOf(text("empty")) })
        assertEquals(2, result.rows.size)
    }

    @Test
    fun leftJoinLateralKeepsGroupsWithNoMatchingRowsAsNull() {
        val db = newDatabase()
        db.seedDepartmentsAndSalaries()
        db.run("INSERT INTO departments VALUES (3, 'empty')")
        val result = db.select(
            "SELECT d.name, top.name FROM departments d " +
                "LEFT JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE " +
                "ORDER BY d.name ASC"
        )
        assertEquals(
            listOf(
                listOf(text("empty"), Value.NullValue),
                listOf(text("eng"), text("grace")),
                listOf(text("sales"), text("kay"))
            ),
            result.rows
        )
    }

    @Test
    fun leftJoinLateralOverAnEmptyBaseTableProducesNoRowsWithoutRunningTheSubquery() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, deptId INTEGER, name TEXT)")
        val result = db.select(
            "SELECT d.name, top.name FROM departments d " +
                "LEFT JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id) AS top ON TRUE"
        )
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun chainedLateralJoinsSeeEarlierLateralAliases() {
        val db = newDatabase()
        db.seedDepartmentsAndSalaries()
        db.run("CREATE TABLE dual (x INTEGER)")
        db.run("INSERT INTO dual VALUES (1)")
        val result = db.select(
            "SELECT d.name, bonus.amount FROM departments d " +
                "JOIN LATERAL (SELECT e.salary FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE " +
                "JOIN LATERAL (SELECT top.salary / 10 AS amount FROM dual) AS bonus ON TRUE " +
                "ORDER BY d.name ASC"
        )
        assertEquals(listOf(listOf(text("eng"), int(40)), listOf(text("sales"), int(20))), result.rows)
    }

    @Test
    fun lateralSubqueryCannotReferenceATableJoinedLater() {
        val db = newDatabase()
        db.seedDepartmentsAndSalaries()
        val failure = assertFailsWith<DiskExecutorException> {
            db.select(
                "SELECT * FROM departments d " +
                    "JOIN LATERAL (SELECT e.name FROM employees e WHERE e.salary = later.salary) AS top ON TRUE " +
                    "JOIN employees later ON later.deptId = d.id"
            )
        }
        assertTrue(failure.message!!.contains("not joined yet"))
    }

    @Test
    fun lateralSubqueryWithStarProjectionResolvesColumnsStructurallyFromItsBaseTable() {
        val db = newDatabase()
        db.seedDepartmentsAndSalaries()
        val result = db.select(
            "SELECT d.name, top.name, top.salary FROM departments d " +
                "JOIN LATERAL (SELECT * FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE " +
                "ORDER BY d.name ASC"
        )
        assertEquals(
            listOf(listOf(text("eng"), text("grace"), int(400)), listOf(text("sales"), text("kay"), int(200))),
            result.rows
        )
    }

    @Test
    fun groupByComputesCountSumAvgMinMaxPerGroup() {
        val db = newDatabase()
        db.seedOrders()
        val result = db.select(
            "SELECT customer_id, COUNT(*), SUM(amount), AVG(amount), MIN(amount), MAX(amount) " +
                "FROM orders GROUP BY customer_id ORDER BY customer_id"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(2), int(150), Value.RealValue(75.0), int(50), int(100)),
                listOf(int(2), int(1), int(200), Value.RealValue(200.0), int(200), int(200))
            ),
            result.rows
        )
    }

    /**
     * STRING_AGG's implementation lives entirely in the shared [kestrel.engine.GroupAggregation]/
     * [kestrel.engine.AggregateFunctions] classes that this disk-backed executor already reuses for
     * every other aggregate - this proves that reuse actually extends to STRING_AGG too, not just that
     * the in-memory engine (AggregateTest) got it right.
     */
    @Test
    fun stringAggGroupsAndOrdersPerGroupOnTheDiskBackedEngine() {
        val db = newDatabase()
        db.run("CREATE TABLE tags (item TEXT, label TEXT)")
        db.run("INSERT INTO tags VALUES ('widget', 'blue'), ('widget', 'small'), ('gadget', 'red')")
        val result = db.select(
            "SELECT item, STRING_AGG(label, ', ' ORDER BY label) FROM tags GROUP BY item ORDER BY item"
        )
        assertEquals(
            listOf(listOf(text("gadget"), text("red")), listOf(text("widget"), text("blue, small"))),
            result.rows
        )
    }

    /**
     * `COUNT/SUM/AVG/MIN/MAX(DISTINCT ...)` dedup lives in the same shared [kestrel.engine.
     * AggregateFunctions.compute] this executor already reuses for every other aggregate - this
     * proves the DISTINCT dedup path itself, not just plain aggregation, actually runs on the
     * disk-backed engine too.
     */
    @Test
    fun countAndSumDistinctDedupOnTheDiskBackedEngine() {
        val db = newDatabase()
        db.run("CREATE TABLE t (dept TEXT, amount INTEGER)")
        db.run("INSERT INTO t VALUES ('eng', 100), ('eng', 100), ('eng', 200), ('ops', 100), ('ops', NULL)")
        assertEquals(listOf(listOf(int(4))), db.select("SELECT COUNT(amount) FROM t").rows)
        assertEquals(listOf(listOf(int(2))), db.select("SELECT COUNT(DISTINCT amount) FROM t").rows)
        assertEquals(listOf(listOf(int(500))), db.select("SELECT SUM(amount) FROM t").rows)
        assertEquals(listOf(listOf(int(300))), db.select("SELECT SUM(DISTINCT amount) FROM t").rows)
    }

    @Test
    fun countDistinctPerGroupOnTheDiskBackedEngine() {
        val db = newDatabase()
        db.run("CREATE TABLE t (dept TEXT, amount INTEGER)")
        db.run("INSERT INTO t VALUES ('eng', 100), ('eng', 100), ('eng', 200), ('ops', 100), ('ops', NULL)")
        val result = db.select("SELECT dept, COUNT(DISTINCT amount) FROM t GROUP BY dept ORDER BY dept ASC")
        assertEquals(listOf(listOf(text("eng"), int(2)), listOf(text("ops"), int(1))), result.rows)
    }

    /**
     * `FILTER (WHERE ...)` narrows the rows an aggregate call itself sees before
     * [kestrel.engine.AggregateFunctions.compute] runs - lives in the same shared
     * [kestrel.engine.GroupAggregation.computeAggregate] this executor already reuses for every other
     * aggregate, so this proves the filtering itself, not just plain/DISTINCT aggregation, runs here too.
     */
    @Test
    fun filterRestrictsWhichRowsEachAggregateSeesOnTheDiskBackedEngine() {
        val db = newDatabase()
        db.run("CREATE TABLE t (dept TEXT, amount INTEGER)")
        db.run("INSERT INTO t VALUES ('eng', 100), ('eng', 100), ('eng', 200), ('ops', 100), ('ops', NULL)")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT COUNT(*) FILTER (WHERE amount > 100) FROM t").rows)
        assertEquals(listOf(listOf(int(400))), db.select("SELECT SUM(amount) FILTER (WHERE dept = 'eng') FROM t").rows)
        assertEquals(
            listOf(listOf(int(0), Value.NullValue)),
            db.select("SELECT COUNT(*) FILTER (WHERE dept = 'nope') AS c, SUM(amount) FILTER (WHERE dept = 'nope') AS s FROM t").rows
        )
    }

    @Test
    fun filterCombinesWithDistinctAndGroupByOnTheDiskBackedEngine() {
        val db = newDatabase()
        db.run("CREATE TABLE t (dept TEXT, amount INTEGER)")
        db.run("INSERT INTO t VALUES ('eng', 100), ('eng', 100), ('eng', 200), ('ops', 100), ('ops', NULL)")
        val result = db.select(
            "SELECT dept, COUNT(DISTINCT amount) FILTER (WHERE amount > 50) AS matching FROM t GROUP BY dept ORDER BY dept ASC"
        )
        assertEquals(listOf(listOf(text("eng"), int(2)), listOf(text("ops"), int(1))), result.rows)
    }

    @Test
    fun offsetSkipsGroupsAfterOrderingAggregatedResultsOnTheDiskBackedEngine() {
        val db = newDatabase()
        db.run("CREATE TABLE t (dept TEXT, amount INTEGER)")
        db.run("INSERT INTO t VALUES ('eng', 100), ('eng', 100), ('eng', 200), ('ops', 100), ('ops', NULL)")
        val result = db.select("SELECT dept, COUNT(DISTINCT amount) FROM t GROUP BY dept ORDER BY dept ASC LIMIT 1 OFFSET 1")
        assertEquals(listOf(listOf(text("ops"), int(1))), result.rows)
    }

    @Test
    fun countStarWithNoGroupByAggregatesTheWholeTable() {
        val db = newDatabase()
        db.seedOrders()
        assertEquals(listOf(listOf(int(3))), db.select("SELECT COUNT(*) FROM orders").rows)
    }

    @Test
    fun countStarOverAnEmptyTableStillProducesOneRow() {
        val db = newDatabase()
        db.run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)")
        assertEquals(listOf(listOf(int(0))), db.select("SELECT COUNT(*) FROM orders").rows)
    }

    @Test
    fun groupByRollupAddsPerCustomerSubtotalsAndAGrandTotal() {
        val db = newDatabase()
        db.seedOrders()
        val result = db.select(
            "SELECT customer_id, SUM(amount) AS total FROM orders GROUP BY ROLLUP (customer_id) " +
                "ORDER BY GROUPING(customer_id), customer_id"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(150)),
                listOf(int(2), int(200)),
                listOf(Value.NullValue, int(350))
            ),
            result.rows
        )
    }

    @Test
    fun havingFiltersOutGroupsThatDoNotMatch() {
        val db = newDatabase()
        db.seedOrders()
        val result = db.select("SELECT customer_id, SUM(amount) FROM orders GROUP BY customer_id HAVING SUM(amount) > 150")
        assertEquals(listOf(listOf(int(2), int(200))), result.rows)
    }

    @Test
    fun orderByOnAnAggregateSortsTheGroupedResult() {
        val db = newDatabase()
        db.seedOrders()
        val result = db.select("SELECT customer_id, SUM(amount) AS total FROM orders GROUP BY customer_id ORDER BY total DESC")
        assertEquals(listOf(listOf(int(2), int(200)), listOf(int(1), int(150))), result.rows)
    }

    @Test
    fun aProjectedColumnNotInGroupByOrAnAggregateIsRejected() {
        val db = newDatabase()
        db.seedOrders()
        val failure = assertFailsWith<DiskExecutorException> {
            db.select("SELECT customer_id, amount FROM orders GROUP BY customer_id")
        }
        assertTrue(failure.message!!.contains("must appear in GROUP BY"))
    }

    @Test
    fun starProjectionCombinedWithGroupByIsRejected() {
        val db = newDatabase()
        db.seedOrders()
        val failure = assertFailsWith<DiskExecutorException> { db.select("SELECT * FROM orders GROUP BY customer_id") }
        assertTrue(failure.message!!.contains("cannot use *"))
    }

    @Test
    fun groupByAggregatesOverAJoinedResult() {
        val db = newDatabase()
        db.seedOrders()
        val result = db.select(
            "SELECT customers.name, SUM(orders.amount) FROM customers JOIN orders ON customers.id = orders.customer_id " +
                "GROUP BY customers.name ORDER BY customers.name"
        )
        assertEquals(listOf(listOf(text("ada"), int(150)), listOf(text("linus"), int(200))), result.rows)
    }

    @Test
    fun dropTableCascadeWithNoDependentsBehavesLikeAPlainDrop() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY)")
        db.run("DROP TABLE users CASCADE")
        assertTrue(db.catalog.tableNames.isEmpty())
    }

    @Test
    fun createIndexBuildsANonUniqueSingleColumnSecondaryIndex() {
        val db = newDatabase()
        db.seedUsers()
        val created = db.run("CREATE INDEX idx_age ON users(age)").single()
        assertEquals(CreateIndexResult("idx_age"), created)
        assertEquals(1, db.catalog.table("users").secondaryIndexes.size)
    }

    @Test
    fun createUniqueIndexBuildsAUniqueSingleColumnSecondaryIndex() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, age INTEGER)")
        val created = db.run("CREATE UNIQUE INDEX idx_age ON users(age)").single()
        assertEquals(CreateIndexResult("idx_age"), created)
        assertTrue(db.catalog.table("users").secondaryIndexes.single().unique)
    }

    @Test
    fun createUniqueIndexRejectsAnInsertThatWouldDuplicateAnExistingValue() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)")
        db.run("CREATE UNIQUE INDEX idx_email ON users(email)")
        db.run("INSERT INTO users VALUES (1, 'ada@example.com')")
        assertFailsWith<DiskTableException> { db.run("INSERT INTO users VALUES (2, 'ada@example.com')") }
        assertEquals(1, db.select("SELECT * FROM users").rows.size)
    }

    @Test
    fun createUniqueIndexAllowsMultipleNullsSinceNullsAreNeverDuplicates() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)")
        db.run("CREATE UNIQUE INDEX idx_email ON users(email)")
        db.run("INSERT INTO users VALUES (1, NULL), (2, NULL)")
        assertEquals(2, db.select("SELECT * FROM users").rows.size)
    }

    @Test
    fun createUniqueIndexOverExistingDuplicateValuesFailsAndLeavesNoTraceOfTheIndex() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)")
        db.run("INSERT INTO users VALUES (1, 'dup@example.com'), (2, 'dup@example.com')")
        assertFailsWith<DiskTableException> { db.run("CREATE UNIQUE INDEX idx_email ON users(email)") }
        assertTrue(db.catalog.table("users").secondaryIndexes.isEmpty())
    }

    @Test
    fun createUniqueIndexRejectsAnUpdateThatWouldDuplicateAnotherRowsValue() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)")
        db.run("CREATE UNIQUE INDEX idx_email ON users(email)")
        db.run("INSERT INTO users VALUES (1, 'ada@example.com'), (2, 'grace@example.com')")
        assertFailsWith<DiskTableException> { db.run("UPDATE users SET email = 'ada@example.com' WHERE id = 2") }
        assertEquals(text("grace@example.com"), db.select("SELECT email FROM users WHERE id = 2").rows.single().single())
    }

    @Test
    fun createUniqueIndexDataAndConstraintSurviveClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)")
        db.run("CREATE UNIQUE INDEX idx_email ON users(email)")
        db.run("INSERT INTO users VALUES (1, 'ada@example.com')")
        db.reopen()
        assertFailsWith<DiskTableException> { db.run("INSERT INTO users VALUES (2, 'ada@example.com')") }
        assertTrue(db.catalog.table("users").secondaryIndexes.single().unique)
    }

    @Test
    fun dropIndexRemovesItFromTheCatalog() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        val dropped = db.run("DROP INDEX idx_age").single()
        assertEquals(DropIndexResult("idx_age"), dropped)
        assertTrue(db.catalog.table("users").secondaryIndexes.isEmpty())
    }

    @Test
    fun dropIndexStopsThePlannerFromPushingDownAFormerlyIndexedEquality() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        db.run("DROP INDEX idx_age")
        val table = db.catalog.table("users")
        val scansBefore = table.scanCount
        db.select("SELECT * FROM users WHERE age = 54")
        assertEquals(0, table.secondaryLookupCount)
        assertEquals(scansBefore + 1, table.scanCount)
    }

    @Test
    fun droppingAUniqueIndexStopsItFromRejectingFurtherDuplicates() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)")
        db.run("CREATE UNIQUE INDEX idx_email ON users(email)")
        db.run("INSERT INTO users VALUES (1, 'ada@example.com')")
        db.run("DROP INDEX idx_email")
        db.run("INSERT INTO users VALUES (2, 'ada@example.com')")
        assertEquals(2, db.select("SELECT * FROM users").rows.size)
    }

    @Test
    fun dropIndexOnACompositeIndexRemovesTheWholeIndexNotJustOneColumn() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_both ON users(age, name)")
        db.run("DROP INDEX idx_both")
        assertTrue(db.catalog.table("users").secondaryIndexes.isEmpty())
    }

    @Test
    fun droppingAnUnknownIndexFails() {
        val db = newDatabase()
        db.seedUsers()
        assertFailsWith<DiskCatalogException> { db.run("DROP INDEX ghost") }
    }

    @Test
    fun droppingAnAlreadyDroppedIndexFails() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        db.run("DROP INDEX idx_age")
        assertFailsWith<DiskCatalogException> { db.run("DROP INDEX idx_age") }
    }

    @Test
    fun dropIndexIsPersistedAcrossClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)")
        db.run("CREATE UNIQUE INDEX idx_email ON users(email)")
        db.run("INSERT INTO users VALUES (1, 'ada@example.com')")
        db.run("DROP INDEX idx_email")
        db.reopen()
        assertTrue(db.catalog.table("users").secondaryIndexes.isEmpty())
        db.run("INSERT INTO users VALUES (2, 'ada@example.com')")
        assertEquals(2, db.select("SELECT * FROM users").rows.size)
    }

    @Test
    fun dropIndexCannotRunInsideAnExplicitTransaction() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        db.run("BEGIN")
        assertFailsWith<DiskExecutorException> { db.run("DROP INDEX idx_age") }
        db.run("ROLLBACK")
    }

    // --- declarative UNIQUE constraints (inline `col TYPE UNIQUE` / table-level `UNIQUE (a, b, ...)`),
    // enforced by a full heap scan per write rather than a backing index, mirroring
    // kestrel.engine.Executor's own uniqueConstraints handling.

    @Test
    fun inlineUniqueColumnRejectsADuplicateValueOnInsert() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        db.run("INSERT INTO users VALUES (1, 'ada@example.com')")
        assertFailsWith<DiskTableException> { db.run("INSERT INTO users VALUES (2, 'ada@example.com')") }
        assertEquals(1, db.select("SELECT * FROM users").rows.size)
    }

    @Test
    fun inlineUniqueColumnAllowsMultipleNulls() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        db.run("INSERT INTO users VALUES (1, NULL), (2, NULL)")
        assertEquals(2, db.select("SELECT * FROM users").rows.size)
    }

    @Test
    fun tableLevelCompositeUniqueConstraintRejectsADuplicateColumnCombination() {
        val db = newDatabase()
        db.run("CREATE TABLE registrations (event_id INTEGER, user_id INTEGER, UNIQUE (event_id, user_id))")
        db.run("INSERT INTO registrations VALUES (1, 100)")
        assertFailsWith<DiskTableException> { db.run("INSERT INTO registrations VALUES (1, 100)") }
        // Sharing only one of the two columns is fine: the constraint is on the pair, not either column alone.
        db.run("INSERT INTO registrations VALUES (1, 200)")
        assertEquals(2, db.select("SELECT * FROM registrations").rows.size)
    }

    @Test
    fun uniqueConstraintRejectsAnUpdateThatWouldDuplicateAnotherRowsValue() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        db.run("INSERT INTO users VALUES (1, 'ada@example.com'), (2, 'grace@example.com')")
        assertFailsWith<DiskTableException> { db.run("UPDATE users SET email = 'ada@example.com' WHERE id = 2") }
    }

    @Test
    fun uniqueConstraintReferencingAnUnknownColumnIsRejectedWithAClearError() {
        val db = newDatabase()
        val failure = assertFailsWith<DiskExecutorException> {
            db.run("CREATE TABLE bad (id INTEGER PRIMARY KEY, UNIQUE (missing_column))")
        }
        assertTrue(failure.message!!.contains("missing_column"))
    }

    @Test
    fun uniqueConstraintDataAndEnforcementSurviveClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        db.run("INSERT INTO users VALUES (1, 'ada@example.com')")
        db.reopen()
        assertFailsWith<DiskTableException> { db.run("INSERT INTO users VALUES (2, 'ada@example.com')") }
        assertEquals(1, db.select("SELECT * FROM users").rows.size)
    }

    // --- composite (multi-column) CREATE [UNIQUE] INDEX

    @Test
    fun createIndexBuildsANonUniqueCompositeSecondaryIndex() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, age INTEGER, name TEXT)")
        val created = db.run("CREATE INDEX idx_both ON users(age, name)").single()
        assertEquals(CreateIndexResult("idx_both"), created)
        val handle = db.catalog.table("users").secondaryIndexes.single()
        assertEquals(listOf("age", "name"), handle.columnNames)
        assertEquals(false, handle.unique)
    }

    @Test
    fun createUniqueCompositeIndexRejectsADuplicateColumnCombinationOnInsert() {
        val db = newDatabase()
        db.run("CREATE TABLE registrations (event_id INTEGER, user_id INTEGER)")
        db.run("CREATE UNIQUE INDEX idx_reg ON registrations(event_id, user_id)")
        db.run("INSERT INTO registrations VALUES (1, 100)")
        assertFailsWith<DiskTableException> { db.run("INSERT INTO registrations VALUES (1, 100)") }
        // Sharing only one of the two columns is fine: the index is on the pair, not either column alone.
        db.run("INSERT INTO registrations VALUES (1, 200)")
        assertEquals(2, db.select("SELECT * FROM registrations").rows.size)
    }

    @Test
    fun createUniqueCompositeIndexAllowsMultipleRowsWithANullInEitherColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE registrations (event_id INTEGER, user_id INTEGER)")
        db.run("CREATE UNIQUE INDEX idx_reg ON registrations(event_id, user_id)")
        db.run("INSERT INTO registrations VALUES (1, NULL), (1, NULL), (NULL, 100), (NULL, 100)")
        assertEquals(4, db.select("SELECT * FROM registrations").rows.size)
    }

    @Test
    fun createUniqueCompositeIndexOverExistingDuplicateCombinationsFailsAndLeavesNoTraceOfTheIndex() {
        val db = newDatabase()
        db.run("CREATE TABLE registrations (event_id INTEGER, user_id INTEGER)")
        db.run("INSERT INTO registrations VALUES (1, 100), (1, 100)")
        assertFailsWith<DiskTableException> { db.run("CREATE UNIQUE INDEX idx_reg ON registrations(event_id, user_id)") }
        assertTrue(db.catalog.table("registrations").secondaryIndexes.isEmpty())
    }

    @Test
    fun createUniqueCompositeIndexRejectsAnUpdateThatWouldDuplicateAnotherRowsCombination() {
        val db = newDatabase()
        db.run("CREATE TABLE registrations (id INTEGER PRIMARY KEY, event_id INTEGER, user_id INTEGER)")
        db.run("CREATE UNIQUE INDEX idx_reg ON registrations(event_id, user_id)")
        db.run("INSERT INTO registrations VALUES (1, 1, 100), (2, 1, 200)")
        assertFailsWith<DiskTableException> { db.run("UPDATE registrations SET user_id = 100 WHERE id = 2") }
        assertEquals(int(200), db.select("SELECT user_id FROM registrations WHERE id = 2").rows.single().single())
    }

    @Test
    fun createUniqueCompositeIndexDataAndConstraintSurviveClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE registrations (event_id INTEGER, user_id INTEGER)")
        db.run("CREATE UNIQUE INDEX idx_reg ON registrations(event_id, user_id)")
        db.run("INSERT INTO registrations VALUES (1, 100)")
        db.reopen()
        assertFailsWith<DiskTableException> { db.run("INSERT INTO registrations VALUES (1, 100)") }
        val handle = db.catalog.table("registrations").secondaryIndexes.single()
        assertEquals(listOf("event_id", "user_id"), handle.columnNames)
        assertTrue(handle.unique)
    }

    // --- index pushdown: proven by DiskTable's access counters, not just by correct results,
    // since a full scan and an index lookup return identical rows for these queries.

    @Test
    fun whereEqualityOnThePrimaryKeyUsesAPointLookupNotAFullScan() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val result = db.select("SELECT name FROM users WHERE id = 2")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
        assertEquals(1, table.pointLookupCount)
        assertEquals(0, table.scanCount)
    }

    @Test
    fun aMissingPrimaryKeyPointLookupReturnsNoRowsWithoutScanning() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val result = db.select("SELECT name FROM users WHERE id = 999")
        assertEquals(emptyList<List<Value>>(), result.rows)
        assertEquals(1, table.pointLookupCount)
        assertEquals(0, table.scanCount)
    }

    @Test
    fun aRangeOnThePrimaryKeyUsesARangeScanNotAFullScan() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val result = db.select("SELECT name FROM users WHERE id > 1 AND id <= 3 ORDER BY id")
        assertEquals(listOf(listOf(text("linus")), listOf(text("grace"))), result.rows)
        assertEquals(1, table.rangeScanCount)
        assertEquals(0, table.scanCount)
    }

    @Test
    fun equalityOnAnUnindexedColumnStillFallsBackToAFullScan() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val result = db.select("SELECT id FROM users WHERE name = 'linus'")
        assertEquals(listOf(listOf(int(2))), result.rows)
        assertEquals(1, table.scanCount)
        assertEquals(0, table.pointLookupCount)
    }

    @Test
    fun equalityOnASecondaryIndexedColumnUsesTheSecondaryIndexNotAFullScan() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        val table = db.catalog.table("users")
        val scansBeforeSelect = table.scanCount // backfilling the new index already scanned once
        val result = db.select("SELECT name FROM users WHERE age = 54")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
        assertEquals(1, table.secondaryLookupCount)
        assertEquals(scansBeforeSelect, table.scanCount)
    }

    @Test
    fun equalityOnAllColumnsOfACompositeSecondaryIndexUsesTheSecondaryIndexNotAFullScan() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, age INTEGER, name TEXT)")
        db.run("INSERT INTO users VALUES (1, 36, 'ada'), (2, 36, 'grace')")
        db.run("CREATE INDEX idx_both ON users(age, name)")
        val table = db.catalog.table("users")
        val scansBeforeSelect = table.scanCount // backfilling the new index already scanned once
        val result = db.select("SELECT id FROM users WHERE age = 36 AND name = 'grace'")
        assertEquals(listOf(listOf(int(2))), result.rows)
        assertEquals(1, table.secondaryLookupCount)
        assertEquals(scansBeforeSelect, table.scanCount)
    }

    @Test
    fun equalityOnOnlyOneColumnOfACompositeSecondaryIndexFallsBackToAFullScan() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, age INTEGER, name TEXT)")
        db.run("INSERT INTO users VALUES (1, 36, 'ada'), (2, 36, 'grace')")
        db.run("CREATE INDEX idx_both ON users(age, name)")
        val table = db.catalog.table("users")
        val scansBeforeSelect = table.scanCount
        val result = db.select("SELECT id FROM users WHERE age = 36")
        assertEquals(2, result.rows.size)
        assertEquals(0, table.secondaryLookupCount)
        assertEquals(scansBeforeSelect + 1, table.scanCount)
    }

    @Test
    fun aRangeOnASingleColumnSecondaryIndexUsesARangeScanNotAFullScan() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        val table = db.catalog.table("users")
        val scansBeforeSelect = table.scanCount // backfilling the new index already scanned once
        val result = db.select("SELECT name FROM users WHERE age > 40 AND age <= 85 ORDER BY age")
        assertEquals(listOf(listOf(text("linus")), listOf(text("grace"))), result.rows)
        assertEquals(1, table.rangeScanCount)
        assertEquals(0, table.secondaryLookupCount)
        assertEquals(scansBeforeSelect, table.scanCount)
    }

    @Test
    fun aRangeOnTheFirstColumnOfACompositeSecondaryIndexUsesARangeScanNotAFullScan() {
        val db = newDatabase()
        db.run("CREATE TABLE order_lines (order_id INTEGER, line_no INTEGER, sku TEXT)")
        db.run("INSERT INTO order_lines VALUES (1, 1, 'apple'), (2, 1, 'kiwi'), (3, 1, 'mango')")
        db.run("CREATE INDEX idx_order_sku ON order_lines(order_id, sku)")
        val table = db.catalog.table("order_lines")
        val scansBeforeSelect = table.scanCount // backfilling the new index already scanned once
        val result = db.select("SELECT sku FROM order_lines WHERE order_id > 1 ORDER BY order_id")
        assertEquals(listOf(listOf(text("kiwi")), listOf(text("mango"))), result.rows)
        assertEquals(1, table.rangeScanCount)
        assertEquals(scansBeforeSelect, table.scanCount)
    }

    @Test
    fun aRangeOnTheSecondColumnOfACompositeSecondaryIndexAfterAnEqualityPrefixUsesARangeScanNotAFullScan() {
        val db = newDatabase()
        db.run("CREATE TABLE order_lines (order_id INTEGER, line_no INTEGER, sku TEXT)")
        db.run("INSERT INTO order_lines VALUES (1, 1, 'apple'), (1, 2, 'mango'), (2, 1, 'kiwi')")
        db.run("CREATE INDEX idx_order_sku ON order_lines(order_id, sku)")
        val table = db.catalog.table("order_lines")
        val scansBeforeSelect = table.scanCount // backfilling the new index already scanned once
        val result = db.select("SELECT line_no FROM order_lines WHERE order_id = 1 AND sku > 'apple'")
        assertEquals(listOf(listOf(int(2))), result.rows)
        assertEquals(1, table.rangeScanCount)
        assertEquals(0, table.secondaryLookupCount)
        assertEquals(scansBeforeSelect, table.scanCount)
    }

    @Test
    fun aRangeOnTheSecondColumnOfACompositeSecondaryIndexWithoutAnEqualityPrefixFallsBackToAFullScan() {
        // Mirrors equalityOnOnlyOneColumnOfACompositeSecondaryIndexFallsBackToAFullScan - the index
        // is sorted by order_id first, so a bare range on sku cannot narrow the scan without an
        // equality prefix pinning order_id down first.
        val db = newDatabase()
        db.run("CREATE TABLE order_lines (order_id INTEGER, line_no INTEGER, sku TEXT)")
        db.run("INSERT INTO order_lines VALUES (1, 1, 'apple'), (1, 2, 'mango'), (2, 1, 'kiwi')")
        db.run("CREATE INDEX idx_order_sku ON order_lines(order_id, sku)")
        val table = db.catalog.table("order_lines")
        val scansBeforeSelect = table.scanCount
        val result = db.select("SELECT line_no FROM order_lines WHERE sku > 'apple'")
        assertEquals(2, result.rows.size)
        assertEquals(0, table.rangeScanCount)
        assertEquals(scansBeforeSelect + 1, table.scanCount)
    }

    @Test
    fun equalityOnASecondaryIndexIsPreferredOverARangeOnTheSameColumnAtRuntime() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        val table = db.catalog.table("users")
        val result = db.select("SELECT name FROM users WHERE age = 54 AND age > 0")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
        assertEquals(1, table.secondaryLookupCount)
        assertEquals(0, table.rangeScanCount)
    }

    @Test
    fun aPrimaryKeyEqualityIsPreferredOverASecondaryIndexInTheSamePredicate() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        val table = db.catalog.table("users")
        // age = 999 matches nobody, but a point lookup on id must still run (and the extra
        // conjunct is applied afterwards by the ordinary full-predicate filter), proving the
        // planner picked the primary key rather than the secondary index for this predicate.
        val result = db.select("SELECT name FROM users WHERE id = 2 AND age = 999")
        assertEquals(emptyList<List<Value>>(), result.rows)
        assertEquals(1, table.pointLookupCount)
        assertEquals(0, table.secondaryLookupCount)
    }

    @Test
    fun updateWithAPrimaryKeyEqualityWhereUsesAPointLookupNotAFullScan() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val updated = db.run("UPDATE users SET age = 99 WHERE id = 1").single()
        assertEquals(UpdateResult(1), updated)
        assertEquals(1, table.pointLookupCount)
        assertEquals(0, table.scanCount)
        assertEquals(int(99), db.select("SELECT age FROM users WHERE id = 1").rows.single().single())
    }

    @Test
    fun deleteWithAPrimaryKeyEqualityWhereUsesAPointLookupNotAFullScan() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val deleted = db.run("DELETE FROM users WHERE id = 3").single()
        assertEquals(DeleteResult(1), deleted)
        assertEquals(1, table.pointLookupCount)
        assertEquals(0, table.scanCount)
        assertEquals(2, db.select("SELECT * FROM users").rows.size)
    }

    // --- table-level composite PRIMARY KEY (a, b): PRIMARY KEY (a, b) instead of an inline
    // `col TYPE PRIMARY KEY`, mirroring kestrel.engine.Executor's own handling of the same clause.

    private fun Database.seedOrderLines() {
        run("CREATE TABLE order_lines (order_id INTEGER, line_no INTEGER, sku TEXT, PRIMARY KEY (order_id, line_no))")
        run("INSERT INTO order_lines VALUES (1, 1, 'widget'), (1, 2, 'gadget'), (2, 1, 'widget')")
    }

    @Test
    fun compositePrimaryKeyColumnsAreImplicitlyNotNullLikeAnInlinePrimaryKey() {
        val db = newDatabase()
        db.seedOrderLines()
        assertFailsWith<DiskTableException> { db.run("INSERT INTO order_lines (line_no, sku) VALUES (1, 'x')") }
    }

    @Test
    fun compositePrimaryKeyRejectsADuplicateColumnCombinationButAllowsSharedPartialValues() {
        val db = newDatabase()
        db.seedOrderLines()
        assertFailsWith<DiskTableException> { db.run("INSERT INTO order_lines VALUES (1, 1, 'duplicate')") }
        assertEquals(3, db.select("SELECT * FROM order_lines").rows.size)
    }

    @Test
    fun compositePrimaryKeyEqualityOnAllColumnsUsesAPointLookupNotAFullScan() {
        val db = newDatabase()
        db.seedOrderLines()
        val table = db.catalog.table("order_lines")
        val result = db.select("SELECT sku FROM order_lines WHERE order_id = 1 AND line_no = 2")
        assertEquals(listOf(listOf(text("gadget"))), result.rows)
        assertEquals(1, table.pointLookupCount)
        assertEquals(0, table.scanCount)
    }

    @Test
    fun compositePrimaryKeyEqualityOnOnlyOneColumnFallsBackToAFullScan() {
        val db = newDatabase()
        db.seedOrderLines()
        val table = db.catalog.table("order_lines")
        val result = db.select("SELECT sku FROM order_lines WHERE order_id = 1")
        assertEquals(setOf("widget", "gadget"), result.rows.map { (it.single() as Value.TextValue).value }.toSet())
        assertEquals(1, table.scanCount)
        assertEquals(0, table.pointLookupCount)
    }

    @Test
    fun aRangeOnTheLastCompositePrimaryKeyColumnAfterAnEqualityPrefixUsesARangeScanNotAFullScan() {
        val db = newDatabase()
        db.seedOrderLines()
        val table = db.catalog.table("order_lines")
        val result = db.select("SELECT sku FROM order_lines WHERE order_id = 1 AND line_no > 1")
        assertEquals(listOf(listOf(text("gadget"))), result.rows)
        assertEquals(1, table.rangeScanCount)
        assertEquals(0, table.scanCount)
    }

    @Test
    fun aTwoSidedRangeOnTheLastCompositePrimaryKeyColumnAfterAnEqualityPrefixUsesARangeScan() {
        val db = newDatabase()
        db.seedOrderLines()
        db.run("INSERT INTO order_lines VALUES (1, 3, 'sprocket')")
        val table = db.catalog.table("order_lines")
        val result = db.select("SELECT sku FROM order_lines WHERE order_id = 1 AND line_no >= 1 AND line_no <= 2")
        assertEquals(setOf("widget", "gadget"), result.rows.map { (it.single() as Value.TextValue).value }.toSet())
        assertEquals(1, table.rangeScanCount)
        assertEquals(0, table.scanCount)
    }

    @Test
    fun aRangeOnAnyOtherCompositePrimaryKeyColumnWithoutTheFullEqualityPrefixFallsBackToAFullScan() {
        val db = newDatabase()
        db.seedOrderLines()
        val table = db.catalog.table("order_lines")
        val result = db.select("SELECT sku FROM order_lines WHERE order_id > 1")
        assertEquals(listOf(listOf(text("widget"))), result.rows)
        assertEquals(1, table.scanCount)
        assertEquals(0, table.rangeScanCount)
    }

    @Test
    fun updateAndDeleteByFullCompositePrimaryKeyUsePointLookups() {
        val db = newDatabase()
        db.seedOrderLines()
        val table = db.catalog.table("order_lines")

        val updated = db.run("UPDATE order_lines SET sku = 'sprocket' WHERE order_id = 1 AND line_no = 1").single()
        assertEquals(UpdateResult(1), updated)
        assertEquals(1, table.pointLookupCount)

        val deleted = db.run("DELETE FROM order_lines WHERE order_id = 2 AND line_no = 1").single()
        assertEquals(DeleteResult(1), deleted)
        assertEquals(2, table.pointLookupCount)
        assertEquals(0, table.scanCount)
        assertEquals(2, db.select("SELECT * FROM order_lines").rows.size)
    }

    @Test
    fun aTableCannotDeclareBothAnInlinePrimaryKeyColumnAndATableLevelCompositePrimaryKey() {
        val db = newDatabase()
        assertFailsWith<DiskExecutorException> {
            db.run("CREATE TABLE bad (id INTEGER PRIMARY KEY, other INTEGER, PRIMARY KEY (id, other))")
        }
    }

    @Test
    fun aCompositePrimaryKeyReferencingAnUnknownColumnIsRejectedWithAClearError() {
        val db = newDatabase()
        val failure = assertFailsWith<DiskExecutorException> {
            db.run("CREATE TABLE bad (order_id INTEGER, line_no INTEGER, PRIMARY KEY (order_id, missing_column))")
        }
        assertTrue(failure.message!!.contains("missing_column"))
    }

    @Test
    fun compositePrimaryKeyDataAndUniquenessSurviveClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.seedOrderLines()
        db.reopen()

        assertFailsWith<DiskTableException> { db.run("INSERT INTO order_lines VALUES (1, 1, 'duplicate')") }
        val result = db.select("SELECT sku FROM order_lines WHERE order_id = 1 AND line_no = 2")
        assertEquals(listOf(listOf(text("gadget"))), result.rows)
        val table = db.catalog.table("order_lines")
        assertEquals(0, table.scanCount, "the point lookup above must not have fallen back to a full scan after reopening")
    }

    private fun Database.seedDepartments() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL, budget INTEGER)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, name TEXT NOT NULL, department_id INTEGER, salary INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng', 1000), (2, 'sales', 500), (3, 'ops', 0)")
        run(
            "INSERT INTO employees VALUES " +
                "(1, 'ada', 1, 120), (2, 'linus', 1, 90), (3, 'grace', 2, 80), (4, 'guido', 2, 70)"
        )
    }

    @Test
    fun scalarSubqueryComparesAgainstAnUncorrelatedAggregate() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select("SELECT name FROM employees WHERE salary > (SELECT AVG(salary) FROM employees) ORDER BY name")
        assertEquals(listOf(listOf(text("ada"))), result.rows)
    }

    @Test
    fun scalarSubqueryReturningNoRowsEvaluatesToNull() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select("SELECT (SELECT id FROM departments WHERE name = 'nope') FROM departments WHERE id = 1")
        assertEquals(listOf(listOf(Value.NullValue)), result.rows)
    }

    @Test
    fun scalarSubqueryReturningMoreThanOneRowFails() {
        val db = newDatabase()
        db.seedDepartments()
        val failure = assertFailsWith<KestrelException> {
            db.select("SELECT (SELECT salary FROM employees) FROM departments WHERE id = 1")
        }
        assertTrue(failure.message!!.contains("more than one row"))
    }

    @Test
    fun scalarSubqueryReturningMoreThanOneColumnFails() {
        val db = newDatabase()
        db.seedDepartments()
        val failure = assertFailsWith<KestrelException> {
            db.select("SELECT (SELECT id, name FROM departments) FROM departments WHERE id = 1")
        }
        assertTrue(failure.message!!.contains("exactly one column"))
    }

    @Test
    fun correlatedScalarSubqueryInProjectionCountsPerOuterRow() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select(
            "SELECT d.name, (SELECT COUNT(*) FROM employees e WHERE e.department_id = d.id) " +
                "FROM departments d ORDER BY d.id"
        )
        assertEquals(
            listOf(listOf(text("eng"), int(2)), listOf(text("sales"), int(2)), listOf(text("ops"), int(0))),
            result.rows
        )
    }

    @Test
    fun correlatedScalarSubqueryInWhereComparesAgainstTheEmployeesOwnDepartmentAverage() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select(
            "SELECT name FROM employees e WHERE e.salary > " +
                "(SELECT AVG(salary) FROM employees e2 WHERE e2.department_id = e.department_id) ORDER BY name"
        )
        assertEquals(listOf(listOf(text("ada")), listOf(text("grace"))), result.rows)
    }

    @Test
    fun inSubqueryMatchesAnUncorrelatedResultSet() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select("SELECT name FROM departments WHERE id IN (SELECT department_id FROM employees) ORDER BY name")
        assertEquals(listOf(listOf(text("eng")), listOf(text("sales"))), result.rows)
    }

    @Test
    fun notInSubqueryExcludesMatchingRows() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select("SELECT name FROM departments WHERE id NOT IN (SELECT department_id FROM employees)")
        assertEquals(listOf(listOf(text("ops"))), result.rows)
    }

    @Test
    fun notInSubqueryWithANullInTheResultSetExcludesEverythingByThreeValuedLogic() {
        val db = newDatabase()
        db.run("CREATE TABLE d (id INTEGER)")
        db.run("CREATE TABLE e (dept INTEGER)")
        db.run("INSERT INTO d VALUES (1), (2)")
        db.run("INSERT INTO e VALUES (1), (NULL)")
        val result = db.select("SELECT id FROM d WHERE id NOT IN (SELECT dept FROM e)")
        assertEquals(emptyList(), result.rows)
    }

    @Test
    fun inSubqueryRequiresExactlyOneColumn() {
        val db = newDatabase()
        db.seedDepartments()
        val failure = assertFailsWith<KestrelException> {
            db.select("SELECT name FROM departments WHERE id IN (SELECT id, name FROM departments)")
        }
        assertTrue(failure.message!!.contains("exactly one column"))
    }

    @Test
    fun existsWithoutCorrelationChecksOnlyWhetherTheSubqueryHasAnyRows() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select("SELECT name FROM departments WHERE EXISTS (SELECT 1 FROM employees) ORDER BY name")
        assertEquals(listOf(listOf(text("eng")), listOf(text("ops")), listOf(text("sales"))), result.rows)
    }

    @Test
    fun correlatedExistsFindsDepartmentsWithEmployees() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select(
            "SELECT name FROM departments d WHERE EXISTS " +
                "(SELECT 1 FROM employees e WHERE e.department_id = d.id) ORDER BY name"
        )
        assertEquals(listOf(listOf(text("eng")), listOf(text("sales"))), result.rows)
    }

    @Test
    fun correlatedNotExistsFindsDepartmentsWithoutEmployees() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select(
            "SELECT name FROM departments d WHERE NOT EXISTS (SELECT 1 FROM employees e WHERE e.department_id = d.id)"
        )
        assertEquals(listOf(listOf(text("ops"))), result.rows)
    }

    @Test
    fun nestedSubqueriesCorrelateAcrossTwoLevelsOfOuterRows() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select(
            "SELECT d.name FROM departments d WHERE EXISTS (" +
                "SELECT 1 FROM employees e WHERE e.department_id = d.id AND e.salary > (" +
                "SELECT AVG(salary) FROM employees e2 WHERE e2.department_id = d.id" +
                ")) ORDER BY d.name"
        )
        assertEquals(listOf(listOf(text("eng")), listOf(text("sales"))), result.rows)
    }

    @Test
    fun correlatedScalarSubqueryIsUsableInUpdateSet() {
        val db = newDatabase()
        db.seedDepartments()
        db.run("CREATE TABLE dept_stats (id INTEGER PRIMARY KEY, headcount INTEGER)")
        db.run("INSERT INTO dept_stats VALUES (1, 0), (2, 0), (3, 0)")
        db.run(
            "UPDATE dept_stats SET headcount = " +
                "(SELECT COUNT(*) FROM employees WHERE employees.department_id = dept_stats.id)"
        )
        val result = db.select("SELECT headcount FROM dept_stats ORDER BY id")
        assertEquals(listOf(listOf(int(2)), listOf(int(2)), listOf(int(0))), result.rows)
    }

    @Test
    fun inSubqueryIsUsableInDeleteWhereClause() {
        val db = newDatabase()
        db.seedDepartments()
        db.run("INSERT INTO employees VALUES (5, 'temp', 3, 10)")
        db.run("DELETE FROM employees WHERE department_id IN (SELECT id FROM departments WHERE budget = 0)")
        val result = db.select("SELECT name FROM employees ORDER BY name")
        assertEquals(
            listOf(listOf(text("ada")), listOf(text("grace")), listOf(text("guido")), listOf(text("linus"))),
            result.rows
        )
    }

    @Test
    fun scalarSubqueryIsUsableAsAnInsertValue() {
        val db = newDatabase()
        db.seedDepartments()
        db.run("CREATE TABLE summary (id INTEGER PRIMARY KEY, employee_count INTEGER)")
        db.run("INSERT INTO summary VALUES (1, (SELECT COUNT(*) FROM employees))")
        val result = db.select("SELECT employee_count FROM summary")
        assertEquals(listOf(listOf(int(4))), result.rows)
    }

    @Test
    fun aggregateOverAnUncorrelatedScalarSubqueryEvaluatesItPerRow() {
        val db = newDatabase()
        db.seedDepartments()
        val result = db.select("SELECT SUM((SELECT budget FROM departments WHERE id = 1)) FROM employees")
        assertEquals(listOf(listOf(int(1000 * 4))), result.rows)
    }

    @Test
    fun existsResultCannotBeUsedAsAnArithmeticOperand() {
        val db = newDatabase()
        db.seedDepartments()
        assertFailsWith<KestrelException> {
            db.select("SELECT 1 + EXISTS (SELECT 1 FROM employees) FROM departments WHERE id = 1")
        }
    }

    @Test
    fun aSubqueryReferencingAnUnknownColumnFailsWithAClearError() {
        val db = newDatabase()
        db.seedDepartments()
        val failure = assertFailsWith<KestrelException> {
            db.select("SELECT (SELECT missing_column FROM employees) FROM departments")
        }
        assertTrue(failure.message!!.contains("missing_column"))
    }

    @Test
    fun subqueryAgainstADerivedTableInsideItselfWorks() {
        val db = newDatabase()
        db.seedDepartments()
        val matches = db.select(
            "SELECT name FROM departments WHERE EXISTS " +
                "(SELECT 1 FROM (SELECT id FROM employees WHERE salary > 100) sub) ORDER BY name"
        )
        assertEquals(listOf(text("eng"), text("ops"), text("sales")), matches.rows.map { it.single() })

        val none = db.select(
            "SELECT name FROM departments WHERE EXISTS (SELECT 1 FROM (SELECT id FROM employees WHERE salary > 1000) sub)"
        )
        assertEquals(emptyList(), none.rows)
    }

    private fun Database.seedCompound() {
        run("CREATE TABLE dual (x INTEGER)")
        run("INSERT INTO dual VALUES (1)")
        run("CREATE TABLE t1 (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("CREATE TABLE t2 (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("INSERT INTO t1 VALUES (1, 'ada'), (2, 'linus')")
        run("INSERT INTO t2 VALUES (2, 'linus'), (3, 'grace')")
    }

    @Test
    fun unionRemovesDuplicateRows() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select("SELECT name FROM t1 UNION SELECT name FROM t2 ORDER BY name")
        assertEquals(listOf(listOf(text("ada")), listOf(text("grace")), listOf(text("linus"))), result.rows)
    }

    @Test
    fun unionAllKeepsDuplicateRows() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select("SELECT name FROM t1 UNION ALL SELECT name FROM t2 ORDER BY name")
        assertEquals(
            listOf(listOf(text("ada")), listOf(text("grace")), listOf(text("linus")), listOf(text("linus"))),
            result.rows
        )
    }

    @Test
    fun intersectReturnsRowsPresentInBothQueries() {
        val db = newDatabase()
        db.seedCompound()
        assertEquals(listOf(listOf(text("linus"))), db.select("SELECT name FROM t1 INTERSECT SELECT name FROM t2").rows)
    }

    @Test
    fun exceptReturnsRowsOnlyInTheLeftQuery() {
        val db = newDatabase()
        db.seedCompound()
        assertEquals(listOf(listOf(text("ada"))), db.select("SELECT name FROM t1 EXCEPT SELECT name FROM t2").rows)
    }

    @Test
    fun intersectAllAndExceptAllPreserveMultisetCounts() {
        val db = newDatabase()
        db.run("CREATE TABLE bag_a (v INTEGER)")
        db.run("CREATE TABLE bag_b (v INTEGER)")
        db.run("INSERT INTO bag_a VALUES (1), (1), (1), (2)")
        db.run("INSERT INTO bag_b VALUES (1), (1), (2), (2), (3)")
        assertEquals(
            listOf(listOf(int(1)), listOf(int(1)), listOf(int(2))),
            db.select("SELECT v FROM bag_a INTERSECT ALL SELECT v FROM bag_b ORDER BY v").rows
        )
        assertEquals(listOf(listOf(int(1))), db.select("SELECT v FROM bag_a EXCEPT ALL SELECT v FROM bag_b ORDER BY v").rows)
    }

    @Test
    fun compoundQueryWithMismatchedColumnCountsFails() {
        val db = newDatabase()
        db.seedCompound()
        assertFailsWith<DiskExecutorException> { db.select("SELECT id, name FROM t1 UNION SELECT id FROM t2") }
    }

    @Test
    fun compoundOperatorsChainLeftAssociatively() {
        val db = newDatabase()
        db.seedCompound()
        db.run("CREATE TABLE t3 (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.run("INSERT INTO t3 VALUES (2, 'linus')")
        val result = db.select("SELECT name FROM t1 UNION ALL SELECT name FROM t2 INTERSECT SELECT name FROM t3 ORDER BY name")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }

    @Test
    fun orderByAndLimitApplyToTheWholeCompoundResultNotEachSide() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select("SELECT name FROM t1 UNION ALL SELECT name FROM t2 ORDER BY name DESC LIMIT 2")
        assertEquals(listOf(listOf(text("linus")), listOf(text("linus"))), result.rows)
    }

    @Test
    fun offsetSkipsRowsOfTheWholeCompoundResultBeforeLimitApplies() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select("SELECT name FROM t1 UNION ALL SELECT name FROM t2 ORDER BY name DESC LIMIT 2 OFFSET 1")
        assertEquals(listOf(listOf(text("linus")), listOf(text("grace"))), result.rows)
    }

    @Test
    fun withClauseMakesACteAvailableInFrom() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select("WITH recent AS (SELECT id, name FROM t1) SELECT name FROM recent WHERE id = 1")
        assertEquals(listOf(listOf(text("ada"))), result.rows)
    }

    @Test
    fun aCteShadowsARealTableOfTheSameName() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select("WITH t1 AS (SELECT id FROM t1 WHERE id = 1) SELECT * FROM t1")
        assertEquals(listOf("id"), result.columns)
        assertEquals(listOf(listOf(int(1))), result.rows)
    }

    @Test
    fun laterCteCanReferenceAnEarlierCteInTheSameWithClause() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select(
            "WITH first_cte AS (SELECT id, name FROM t1), second_cte AS (SELECT name FROM first_cte WHERE id = 2) " +
                "SELECT name FROM second_cte"
        )
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }

    @Test
    fun cteCanBeJoinedWithARealDiskTable() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select("WITH t1_names AS (SELECT id, name FROM t1) SELECT t2.name FROM t1_names JOIN t2 ON t1_names.id = t2.id")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }

    @Test
    fun cteWithExplicitColumnListRenamesOutputColumns() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select("WITH renamed(label) AS (SELECT name FROM t1) SELECT label FROM renamed ORDER BY label")
        assertEquals(listOf(listOf(text("ada")), listOf(text("linus"))), result.rows)
    }

    @Test
    fun cteColumnListSizeMustMatchQueryColumnCount() {
        val db = newDatabase()
        db.seedCompound()
        val failure = assertFailsWith<DiskExecutorException> { db.select("WITH bad(a, b, c) AS (SELECT id FROM t1) SELECT * FROM bad") }
        assertTrue(failure.message!!.contains("bad"))
    }

    @Test
    fun recursiveCteGeneratesABoundedSequence() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select(
            "WITH RECURSIVE counter(n) AS (" +
                "SELECT 1 FROM dual " +
                "UNION ALL " +
                "SELECT n + 1 FROM counter WHERE n < 5" +
                ") SELECT n FROM counter ORDER BY n"
        )
        assertEquals((1..5).map { listOf(int(it.toLong())) }, result.rows)
    }

    @Test
    fun recursiveCteWithUnionDeduplicatesAndTerminatesOnACycleThroughARealDiskTable() {
        val db = newDatabase()
        db.seedCompound()
        db.run("CREATE TABLE edges (from_node INTEGER, to_node INTEGER)")
        db.run("INSERT INTO edges VALUES (1, 2), (2, 3), (3, 1), (3, 4)")
        val result = db.select(
            "WITH RECURSIVE reachable(node) AS (" +
                "SELECT 1 FROM dual " +
                "UNION " +
                "SELECT edges.to_node FROM edges JOIN reachable ON edges.from_node = reachable.node" +
                ") SELECT node FROM reachable ORDER BY node"
        )
        assertEquals(listOf(1L, 2L, 3L, 4L).map { listOf(int(it)) }, result.rows)
    }

    @Test
    fun recursiveCteWithoutSelfReferenceBehavesAsAPlainCompoundQuery() {
        val db = newDatabase()
        db.seedCompound()
        val result = db.select(
            "WITH RECURSIVE notActuallyRecursive AS (SELECT 1 AS n FROM dual UNION ALL SELECT 2 FROM dual) " +
                "SELECT n FROM notActuallyRecursive ORDER BY n"
        )
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), result.rows)
    }

    @Test
    fun recursiveCteReferencingItselfOnBothSidesFails() {
        val db = newDatabase()
        db.seedCompound()
        val failure = assertFailsWith<DiskExecutorException> {
            db.select("WITH RECURSIVE bad(n) AS (SELECT n FROM bad UNION ALL SELECT n FROM bad) SELECT n FROM bad")
        }
        assertTrue(failure.message!!.contains("bad"))
    }

    @Test
    fun nonTerminatingRecursiveCteFailsInsteadOfLoopingForever() {
        val db = newDatabase()
        db.seedCompound()
        val failure = assertFailsWith<DiskExecutorException> {
            db.select(
                "WITH RECURSIVE counter(n) AS (SELECT 1 FROM dual UNION ALL SELECT n + 1 FROM counter) SELECT n FROM counter"
            )
        }
        assertTrue(failure.message!!.contains("did not terminate"))
    }

    @Test
    fun aWithClauseThatNeverReferencesItsCteLeavesOrdinaryPrimaryKeyPushdownUntouched() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val pointLookupsBefore = table.pointLookupCount
        val scansBefore = table.scanCount
        val result = db.select("WITH unused AS (SELECT id FROM users) SELECT name FROM users WHERE id = 1")
        assertEquals(listOf(listOf(text("ada"))), result.rows)
        // Every CTE is materialized up front regardless of whether the body ends up using it (mirroring
        // kestrel.engine.SelectRunner), so "unused" itself contributes exactly one full scan - the point is
        // that the OUTER query's own WHERE id = 1 still gets its point lookup rather than also falling
        // back to a scan just because a WITH clause is present.
        assertEquals(scansBefore + 1, table.scanCount)
        assertEquals(pointLookupsBefore + 1, table.pointLookupCount)
    }

    /**
     * eng (deptId 1): ada/linus tie at 400 (ada inserted first), grace at 300. sales (deptId 2):
     * sam/kay tie at 200 (sam inserted first), meg at 100. Mirrors kestrel.WindowFunctionTest's seed()
     * exactly so results can be cross-checked against the in-memory engine's own known-good values.
     */
    private fun Database.seedWindowEmployees() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, deptId INTEGER, name TEXT, salary INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng'), (2, 'sales')")
        run(
            "INSERT INTO employees VALUES " +
                "(1, 1, 'ada', 400), (2, 1, 'linus', 400), (3, 1, 'grace', 300), " +
                "(4, 2, 'sam', 200), (5, 2, 'kay', 200), (6, 2, 'meg', 100)"
        )
    }

    @Test
    fun rowNumberAssignsSequentialNumbersWithinEachPartition() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, ROW_NUMBER() OVER (PARTITION BY deptId ORDER BY salary DESC) AS rn " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(1)), listOf(int(2), int(2)), listOf(int(3), int(3)),
                listOf(int(4), int(1)), listOf(int(5), int(2)), listOf(int(6), int(3))
            ),
            result.rows
        )
    }

    @Test
    fun rowNumberWithoutOverOrderByFallsBackToPartitionArrivalOrder() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select("SELECT id, ROW_NUMBER() OVER (PARTITION BY deptId) AS rn FROM employees ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(int(1), int(1)), listOf(int(2), int(2)), listOf(int(3), int(3)),
                listOf(int(4), int(1)), listOf(int(5), int(2)), listOf(int(6), int(3))
            ),
            result.rows
        )
    }

    @Test
    fun rankLeavesGapsAfterTiesButDenseRankDoesNot() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, RANK() OVER (PARTITION BY deptId ORDER BY salary DESC) AS r, " +
                "DENSE_RANK() OVER (PARTITION BY deptId ORDER BY salary DESC) AS dr " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(1), int(1)),
                listOf(int(2), int(1), int(1)),
                listOf(int(3), int(3), int(2)),
                listOf(int(4), int(1), int(1)),
                listOf(int(5), int(1), int(1)),
                listOf(int(6), int(3), int(2))
            ),
            result.rows
        )
    }

    @Test
    fun rankWithoutPartitionOrdersTheWholeResultSet() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select("SELECT id, RANK() OVER (ORDER BY salary DESC) AS r FROM employees ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(int(1), int(1)), listOf(int(2), int(1)), listOf(int(3), int(3)),
                listOf(int(4), int(4)), listOf(int(5), int(4)), listOf(int(6), int(6))
            ),
            result.rows
        )
    }

    @Test
    fun aggregateWindowWithoutOrderByRepeatsTheWholePartitionTotalOnEveryRow() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select("SELECT id, SUM(salary) OVER (PARTITION BY deptId) AS deptTotal FROM employees ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(int(1), int(1100)), listOf(int(2), int(1100)), listOf(int(3), int(1100)),
                listOf(int(4), int(500)), listOf(int(5), int(500)), listOf(int(6), int(500))
            ),
            result.rows
        )
    }

    @Test
    fun aggregateWindowWithOrderByRunsCumulativelyAndTiesSharePeerGroupTotal() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY salary DESC) AS running FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(800)), listOf(int(2), int(800)), listOf(int(3), int(1100)),
                listOf(int(4), int(400)), listOf(int(5), int(400)), listOf(int(6), int(500))
            ),
            result.rows
        )
    }

    @Test
    fun aggregateWindowWithUniqueOrderKeyProducesARunningTotal() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select("SELECT id, SUM(salary) OVER (ORDER BY id) AS running FROM employees ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(int(1), int(400)), listOf(int(2), int(800)), listOf(int(3), int(1100)),
                listOf(int(4), int(1300)), listOf(int(5), int(1500)), listOf(int(6), int(1600))
            ),
            result.rows
        )
    }

    @Test
    fun countStarOverPartitionCountsRowsRegardlessOfNulls() {
        val db = newDatabase()
        db.seedWindowEmployees()
        db.run("INSERT INTO employees VALUES (7, 3, 'unpaid', NULL)")
        val result = db.select(
            "SELECT COUNT(*) OVER (PARTITION BY deptId) AS n, COUNT(salary) OVER (PARTITION BY deptId) AS paid " +
                "FROM employees WHERE deptId = 3"
        )
        assertEquals(listOf(listOf(int(1), int(0))), result.rows)
    }

    @Test
    fun windowFunctionOverAJoinPartitionsByTheJoinedColumn() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT e.name, d.name AS dept, " +
                "ROW_NUMBER() OVER (PARTITION BY d.name ORDER BY e.salary DESC) AS rn " +
                "FROM employees e JOIN departments d ON e.deptId = d.id " +
                "WHERE d.name = 'eng' ORDER BY rn ASC"
        )
        assertEquals(
            listOf(
                listOf(text("ada"), text("eng"), int(1)),
                listOf(text("linus"), text("eng"), int(2)),
                listOf(text("grace"), text("eng"), int(3))
            ),
            result.rows
        )
    }

    @Test
    fun orderByCanReferenceAWindowFunctionByItsProjectionAlias() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select("SELECT id, RANK() OVER (ORDER BY salary DESC) AS r FROM employees ORDER BY r ASC, id ASC")
        assertEquals((1..6).map { it.toLong() }, result.rows.map { (it[0] as Value.IntegerValue).value })
    }

    @Test
    fun selectStarWithAWindowFunctionOnlyInOrderByDoesNotLeakTheExtraColumn() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select("SELECT * FROM departments ORDER BY ROW_NUMBER() OVER (ORDER BY id) DESC")
        assertEquals(listOf("id", "name"), result.columns)
        assertEquals(listOf(listOf(int(2), text("sales")), listOf(int(1), text("eng"))), result.rows)
    }

    @Test
    fun windowFunctionIsRejectedInWhere() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT id FROM employees WHERE ROW_NUMBER() OVER (ORDER BY id) = 1")
        }
        assertTrue(error.message!!.contains("WHERE"), error.message!!)
    }

    @Test
    fun windowFunctionIsRejectedInGroupBy() {
        val db = newDatabase()
        db.seedWindowEmployees()
        assertFailsWith<DiskExecutorException> {
            db.run("SELECT COUNT(*) FROM employees GROUP BY ROW_NUMBER() OVER (ORDER BY id)")
        }
    }

    @Test
    fun windowFunctionIsRejectedInHaving() {
        val db = newDatabase()
        db.seedWindowEmployees()
        assertFailsWith<DiskExecutorException> {
            db.run("SELECT deptId, COUNT(*) FROM employees GROUP BY deptId HAVING ROW_NUMBER() OVER (ORDER BY deptId) > 1")
        }
    }

    @Test
    fun windowFunctionIsRejectedInAnOnCondition() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT e.id FROM employees e JOIN departments d ON ROW_NUMBER() OVER (ORDER BY e.id) = d.id")
        }
        assertTrue(error.message!!.contains("ON condition"), error.message!!)
    }

    @Test
    fun windowFunctionCannotBeCombinedWithGroupByInTheSameQuery() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT deptId, COUNT(*), ROW_NUMBER() OVER (ORDER BY deptId) FROM employees GROUP BY deptId")
        }
        assertTrue(error.message!!.contains("combined"), error.message!!)
    }

    @Test
    fun rankRequiresAnOrderByInsideOver() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT RANK() OVER (PARTITION BY deptId) FROM employees")
        }
        assertTrue(error.message!!.contains("ORDER BY"), error.message!!)
    }

    @Test
    fun denseRankRequiresAnOrderByInsideOver() {
        val db = newDatabase()
        db.seedWindowEmployees()
        assertFailsWith<DiskExecutorException> {
            db.run("SELECT DENSE_RANK() OVER (PARTITION BY deptId) FROM employees")
        }
    }

    @Test
    fun nestedWindowFunctionsAreRejected() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT SUM(salary) OVER (ORDER BY ROW_NUMBER() OVER (ORDER BY id)) FROM employees")
        }
        assertTrue(error.message!!.contains("nested"), error.message!!)
    }

    @Test
    fun lagReturnsThePreviousRowsValueWithinEachPartitionAndNullAtTheEdge() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, LAG(salary) OVER (PARTITION BY deptId ORDER BY id) AS prev FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), Value.NullValue), listOf(int(2), int(400)), listOf(int(3), int(400)),
                listOf(int(4), Value.NullValue), listOf(int(5), int(200)), listOf(int(6), int(200))
            ),
            result.rows
        )
    }

    @Test
    fun leadReturnsTheNextRowsValueWithinEachPartitionAndNullAtTheEdge() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, LEAD(salary) OVER (PARTITION BY deptId ORDER BY id) AS next FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(400)), listOf(int(2), int(300)), listOf(int(3), Value.NullValue),
                listOf(int(4), int(200)), listOf(int(5), int(100)), listOf(int(6), Value.NullValue)
            ),
            result.rows
        )
    }

    @Test
    fun lagWithAnExplicitOffsetSkipsMultipleRows() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select("SELECT id, LAG(salary, 2) OVER (ORDER BY id) AS prev2 FROM employees ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(int(1), Value.NullValue), listOf(int(2), Value.NullValue), listOf(int(3), int(400)),
                listOf(int(4), int(400)), listOf(int(5), int(300)), listOf(int(6), int(200))
            ),
            result.rows
        )
    }

    @Test
    fun lagPastThePartitionEdgeFallsBackToTheDefaultExpressionInsteadOfNull() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, LAG(salary, 1, 0) OVER (PARTITION BY deptId ORDER BY id) AS prev FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(int(0), int(400), int(400), int(0), int(200), int(200)), result.rows.map { it[1] })
    }

    @Test
    fun lagOffsetMustNotBeNegative() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT LAG(salary, -1) OVER (ORDER BY id) FROM employees")
        }
        assertTrue(error.message!!.contains("negative"), error.message!!)
    }

    @Test
    fun lagOffsetMustBeAnInteger() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT LAG(salary, 1.5) OVER (ORDER BY id) FROM employees")
        }
        assertTrue(error.message!!.contains("integer"), error.message!!)
    }

    @Test
    fun firstValueReturnsTheFirstRowsValueForEveryRowInThePartition() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, FIRST_VALUE(salary) OVER (PARTITION BY deptId ORDER BY id) AS f FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(400L, 400L, 400L, 200L, 200L, 200L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun lastValueSharesOneValuePerPeerGroupRatherThanTheWholePartition() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, LAST_VALUE(salary) OVER (PARTITION BY deptId ORDER BY salary DESC) AS l FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(400L, 400L, 300L, 200L, 200L, 100L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun lastValueWithoutOverOrderByUsesTheWholePartitionsLastRow() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, LAST_VALUE(salary) OVER (PARTITION BY deptId) AS l FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(300L, 300L, 300L, 100L, 100L, 100L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun ntileSplitsEachPartitionIntoAsEvenBucketsAsPossible() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, NTILE(2) OVER (PARTITION BY deptId ORDER BY salary DESC) AS bucket FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(1L, 1L, 2L, 1L, 1L, 2L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun ntileWithMoreBucketsThanRowsLeavesTheHighestBucketsUnused() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, NTILE(5) OVER (PARTITION BY deptId ORDER BY salary DESC) AS bucket FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(1L, 2L, 3L, 1L, 2L, 3L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun ntileBucketCountMustBePositive() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT NTILE(0) OVER (ORDER BY id) FROM employees")
        }
        assertTrue(error.message!!.contains("positive"), error.message!!)
    }

    @Test
    fun ntileBucketCountMustBeAnInteger() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT NTILE(1.5) OVER (ORDER BY id) FROM employees")
        }
        assertTrue(error.message!!.contains("integer"), error.message!!)
    }

    @Test
    fun windowFunctionNestedInsideLagsOffsetIsRejected() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT LAG(salary, ROW_NUMBER() OVER (ORDER BY id)) OVER (ORDER BY id) FROM employees")
        }
        assertTrue(error.message!!.contains("nested"), error.message!!)
    }

    @Test
    fun explicitRowsFrameProducesAMovingAverageInsteadOfARunningTotal() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS s " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(400L, 800L, 700L, 200L, 400L, 300L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rowsBetweenCurrentRowAndFollowingLooksForward() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN CURRENT ROW AND 1 FOLLOWING) AS s " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(listOf(800L, 700L, 300L, 400L, 300L, 100L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rowsBetweenUnboundedPrecedingAndCurrentRowMatchesTheImplicitDefaultOnAUniqueOrderKey() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val explicit = db.select(
            "SELECT id, SUM(salary) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s " +
                "FROM employees ORDER BY id ASC"
        )
        val implicit = db.select("SELECT id, SUM(salary) OVER (ORDER BY id) AS s FROM employees ORDER BY id ASC")
        assertEquals(implicit.rows, explicit.rows)
    }

    @Test
    fun frameEntirelyPastThePartitionEdgeAggregatesToNullAndCountsZero() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 5 FOLLOWING AND 9 FOLLOWING) AS s, " +
                "COUNT(*) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 5 FOLLOWING AND 9 FOLLOWING) AS c " +
                "FROM employees WHERE deptId = 1 ORDER BY id ASC"
        )
        assertEquals(
            listOf(listOf(int(1), Value.NullValue, int(0)), listOf(int(2), Value.NullValue, int(0)), listOf(int(3), Value.NullValue, int(0))),
            result.rows
        )
    }

    @Test
    fun firstValueAndLastValueRespectAnExplicitFrameInsteadOfThePeerGroupDefault() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, " +
                "FIRST_VALUE(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS f, " +
                "LAST_VALUE(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN CURRENT ROW AND 1 FOLLOWING) AS l " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(400), int(400)),
                listOf(int(2), int(400), int(300)),
                listOf(int(3), int(400), int(300)),
                listOf(int(4), int(200), int(200)),
                listOf(int(5), int(200), int(100)),
                listOf(int(6), int(200), int(100))
            ),
            result.rows
        )
    }

    @Test
    fun rowsFrameShorthandWithoutBetweenEndsAtCurrentRow() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val explicit = db.select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS 1 PRECEDING) AS s FROM employees ORDER BY id ASC"
        )
        val spelledOut = db.select(
            "SELECT id, SUM(salary) OVER (PARTITION BY deptId ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS s " +
                "FROM employees ORDER BY id ASC"
        )
        assertEquals(spelledOut.rows, explicit.rows)
    }

    @Test
    fun rowsFrameOffsetMustNotBeNegative() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN -1 PRECEDING AND CURRENT ROW) FROM employees")
        }
        assertTrue(error.message!!.contains("negative"), error.message!!)
    }

    @Test
    fun rowsFrameOffsetMustBeAnInteger() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN 1.5 PRECEDING AND CURRENT ROW) FROM employees")
        }
        assertTrue(error.message!!.contains("integer"), error.message!!)
    }

    @Test
    fun rowsFrameIsRejectedOnRankingAndOffsetWindowFunctions() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT ROW_NUMBER() OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM employees")
        }
        assertTrue(error.message!!.contains("frame"), error.message!!)
    }

    @Test
    fun windowFunctionNestedInsideAFrameOffsetIsRejected() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run(
                "SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN ROW_NUMBER() OVER (ORDER BY id) PRECEDING AND CURRENT ROW) " +
                    "FROM employees"
            )
        }
        assertTrue(error.message!!.contains("nested"), error.message!!)
    }

    /** amount is monotonic in id (10, 20, 20, 30, 50) so `ORDER BY id` and `ORDER BY amount` agree, with a tie at 20. */
    private fun Database.seedWindowSales() {
        run("CREATE TABLE sales (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("INSERT INTO sales VALUES (1, 10), (2, 20), (3, 20), (4, 30), (5, 50)")
    }

    @Test
    fun rangeBetweenPrecedingAndFollowingIncludesRowsWithinValueDistanceRegardlessOfPhysicalRowCount() {
        val db = newDatabase()
        db.seedWindowSales()
        val result = db.select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING) AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(50L, 80L, 80L, 70L, 50L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rangeBetweenPrecedingAndFollowingOnADescendingOrderByFlipsWhichDirectionIsPreceding() {
        val db = newDatabase()
        db.seedWindowSales()
        val result = db.select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount DESC RANGE BETWEEN 15 PRECEDING AND 5 FOLLOWING) AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(50L, 70L, 70L, 30L, 50L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rangeCurrentRowExpandsToTheWholePeerGroupUnlikeRowsCurrentRow() {
        val db = newDatabase()
        db.seedWindowSales()
        val range = db.select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s FROM sales ORDER BY id ASC"
        )
        val rows = db.select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(10L, 50L, 50L, 80L, 130L), range.rows.map { (it[1] as Value.IntegerValue).value })
        assertEquals(listOf(10L, 30L, 50L, 80L, 130L), rows.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rangeFrameMeasuresDistanceOnADecimalColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE prices (id INTEGER PRIMARY KEY, price DECIMAL(10, 2))")
        db.run("INSERT INTO prices VALUES (1, 1.00), (2, 1.40), (3, 1.50), (4, 2.00)")
        val result = db.select(
            "SELECT id, COUNT(*) OVER (ORDER BY price RANGE BETWEEN 0.5 PRECEDING AND 0.5 FOLLOWING) AS c FROM prices ORDER BY id ASC"
        )
        assertEquals(listOf(3L, 3L, 4L, 2L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rangeFrameMeasuresDistanceInDaysOnADateColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE events (id INTEGER PRIMARY KEY, day DATE)")
        db.run(
            "INSERT INTO events VALUES " +
                "(1, CAST('2024-01-01' AS DATE)), (2, CAST('2024-01-02' AS DATE)), " +
                "(3, CAST('2024-01-05' AS DATE)), (4, CAST('2024-01-06' AS DATE)), (5, CAST('2024-01-10' AS DATE))"
        )
        val result = db.select(
            "SELECT id, COUNT(*) OVER (ORDER BY day RANGE BETWEEN 2 PRECEDING AND 2 FOLLOWING) AS c FROM events ORDER BY id ASC"
        )
        assertEquals(listOf(2L, 2L, 2L, 2L, 1L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun rangeFrameEntirelyPastThePartitionEdgeAggregatesToNullAndCountsZero() {
        val db = newDatabase()
        db.seedWindowSales()
        val result = db.select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount RANGE BETWEEN 100 FOLLOWING AND 200 FOLLOWING) AS s, " +
                "COUNT(*) OVER (ORDER BY amount RANGE BETWEEN 100 FOLLOWING AND 200 FOLLOWING) AS c " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals((1L..5L).map { listOf(int(it), Value.NullValue, int(0)) }, result.rows)
    }

    @Test
    fun rangeFrameWithAPrecedingBoundRequiresExactlyOneOrderByColumn() {
        val db = newDatabase()
        db.seedWindowSales()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT SUM(amount) OVER (ORDER BY amount, id RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING) FROM sales")
        }
        assertTrue(error.message!!.contains("exactly one ORDER BY column"), error.message!!)
    }

    @Test
    fun rangeFrameWithAPrecedingBoundRequiresANumericOrDateOrderColumn() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT SUM(salary) OVER (ORDER BY name RANGE BETWEEN 1 PRECEDING AND CURRENT ROW) FROM employees")
        }
        assertTrue(error.message!!.contains("numeric or DATE"), error.message!!)
    }

    @Test
    fun rangeFrameOffsetMustBeNumeric() {
        val db = newDatabase()
        db.seedWindowSales()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT SUM(amount) OVER (ORDER BY amount RANGE BETWEEN 'x' PRECEDING AND CURRENT ROW) FROM sales")
        }
        assertTrue(error.message!!.contains("numeric"), error.message!!)
    }

    @Test
    fun rangeFrameOffsetMustNotBeNegative() {
        val db = newDatabase()
        db.seedWindowSales()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT SUM(amount) OVER (ORDER BY amount RANGE BETWEEN -1 PRECEDING AND CURRENT ROW) FROM sales")
        }
        assertTrue(error.message!!.contains("negative"), error.message!!)
    }

    @Test
    fun groupsBetweenPrecedingAndFollowingCountsPeerGroupsNotPhysicalRows() {
        val db = newDatabase()
        db.seedWindowSales()
        val groups = db.select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS s FROM sales ORDER BY id ASC"
        )
        val rows = db.select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(50L, 80L, 80L, 120L, 80L), groups.rows.map { (it[1] as Value.IntegerValue).value })
        assertEquals(listOf(30L, 50L, 70L, 100L, 80L), rows.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun groupsCurrentRowExpandsToTheWholePeerGroupJustLikeRangeCurrentRow() {
        val db = newDatabase()
        db.seedWindowSales()
        val groups = db.select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s FROM sales ORDER BY id ASC"
        )
        val range = db.select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(10L, 50L, 50L, 80L, 130L), groups.rows.map { (it[1] as Value.IntegerValue).value })
        assertEquals(groups.rows, range.rows)
    }

    @Test
    fun groupsFrameWorksWithAMultiColumnNonNumericOrderByUnlikeRange() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val result = db.select(
            "SELECT id, COUNT(*) OVER (ORDER BY deptId, name GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS c " +
                "FROM employees ORDER BY deptId ASC, name ASC"
        )
        assertEquals(listOf(int(1), int(3), int(2), int(5), int(6), int(4)), result.rows.map { it[0] })
        assertEquals(listOf(2L, 3L, 3L, 3L, 3L, 2L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun groupsFrameEntirelyPastThePartitionEdgeAggregatesToNullAndCountsZero() {
        val db = newDatabase()
        db.seedWindowSales()
        val result = db.select(
            "SELECT id, SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN 100 FOLLOWING AND 200 FOLLOWING) AS s, " +
                "COUNT(*) OVER (ORDER BY amount GROUPS BETWEEN 100 FOLLOWING AND 200 FOLLOWING) AS c " +
                "FROM sales ORDER BY id ASC"
        )
        assertEquals((1L..5L).map { listOf(int(it), Value.NullValue, int(0)) }, result.rows)
    }

    @Test
    fun groupsFrameIsRejectedOnRankingAndOffsetWindowFunctions() {
        val db = newDatabase()
        db.seedWindowEmployees()
        val error = assertFailsWith<DiskExecutorException> {
            db.run("SELECT ROW_NUMBER() OVER (ORDER BY id GROUPS BETWEEN 1 PRECEDING AND CURRENT ROW) FROM employees")
        }
        assertTrue(error.message!!.contains("GROUPS"), error.message!!)
    }

    @Test
    fun excludeCurrentRowRemovesOnlyTheCurrentRowFromTheWholePartitionFrame() {
        val db = newDatabase()
        db.seedWindowSales()
        val result = db.select(
            "SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE CURRENT ROW" +
                ") AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(120L, 110L, 110L, 100L, 80L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun excludeGroupRemovesTheWholePeerGroupIncludingTheCurrentRow() {
        val db = newDatabase()
        db.seedWindowSales()
        val result = db.select(
            "SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE GROUP" +
                ") AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(120L, 90L, 90L, 100L, 80L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun excludeTiesRemovesOnlyTheOtherTiedRowsAndKeepsTheCurrentRow() {
        val db = newDatabase()
        db.seedWindowSales()
        val result = db.select(
            "SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount RANGE BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE TIES" +
                ") AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(130L, 110L, 110L, 130L, 130L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    @Test
    fun excludeCurrentRowInteractsWithABoundedFrameInsteadOfOnlyTheWholePartition() {
        val db = newDatabase()
        db.seedWindowSales()
        val result = db.select(
            "SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING EXCLUDE CURRENT ROW" +
                ") AS s FROM sales ORDER BY id ASC"
        )
        assertEquals(listOf(20L, 30L, 50L, 70L, 30L), result.rows.map { (it[1] as Value.IntegerValue).value })
    }

    private fun Database.seedUsersForViews() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER, dept TEXT)")
        run(
            "INSERT INTO users VALUES " +
                "(1, 'ada', 36, 'eng'), (2, 'linus', 54, 'eng'), (3, 'grace', 85, 'navy'), (4, 'alan', 41, NULL)"
        )
    }

    @Test
    fun createViewReturnsResultAndRegistersName() {
        val db = newDatabase()
        db.seedUsersForViews()
        val result = db.run("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 40").single()
        assertEquals(CreateViewResult("adults"), result)
        assertEquals(listOf("adults"), db.catalog.viewNames)
    }

    @Test
    fun queryingAViewRunsItsDefiningQueryFresh() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 40")
        val first = db.select("SELECT name FROM adults ORDER BY id ASC")
        assertEquals(listOf(text("linus"), text("grace"), text("alan")), first.rows.map { it.single() })

        db.run("INSERT INTO users VALUES (5, 'margaret', 61, 'navy')")
        val second = db.select("SELECT name FROM adults ORDER BY id ASC")
        assertEquals(
            listOf(text("linus"), text("grace"), text("alan"), text("margaret")),
            second.rows.map { it.single() }
        )
    }

    @Test
    fun viewCanBeUsedAsAJoinTarget() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE TABLE bonuses (dept TEXT, amount INTEGER)")
        db.run("INSERT INTO bonuses VALUES ('eng', 1000), ('navy', 500)")
        db.run("CREATE VIEW eng_staff AS SELECT id, name, dept FROM users WHERE dept = 'eng'")
        val result = db.select(
            "SELECT eng_staff.name, bonuses.amount FROM eng_staff " +
                "JOIN bonuses ON eng_staff.dept = bonuses.dept ORDER BY eng_staff.id ASC"
        )
        assertEquals(
            listOf(listOf(text("ada"), int(1000)), listOf(text("linus"), int(1000))),
            result.rows
        )
    }

    @Test
    fun viewOverAggregateQueryWorks() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW dept_counts AS SELECT dept, COUNT(*) AS n FROM users GROUP BY dept")
        val result = db.select("SELECT dept, n FROM dept_counts WHERE n > 1 ORDER BY dept ASC")
        assertEquals(listOf(listOf(text("eng"), int(2))), result.rows)
    }

    @Test
    fun viewDefinitionCanReferenceAnotherView() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id, name, age FROM users WHERE age >= 40")
        db.run("CREATE VIEW seniors AS SELECT id, name FROM adults WHERE age >= 60")
        val result = db.select("SELECT name FROM seniors")
        assertEquals(listOf(listOf(text("grace"))), result.rows)
    }

    @Test
    fun viewCanBeReferencedInsideAWithCteBody() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id, name, age FROM users WHERE age >= 40")
        val result = db.select("WITH a AS (SELECT name FROM adults WHERE age >= 60) SELECT name FROM a")
        assertEquals(listOf(listOf(text("grace"))), result.rows)
    }

    @Test
    fun viewCanAppearOnEitherSideOfASetOperation() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW eng AS SELECT name FROM users WHERE dept = 'eng'")
        db.run("CREATE VIEW navy AS SELECT name FROM users WHERE dept = 'navy'")
        val result = db.select("SELECT name FROM eng UNION SELECT name FROM navy ORDER BY name")
        assertEquals(listOf(listOf(text("ada")), listOf(text("grace")), listOf(text("linus"))), result.rows)
    }

    @Test
    fun creatingAViewValidatesColumnsEagerly() {
        val db = newDatabase()
        db.seedUsersForViews()
        assertFailsWith<KestrelException> { db.run("CREATE VIEW broken AS SELECT nope FROM users") }
        assertTrue(db.catalog.viewNames.isEmpty())
    }

    @Test
    fun creatingAViewOverAnUnknownTableFails() {
        val db = newDatabase()
        assertFailsWith<KestrelException> { db.run("CREATE VIEW v AS SELECT * FROM missing") }
    }

    @Test
    fun duplicateViewNameFailsWithoutOrReplace() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        assertFailsWith<KestrelException> { db.run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 21") }
    }

    @Test
    fun createOrReplaceViewSwapsTheDefinition() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        db.run("CREATE OR REPLACE VIEW adults AS SELECT id FROM users WHERE age >= 50")
        val result = db.select("SELECT id FROM adults ORDER BY id ASC")
        assertEquals(listOf(listOf(int(2)), listOf(int(3))), result.rows)
    }

    @Test
    fun dropViewRemovesItAndItStopsBeingQueryable() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        val result = db.run("DROP VIEW adults").single()
        assertEquals(DropViewResult("adults"), result)
        assertFailsWith<KestrelException> { db.run("SELECT * FROM adults") }
    }

    @Test
    fun droppingAnUnknownViewFails() {
        val db = newDatabase()
        assertFailsWith<DiskCatalogException> { db.run("DROP VIEW nope") }
    }

    @Test
    fun cannotCreateTableWithSameNameAsAnExistingView() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        assertFailsWith<DiskCatalogException> { db.run("CREATE TABLE adults (id INTEGER)") }
    }

    @Test
    fun cannotCreateViewWithSameNameAsAnExistingTable() {
        val db = newDatabase()
        db.seedUsersForViews()
        assertFailsWith<DiskCatalogException> { db.run("CREATE VIEW users AS SELECT id FROM users") }
    }

    @Test
    fun dropTableRefusesAViewNameAndDropViewRefusesATableName() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        assertFailsWith<DiskCatalogException> { db.run("DROP TABLE adults") }
        assertFailsWith<DiskCatalogException> { db.run("DROP VIEW users") }
    }

    @Test
    fun insertUpdateDeleteAgainstAViewAreRejected() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 18")
        assertFailsWith<DiskCatalogException> { db.run("INSERT INTO adults VALUES (9, 'x')") }
        assertFailsWith<DiskCatalogException> { db.run("UPDATE adults SET name = 'x'") }
        assertFailsWith<DiskCatalogException> { db.run("DELETE FROM adults") }
    }

    @Test
    fun creatingAnIndexOnAViewIsRejected() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 18")
        assertFailsWith<DiskCatalogException> { db.run("CREATE INDEX idx ON adults (id)") }
    }

    @Test
    fun mutuallyRecursiveViewsFailInsteadOfOverflowingTheStack() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW a AS SELECT id FROM users")
        db.run("CREATE VIEW b AS SELECT id FROM a")
        db.run("CREATE OR REPLACE VIEW a AS SELECT id FROM b")
        assertFailsWith<DiskExecutorException> { db.run("SELECT * FROM a") }
    }

    @Test
    fun selfReferencingViewFailsCleanly() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW a AS SELECT id FROM users")
        db.run("CREATE OR REPLACE VIEW a AS SELECT id FROM a")
        assertFailsWith<DiskExecutorException> { db.run("SELECT * FROM a") }
    }

    @Test
    fun dropTableWithADependentViewFailsByDefaultAndAlsoWithExplicitRestrict() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        assertFailsWith<DiskCatalogException> { db.run("DROP TABLE users") }
        assertFailsWith<DiskCatalogException> { db.run("DROP TABLE users RESTRICT") }
        assertEquals(listOf("users"), db.catalog.tableNames)
        assertEquals(listOf("adults"), db.catalog.viewNames)
    }

    @Test
    fun dropViewWithADependentViewFailsByDefault() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        db.run("CREATE VIEW seniorAdults AS SELECT id FROM adults WHERE id > 0")
        assertFailsWith<DiskCatalogException> { db.run("DROP VIEW adults") }
        assertEquals(listOf("adults", "seniorAdults"), db.catalog.viewNames)
    }

    @Test
    fun dropViewCascadeDropsATransitiveChainOfDependentViews() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW a AS SELECT id FROM users WHERE age >= 18")
        db.run("CREATE VIEW b AS SELECT id FROM a WHERE id > 0")
        db.run("CREATE VIEW c AS SELECT id FROM b WHERE id > 0")
        db.run("DROP VIEW a CASCADE")
        assertTrue(db.catalog.viewNames.isEmpty())
    }

    @Test
    fun dropTableWithADiamondOfDependentViewsIsBlockedByEveryDirectDependent() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW b AS SELECT id FROM users WHERE age >= 18")
        db.run("CREATE VIEW c AS SELECT id FROM users WHERE age >= 21")
        assertFailsWith<DiskCatalogException> { db.run("DROP TABLE users") }
        assertEquals(listOf("users"), db.catalog.tableNames)
        assertEquals(listOf("b", "c"), db.catalog.viewNames)
    }

    @Test
    fun dropTableCascadeDropsTheTableAndItsDependentView() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        db.run("DROP TABLE users CASCADE")
        assertTrue(db.catalog.tableNames.isEmpty())
        assertTrue(db.catalog.viewNames.isEmpty())
    }

    @Test
    fun dropTableCascadeHandlesADiamondOfDependentViewsWithoutFailing() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW b AS SELECT id FROM users WHERE age >= 18")
        db.run("CREATE VIEW c AS SELECT id FROM users WHERE age >= 21")
        db.run("CREATE VIEW d AS SELECT b.id FROM b JOIN c ON b.id = c.id")
        db.run("DROP TABLE users CASCADE")
        assertTrue(db.catalog.tableNames.isEmpty())
        assertTrue(db.catalog.viewNames.isEmpty())
    }

    @Test
    fun dropTableCascadeDroppedViewsSurviveClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        db.run("DROP TABLE users CASCADE")
        db.reopen()
        assertTrue(db.catalog.tableNames.isEmpty())
        assertTrue(db.catalog.viewNames.isEmpty())
    }

    @Test
    fun columnTypeOfAViewIsInferredFromActualData() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW names AS SELECT name FROM users")
        val result = db.select("SELECT name FROM names WHERE name = 'ada'")
        assertEquals(listOf(listOf(text("ada"))), result.rows)
    }

    @Test
    fun viewDefinitionSurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 40")
        db.reopen()
        assertEquals(listOf("adults"), db.catalog.viewNames)
        val result = db.select("SELECT name FROM adults ORDER BY id ASC")
        assertEquals(listOf(text("linus"), text("grace"), text("alan")), result.rows.map { it.single() })

        db.run("INSERT INTO users VALUES (5, 'margaret', 61, 'navy')")
        db.reopen()
        val afterInsertAndReopen = db.select("SELECT name FROM adults ORDER BY id ASC")
        assertEquals(
            listOf(text("linus"), text("grace"), text("alan"), text("margaret")),
            afterInsertAndReopen.rows.map { it.single() }
        )
    }

    @Test
    fun viewOverViewAndDropViewSurviveReopen() {
        val db = newDatabase()
        db.seedUsersForViews()
        db.run("CREATE VIEW adults AS SELECT id, name, age FROM users WHERE age >= 40")
        db.run("CREATE VIEW seniors AS SELECT id, name FROM adults WHERE age >= 60")
        db.reopen()
        assertEquals(listOf(listOf(text("grace"))), db.select("SELECT name FROM seniors").rows)

        db.run("DROP VIEW seniors")
        db.reopen()
        assertEquals(listOf("adults"), db.catalog.viewNames)
        assertFailsWith<KestrelException> { db.run("SELECT * FROM seniors") }
    }

    @Test
    fun columnLevelCheckAcceptsSatisfyingRowsAndRejectsViolatingOnes() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        assertEquals(InsertResult(1), db.run("INSERT INTO t VALUES (1, 30)").single())
        val error = assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (2, -5)") }
        assertTrue(error.message!!.contains("CHECK"))
    }

    @Test
    fun checkPassesWhenExpressionIsUnknownBecauseOfNull() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        // three-valued logic: NULL > 0 is UNKNOWN, not FALSE, so the row is accepted
        assertEquals(InsertResult(1), db.run("INSERT INTO t (id) VALUES (1)").single())
    }

    @Test
    fun tableLevelCheckCanSpanMultipleColumns() {
        val db = newDatabase()
        db.run("CREATE TABLE ranges (id INTEGER PRIMARY KEY, low INTEGER, high INTEGER, CHECK (low <= high))")
        assertEquals(InsertResult(1), db.run("INSERT INTO ranges VALUES (1, 1, 10)").single())
        assertFailsWith<KestrelException> { db.run("INSERT INTO ranges VALUES (2, 10, 1)") }
    }

    @Test
    fun multipleCheckConstraintsAllMustBeSatisfied() {
        val db = newDatabase()
        db.run(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age >= 0), " +
                "score INTEGER CHECK (score BETWEEN 0 AND 100))"
        )
        assertEquals(InsertResult(1), db.run("INSERT INTO t VALUES (1, 30, 90)").single())
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (2, 30, 150)") }
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (3, -1, 90)") }
    }

    @Test
    fun checkIsEnforcedOnUpdateToo() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        db.run("INSERT INTO t VALUES (1, 30)")
        assertFailsWith<KestrelException> { db.run("UPDATE t SET age = -1 WHERE id = 1") }
        assertEquals(UpdateResult(1), db.run("UPDATE t SET age = 40 WHERE id = 1").single())
    }

    @Test
    fun checkRejectsAggregateFunctions() {
        val db = newDatabase()
        assertFailsWith<KestrelException> {
            db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER CHECK (SUM(amount) > 0))")
        }
    }

    @Test
    fun checkRejectsSubqueries() {
        val db = newDatabase()
        db.run("CREATE TABLE other (id INTEGER PRIMARY KEY)")
        assertFailsWith<KestrelException> {
            db.run("CREATE TABLE t (id INTEGER PRIMARY KEY CHECK (id IN (SELECT id FROM other)))")
        }
    }

    @Test
    fun checkRejectsUnknownColumn() {
        val db = newDatabase()
        assertFailsWith<KestrelException> {
            db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, CHECK (nope > 0))")
        }
    }

    @Test
    fun checkCanReferenceStringAndCallExpressions() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT CHECK (LENGTH(name) > 0))")
        assertEquals(InsertResult(1), db.run("INSERT INTO t VALUES (1, 'ada')").single())
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (2, '')") }
    }

    @Test
    fun checkDefinitionSurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0), CHECK (id > 0))")
        db.run("INSERT INTO t VALUES (1, 30)")
        db.reopen()

        assertEquals(InsertResult(1), db.run("INSERT INTO t VALUES (2, 40)").single())
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (3, -1)") }
        assertFailsWith<KestrelException> { db.run("UPDATE t SET age = -5 WHERE id = 1") }

        db.reopen()
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (4, -1)") }
        assertEquals(2, db.select("SELECT * FROM t").rows.size)
    }

    private fun Database.seedDepartmentsAndEmployees() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run(
            "CREATE TABLE employees (" +
                "id INTEGER PRIMARY KEY, name TEXT NOT NULL, dept_id INTEGER REFERENCES departments(id))"
        )
        run("INSERT INTO departments VALUES (1, 'eng'), (2, 'ops')")
    }

    private fun Database.seedCascadeDepartments() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                "dept_id INTEGER REFERENCES departments(id) ON DELETE CASCADE ON UPDATE CASCADE)"
        )
        run("INSERT INTO departments VALUES (1, 'eng'), (2, 'ops')")
        run("INSERT INTO employees VALUES (1, 'ada', 1), (2, 'linus', 1), (3, 'grace', 2)")
    }

    private fun Database.seedSetNullDepartments(childNotNull: Boolean) {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        val childColumn = if (childNotNull) "dept_id INTEGER NOT NULL" else "dept_id INTEGER"
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                "$childColumn REFERENCES departments(id) ON DELETE SET NULL)"
        )
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("INSERT INTO employees VALUES (1, 'ada', 1)")
    }

    @Test
    fun insertRejectsForeignKeyViolation() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        val error = assertFailsWith<KestrelException> { db.run("INSERT INTO employees VALUES (1, 'ada', 99)") }
        assertTrue(error.message!!.contains("FOREIGN KEY"))
    }

    @Test
    fun insertAllowsNullForeignKeyValue() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        assertEquals(InsertResult(1), db.run("INSERT INTO employees VALUES (1, 'ada', NULL)").single())
    }

    @Test
    fun insertSucceedsWhenReferencedRowExists() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        assertEquals(InsertResult(1), db.run("INSERT INTO employees VALUES (1, 'ada', 1)").single())
    }

    @Test
    fun updateRejectsForeignKeyViolationOnChildColumn() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        db.run("INSERT INTO employees VALUES (1, 'ada', 1)")
        assertFailsWith<KestrelException> { db.run("UPDATE employees SET dept_id = 99 WHERE id = 1") }
    }

    @Test
    fun foreignKeyRequiresReferencedTableToExist() {
        val db = newDatabase()
        assertFailsWith<KestrelException> {
            db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER REFERENCES missing(id))")
        }
    }

    @Test
    fun foreignKeyRequiresReferencedColumnToBeAPrimaryKey() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        assertFailsWith<KestrelException> {
            db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, x TEXT REFERENCES departments(name))")
        }
    }

    @Test
    fun tableLevelForeignKeySyntaxIsEquivalentToColumnLevel() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        db.run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER, " +
                "FOREIGN KEY (dept_id) REFERENCES departments(id))"
        )
        db.run("INSERT INTO departments VALUES (1, 'eng')")
        assertEquals(InsertResult(1), db.run("INSERT INTO employees VALUES (1, 1)").single())
        assertFailsWith<KestrelException> { db.run("INSERT INTO employees VALUES (2, 99)") }
    }

    @Test
    fun deleteIsRestrictedByDefaultWhenChildrenReferenceTheRow() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        db.run("INSERT INTO employees VALUES (1, 'ada', 1)")
        val error = assertFailsWith<KestrelException> { db.run("DELETE FROM departments WHERE id = 1") }
        assertTrue(error.message!!.contains("FOREIGN KEY"))
        assertEquals(1, db.select("SELECT * FROM departments WHERE id = 1").rows.size)
    }

    @Test
    fun deleteWithoutReferencingChildrenSucceeds() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        assertEquals(DeleteResult(1), db.run("DELETE FROM departments WHERE id = 2").single())
    }

    @Test
    fun onDeleteCascadeRemovesReferencingChildren() {
        val db = newDatabase()
        db.seedCascadeDepartments()
        assertEquals(DeleteResult(1), db.run("DELETE FROM departments WHERE id = 1").single())
        assertEquals(listOf(listOf(text("grace"))), db.select("SELECT name FROM employees ORDER BY id").rows)
    }

    @Test
    fun onUpdateCascadeChangesChildForeignKeyWhenParentPrimaryKeyChanges() {
        val db = newDatabase()
        db.seedCascadeDepartments()
        db.run("UPDATE departments SET id = 10 WHERE id = 1")
        val depts = db.select("SELECT dept_id FROM employees ORDER BY id")
        assertEquals(listOf(listOf(int(10)), listOf(int(10)), listOf(int(2))), depts.rows)
    }

    @Test
    fun onUpdateRestrictByDefaultBlocksPrimaryKeyChangeWhenChildrenReference() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        db.run("INSERT INTO employees VALUES (1, 'ada', 1)")
        assertFailsWith<KestrelException> { db.run("UPDATE departments SET id = 99 WHERE id = 1") }
    }

    @Test
    fun onDeleteSetNullNullsOutReferencingChildren() {
        val db = newDatabase()
        db.seedSetNullDepartments(childNotNull = false)
        db.run("DELETE FROM departments WHERE id = 1")
        assertEquals(listOf(listOf(Value.NullValue)), db.select("SELECT dept_id FROM employees WHERE id = 1").rows)
    }

    @Test
    fun onDeleteSetNullFailsWhenChildColumnIsNotNull() {
        val db = newDatabase()
        db.seedSetNullDepartments(childNotNull = true)
        val error = assertFailsWith<KestrelException> { db.run("DELETE FROM departments WHERE id = 1") }
        assertTrue(error.message!!.contains("NOT NULL"))
    }

    @Test
    fun selfReferencingForeignKeyIsAllowedAndCascadesThroughMultipleLevels() {
        val db = newDatabase()
        db.run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                "manager_id INTEGER REFERENCES employees(id) ON DELETE CASCADE)"
        )
        db.run(
            "INSERT INTO employees VALUES " +
                "(1, 'ceo', NULL), (2, 'vp', 1), (3, 'manager', 2), (4, 'ic', 3)"
        )
        assertEquals(DeleteResult(1), db.run("DELETE FROM employees WHERE id = 1").single())
        assertEquals(emptyList(), db.select("SELECT id FROM employees").rows)
    }

    @Test
    fun selfReferencingForeignKeyAllowsForwardAndBackwardSiblingReferencesInTheSameInsertBatch() {
        val db = newDatabase()
        db.run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                "manager_id INTEGER REFERENCES employees(id))"
        )
        // row 1 references row 2 (forward, not yet on disk) and row 3 references row 1 (backward) -
        // order-independence within a single multi-row INSERT statement.
        assertEquals(
            InsertResult(3),
            db.run("INSERT INTO employees VALUES (1, 'a', 2), (2, 'b', NULL), (3, 'c', 1)").single()
        )
    }

    @Test
    fun dropTableIsRestrictedByDefaultWhenAForeignKeyReferencesIt() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        val error = assertFailsWith<KestrelException> { db.run("DROP TABLE departments") }
        assertTrue(error.message!!.contains("FOREIGN KEY"))
    }

    @Test
    fun dropTableCascadeStripsTheForeignKeyButKeepsTheReferencingTableAndItsRows() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        db.run("INSERT INTO employees VALUES (1, 'ada', 1)")
        db.run("DROP TABLE departments CASCADE")
        assertEquals(listOf("employees"), db.catalog.tableNames)
        assertEquals(1, db.select("SELECT * FROM employees").rows.size)
        // the constraint is gone, so a now-dangling value is accepted
        assertEquals(InsertResult(1), db.run("INSERT INTO employees VALUES (2, 'linus', 99)").single())
    }

    @Test
    fun dropTableCascadeStrippedForeignKeySurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        db.run("INSERT INTO employees VALUES (1, 'ada', 1)")
        db.run("DROP TABLE departments CASCADE")
        db.reopen()
        assertEquals(listOf("employees"), db.catalog.tableNames)
        assertEquals(InsertResult(1), db.run("INSERT INTO employees VALUES (2, 'linus', 99)").single())
    }

    @Test
    fun dropTableCascadeDropsDependentViewsAndStripsForeignKeysInTheSameStatement() {
        val db = newDatabase()
        db.seedDepartmentsAndEmployees()
        db.run("CREATE VIEW deptNames AS SELECT name FROM departments")
        db.run("INSERT INTO employees VALUES (1, 'ada', 1)")
        db.run("DROP TABLE departments CASCADE")
        assertEquals(listOf("employees"), db.catalog.tableNames)
        assertTrue(db.catalog.viewNames.isEmpty())
        assertEquals(InsertResult(1), db.run("INSERT INTO employees VALUES (2, 'linus', 99)").single())
    }

    @Test
    fun foreignKeyConstraintSurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.seedCascadeDepartments()
        db.reopen()

        assertFailsWith<KestrelException> { db.run("INSERT INTO employees VALUES (4, 'ed', 99)") }
        db.run("DELETE FROM departments WHERE id = 1")
        assertEquals(listOf(listOf(text("grace"))), db.select("SELECT name FROM employees ORDER BY id").rows)

        db.reopen()
        assertFailsWith<KestrelException> { db.run("DROP TABLE departments") }
        assertEquals(1, db.select("SELECT * FROM employees").rows.size)
    }

    // ---- ALTER TABLE ADD COLUMN ----

    @Test
    fun addColumnAppendsNullToExistingRowsWhenNoDefaultIsGiven() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("INSERT INTO t VALUES (1, 'ada')")
        val result = db.run("ALTER TABLE t ADD COLUMN age INTEGER").single()
        assertEquals(AlterTableResult("t"), result)
        assertEquals(listOf(listOf(int(1), text("ada"), Value.NullValue)), db.select("SELECT * FROM t").rows)
    }

    @Test
    fun addColumnBackfillsExistingRowsWithDefaultValue() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("INSERT INTO t VALUES (1, 'ada'), (2, 'linus')")
        db.run("ALTER TABLE t ADD COLUMN status TEXT DEFAULT 'active'")
        assertEquals(
            listOf(listOf(text("active")), listOf(text("active"))),
            db.select("SELECT status FROM t ORDER BY id ASC").rows
        )
    }

    @Test
    fun addColumnRejectsNotNullWithoutDefaultWhenTableHasRows() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO t VALUES (1)")
        val error = assertFailsWith<KestrelException> { db.run("ALTER TABLE t ADD COLUMN name TEXT NOT NULL") }
        assertTrue(error.message!!.contains("NOT NULL"))
        assertEquals(listOf("id"), db.select("SELECT * FROM t").columns)
    }

    @Test
    fun addColumnAllowsNotNullWithoutDefaultWhenTableIsEmpty() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("ALTER TABLE t ADD COLUMN name TEXT NOT NULL")
        db.run("INSERT INTO t VALUES (1, 'ada')")
        assertEquals(listOf(listOf(text("ada"))), db.select("SELECT name FROM t").rows)
        assertFailsWith<KestrelException> { db.run("INSERT INTO t (id) VALUES (2)") }
    }

    @Test
    fun addColumnRejectsDuplicateColumnName() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE t ADD COLUMN name TEXT") }
        assertTrue(error.message!!.contains("duplicate column"))
    }

    @Test
    fun addColumnRequiresTableToExist() {
        val db = newDatabase()
        assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE missing ADD COLUMN x INTEGER") }
    }

    @Test
    fun newColumnParticipatesInInsertUpdateWhereAndSelect() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("INSERT INTO t VALUES (1, 'ada')")
        db.run("ALTER TABLE t ADD COLUMN age INTEGER DEFAULT 0")
        db.run("INSERT INTO t VALUES (2, 'linus', 54)")
        db.run("UPDATE t SET age = 36 WHERE id = 1")
        assertEquals(listOf(listOf(text("linus"))), db.select("SELECT name FROM t WHERE age > 40").rows)
    }

    @Test
    fun addColumnOnATableWithASecondaryIndexKeepsThePushdownWorking() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        db.run("ALTER TABLE users ADD COLUMN dept TEXT DEFAULT 'eng'")
        val table = db.catalog.table("users")
        val scansBefore = table.scanCount
        val result = db.select("SELECT name FROM users WHERE age = 54")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
        assertEquals(1, table.secondaryLookupCount)
        assertEquals(scansBefore, table.scanCount)
        assertEquals(listOf(listOf(text("eng"))), db.select("SELECT dept FROM users WHERE id = 1").rows)
    }

    @Test
    fun addColumnDefinitionAndBackfilledDataSurviveClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("INSERT INTO t VALUES (1, 'ada')")
        db.run("ALTER TABLE t ADD COLUMN status TEXT DEFAULT 'active'")
        db.reopen()
        assertEquals(listOf(listOf(int(1), text("ada"), text("active"))), db.select("SELECT * FROM t").rows)
        db.run("INSERT INTO t VALUES (2, 'linus', DEFAULT)")
        assertEquals(listOf(listOf(text("active"))), db.select("SELECT status FROM t WHERE id = 2").rows)
    }

    // ---- ALTER TABLE DROP COLUMN ----

    @Test
    fun dropColumnRemovesColumnFromSchemaAndData() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, bio TEXT)")
        db.run("INSERT INTO t VALUES (1, 'ada', 'mathematician')")
        db.run("ALTER TABLE t DROP COLUMN bio")
        val result = db.select("SELECT * FROM t")
        assertEquals(listOf("id", "name"), result.columns)
        assertEquals(listOf(listOf(int(1), text("ada"))), result.rows)
    }

    @Test
    fun dropColumnRejectsWhenItIsTheOnlyColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER)")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE t DROP COLUMN id") }
        assertTrue(error.message!!.contains("no columns"))
    }

    @Test
    fun dropColumnRejectsPrimaryKeyColumnEvenWithCascade() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE t DROP COLUMN id CASCADE") }
        assertTrue(error.message!!.contains("PRIMARY KEY"))
    }

    @Test
    fun dropColumnRestrictsWhenReferencedByAnotherTablesForeignKey() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY, code TEXT UNIQUE)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_code TEXT REFERENCES departments(code))")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE departments DROP COLUMN code") }
        assertTrue(error.message!!.contains("FOREIGN KEY"))
    }

    @Test
    fun dropColumnCascadeRemovesTheReferencingForeignKey() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY, code TEXT UNIQUE)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_code TEXT REFERENCES departments(code))")
        db.run("INSERT INTO departments VALUES (1, 'eng')")
        db.run("ALTER TABLE departments DROP COLUMN code CASCADE")
        db.run("INSERT INTO employees VALUES (1, 'ghost')")
        assertEquals(listOf(listOf(text("ghost"))), db.select("SELECT dept_code FROM employees").rows)
    }

    @Test
    fun dropColumnRestrictsWhenUsedByItsOwnForeignKey() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE employees DROP COLUMN dept_id") }
        assertTrue(error.message!!.contains("FOREIGN KEY"))
    }

    @Test
    fun dropColumnCascadeRemovesItsOwnForeignKey() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        db.run("ALTER TABLE employees DROP COLUMN dept_id CASCADE")
        assertEquals(listOf("id"), db.select("SELECT * FROM employees").columns)
    }

    @Test
    fun dropColumnRestrictsWhenUsedByAUniqueConstraint() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE t DROP COLUMN email") }
        assertTrue(error.message!!.contains("UNIQUE"))
    }

    @Test
    fun dropColumnCascadeRemovesTheUniqueConstraint() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        db.run("INSERT INTO t VALUES (1, 'a@x.com')")
        db.run("ALTER TABLE t DROP COLUMN email CASCADE")
        db.run("INSERT INTO t VALUES (2)")
        assertEquals(2, db.select("SELECT * FROM t").rows.size)
    }

    @Test
    fun dropColumnRestrictsWhenUsedByACheckConstraint() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE t DROP COLUMN age") }
        assertTrue(error.message!!.contains("CHECK"))
    }

    @Test
    fun dropColumnCascadeRemovesTheCheckConstraint() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        db.run("ALTER TABLE t DROP COLUMN age CASCADE")
        db.run("INSERT INTO t VALUES (1)")
        assertEquals(1, db.select("SELECT * FROM t").rows.size)
    }

    @Test
    fun dropColumnRestrictsWhenUsedByAnIndex() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        db.run("CREATE INDEX idx_age ON t (age)")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE t DROP COLUMN age") }
        assertTrue(error.message!!.contains("idx_age"))
    }

    /**
     * Beyond mirroring the in-memory engine's own coverage, this also proves the position
     * realignment [DiskTable.rewriteRows] performs for every surviving secondary index: `dept`
     * shifts from column position 2 to 1 once `age` (position 1) is dropped, and `idx_dept` must
     * keep resolving against the new position rather than the stale one it was built with.
     */
    @Test
    fun dropColumnCascadeRemovesTheIndexAndASurvivingLaterIndexStillResolvesAtItsShiftedPosition() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER, dept TEXT)")
        db.run("INSERT INTO t VALUES (1, 30, 'eng'), (2, 40, 'ops')")
        db.run("CREATE INDEX idx_age ON t (age)")
        db.run("CREATE INDEX idx_dept ON t (dept)")
        db.run("ALTER TABLE t DROP COLUMN age CASCADE")

        val table = db.catalog.table("t")
        assertEquals(listOf("idx_dept"), table.secondaryIndexes.map { it.name })
        val result = db.select("SELECT id FROM t WHERE dept = 'ops'")
        assertEquals(listOf(listOf(int(2))), result.rows)
        assertEquals(1, table.secondaryLookupCount)
    }

    @Test
    fun dropColumnDefinitionAndRemainingDataSurviveClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, bio TEXT)")
        db.run("INSERT INTO t VALUES (1, 'ada', 'mathematician')")
        db.run("ALTER TABLE t DROP COLUMN bio")
        db.reopen()
        assertEquals(listOf("id", "name"), db.select("SELECT * FROM t").columns)
        assertEquals(listOf(listOf(int(1), text("ada"))), db.select("SELECT * FROM t").rows)
    }

    // ---- ALTER TABLE RENAME COLUMN ----

    @Test
    fun renameColumnRenamesSchemaAndPreservesData() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, bio TEXT)")
        db.run("INSERT INTO t VALUES (1, 'mathematician')")
        db.run("ALTER TABLE t RENAME COLUMN bio TO biography")
        val result = db.select("SELECT * FROM t")
        assertEquals(listOf("id", "biography"), result.columns)
        assertEquals(listOf(listOf(int(1), text("mathematician"))), result.rows)
    }

    @Test
    fun renameColumnRejectsUnknownColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE t RENAME COLUMN missing TO whatever") }
    }

    @Test
    fun renameColumnRejectsWhenNewNameAlreadyExists() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE t RENAME COLUMN name TO id") }
        assertTrue(error.message!!.contains("already exists"))
    }

    @Test
    fun renameColumnUpdatesCompositePrimaryKeyListing() {
        val db = newDatabase()
        db.run("CREATE TABLE t (region TEXT, seq INTEGER, PRIMARY KEY (region, seq))")
        db.run("INSERT INTO t VALUES ('eu', 1)")
        db.run("ALTER TABLE t RENAME COLUMN region TO area")
        assertFailsWith<DiskTableException> { db.run("INSERT INTO t VALUES ('eu', 1)") }
        db.run("INSERT INTO t VALUES ('eu', 2)")
        assertEquals(2, db.select("SELECT * FROM t").rows.size)
    }

    @Test
    fun renameColumnUpdatesItsOwnForeignKeyColumns() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        db.run("INSERT INTO departments VALUES (1)")
        db.run("ALTER TABLE employees RENAME COLUMN dept_id TO department_id")
        assertFailsWith<KestrelException> { db.run("INSERT INTO employees VALUES (1, 99)") }
        db.run("INSERT INTO employees VALUES (1, 1)")
        assertEquals(1, db.select("SELECT * FROM employees").rows.size)
    }

    @Test
    fun renameColumnPropagatesToOtherTablesForeignKeyReferencedColumns() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id) ON DELETE CASCADE)")
        db.run("INSERT INTO departments VALUES (1)")
        db.run("INSERT INTO employees VALUES (1, 1)")
        db.run("ALTER TABLE departments RENAME COLUMN id TO dept_pk")
        db.run("DELETE FROM departments WHERE dept_pk = 1")
        assertTrue(db.select("SELECT * FROM employees").rows.isEmpty())
    }

    @Test
    fun renameColumnUpdatesAUniqueConstraint() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        db.run("INSERT INTO t VALUES (1, 'a@x.com')")
        db.run("ALTER TABLE t RENAME COLUMN email TO contact_email")
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (2, 'a@x.com')") }
    }

    @Test
    fun renameColumnUpdatesACheckConstraintAndStillEnforcesIt() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        db.run("ALTER TABLE t RENAME COLUMN age TO years")
        assertFailsWith<KestrelException> { db.run("INSERT INTO t VALUES (1, -5)") }
        db.run("INSERT INTO t VALUES (1, 30)")
        assertEquals(listOf(listOf(int(30))), db.select("SELECT years FROM t").rows)
    }

    @Test
    fun renameColumnUpdatesASecondaryIndexAndPushdownStillWorks() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        db.run("INSERT INTO t VALUES (1, 30), (2, 40)")
        db.run("CREATE INDEX idx_age ON t (age)")
        db.run("ALTER TABLE t RENAME COLUMN age TO years")

        val table = db.catalog.table("t")
        val result = db.select("SELECT id FROM t WHERE years = 30")
        assertEquals(listOf(listOf(int(1))), result.rows)
        assertEquals(1, table.secondaryLookupCount)
        assertEquals(listOf("years"), table.secondaryIndexes.single().columnNames)
    }

    @Test
    fun renameColumnDefinitionSurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, bio TEXT)")
        db.run("INSERT INTO t VALUES (1, 'mathematician')")
        db.run("ALTER TABLE t RENAME COLUMN bio TO biography")
        db.reopen()
        assertEquals(listOf("id", "biography"), db.select("SELECT * FROM t").columns)
        assertEquals(listOf(listOf(int(1), text("mathematician"))), db.select("SELECT * FROM t").rows)
    }

    // ---- ALTER TABLE RENAME TO ----

    @Test
    fun renameTableRenamesAndPreservesData() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("INSERT INTO t VALUES (1, 'ada')")
        val result = db.run("ALTER TABLE t RENAME TO people").single()
        assertEquals(AlterTableResult("people"), result)
        assertEquals(listOf(listOf(int(1), text("ada"))), db.select("SELECT * FROM people").rows)
        assertFailsWith<DiskCatalogException> { db.run("SELECT * FROM t") }
        assertEquals(listOf("people"), db.catalog.tableNames)
    }

    @Test
    fun renameTableRejectsWhenNewNameAlreadyExistsAsTable() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE other (id INTEGER PRIMARY KEY)")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE t RENAME TO other") }
        assertTrue(error.message!!.contains("already exists"))
    }

    @Test
    fun renameTableRejectsWhenNewNameAlreadyExistsAsView() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE other (id INTEGER PRIMARY KEY)")
        db.run("CREATE VIEW v AS SELECT * FROM other")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE t RENAME TO v") }
        assertTrue(error.message!!.contains("already exists"))
    }

    @Test
    fun renameTableRejectsRenamingAView() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("CREATE VIEW v AS SELECT * FROM t")
        val error = assertFailsWith<DiskCatalogException> { db.run("ALTER TABLE v RENAME TO w") }
        assertTrue(error.message!!.contains("cannot modify view"))
    }

    @Test
    fun renameTablePropagatesToOtherTablesForeignKeyReference() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id) ON DELETE CASCADE)")
        db.run("INSERT INTO departments VALUES (1)")
        db.run("INSERT INTO employees VALUES (1, 1)")
        db.run("ALTER TABLE departments RENAME TO divisions")
        db.run("INSERT INTO divisions VALUES (2)")
        db.run("INSERT INTO employees VALUES (2, 2)")
        assertFailsWith<KestrelException> { db.run("INSERT INTO employees VALUES (3, 99)") }
        db.run("DELETE FROM divisions WHERE id = 1")
        assertEquals(listOf(listOf(int(2))), db.select("SELECT id FROM employees").rows)
    }

    @Test
    fun renameTableUpdatesItsOwnSelfReferencingForeignKey() {
        val db = newDatabase()
        db.run("CREATE TABLE staff (id INTEGER PRIMARY KEY, manager_id INTEGER REFERENCES staff(id))")
        db.run("INSERT INTO staff VALUES (1, NULL)")
        db.run("ALTER TABLE staff RENAME TO personnel")
        db.run("INSERT INTO personnel VALUES (2, 1)")
        assertFailsWith<KestrelException> { db.run("INSERT INTO personnel VALUES (3, 99)") }
        assertEquals(2, db.select("SELECT * FROM personnel").rows.size)
    }

    @Test
    fun renameTableUpdatesASecondaryIndexAndPushdownStillWorks() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        db.run("INSERT INTO t VALUES (1, 30), (2, 40)")
        db.run("CREATE INDEX idx_age ON t (age)")
        db.run("ALTER TABLE t RENAME TO people")

        val table = db.catalog.table("people")
        val result = db.select("SELECT id FROM people WHERE age = 30")
        assertEquals(listOf(listOf(int(1))), result.rows)
        assertEquals(1, table.secondaryLookupCount)
    }

    @Test
    fun renameTablePropagatesIntoASimpleViewDefinition() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("INSERT INTO t VALUES (1, 'ada')")
        db.run("CREATE VIEW v AS SELECT * FROM t")
        db.run("ALTER TABLE t RENAME TO people")
        assertEquals(listOf(listOf(int(1), text("ada"))), db.select("SELECT * FROM v").rows)
    }

    @Test
    fun renameTableRenamingToItsOwnNameIsANoOp() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO t VALUES (1)")
        db.run("ALTER TABLE t RENAME TO t")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT * FROM t").rows)
    }

    @Test
    fun renameTableDefinitionAndViewSurviveClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("INSERT INTO t VALUES (1, 'ada')")
        db.run("CREATE VIEW v AS SELECT * FROM t")
        db.run("ALTER TABLE t RENAME TO people")
        db.reopen()
        assertEquals(listOf("people"), db.catalog.tableNames)
        assertEquals(listOf(listOf(int(1), text("ada"))), db.select("SELECT * FROM v").rows)
        assertFailsWith<DiskCatalogException> { db.run("SELECT * FROM t") }
    }

    /**
     * The regression this guards against: [DiskCatalog.renameTable] used to capture the directory
     * key a [DiskTable]'s `onRootsChanged` callback persists under *by value* at table-creation
     * time. After a rename moved the row to a new map key, any *later* write that actually split a
     * B+Tree (not the rename itself, which persists synchronously) would still try to persist under
     * the stale, now-removed key and blow up with `NoSuchElementException`. 300 sequential inserts
     * reliably force at least one primary-key index split (same row count [DiskCatalogTest] already
     * relies on for the same reason).
     */
    @Test
    fun renameTableFollowedByEnoughInsertsToSplitTheIndexPersistsCorrectlyAfterReopen() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO t VALUES (1)")
        db.run("ALTER TABLE t RENAME TO renamed")
        for (id in 2..300) db.run("INSERT INTO renamed VALUES ($id)")

        db.reopen()
        assertEquals(300, db.select("SELECT * FROM renamed").rows.size)
        for (id in listOf(1, 150, 300)) {
            assertEquals(listOf(listOf(int(id.toLong()))), db.select("SELECT id FROM renamed WHERE id = $id").rows)
        }
    }

    /**
     * `PREPARE`/`EXECUTE`/`DEALLOCATE` ported to the disk-backed executor - see [DiskExecutor]'s own
     * class doc. These mirror [kestrel.PreparedStatementTest] (the in-memory engine's own suite for
     * the same feature) case by case, plus two disk-specific ones proving via a real close/reopen of
     * the backing files that the registry is genuinely session-scoped rather than durable - the same
     * distinction [kestrel.PersistenceTest] draws for the in-memory engine's own text WAL.
     */
    @Test
    fun prepareReturnsItsNameAndExecuteRunsAParameterizedSelect() {
        val db = newDatabase()
        db.seedUsers()
        assertEquals(
            PrepareResult("byId"),
            db.run("PREPARE byId AS SELECT name, age FROM users WHERE id = \$1").single()
        )
        val result = db.select("EXECUTE byId(2)")
        assertEquals(listOf("name", "age"), result.columns)
        assertEquals(listOf(listOf(text("linus"), int(54))), result.rows)
    }

    @Test
    fun theSamePreparedStatementCanBeExecutedRepeatedlyWithDifferentArguments() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE byId AS SELECT name FROM users WHERE id = \$1")
        assertEquals(listOf(listOf(text("ada"))), db.select("EXECUTE byId(1)").rows)
        assertEquals(listOf(listOf(text("grace"))), db.select("EXECUTE byId(3)").rows)
    }

    @Test
    fun executeSubstitutesMultipleParametersPositionally() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE inRange AS SELECT name FROM users WHERE age >= \$1 AND age <= \$2 ORDER BY age")
        assertEquals(
            listOf(listOf(text("ada")), listOf(text("linus"))),
            db.select("EXECUTE inRange(30, 60)").rows
        )
    }

    @Test
    fun prepareCanWrapInsertAndExecuteInsertsARealRow() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER DEFAULT 0)")
        db.run("PREPARE addUser AS INSERT INTO users VALUES (\$1, \$2, \$3)")
        assertEquals(InsertResult(1), db.run("EXECUTE addUser(1, 'ada', 36)").single())
        assertEquals(listOf(listOf(int(1), text("ada"), int(36))), db.select("SELECT * FROM users").rows)
        assertEquals(InsertResult(1), db.run("EXECUTE addUser(2, 'linus', 54)").single())
        assertEquals(2, db.select("SELECT * FROM users").rows.size)
    }

    @Test
    fun prepareCanWrapUpdate() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE birthday AS UPDATE users SET age = age + \$1 WHERE id = \$2")
        assertEquals(UpdateResult(1), db.run("EXECUTE birthday(1, 1)").single())
        assertEquals(listOf(listOf(int(37))), db.select("SELECT age FROM users WHERE id = 1").rows)
    }

    @Test
    fun prepareCanWrapDelete() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE removeUser AS DELETE FROM users WHERE id = \$1")
        assertEquals(DeleteResult(1), db.run("EXECUTE removeUser(2)").single())
        assertEquals(2, db.select("SELECT * FROM users").rows.size)
    }

    @Test
    fun prepareCanWrapMergeAndAParameterReachesTheMatchedUpdateAssignment() {
        val db = newDatabase()
        db.run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER)")
        db.run("CREATE TABLE staging (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER)")
        db.run("INSERT INTO accounts VALUES (1, 'ada', 100)")
        db.run("INSERT INTO staging VALUES (1, 'ada', 500)")
        db.run(
            "PREPARE topUp AS MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = staging.balance + \$1"
        )
        db.run("EXECUTE topUp(10)")
        assertEquals(listOf(listOf(int(510))), db.select("SELECT balance FROM accounts WHERE id = 1").rows)
        // A second EXECUTE with a DIFFERENT argument proves substitution recomputes fresh every call
        // rather than baking in whatever value the first call used.
        db.run("EXECUTE topUp(20)")
        assertEquals(listOf(listOf(int(520))), db.select("SELECT balance FROM accounts WHERE id = 1").rows)
    }

    @Test
    fun executeWithTheWrongNumberOfArgumentsFailsWithAClearMessage() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE byId AS SELECT name FROM users WHERE id = \$1")
        val tooMany = assertFailsWith<DiskExecutorException> { db.run("EXECUTE byId(1, 2)") }
        assertTrue(tooMany.message!!.contains("expects 1 parameter"), tooMany.message)
        assertFailsWith<DiskExecutorException> { db.run("EXECUTE byId()") }
    }

    @Test
    fun executeOfAnUnknownNameFails() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        assertFailsWith<DiskExecutorException> { db.run("EXECUTE nope(1)") }
    }

    @Test
    fun preparingTheSameNameTwiceFails() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE byId AS SELECT name FROM users WHERE id = \$1")
        assertFailsWith<DiskExecutorException> { db.run("PREPARE byId AS SELECT age FROM users WHERE id = \$1") }
    }

    @Test
    fun deallocateRemovesAPreparedStatementSoExecuteNoLongerFindsIt() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE byId AS SELECT name FROM users WHERE id = \$1")
        assertEquals(DeallocateResult("byId", all = false), db.run("DEALLOCATE byId").single())
        assertFailsWith<DiskExecutorException> { db.run("EXECUTE byId(1)") }
    }

    @Test
    fun deallocateAllRemovesEveryPreparedStatement() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE p1 AS SELECT name FROM users WHERE id = \$1")
        db.run("PREPARE p2 AS SELECT age FROM users WHERE id = \$1")
        assertEquals(DeallocateResult(null, all = true), db.run("DEALLOCATE ALL").single())
        assertFailsWith<DiskExecutorException> { db.run("EXECUTE p1(1)") }
        assertFailsWith<DiskExecutorException> { db.run("EXECUTE p2(1)") }
    }

    @Test
    fun aNameCanBeReparedAfterBeingDeallocated() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE byId AS SELECT name FROM users WHERE id = \$1")
        db.run("DEALLOCATE byId")
        db.run("PREPARE byId AS SELECT age FROM users WHERE id = \$1")
        assertEquals(listOf(listOf(int(36))), db.select("EXECUTE byId(1)").rows)
    }

    @Test
    fun preparedStatementsAreNotUndoneByRollback() {
        val db = newDatabase()
        db.seedUsers()
        db.run("BEGIN")
        db.run("PREPARE byId AS SELECT name FROM users WHERE id = \$1")
        db.run("ROLLBACK")
        // Session-scoped, deliberately outside the storage-engine transaction that ROLLBACK undoes -
        // see DiskExecutor's own doc on preparedStatements for why.
        assertEquals(listOf(listOf(text("ada"))), db.select("EXECUTE byId(1)").rows)
    }

    @Test
    fun executeArgumentsMustBeConstantNotColumnReferences() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE byId AS SELECT name FROM users WHERE id = \$1")
        // The bare Evaluator() resolveExecute uses to evaluate EXECUTE arguments as constants is
        // shared with the in-memory engine and throws the shared KestrelException directly, not a
        // DiskExecutorException - unlike disk-executor-specific validation elsewhere in this class.
        assertFailsWith<KestrelException> { db.run("EXECUTE byId(id)") }
    }

    @Test
    fun preparedStatementDoesNotSurviveReopen() {
        val db = newDatabase()
        db.seedUsers()
        db.run("PREPARE byId AS SELECT name FROM users WHERE id = \$1")
        assertEquals(listOf(listOf(text("ada"))), db.select("EXECUTE byId(1)").rows)

        db.reopen()
        assertFailsWith<DiskExecutorException> { db.run("EXECUTE byId(1)") }
    }

    @Test
    fun aPreparedInsertsRowSurvivesReopenEvenThoughThePrepareItselfDoesNot() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER DEFAULT 0)")
        db.run("PREPARE addUser AS INSERT INTO users VALUES (\$1, \$2, \$3)")
        db.run("EXECUTE addUser(1, 'ada', 36)")

        db.reopen()
        assertEquals(listOf(listOf(int(1), text("ada"), int(36))), db.select("SELECT * FROM users").rows)
        assertFailsWith<DiskExecutorException> { db.run("EXECUTE addUser(2, 'linus', 54)") }
    }

    /**
     * `CREATE TEMP TABLE`/`CREATE TEMPORARY TABLE` used to be rejected outright here - see
     * [DiskExecutorTemporaryTableTest] for the full feature (now a real, live in-memory-only table
     * on this executor) this pair of tests only confirms is reachable through both spellings.
     */
    @Test
    fun createTempTableIsAcceptedByTheDiskBackedExecutor() {
        val db = newDatabase()
        assertEquals(CreateTableResult("scratch"), db.run("CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY)").single())
        db.run("INSERT INTO scratch VALUES (1)")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT * FROM scratch").rows)
    }

    @Test
    fun createTemporaryTableIsAcceptedByTheDiskBackedExecutor() {
        val db = newDatabase()
        assertEquals(CreateTableResult("scratch"), db.run("CREATE TEMPORARY TABLE scratch (id INTEGER PRIMARY KEY)").single())
    }
}
