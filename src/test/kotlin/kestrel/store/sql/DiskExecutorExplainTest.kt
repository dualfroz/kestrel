package kestrel.store.sql

import kestrel.engine.ExplainResult
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

class DiskExecutorExplainTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun parse(sql: String): List<Statement> = Parser(Lexer(sql).tokenize()).parseProgram()

    private fun int(value: Long) = Value.IntegerValue(value)

    private inner class Database(dataFile: File, walFile: File) {
        val catalog = DiskCatalog.open(dataFile, walFile)
        val executor = DiskExecutor(catalog)

        fun run(sql: String): List<kestrel.engine.StatementResult> = parse(sql).map { executor.execute(it) }
        fun explain(sql: String): List<String> = (run(sql).single() as ExplainResult).plan
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-explain-data"), tempFile("kestrel-disk-explain-wal"))

    private fun Database.seedUsers() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER)")
        run("INSERT INTO users VALUES (1, 'ada', 36), (2, 'linus', 54), (3, 'grace', 85)")
    }

    private fun Database.seedOrders() {
        run("CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("INSERT INTO customers VALUES (1, 'ada'), (2, 'linus'), (3, 'grace')")
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, amount INTEGER NOT NULL)")
        run("INSERT INTO orders VALUES (1, 1, 100), (2, 1, 50), (3, 2, 200)")
    }

    @Test
    fun explainOnAnUnindexedWhereReportsASeqScanAndTouchesNoRowData() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val plan = db.explain("EXPLAIN SELECT name FROM users WHERE name = 'linus'")
        assertTrue(plan.any { it == "Seq Scan on users" })
        assertEquals(0, table.scanCount)
    }

    @Test
    fun explainOnAPrimaryKeyEqualityReportsAPrimaryKeyScanAndTouchesNoRowData() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val plan = db.explain("EXPLAIN SELECT name FROM users WHERE id = 2")
        assertTrue(plan.any { it == "Primary Key Scan on users (id = 2)" })
        assertEquals(0, table.pointLookupCount)
        assertEquals(0, table.scanCount)
    }

    @Test
    fun explainOnAPrimaryKeyRangeReportsAPrimaryKeyRangeScanAndTouchesNoRowData() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val plan = db.explain("EXPLAIN SELECT name FROM users WHERE id > 1 AND id <= 3")
        assertTrue(plan.any { it == "Primary Key Range Scan on users (id > 1 AND id <= 3)" })
        assertEquals(0, table.rangeScanCount)
        assertEquals(0, table.scanCount)
    }

    @Test
    fun explainOnACompositePrimaryKeyEqualityReportsAPrimaryKeyScan() {
        val db = newDatabase()
        db.run("CREATE TABLE order_lines (order_id INTEGER, line_no INTEGER, sku TEXT, PRIMARY KEY (order_id, line_no))")
        db.run("INSERT INTO order_lines VALUES (1, 1, 'widget'), (1, 2, 'gadget')")
        val table = db.catalog.table("order_lines")
        val plan = db.explain("EXPLAIN SELECT sku FROM order_lines WHERE order_id = 1 AND line_no = 2")
        assertTrue(plan.any { it == "Primary Key Scan on order_lines (order_id = 1 AND line_no = 2)" })
        assertEquals(0, table.pointLookupCount)
    }

    @Test
    fun explainOnACompositePrimaryKeyRangeReportsAPrimaryKeyRangeScan() {
        val db = newDatabase()
        db.run("CREATE TABLE order_lines (order_id INTEGER, line_no INTEGER, sku TEXT, PRIMARY KEY (order_id, line_no))")
        db.run("INSERT INTO order_lines VALUES (1, 1, 'widget'), (1, 2, 'gadget')")
        val table = db.catalog.table("order_lines")
        val plan = db.explain("EXPLAIN SELECT sku FROM order_lines WHERE order_id = 1 AND line_no > 1")
        assertTrue(plan.any { it == "Primary Key Range Scan on order_lines (order_id = 1 AND line_no > 1)" })
        assertEquals(0, table.rangeScanCount)
        assertEquals(0, table.scanCount)
    }

    @Test
    fun explainOnASecondaryIndexEqualityNamesTheIndexAndTouchesNoRowData() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        val table = db.catalog.table("users")
        val lookupsBeforeExplain = table.secondaryLookupCount
        val scansBeforeExplain = table.scanCount
        val plan = db.explain("EXPLAIN SELECT name FROM users WHERE age = 54")
        assertTrue(plan.any { it == "Index Scan using idx_age on users (age = 54)" })
        assertEquals(lookupsBeforeExplain, table.secondaryLookupCount)
        assertEquals(scansBeforeExplain, table.scanCount)
    }

    @Test
    fun explainOnASecondaryIndexRangeNamesTheIndexAndTouchesNoRowData() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        val table = db.catalog.table("users")
        val rangeScansBeforeExplain = table.rangeScanCount
        val scansBeforeExplain = table.scanCount
        val plan = db.explain("EXPLAIN SELECT name FROM users WHERE age > 40 AND age <= 85")
        assertTrue(plan.any { it == "Index Range Scan using idx_age on users (age > 40 AND age <= 85)" })
        assertEquals(rangeScansBeforeExplain, table.rangeScanCount)
        assertEquals(scansBeforeExplain, table.scanCount)
    }

    @Test
    fun explainOnACompositeSecondaryIndexPrefixRangeNamesBothThePrefixEqualityAndTheRange() {
        val db = newDatabase()
        db.run("CREATE TABLE order_lines (order_id INTEGER, line_no INTEGER, sku TEXT)")
        db.run("INSERT INTO order_lines VALUES (1, 1, 'apple'), (1, 2, 'mango'), (2, 1, 'kiwi')")
        db.run("CREATE INDEX idx_order_sku ON order_lines(order_id, sku)")
        val table = db.catalog.table("order_lines")
        val rangeScansBeforeExplain = table.rangeScanCount
        val scansBeforeExplain = table.scanCount
        val plan = db.explain("EXPLAIN SELECT line_no FROM order_lines WHERE order_id = 1 AND sku > 'apple'")
        assertTrue(plan.any { it == "Index Range Scan using idx_order_sku on order_lines (order_id = 1 AND sku > 'apple')" })
        assertEquals(rangeScansBeforeExplain, table.rangeScanCount)
        assertEquals(scansBeforeExplain, table.scanCount)
    }

    @Test
    fun explainRevertsToASeqScanAfterTheIndexItWasUsingIsDropped() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE INDEX idx_age ON users(age)")
        db.run("DROP INDEX idx_age")
        val plan = db.explain("EXPLAIN SELECT name FROM users WHERE age = 54")
        assertTrue(plan.any { it == "Seq Scan on users" })
    }

    @Test
    fun explainOnACompositeSecondaryIndexEqualityNamesEveryColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, age INTEGER, name TEXT)")
        db.run("INSERT INTO users VALUES (1, 36, 'ada'), (2, 36, 'grace')")
        db.run("CREATE INDEX idx_both ON users(age, name)")
        val plan = db.explain("EXPLAIN SELECT id FROM users WHERE age = 36 AND name = 'grace'")
        assertTrue(plan.any { it == "Index Scan using idx_both on users (age = 36 AND name = 'grace')" })
    }

    @Test
    fun explainOnOnlyOneColumnOfACompositeIndexFallsBackToASeqScan() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, age INTEGER, name TEXT)")
        db.run("INSERT INTO users VALUES (1, 36, 'ada')")
        db.run("CREATE INDEX idx_both ON users(age, name)")
        val plan = db.explain("EXPLAIN SELECT id FROM users WHERE age = 36")
        assertTrue(plan.any { it == "Seq Scan on users" })
    }

    @Test
    fun explainWithAJoinNeverPushesDownEvenWhenWhereMatchesThePrimaryKey() {
        val db = newDatabase()
        db.seedOrders()
        val plan = db.explain(
            "EXPLAIN SELECT customers.name, orders.amount FROM customers JOIN orders " +
                "ON customers.id = orders.customer_id WHERE customers.id = 1"
        )
        assertTrue(plan.any { it.trim() == "-> Seq Scan on customers" })
        assertTrue(plan.none { it.contains("Primary Key") })
    }

    @Test
    fun explainNeverPrintsHashJoinEvenForAnEqualityCondition() {
        val db = newDatabase()
        db.seedOrders()
        val plan = db.explain(
            "EXPLAIN SELECT customers.name, orders.amount FROM customers " +
                "JOIN orders ON customers.id = orders.customer_id"
        )
        assertTrue(plan.any { it == "Nested Loop Inner" })
        assertTrue(plan.none { it.contains("Hash Join") })
        assertTrue(plan.any { it.trim() == "-> Seq Scan on orders" })
    }

    @Test
    fun explainAnnotatesRightFullAndCrossJoinsWithTheirLabel() {
        val db = newDatabase()
        db.seedOrders()
        val right = db.explain("EXPLAIN SELECT customers.name FROM customers RIGHT JOIN orders ON customers.id = orders.customer_id")
        assertTrue(right.any { it == "Nested Loop Right" })
        val full = db.explain("EXPLAIN SELECT customers.name FROM customers FULL JOIN orders ON customers.id = orders.customer_id")
        assertTrue(full.any { it == "Nested Loop Full" })
        val cross = db.explain("EXPLAIN SELECT customers.name FROM customers CROSS JOIN orders")
        assertTrue(cross.any { it == "Nested Loop Cross" })
    }

    @Test
    fun explainAnnotatesALateralJoinAndNamesItsRightSideALateralSubqueryScan() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, deptId INTEGER, name TEXT, salary INTEGER)")
        db.run("INSERT INTO departments VALUES (1, 'eng')")
        db.run("INSERT INTO employees VALUES (1, 1, 'ada', 300)")
        val plan = db.explain(
            "EXPLAIN SELECT d.name, top.name FROM departments d " +
                "JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE"
        )
        assertTrue(plan.any { it == "Nested Loop Inner Lateral" })
        assertTrue(plan.any { it.trim() == "-> Lateral Subquery Scan on top" })
    }

    @Test
    fun explainOnAGroupByReportsGroupAggregateWithItsKeys() {
        val db = newDatabase()
        db.seedOrders()
        val plan = db.explain("EXPLAIN SELECT customer_id, SUM(amount) FROM orders GROUP BY customer_id")
        assertTrue(plan.any { it.trim() == "GroupAggregate keys: customer_id" })
    }

    @Test
    fun explainOnAPlainAggregateWithNoGroupByReportsAggregate() {
        val db = newDatabase()
        db.seedOrders()
        val plan = db.explain("EXPLAIN SELECT COUNT(*) FROM orders")
        assertTrue(plan.any { it.trim() == "Aggregate" })
    }

    @Test
    fun explainOnARollupReportsTheNumberOfGroupingSets() {
        val db = newDatabase()
        db.seedOrders()
        val plan = db.explain("EXPLAIN SELECT customer_id, SUM(amount) FROM orders GROUP BY ROLLUP (customer_id)")
        assertTrue(plan.any { it.contains("(2 grouping sets)") })
    }

    @Test
    fun explainOnAPlainGroupByDoesNotMentionGroupingSets() {
        val db = newDatabase()
        db.seedOrders()
        val plan = db.explain("EXPLAIN SELECT customer_id, SUM(amount) FROM orders GROUP BY customer_id")
        assertTrue(plan.none { it.contains("grouping sets") })
    }

    @Test
    fun explainWithOrderByWrapsSortAroundThePlan() {
        val db = newDatabase()
        db.seedUsers()
        val plan = db.explain("EXPLAIN SELECT name FROM users ORDER BY age")
        assertEquals("Sort", plan.first())
    }

    @Test
    fun explainOnDistinctReportsUnique() {
        val db = newDatabase()
        db.seedUsers()
        val plan = db.explain("EXPLAIN SELECT DISTINCT age FROM users")
        assertEquals("Unique", plan.first())
    }

    @Test
    fun explainOnDistinctOnReportsUniqueDistinctOn() {
        val db = newDatabase()
        db.seedUsers()
        val plan = db.explain("EXPLAIN SELECT DISTINCT ON (age) name FROM users ORDER BY age")
        assertEquals("Unique (DISTINCT ON)", plan.first())
    }

    @Test
    fun explainWithLimitWrapsLimitAroundThePlan() {
        val db = newDatabase()
        db.seedUsers()
        val plan = db.explain("EXPLAIN SELECT name FROM users LIMIT 5")
        assertEquals("Limit 5", plan.first())
    }

    @Test
    fun explainWithLimitAndOffsetWrapsLimitOutermostAndOffsetJustInside() {
        val db = newDatabase()
        db.seedUsers()
        val plan = db.explain("EXPLAIN SELECT name FROM users ORDER BY name LIMIT 5 OFFSET 2")
        assertEquals("Limit 5", plan[0])
        assertEquals("  Offset 2", plan[1])
    }

    @Test
    fun explainOverACompoundQueryWithOffsetWrapsOffsetJustInsideLimit() {
        val db = newDatabase()
        db.run("CREATE TABLE t1 (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.run("CREATE TABLE t2 (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.run("INSERT INTO t1 VALUES (1, 'ada')")
        db.run("INSERT INTO t2 VALUES (2, 'linus')")
        val plan = db.explain("EXPLAIN SELECT name FROM t1 UNION SELECT name FROM t2 ORDER BY name LIMIT 1 OFFSET 1")
        assertEquals("Limit 1", plan[0])
        assertEquals("  Offset 1", plan[1])
    }

    @Test
    fun explainOverACompoundQueryDescribesTheSetOperatorAndBothSides() {
        val db = newDatabase()
        db.run("CREATE TABLE t1 (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.run("CREATE TABLE t2 (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        db.run("INSERT INTO t1 VALUES (1, 'ada')")
        db.run("INSERT INTO t2 VALUES (2, 'linus')")
        val plan = db.explain("EXPLAIN SELECT name FROM t1 UNION SELECT name FROM t2")
        assertEquals("Union", plan.first())
        assertTrue(plan.any { it.trim() == "-> Seq Scan on t1" })
        assertTrue(plan.any { it.trim() == "-> Seq Scan on t2" })
    }

    @Test
    fun explainOverAWithRecursiveQueryDescribesTheCteAndItsBody() {
        val db = newDatabase()
        db.run("CREATE TABLE dual (x INTEGER)")
        db.run("INSERT INTO dual VALUES (1)")
        val plan = db.explain(
            "EXPLAIN WITH RECURSIVE counter(n) AS (" +
                "SELECT 1 FROM dual UNION ALL SELECT n + 1 FROM counter WHERE n < 5" +
                ") SELECT n FROM counter"
        )
        assertTrue(plan.any { it == "CTE counter" })
        assertTrue(plan.any { it == "Seq Scan on counter" })
    }

    @Test
    fun explainOverAViewDescribesTheOuterQueryAgainstTheViewsMaterializedSchema() {
        val db = newDatabase()
        db.seedUsers()
        db.run("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 40")
        val plan = db.explain("EXPLAIN SELECT * FROM adults")
        assertTrue(plan.any { it == "Seq Scan on adults" })
    }

    @Test
    fun explainOverADerivedTableDescribesItByItsAlias() {
        val db = newDatabase()
        db.seedUsers()
        val plan = db.explain("EXPLAIN SELECT s.name FROM (SELECT id, name FROM users) AS s")
        assertTrue(plan.any { it == "Seq Scan on s" })
    }

    @Test
    fun explainRejectsAWindowFunctionInWhereStructurallyWithoutRunningTheQuery() {
        val db = newDatabase()
        db.seedUsers()
        val table = db.catalog.table("users")
        val failure = assertFailsWith<DiskExecutorException> {
            db.explain("EXPLAIN SELECT name FROM users WHERE ROW_NUMBER() OVER (ORDER BY id) > 1")
        }
        assertTrue(failure.message!!.contains("window functions are not allowed in WHERE"))
        assertEquals(0, table.scanCount)
    }

    @Test
    fun explainRejectsAColumnMissingFromGroupByJustLikeRunningTheQueryWould() {
        val db = newDatabase()
        db.seedOrders()
        val failure = assertFailsWith<DiskExecutorException> {
            db.explain("EXPLAIN SELECT customer_id, amount FROM orders GROUP BY customer_id")
        }
        assertTrue(failure.message!!.contains("must appear in GROUP BY"))
    }

    @Test
    fun explainRejectsADuplicateTableAliasJustLikeRunningTheQueryWould() {
        val db = newDatabase()
        db.seedUsers()
        val failure = assertFailsWith<DiskExecutorException> {
            db.explain("EXPLAIN SELECT * FROM users u JOIN users u ON u.id = u.id")
        }
        assertTrue(failure.message!!.contains("duplicate table alias"))
    }

    @Test
    fun explainOnAMissingTableStillReportsNoSuchTable() {
        val db = newDatabase()
        assertFailsWith<DiskCatalogException> { db.explain("EXPLAIN SELECT * FROM missing") }
    }
}
