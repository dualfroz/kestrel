package kestrel

import java.math.BigDecimal
import java.time.LocalDate
import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.ExplainResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExpressionLanguageTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private fun seedUsers() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER, bio TEXT)")
        run(
            "INSERT INTO users VALUES " +
                "(1, 'ada', 36, 'mathematician'), (2, 'linus', 54, NULL), " +
                "(3, 'grace', 85, 'rear admiral')"
        )
    }

    @Test
    fun scalarFunctionInProjection() {
        seedUsers()
        val result = select("SELECT UPPER(name) FROM users ORDER BY id ASC")
        assertEquals(listOf("upper"), result.columns)
        assertEquals(listOf(text("ADA"), text("LINUS"), text("GRACE")), result.rows.map { it[0] })
    }

    @Test
    fun scalarFunctionInWhereClause() {
        seedUsers()
        val result = select("SELECT name FROM users WHERE LENGTH(name) > 4 ORDER BY name ASC")
        assertEquals(listOf(listOf(text("grace")), listOf(text("linus"))), result.rows)
    }

    @Test
    fun scalarFunctionUsableInUpdateAssignment() {
        seedUsers()
        run("UPDATE users SET name = UPPER(name) WHERE id = 1")
        val result = select("SELECT name FROM users WHERE id = 1")
        assertEquals(listOf(listOf(text("ADA"))), result.rows)
    }

    @Test
    fun nestedFunctionCalls() {
        seedUsers()
        val result = select("SELECT SUBSTR(UPPER(name), 1, 1) FROM users WHERE id = 1")
        assertEquals(listOf(listOf(text("A"))), result.rows)
    }

    @Test
    fun isNullFiltersRowsWithMissingValues() {
        seedUsers()
        val result = select("SELECT name FROM users WHERE bio IS NULL")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
        val negated = select("SELECT COUNT(*) FROM users WHERE bio IS NOT NULL")
        assertEquals(listOf(listOf(int(2))), negated.rows)
    }

    @Test
    fun inClauseMatchesAgainstLiteralSet() {
        seedUsers()
        val result = select("SELECT name FROM users WHERE age IN (36, 85) ORDER BY age ASC")
        assertEquals(listOf(listOf(text("ada")), listOf(text("grace"))), result.rows)
    }

    @Test
    fun notInExcludesMatchingRows() {
        seedUsers()
        val result = select("SELECT name FROM users WHERE age NOT IN (36, 85)")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }

    @Test
    fun betweenSelectsInclusiveRange() {
        seedUsers()
        val result = select("SELECT name FROM users WHERE age BETWEEN 40 AND 90 ORDER BY age ASC")
        assertEquals(listOf(listOf(text("linus")), listOf(text("grace"))), result.rows)
    }

    @Test
    fun likeMatchesWildcardPattern() {
        seedUsers()
        val result = select("SELECT name FROM users WHERE bio LIKE '%admiral' ")
        assertEquals(listOf(listOf(text("grace"))), result.rows)
    }

    @Test
    fun caseExpressionInProjection() {
        seedUsers()
        val result = select(
            "SELECT name, CASE WHEN age < 50 THEN 'young' WHEN age < 80 THEN 'mid' ELSE 'senior' END " +
                "FROM users ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(text("ada"), text("young")),
                listOf(text("linus"), text("mid")),
                listOf(text("grace"), text("senior"))
            ),
            result.rows
        )
    }

    @Test
    fun caseExpressionUsableInWhereClause() {
        seedUsers()
        val result = select(
            "SELECT name FROM users WHERE CASE WHEN age > 40 THEN TRUE ELSE FALSE END ORDER BY name ASC"
        )
        assertEquals(listOf(listOf(text("grace")), listOf(text("linus"))), result.rows)
    }

    @Test
    fun combiningPredicatesWithLogicalOperators() {
        seedUsers()
        val result = select(
            "SELECT name FROM users WHERE age BETWEEN 30 AND 60 AND name LIKE 'a%'"
        )
        assertEquals(listOf(listOf(text("ada"))), result.rows)
    }

    @Test
    fun aggregateOfScalarFunctionResult() {
        seedUsers()
        val result = select("SELECT SUM(LENGTH(name)) FROM users")
        assertEquals(listOf(listOf(int(3 + 5 + 5))), result.rows)
    }

    @Test
    fun groupByWithFunctionOnGroupedColumn() {
        run("CREATE TABLE t (category TEXT, amount INTEGER)")
        run("INSERT INTO t VALUES ('Eng', 10), ('eng', 20), ('Ops', 5)")
        val result = select("SELECT LOWER(category), SUM(amount) FROM t GROUP BY LOWER(category) ORDER BY LOWER(category) ASC")
        assertEquals(
            listOf(listOf(text("eng"), int(30)), listOf(text("ops"), int(5))),
            result.rows
        )
    }

    @Test
    fun scalarFunctionNotAllowedAsAggregateGroupingViolation() {
        seedUsers()
        assertFailsWith<KestrelException> {
            select("SELECT UPPER(name), COUNT(*) FROM users GROUP BY age")
        }
    }

    @Test
    fun newPredicateFormsCoexistWithIndexPushdownOnOtherConjuncts() {
        seedUsers()
        run("CREATE INDEX idxId ON users (id)")
        val plan = (run("EXPLAIN SELECT name FROM users WHERE id = 1 AND age BETWEEN 1 AND 100").single() as ExplainResult).plan
        assertTrue(plan.any { it.contains("Index Scan") }, "expected an index scan but was: $plan")
        val result = select("SELECT name FROM users WHERE id = 1 AND age BETWEEN 1 AND 100")
        assertEquals(listOf(listOf(text("ada"))), result.rows)
    }

    @Test
    fun unknownFunctionNameReportsClearError() {
        seedUsers()
        assertFailsWith<KestrelException> { select("SELECT NOSUCHFUNC(name) FROM users") }
    }

    private fun decimal(value: String) = Value.DecimalValue(BigDecimal(value))
    private fun date(value: String) = Value.DateValue(LocalDate.parse(value))

    @Test
    fun decimalColumnCoercesIntegerAndRealButRejectsBareText() {
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, amount DECIMAL(10, 2))")
        run("INSERT INTO orders VALUES (1, 5), (2, 5.5)")
        val result = select("SELECT amount FROM orders ORDER BY id ASC")
        assertEquals(listOf(listOf(decimal("5.00")), listOf(decimal("5.50"))), result.rows)
        assertFailsWith<KestrelException> { run("INSERT INTO orders VALUES (3, '5.00')") }
    }

    @Test
    fun dateColumnRequiresExplicitCastFromText() {
        run("CREATE TABLE events (id INTEGER PRIMARY KEY, happened DATE)")
        assertFailsWith<KestrelException> { run("INSERT INTO events VALUES (1, '2024-01-01')") }
        run("INSERT INTO events VALUES (1, CAST('2024-01-01' AS DATE))")
        val result = select("SELECT happened FROM events")
        assertEquals(listOf(listOf(date("2024-01-01"))), result.rows)
    }

    @Test
    fun decimalSumAndAverageStayExactAcrossGroupBy() {
        run("CREATE TABLE orders (id INTEGER PRIMARY KEY, dept TEXT, amount DECIMAL(10, 2))")
        run(
            "INSERT INTO orders VALUES " +
                "(1, 'eng', 10.10), (2, 'eng', 10.20), (3, 'eng', 10.30), (4, 'ops', 1.11)"
        )
        val result = select("SELECT dept, SUM(amount), AVG(amount) FROM orders GROUP BY dept ORDER BY dept ASC")
        assertEquals(
            listOf(
                listOf(text("eng"), decimal("30.60"), decimal("10.20")),
                listOf(text("ops"), decimal("1.11"), decimal("1.11"))
            ),
            result.rows
        )
    }

    @Test
    fun dateArithmeticEndToEndThroughSelectAndWhere() {
        run("CREATE TABLE events (id INTEGER PRIMARY KEY, happened DATE)")
        run(
            "INSERT INTO events VALUES " +
                "(1, CAST('2024-01-01' AS DATE)), (2, CAST('2024-06-01' AS DATE))"
        )
        val projected = select("SELECT happened + 7 FROM events ORDER BY id ASC")
        assertEquals(listOf(listOf(date("2024-01-08")), listOf(date("2024-06-08"))), projected.rows)

        val filtered = select(
            "SELECT id FROM events WHERE happened > CAST('2024-03-01' AS DATE)"
        )
        assertEquals(listOf(listOf(int(2))), filtered.rows)
    }

    @Test
    fun castExpressionWorksInProjectionAndWhereAgainstOrdinaryColumns() {
        seedUsers()
        val projected = select("SELECT CAST(age AS TEXT) FROM users WHERE id = 1")
        assertEquals(listOf(listOf(text("36"))), projected.rows)

        val filtered = select("SELECT name FROM users WHERE CAST(age AS REAL) > 40.0 ORDER BY name ASC")
        assertEquals(listOf(listOf(text("grace")), listOf(text("linus"))), filtered.rows)
    }

    @Test
    fun dateComponentFunctionsEndToEndThroughSelectAndWhere() {
        run("CREATE TABLE events (id INTEGER PRIMARY KEY, happened DATE)")
        run(
            "INSERT INTO events VALUES " +
                "(1, CAST('2024-03-15' AS DATE)), (2, CAST('2023-11-02' AS DATE))"
        )
        val projected = select("SELECT YEAR(happened), MONTH(happened), DAY(happened) FROM events ORDER BY id ASC")
        assertEquals(
            listOf(listOf(int(2024), int(3), int(15)), listOf(int(2023), int(11), int(2))),
            projected.rows
        )

        val filtered = select("SELECT id FROM events WHERE YEAR(happened) = 2023")
        assertEquals(listOf(listOf(int(2))), filtered.rows)

        val today = select("SELECT CURRENT_DATE() FROM events WHERE id = 1")
        assertEquals(listOf(listOf(date(LocalDate.now().toString()))), today.rows)
    }

    @Test
    fun dateAddDateSubAndDatediffEndToEndThroughSelectAndWhere() {
        run("CREATE TABLE events (id INTEGER PRIMARY KEY, happened DATE)")
        run(
            "INSERT INTO events VALUES " +
                "(1, CAST('2024-03-15' AS DATE)), (2, CAST('2024-03-20' AS DATE))"
        )
        val shifted = select(
            "SELECT DATE_ADD(happened, 5), DATE_SUB(happened, 5) FROM events ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(date("2024-03-20"), date("2024-03-10")),
                listOf(date("2024-03-25"), date("2024-03-15"))
            ),
            shifted.rows
        )

        val diff = select(
            "SELECT DATEDIFF((SELECT happened FROM events WHERE id = 2), happened) FROM events WHERE id = 1"
        )
        assertEquals(listOf(listOf(int(5))), diff.rows)

        val filtered = select("SELECT id FROM events WHERE DATE_ADD(happened, 5) = CAST('2024-03-25' AS DATE)")
        assertEquals(listOf(listOf(int(2))), filtered.rows)
    }

    @Test
    fun repeatLpadAndRpadEndToEndThroughSelectAndUpdate() {
        run("CREATE TABLE codes (id INTEGER PRIMARY KEY, code TEXT)")
        run("INSERT INTO codes VALUES (1, '7'), (2, '42')")
        val padded = select("SELECT id, LPAD(code, 4, '0') FROM codes ORDER BY id ASC")
        assertEquals(listOf(listOf(int(1), text("0007")), listOf(int(2), text("0042"))), padded.rows)

        run("UPDATE codes SET code = RPAD(code, 5, '-') WHERE id = 1")
        assertEquals(listOf(listOf(text("7----"))), select("SELECT code FROM codes WHERE id = 1").rows)

        val separator = select("SELECT REPEAT('-', 3) FROM codes WHERE id = 2")
        assertEquals(listOf(listOf(text("---"))), separator.rows)
    }

    @Test
    fun signTruncGreatestAndLeastEndToEndThroughSelectWhereAndUpdate() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER, limitAmount INTEGER, ratio REAL)")
        run("INSERT INTO accounts VALUES (1, -50, 100, 0.0), (2, 75, 40, 0.0), (3, 0, 0, 0.0)")

        val signs = select("SELECT id, SIGN(balance) FROM accounts ORDER BY id ASC")
        assertEquals(listOf(listOf(int(1), int(-1)), listOf(int(2), int(1)), listOf(int(3), int(0))), signs.rows)

        val capped = select("SELECT id, LEAST(balance, limitAmount) FROM accounts ORDER BY id ASC")
        assertEquals(listOf(listOf(int(1), int(-50)), listOf(int(2), int(40)), listOf(int(3), int(0))), capped.rows)

        val overLimit = select("SELECT id FROM accounts WHERE GREATEST(balance, 0) > limitAmount")
        assertEquals(listOf(listOf(int(2))), overLimit.rows)

        run("UPDATE accounts SET ratio = TRUNC(balance / 3.0) WHERE id = 2")
        assertEquals(listOf(listOf(Value.RealValue(25.0))), select("SELECT ratio FROM accounts WHERE id = 2").rows)
    }
}
