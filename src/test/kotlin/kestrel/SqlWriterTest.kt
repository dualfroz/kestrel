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

