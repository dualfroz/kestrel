package kestrel

import java.math.BigDecimal
import java.time.LocalDate
import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.persistence.SqlWriter
import kestrel.sql.ast.Statement
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqlWriterTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun parseOne(sql: String): Statement = Parser(Lexer(sql).tokenize()).parseProgram().single()

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun text(value: String) = Value.TextValue(value)

    private fun seed() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, category TEXT, bio TEXT)")
        run(
            "INSERT INTO t VALUES (1, 'a', 'mathematician'), (2, 'b', NULL), (3, 'c', 'rear admiral')"
        )
    }

    private fun writeThenReplay(sql: String) {
        val statement = parseOne(sql)
        val rendered = SqlWriter.write(statement)
        run(rendered)
    }

    @Test
    fun updateWithCaseExpressionRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("UPDATE t SET category = CASE WHEN id = 1 THEN 'first' ELSE category END WHERE id = 1")
        assertEquals(listOf(listOf(text("first"))), select("SELECT category FROM t WHERE id = 1").rows)
    }

    @Test
    fun deleteWithInAndLikeRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("DELETE FROM t WHERE category IN ('b') OR bio LIKE '%admiral'")
        val remaining = select("SELECT category FROM t ORDER BY id ASC")
        assertEquals(listOf(listOf(text("a"))), remaining.rows)
    }

    @Test
    fun updateWithIsNullAndBetweenRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("UPDATE t SET category = UPPER(category) WHERE bio IS NULL OR id BETWEEN 3 AND 3")
        val result = select("SELECT category FROM t ORDER BY id ASC")
        assertEquals(listOf(listOf(text("a")), listOf(text("B")), listOf(text("C"))), result.rows)
    }

    @Test
    fun truncateRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("TRUNCATE TABLE t")
        assertEquals(emptyList(), select("SELECT * FROM t").rows)
    }

    @Test
    fun truncateRestartIdentityCascadeRoundTripsThroughSqlWriterAgainstAFreshReferencingTable() {
        run("CREATE SEQUENCE seq")
        run("CREATE TABLE parent (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('seq'))")
        run("CREATE TABLE child (id INTEGER PRIMARY KEY, parent_id INTEGER REFERENCES parent(id))")
        run("INSERT INTO parent (id) VALUES (NEXTVAL('seq')), (NEXTVAL('seq'))")
        run("INSERT INTO child VALUES (1, 1)")
        writeThenReplay("TRUNCATE TABLE parent RESTART IDENTITY CASCADE")
        assertEquals(emptyList(), select("SELECT * FROM child").rows)
        run("INSERT INTO parent (id) VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(Value.IntegerValue(1))), select("SELECT id FROM parent").rows)
    }

    @Test
    fun mergeUpdateAndInsertRoundTripThroughSqlWriter() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        run("CREATE TABLE staging (id INTEGER, amount INTEGER)")
        run("INSERT INTO accounts VALUES (1, 100)")
        run("INSERT INTO staging VALUES (1, 10), (2, 5)")
        writeThenReplay(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount)"
        )
        assertEquals(
            listOf(listOf(Value.IntegerValue(1), Value.IntegerValue(110)), listOf(Value.IntegerValue(2), Value.IntegerValue(5))),
            select("SELECT id, balance FROM accounts ORDER BY id").rows
        )
    }

    @Test
    fun mergeWithAliasesAndADerivedTableSourceRoundTripsThroughSqlWriter() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        run("CREATE TABLE staging (id INTEGER, amount INTEGER)")
        run("INSERT INTO staging VALUES (1, 10), (1, 20)")
        writeThenReplay(
            "MERGE INTO accounts AS acc USING (SELECT id, SUM(amount) AS total FROM staging GROUP BY id) AS agg " +
                "ON acc.id = agg.id " +
                "WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (agg.id, agg.total)"
        )
        assertEquals(listOf(listOf(Value.IntegerValue(1), Value.IntegerValue(30))), select("SELECT id, balance FROM accounts").rows)
    }

    @Test
    fun mergeCanPersistAndDeclaresItselfPersistable() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE s (id INTEGER)")
        val statement = parseOne("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE")
        assertTrue(SqlWriter.canPersist(statement))
        assertTrue(SqlWriter.write(statement).startsWith("MERGE INTO t USING s ON"))
    }

    @Test
    fun renderedSqlIsExecutableAsPersistedTextVerbatim() {
        seed()
        val statement = parseOne("UPDATE t SET category = CONCAT(category, category) WHERE id NOT IN (2)")
        val rendered = SqlWriter.write(statement)
        val reparsed = Parser(Lexer(rendered).tokenize()).parseProgram().single()
        executor.execute(reparsed)
        val result = select("SELECT category FROM t ORDER BY id ASC")
        assertEquals(listOf(listOf(text("aa")), listOf(text("b")), listOf(text("cc"))), result.rows)
    }

    @Test
    fun createViewWithMultiColumnOrderByRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("CREATE VIEW ordered AS SELECT id, category FROM t ORDER BY category ASC, id DESC")
        val result = select("SELECT category FROM ordered")
        assertEquals(listOf(listOf(text("a")), listOf(text("b")), listOf(text("c"))), result.rows)
    }

    @Test
    fun createViewWithNullsFirstRoundTripsThroughSqlWriter() {
        run("CREATE TABLE scored (id INTEGER PRIMARY KEY, score INTEGER)")
        run("INSERT INTO scored VALUES (1, 10), (2, NULL), (3, 30), (4, NULL)")
        val statement = parseOne("CREATE VIEW ranked AS SELECT id, score FROM scored ORDER BY score DESC NULLS FIRST")
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("ORDER BY score DESC NULLS FIRST"), rendered)
        run(rendered)
        val result = select("SELECT id FROM ranked")
        assertEquals(
            listOf(Value.IntegerValue(2), Value.IntegerValue(4), Value.IntegerValue(3), Value.IntegerValue(1))
                .map { listOf(it) },
            result.rows
        )
    }

    @Test
    fun createViewRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("CREATE VIEW letters AS SELECT id, category FROM t WHERE category IS NOT NULL")
        val result = select("SELECT category FROM letters ORDER BY id ASC")
        assertEquals(listOf(listOf(text("a")), listOf(text("b")), listOf(text("c"))), result.rows)
    }

    @Test
    fun createOrReplaceViewRoundTripsThroughSqlWriter() {
        seed()
        run("CREATE VIEW letters AS SELECT id FROM t")
        writeThenReplay("CREATE OR REPLACE VIEW letters AS SELECT id FROM t WHERE id > 1")
        val result = select("SELECT id FROM letters ORDER BY id ASC")
        assertEquals(listOf(listOf(Value.IntegerValue(2)), listOf(Value.IntegerValue(3))), result.rows)
    }

    @Test
    fun createViewOverARollupGroupByRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (dept TEXT, amount INTEGER)")
        run("INSERT INTO sales VALUES ('eng', 100), ('eng', 200), ('ops', 50)")
        writeThenReplay("CREATE VIEW dept_totals AS SELECT dept, SUM(amount) AS total FROM sales GROUP BY ROLLUP (dept)")
        val result = select("SELECT dept, total FROM dept_totals ORDER BY dept")
        assertEquals(
            listOf(
                listOf(Value.NullValue, Value.IntegerValue(350)),
                listOf(text("eng"), Value.IntegerValue(300)),
                listOf(text("ops"), Value.IntegerValue(50))
            ),
            result.rows
        )
    }

    @Test
    fun createViewOverStringAggWithASeparatorAndAnInternalOrderByRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (dept TEXT, region TEXT)")
        run("INSERT INTO sales VALUES ('eng', 'us'), ('eng', 'eu')")
        writeThenReplay(
            "CREATE VIEW dept_regions AS SELECT dept, STRING_AGG(region, '/' ORDER BY region) AS regions " +
                "FROM sales GROUP BY dept"
        )
        val result = select("SELECT dept, regions FROM dept_regions")
        assertEquals(listOf(listOf(text("eng"), text("eu/us"))), result.rows)
    }

    @Test
    fun createViewOverACountDistinctAggregateRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (dept TEXT, amount INTEGER)")
        run("INSERT INTO sales VALUES ('eng', 100), ('eng', 100), ('eng', 200), ('ops', 100)")
        writeThenReplay("CREATE VIEW dept_distinct_amounts AS SELECT dept, COUNT(DISTINCT amount) AS n FROM sales GROUP BY dept")
        val result = select("SELECT dept, n FROM dept_distinct_amounts ORDER BY dept")
        assertEquals(
            listOf(listOf(text("eng"), Value.IntegerValue(2)), listOf(text("ops"), Value.IntegerValue(1))),
            result.rows
        )
    }

    @Test
    fun createViewOverAFilteredAggregateRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (dept TEXT, amount INTEGER)")
        run("INSERT INTO sales VALUES ('eng', 100), ('eng', 100), ('eng', 200), ('ops', 100)")
        writeThenReplay(
            "CREATE VIEW dept_high_value_counts AS SELECT dept, COUNT(*) FILTER (WHERE amount > 100) AS n " +
                "FROM sales GROUP BY dept"
        )
        val result = select("SELECT dept, n FROM dept_high_value_counts ORDER BY dept")
        assertEquals(
            listOf(listOf(text("eng"), Value.IntegerValue(1)), listOf(text("ops"), Value.IntegerValue(0))),
            result.rows
        )
    }

    @Test
    fun createViewOverDistinctRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (dept TEXT, region TEXT)")
        run("INSERT INTO sales VALUES ('eng', 'eu'), ('eng', 'eu'), ('ops', 'eu')")
        writeThenReplay("CREATE VIEW depts AS SELECT DISTINCT dept FROM sales")
        val result = select("SELECT dept FROM depts ORDER BY dept")
        assertEquals(listOf(listOf(text("eng")), listOf(text("ops"))), result.rows)
    }

    @Test
    fun createViewOverLimitAndOffsetRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (dept TEXT, amount INTEGER)")
        run("INSERT INTO sales VALUES ('eng', 300), ('eng', 200), ('ops', 100)")
        writeThenReplay("CREATE VIEW second_highest AS SELECT dept, amount FROM sales ORDER BY amount DESC LIMIT 1 OFFSET 1")
        val result = select("SELECT dept, amount FROM second_highest")
        assertEquals(listOf(listOf(text("eng"), Value.IntegerValue(200))), result.rows)
    }

    @Test
    fun createViewOverDistinctOnRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (dept TEXT, amount INTEGER)")
        run("INSERT INTO sales VALUES ('eng', 100), ('eng', 200), ('ops', 50)")
        writeThenReplay(
            "CREATE VIEW top_per_dept AS SELECT DISTINCT ON (dept) dept, amount FROM sales ORDER BY dept, amount DESC"
        )
        val result = select("SELECT dept, amount FROM top_per_dept ORDER BY dept")
        assertEquals(
            listOf(listOf(text("eng"), Value.IntegerValue(200)), listOf(text("ops"), Value.IntegerValue(50))),
            result.rows
        )
    }

    @Test
    fun createViewOverGroupingSetsRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (dept TEXT, region TEXT, amount INTEGER)")
        run("INSERT INTO sales VALUES ('eng', 'eu', 100), ('eng', 'us', 200), ('ops', 'eu', 50)")
        writeThenReplay(
            "CREATE VIEW totals AS SELECT dept, region, SUM(amount) AS total FROM sales GROUP BY GROUPING SETS ((dept), (region))"
        )
        val result = select("SELECT dept, region, total FROM totals ORDER BY dept, region")
        assertEquals(
            listOf(
                listOf(Value.NullValue, text("eu"), Value.IntegerValue(150)),
                listOf(Value.NullValue, text("us"), Value.IntegerValue(200)),
                listOf(text("eng"), Value.NullValue, Value.IntegerValue(300)),
                listOf(text("ops"), Value.NullValue, Value.IntegerValue(50))
            ),
            result.rows
        )
    }

    @Test
    fun dropViewRoundTripsThroughSqlWriter() {
        seed()
        run("CREATE VIEW letters AS SELECT id FROM t")
        writeThenReplay("DROP VIEW letters")
        assertEquals(emptyList(), catalog.viewNames)
    }

    @Test
    fun createViewOverADerivedTableRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay(
            "CREATE VIEW letters AS SELECT s.id, s.category FROM (SELECT id, category FROM t WHERE bio IS NULL) AS s"
        )
        val result = select("SELECT id FROM letters")
        assertEquals(listOf(listOf(Value.IntegerValue(2))), result.rows)
    }

    @Test
    fun createViewOverALateralJoinRoundTripsThroughSqlWriter() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, deptId INTEGER, name TEXT, salary INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng'), (2, 'sales')")
        run(
            "INSERT INTO employees VALUES (1, 1, 'ada', 300), (2, 1, 'linus', 250), (3, 2, 'sam', 150)"
        )
        val statement = parseOne(
            "CREATE VIEW top_earners AS SELECT d.name AS dept, top.name AS emp FROM departments d " +
                "JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("LATERAL ("), rendered)
        run(rendered)
        val result = select("SELECT dept, emp FROM top_earners ORDER BY dept ASC")
        assertEquals(listOf(listOf(text("eng"), text("ada")), listOf(text("sales"), text("sam"))), result.rows)
    }

    @Test
    fun dumpAndReplayOfALateralViewPreservesTheLateralKeyword() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, deptId INTEGER, name TEXT, salary INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("INSERT INTO employees VALUES (1, 1, 'ada', 300), (2, 1, 'linus', 250)")
        run(
            "CREATE VIEW top_earners AS SELECT d.name AS dept, top.name AS emp FROM departments d " +
                "JOIN LATERAL (SELECT e.name FROM employees e WHERE e.deptId = d.id " +
                "ORDER BY e.salary DESC LIMIT 1) AS top ON TRUE"
        )
        val dump = SqlWriter.dump(catalog)
        assertTrue(dump.contains("LATERAL ("), dump)

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }
        val result = Parser(Lexer("SELECT emp FROM top_earners").tokenize()).parseProgram().single()
            .let { freshExecutor.execute(it) } as SelectResult
        assertEquals(listOf(text("ada")), result.rows.single())
    }

    @Test
    fun createViewOverARightFullAndCrossJoinRoundTripsThroughSqlWriterWithCorrectKeywords() {
        run("CREATE TABLE customers (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE orders3 (id INTEGER PRIMARY KEY, customerId INTEGER)")
        run("INSERT INTO customers VALUES (1, 'ada'), (2, 'grace')")
        // customerId 99 matches no customer, and customer 'grace' (id 2) has no order - so a plain
        // INNER JOIN would drop both, letting RIGHT/FULL prove they actually kept what INNER cannot.
        run("INSERT INTO orders3 VALUES (10, 1), (11, 99)")

        val rightStatement = parseOne("CREATE VIEW r AS SELECT customers.name, orders3.id FROM customers RIGHT JOIN orders3 ON customers.id = orders3.customerId")
        val rightRendered = SqlWriter.write(rightStatement)
        assertTrue(rightRendered.contains("RIGHT JOIN"), rightRendered)
        run(rightRendered)
        assertEquals(
            setOf(listOf(text("ada"), Value.IntegerValue(10)), listOf(Value.NullValue, Value.IntegerValue(11))),
            select("SELECT name, id FROM r").rows.toSet()
        )

        val fullStatement = parseOne("CREATE VIEW f AS SELECT customers.name, orders3.id FROM customers FULL JOIN orders3 ON customers.id = orders3.customerId")
        val fullRendered = SqlWriter.write(fullStatement)
        assertTrue(fullRendered.contains("FULL JOIN"), fullRendered)
        run(fullRendered)
        assertEquals(3, select("SELECT name, id FROM f").rows.size)

        val crossStatement = parseOne("CREATE VIEW x AS SELECT customers.name, orders3.id FROM customers CROSS JOIN orders3")
        val crossRendered = SqlWriter.write(crossStatement)
        assertTrue(crossRendered.contains("CROSS JOIN"), crossRendered)
        assertTrue(!crossRendered.contains(" ON "), crossRendered)
        run(crossRendered)
        assertEquals(4, select("SELECT name, id FROM x").rows.size)
    }

    @Test
    fun createViewOverAUnionQueryRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay(
            "CREATE VIEW letters AS SELECT category FROM t WHERE id = 1 UNION SELECT category FROM t WHERE id = 2"
        )
        val result = select("SELECT category FROM letters ORDER BY category")
        assertEquals(listOf(listOf(text("a")), listOf(text("b"))), result.rows)
    }

    @Test
    fun createViewOverAnIntersectAllQueryRoundTripsThroughSqlWriter() {
        run("CREATE TABLE bag_a (v INTEGER)")
        run("CREATE TABLE bag_b (v INTEGER)")
        run("INSERT INTO bag_a VALUES (1), (1), (2)")
        run("INSERT INTO bag_b VALUES (1), (2), (2)")
        writeThenReplay("CREATE VIEW shared AS SELECT v FROM bag_a INTERSECT ALL SELECT v FROM bag_b")
        val result = select("SELECT v FROM shared ORDER BY v")
        assertEquals(listOf(listOf(Value.IntegerValue(1)), listOf(Value.IntegerValue(2))), result.rows)
    }

    @Test
    fun createViewOverARecursiveCteRoundTripsThroughSqlWriter() {
        run("CREATE TABLE dual (x INTEGER)")
        run("INSERT INTO dual VALUES (1)")
        writeThenReplay(
            "CREATE VIEW counter_view AS WITH RECURSIVE counter(n) AS (" +
                "SELECT 1 FROM dual UNION ALL SELECT n + 1 FROM counter WHERE n < 3" +
                ") SELECT n FROM counter"
        )
        val result = select("SELECT n FROM counter_view ORDER BY n")
        assertEquals(listOf(listOf(Value.IntegerValue(1)), listOf(Value.IntegerValue(2)), listOf(Value.IntegerValue(3))), result.rows)
    }

    @Test
    fun dumpIncludesViewDefinitionsAfterTablesAndIndexes() {
        seed()
        run("CREATE VIEW letters AS SELECT id, category FROM t WHERE category IS NOT NULL")
        val dump = SqlWriter.dump(catalog)
        assertTrue(dump.contains("CREATE VIEW letters AS"))
        assertTrue(dump.indexOf("CREATE TABLE t") < dump.indexOf("CREATE VIEW letters"))
    }

    @Test
    fun createTableWithDecimalAndDateColumnsRoundTripsThroughSqlWriter() {
        val rendered = SqlWriter.write(parseOne("CREATE TABLE priced (amount DECIMAL(10, 2), born DATE)"))
        assertTrue(rendered.contains("DECIMAL(10, 2)"))
        assertTrue(rendered.contains("DATE"))
        writeThenReplay("CREATE TABLE priced (id INTEGER PRIMARY KEY, amount DECIMAL(10, 2), born DATE)")
        run("INSERT INTO priced VALUES (1, 5, CAST('2024-01-01' AS DATE))")
        val result = select("SELECT amount FROM priced")
        assertEquals(listOf(listOf(Value.DecimalValue(BigDecimal("5.00")))), result.rows)
    }

    @Test
    fun castExpressionRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("UPDATE t SET category = CAST(id AS TEXT) WHERE id = 1")
        assertEquals(listOf(listOf(text("1"))), select("SELECT category FROM t WHERE id = 1").rows)
    }

    @Test
    fun dropTableAndDropViewCascadeRoundTripThroughSqlWriter() {
        seed()
        run("CREATE VIEW letters AS SELECT id FROM t")
        writeThenReplay("DROP TABLE t CASCADE")
        assertTrue(catalog.tableNames.isEmpty())
        assertTrue(catalog.viewNames.isEmpty())
    }

    @Test
    fun dumpAndReplayPreservesDecimalAndDateValuesExactly() {
        run("CREATE TABLE priced (id INTEGER PRIMARY KEY, amount DECIMAL(12, 4), born DATE)")
        run("INSERT INTO priced VALUES (1, CAST('123456.7890' AS DECIMAL(12, 4)), CAST('2024-01-15' AS DATE))")
        val dump = SqlWriter.dump(catalog)

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }

        val result = freshExecutor.execute(
            Parser(Lexer("SELECT amount, born FROM priced").tokenize()).parseProgram().single()
        ) as SelectResult
        assertEquals(
            listOf(listOf(Value.DecimalValue(BigDecimal("123456.7890")), Value.DateValue(LocalDate.of(2024, 1, 15)))),
            result.rows
        )
    }

    @Test
    fun createTableWithForeignKeyAndCheckRoundTripsThroughSqlWriter() {
        run(SqlWriter.write(parseOne("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")))
        run(
            SqlWriter.write(
                parseOne(
                    "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER " +
                        "REFERENCES departments(id) ON DELETE CASCADE, age INTEGER CHECK (age > 0))"
                )
            )
        )
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("INSERT INTO employees VALUES (1, 1, 30)")
        assertFailsWith<KestrelException> { run("INSERT INTO employees VALUES (2, 1, -1)") }
        run("DELETE FROM departments WHERE id = 1")
        assertTrue(select("SELECT * FROM employees").rows.isEmpty())
    }

    @Test
    fun dumpAndReplayPreservesForeignKeyAndCheckConstraints() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id) " +
                "ON DELETE CASCADE, age INTEGER CHECK (age > 0))"
        )
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("INSERT INTO employees VALUES (1, 1, 30)")
        val dump = SqlWriter.dump(catalog)

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }

        Parser(Lexer("DELETE FROM departments WHERE id = 1").tokenize()).parseProgram()
            .forEach { freshExecutor.execute(it) }
        val remaining = freshExecutor.execute(
            Parser(Lexer("SELECT * FROM employees").tokenize()).parseProgram().single()
        ) as SelectResult
        assertTrue(remaining.rows.isEmpty())
    }

    @Test
    fun createTableWithUniqueConstraintsRoundTripsThroughSqlWriter() {
        run(
            SqlWriter.write(
                parseOne(
                    "CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE, a INTEGER, b INTEGER, UNIQUE (a, b))"
                )
            )
        )
        run("INSERT INTO t VALUES (1, 'ada@example.com', 1, 1)")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (2, 'ada@example.com', 2, 2)") }
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (3, 'grace@example.com', 1, 1)") }
        val result = exec("INSERT INTO t VALUES (4, 'grace@example.com', 1, 2)")
        assertEquals(InsertResult(1), result)
    }

    @Test
    fun dumpAndReplayPreservesUniqueConstraints() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        run("INSERT INTO t VALUES (1, 'ada@example.com')")
        val dump = SqlWriter.dump(catalog)

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }

        assertFailsWith<KestrelException> {
            Parser(Lexer("INSERT INTO t VALUES (2, 'ada@example.com')").tokenize()).parseProgram()
                .forEach { freshExecutor.execute(it) }
        }
    }

    @Test
    fun createUniqueIndexRoundTripsThroughSqlWriter() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT)")
        writeThenReplay("CREATE UNIQUE INDEX idx_email ON t (email)")
        run("INSERT INTO t VALUES (1, 'ada@example.com')")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (2, 'ada@example.com')") }
    }

    @Test
    fun dumpAndReplayPreservesAUniqueIndex() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT)")
        run("CREATE UNIQUE INDEX idx_email ON t (email)")
        run("INSERT INTO t VALUES (1, 'ada@example.com')")
        val dump = SqlWriter.dump(catalog)
        assertTrue(dump.contains("CREATE UNIQUE INDEX idx_email ON t (email);"))

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }

        assertFailsWith<KestrelException> {
            Parser(Lexer("INSERT INTO t VALUES (2, 'ada@example.com')").tokenize()).parseProgram()
                .forEach { freshExecutor.execute(it) }
        }
    }

    @Test
    fun alterTableAddColumnWithDefaultRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("ALTER TABLE t ADD COLUMN status TEXT NOT NULL DEFAULT 'new'")
        assertEquals(listOf(text("new"), text("new"), text("new")), select("SELECT status FROM t").rows.map { it.single() })
        run("INSERT INTO t (id) VALUES (4)")
        assertEquals(listOf(text("new")), select("SELECT status FROM t WHERE id = 4").rows.single())
    }

    @Test
    fun alterTableDropColumnRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("ALTER TABLE t DROP COLUMN bio")
        val columns = (select("SELECT * FROM t WHERE id = 1")).columns
        assertTrue(columns.none { it.equals("bio", ignoreCase = true) })
    }

    @Test
    fun alterTableRenameColumnRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("ALTER TABLE t RENAME COLUMN bio TO biography")
        assertEquals(listOf(text("mathematician")), select("SELECT biography FROM t WHERE id = 1").rows.single())
    }

    @Test
    fun alterTableRenameToRoundTripsThroughSqlWriter() {
        seed()
        writeThenReplay("ALTER TABLE t RENAME TO renamed")
        assertEquals(listOf(text("mathematician")), select("SELECT bio FROM renamed WHERE id = 1").rows.single())
    }

    @Test
    fun dumpAndReplayOfARenamedTableWithADependentViewPreservesTheRewrittenReference() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("CREATE VIEW v AS SELECT id, name FROM departments")
        run("ALTER TABLE departments RENAME TO divisions")
        val dump = SqlWriter.dump(catalog)
        assertTrue(dump.contains("FROM divisions"), dump)
        assertTrue(!dump.contains("FROM departments"), dump)

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }
        val result = Parser(Lexer("SELECT * FROM v").tokenize()).parseProgram().single()
            .let { freshExecutor.execute(it) } as SelectResult
        assertEquals(listOf(text("eng")), listOf(result.rows.single()[1]))
    }

    @Test
    fun dumpAndReplayPreservesColumnDefaultValue() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'pending')")
        run("INSERT INTO t (id) VALUES (1)")
        val dump = SqlWriter.dump(catalog)

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }
        freshExecutor.execute(Parser(Lexer("INSERT INTO t (id) VALUES (2)").tokenize()).parseProgram().single())

        val result = freshExecutor.execute(
            Parser(Lexer("SELECT status FROM t ORDER BY id ASC").tokenize()).parseProgram().single()
        ) as SelectResult
        assertEquals(listOf(listOf(text("pending")), listOf(text("pending"))), result.rows)
    }

    @Test
    fun dumpAndReplayPreservesAGeneratedColumnsExpressionNotJustItsAlreadyComputedValue() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("INSERT INTO t (a) VALUES (5)")
        val dump = SqlWriter.dump(catalog)
        assertTrue(dump.contains("GENERATED ALWAYS AS"), dump)

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }
        // A row inserted only AFTER replay proves the GENERATED expression itself round-tripped
        // (not just the value 10 already sitting in the dump's INSERT statement).
        freshExecutor.execute(Parser(Lexer("INSERT INTO t (a) VALUES (7)").tokenize()).parseProgram().single())

        val result = freshExecutor.execute(
            Parser(Lexer("SELECT b FROM t ORDER BY a ASC").tokenize()).parseProgram().single()
        ) as SelectResult
        assertEquals(listOf(listOf(Value.IntegerValue(10)), listOf(Value.IntegerValue(14))), result.rows)
    }

    @Test
    fun dumpAfterAlterTableAddColumnReflectsTheNewShape() {
        seed()
        run("ALTER TABLE t ADD COLUMN status TEXT DEFAULT 'new'")
        val dump = SqlWriter.dump(catalog)
        assertTrue(dump.contains("status TEXT DEFAULT 'new'"))

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }
        val result = freshExecutor.execute(
            Parser(Lexer("SELECT status FROM t WHERE id = 1").tokenize()).parseProgram().single()
        ) as SelectResult
        assertEquals(listOf(listOf(text("new"))), result.rows)
    }

    @Test
    fun dumpAndReplayAfterAlterTableAddColumnGeneratedPreservesTheExpressionAndBackfilledValues() {
        run("CREATE TABLE t (a INTEGER)")
        run("INSERT INTO t (a) VALUES (5)")
        run("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (a * 2) STORED")
        val dump = SqlWriter.dump(catalog)
        assertTrue(dump.contains("GENERATED ALWAYS AS"), dump)

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }
        // A row inserted only AFTER replay proves the GENERATED expression itself round-tripped,
        // not just the value backfilled into row a=5 before the dump was taken.
        freshExecutor.execute(Parser(Lexer("INSERT INTO t (a) VALUES (7)").tokenize()).parseProgram().single())

        val result = freshExecutor.execute(
            Parser(Lexer("SELECT b FROM t ORDER BY a ASC").tokenize()).parseProgram().single()
        ) as SelectResult
        assertEquals(listOf(listOf(Value.IntegerValue(10)), listOf(Value.IntegerValue(14))), result.rows)
    }

    @Test
    fun createCompositeUniqueIndexRoundTripsThroughSqlWriter() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER)")
        writeThenReplay("CREATE UNIQUE INDEX idx_ab ON t (a, b)")
        run("INSERT INTO t VALUES (1, 1, 1)")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (2, 1, 1)") }
        run("INSERT INTO t VALUES (3, 1, 2)")
    }

    @Test
    fun dumpAndReplayPreservesACompositeUniqueIndex() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER)")
        run("CREATE UNIQUE INDEX idx_ab ON t (a, b)")
        run("INSERT INTO t VALUES (1, 1, 1)")
        val dump = SqlWriter.dump(catalog)
        assertTrue(dump.contains("CREATE UNIQUE INDEX idx_ab ON t (a, b);"))

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }

        assertFailsWith<KestrelException> {
            Parser(Lexer("INSERT INTO t VALUES (2, 1, 1)").tokenize()).parseProgram()
                .forEach { freshExecutor.execute(it) }
        }
    }

    @Test
    fun createViewOverAWindowFunctionRoundTripsThroughSqlWriter() {
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept TEXT, salary INTEGER)")
        run(
            "INSERT INTO employees VALUES (1, 'eng', 300), (2, 'eng', 400), (3, 'sales', 150)"
        )
        val statement = parseOne(
            "CREATE VIEW ranked AS SELECT id, dept, " +
                "RANK() OVER (PARTITION BY dept ORDER BY salary DESC) AS r FROM employees"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("OVER (PARTITION BY dept ORDER BY salary DESC)"), rendered)
        run(rendered)
        val result = select("SELECT id, r FROM ranked ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(Value.IntegerValue(1), Value.IntegerValue(2)),
                listOf(Value.IntegerValue(2), Value.IntegerValue(1)),
                listOf(Value.IntegerValue(3), Value.IntegerValue(1))
            ),
            result.rows
        )
    }

    @Test
    fun dumpAndReplayOfAWindowFunctionViewPreservesTheOverClause() {
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept TEXT, salary INTEGER)")
        run("INSERT INTO employees VALUES (1, 'eng', 300), (2, 'eng', 400)")
        run(
            "CREATE VIEW totals AS SELECT id, SUM(salary) OVER (PARTITION BY dept) AS deptTotal FROM employees"
        )
        val dump = SqlWriter.dump(catalog)
        assertTrue(dump.contains("OVER (PARTITION BY dept)"), dump)

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }
        val result = Parser(Lexer("SELECT deptTotal FROM totals ORDER BY id ASC").tokenize()).parseProgram().single()
            .let { freshExecutor.execute(it) } as SelectResult
        assertEquals(listOf(listOf(Value.IntegerValue(700)), listOf(Value.IntegerValue(700))), result.rows)
    }

    @Test
    fun createViewOverLagWithOffsetAndDefaultRoundTripsThroughSqlWriter() {
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept TEXT, salary INTEGER)")
        run("INSERT INTO employees VALUES (1, 'eng', 400), (2, 'eng', 300)")
        val statement = parseOne(
            "CREATE VIEW withPrev AS SELECT id, dept, " +
                "LAG(salary, 1, 0) OVER (PARTITION BY dept ORDER BY salary DESC) AS prevSalary FROM employees"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("LAG(salary, 1, 0) OVER (PARTITION BY dept ORDER BY salary DESC)"), rendered)
        run(rendered)
        val result = select("SELECT id, prevSalary FROM withPrev ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(Value.IntegerValue(1), Value.IntegerValue(0)),
                listOf(Value.IntegerValue(2), Value.IntegerValue(400))
            ),
            result.rows
        )
    }

    @Test
    fun createViewOverAnExplicitRowsFrameRoundTripsThroughSqlWriter() {
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept TEXT, salary INTEGER)")
        run("INSERT INTO employees VALUES (1, 'eng', 300), (2, 'eng', 400), (3, 'eng', 100)")
        val statement = parseOne(
            "CREATE VIEW moving AS SELECT id, dept, " +
                "SUM(salary) OVER (PARTITION BY dept ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) AS s " +
                "FROM employees"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(
            rendered.contains("OVER (PARTITION BY dept ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW)"),
            rendered
        )
        run(rendered)
        val result = select("SELECT id, s FROM moving ORDER BY id ASC")
        assertEquals(
            listOf(
                listOf(Value.IntegerValue(1), Value.IntegerValue(300)),
                listOf(Value.IntegerValue(2), Value.IntegerValue(700)),
                listOf(Value.IntegerValue(3), Value.IntegerValue(500))
            ),
            result.rows
        )
    }

    @Test
    fun rowsFrameShorthandIsWrittenBackOutInCanonicalBetweenForm() {
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, salary INTEGER)")
        run("INSERT INTO employees VALUES (1, 400), (2, 300)")
        val statement = parseOne(
            "CREATE VIEW shorthand AS SELECT id, SUM(salary) OVER (ORDER BY id ROWS 1 PRECEDING) AS s FROM employees"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("ROWS BETWEEN 1 PRECEDING AND CURRENT ROW"), rendered)
    }

    @Test
    fun unboundedFrameBoundsRoundTripThroughSqlWriter() {
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, salary INTEGER)")
        run("INSERT INTO employees VALUES (1, 400), (2, 300)")
        val statement = parseOne(
            "CREATE VIEW wholePartition AS SELECT id, " +
                "SUM(salary) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS s FROM employees"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING"), rendered)
    }

    @Test
    fun createViewOverAnExplicitRangeFrameRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("INSERT INTO sales VALUES (1, 10), (2, 20), (3, 20), (4, 30), (5, 50)")
        val statement = parseOne(
            "CREATE VIEW valueTotals AS SELECT id, " +
                "SUM(amount) OVER (ORDER BY amount RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING) AS s FROM sales"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("OVER (ORDER BY amount RANGE BETWEEN 10 PRECEDING AND 10 FOLLOWING)"), rendered)
        run(rendered)
        val result = select("SELECT id, s FROM valueTotals ORDER BY id ASC")
        assertEquals(
            listOf(50L, 80L, 80L, 70L, 50L),
            result.rows.map { (it[1] as Value.IntegerValue).value }
        )
    }

    @Test
    fun createViewOverAnExcludeCurrentRowFrameRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("INSERT INTO sales VALUES (1, 10), (2, 20), (3, 20), (4, 30), (5, 50)")
        val statement = parseOne(
            "CREATE VIEW excludingSelf AS SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE CURRENT ROW" +
                ") AS s FROM sales"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(
            rendered.contains("ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE CURRENT ROW"),
            rendered
        )
        run(rendered)
        val result = select("SELECT id, s FROM excludingSelf ORDER BY id ASC")
        assertEquals(
            listOf(120L, 110L, 110L, 100L, 80L),
            result.rows.map { (it[1] as Value.IntegerValue).value }
        )
    }

    @Test
    fun createViewOverAnExplicitGroupsFrameRoundTripsThroughSqlWriter() {
        run("CREATE TABLE sales (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("INSERT INTO sales VALUES (1, 10), (2, 20), (3, 20), (4, 30), (5, 50)")
        val statement = parseOne(
            "CREATE VIEW groupTotals AS SELECT id, " +
                "SUM(amount) OVER (ORDER BY amount GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS s FROM sales"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("OVER (ORDER BY amount GROUPS BETWEEN 1 PRECEDING AND 1 FOLLOWING)"), rendered)
        run(rendered)
        val result = select("SELECT id, s FROM groupTotals ORDER BY id ASC")
        assertEquals(
            listOf(50L, 80L, 80L, 120L, 80L),
            result.rows.map { (it[1] as Value.IntegerValue).value }
        )
    }

    @Test
    fun excludeNoOthersIsOmittedFromRenderedSqlSinceItIsTheDefault() {
        run("CREATE TABLE sales (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("INSERT INTO sales VALUES (1, 10), (2, 20)")
        val statement = parseOne(
            "CREATE VIEW totals AS SELECT id, SUM(amount) OVER (" +
                "ORDER BY amount ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE NO OTHERS" +
                ") AS s FROM sales"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(!rendered.contains("EXCLUDE"), rendered)
    }

    @Test
    fun insertOnConflictDoNothingRoundTripsThroughSqlWriter() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 100)")
        val statement = parseOne("INSERT INTO accounts VALUES (1, 999) ON CONFLICT (id) DO NOTHING")
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("ON CONFLICT (id) DO NOTHING"), rendered)
        run(rendered)
        assertEquals(listOf(listOf(Value.IntegerValue(100))), select("SELECT balance FROM accounts WHERE id = 1").rows)
    }

    @Test
    fun insertOnConflictDoUpdateWithExcludedAndWhereRoundTripsThroughSqlWriter() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER)")
        run("INSERT INTO accounts VALUES (1, 100)")
        val statement = parseOne(
            "INSERT INTO accounts VALUES (1, 50) ON CONFLICT (id) DO UPDATE SET balance = accounts.balance + excluded.balance WHERE excluded.balance > 0"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("ON CONFLICT (id) DO UPDATE SET balance = (accounts.balance + excluded.balance) WHERE (excluded.balance > 0)"), rendered)
        run(rendered)
        assertEquals(listOf(listOf(Value.IntegerValue(150))), select("SELECT balance FROM accounts WHERE id = 1").rows)
    }

    @Test
    fun createTriggerRoundTripsThroughSqlWriter() {
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("CREATE TABLE audit (order_id INTEGER)")
        val statement = parseOne(
            "CREATE TRIGGER log_insert AFTER INSERT ON orders FOR EACH ROW WHEN NEW.amount > 0 " +
                "BEGIN INSERT INTO audit VALUES (NEW.id); END"
        )
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("CREATE TRIGGER log_insert AFTER INSERT ON orders FOR EACH ROW WHEN"), rendered)
        assertTrue(rendered.contains("INSERT INTO audit VALUES (NEW.id)"), rendered)
        run(rendered)
        run("INSERT INTO orders VALUES (1, 50)")
        assertEquals(listOf(listOf(Value.IntegerValue(1))), select("SELECT order_id FROM audit").rows)
    }

    @Test
    fun dropTriggerRoundTripsThroughSqlWriter() {
        run("CREATE TABLE t (id INTEGER)")
        run("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN DELETE FROM t WHERE 1 = 0; END")
        val rendered = SqlWriter.write(parseOne("DROP TRIGGER log"))
        assertEquals("DROP TRIGGER log;", rendered)
        run(rendered)
        assertFailsWith<KestrelException> { run("DROP TRIGGER log") }
    }

    @Test
    fun createSequenceRoundTripsThroughSqlWriter() {
        val statement = parseOne("CREATE SEQUENCE seq START WITH 5 INCREMENT BY 2 MAXVALUE 100 CYCLE")
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("CREATE SEQUENCE seq"), rendered)
        assertTrue(rendered.contains("INCREMENT BY 2"), rendered)
        assertTrue(rendered.contains("MAXVALUE 100"), rendered)
        assertTrue(rendered.contains("START WITH 5"), rendered)
        assertTrue(rendered.contains("CYCLE"), rendered)
        run(rendered)
        run("CREATE TABLE t (id INTEGER)")
        run("INSERT INTO t VALUES (NEXTVAL('seq'))")
        assertEquals(listOf(listOf(Value.IntegerValue(5))), select("SELECT id FROM t").rows)
    }

