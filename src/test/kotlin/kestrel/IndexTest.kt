package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.ExplainResult
import kestrel.engine.Executor
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IndexTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun explain(sql: String): List<String> = (run(sql).single() as ExplainResult).plan

    private fun seed() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER)")
        run(
            "INSERT INTO users VALUES " +
                "(1, 'ada', 36), (2, 'linus', 54), (3, 'grace', 85), (4, 'don', 54), (5, 'edsger', 40)"
        )
    }

    @Test
    fun equalityLookupMatchesFullScan() {
        seed()
        val scanned = select("SELECT name FROM users WHERE age = 54").rows
        run("CREATE INDEX idxAge ON users (age)")
        val indexed = select("SELECT name FROM users WHERE age = 54").rows
        assertEquals(scanned, indexed)
        assertEquals(2, indexed.size)
    }

    @Test
    fun rangeLookupMatchesFullScan() {
        seed()
        val scanned = select("SELECT name FROM users WHERE age >= 40 AND age < 85").rows
        run("CREATE INDEX idxAge ON users (age)")
        val indexed = select("SELECT name FROM users WHERE age >= 40 AND age < 85").rows
        assertEquals(scanned, indexed)
    }

    @Test
    fun plannerChoosesIndexForEquality() {
        seed()
        assertTrue(explain("EXPLAIN SELECT name FROM users WHERE age = 54").any { it.contains("Seq Scan") })
        run("CREATE INDEX idxAge ON users (age)")
        val plan = explain("EXPLAIN SELECT name FROM users WHERE age = 54")
        assertTrue(plan.any { it.contains("Index Scan using idxAge") }, "expected index scan, got $plan")
    }

    @Test
    fun plannerChoosesIndexForRange() {
        seed()
        run("CREATE INDEX idxAge ON users (age)")
        val plan = explain("EXPLAIN SELECT name FROM users WHERE age > 50")
        assertTrue(plan.any { it.contains("Index Scan using idxAge") && it.contains("age > 50") }, "got $plan")
    }

    @Test
    fun plannerFallsBackToScanWithoutIndex() {
        seed()
        val plan = explain("EXPLAIN SELECT name FROM users WHERE age > 50")
        assertTrue(plan.any { it.contains("Seq Scan on users") }, "got $plan")
    }

    @Test
    fun indexStaysCorrectAfterUpdateAndDelete() {
        seed()
        run("CREATE INDEX idxAge ON users (age)")
        run("UPDATE users SET age = 54 WHERE name = 'ada'")
        run("DELETE FROM users WHERE name = 'linus'")
        val indexed = select("SELECT name FROM users WHERE age = 54").rows
            .map { (it.single() as Value.TextValue).value }
        assertEquals(setOf("ada", "don"), indexed.toSet())
    }

    @Test
    fun dropIndexRemovesItFromPlanner() {
        seed()
        run("CREATE INDEX idxAge ON users (age)")
        run("DROP INDEX idxAge")
        val plan = explain("EXPLAIN SELECT name FROM users WHERE age = 54")
        assertTrue(plan.any { it.contains("Seq Scan on users") }, "got $plan")
    }

    @Test
    fun duplicateIndexIsRejected() {
        seed()
        run("CREATE INDEX idxAge ON users (age)")
        assertFailsWith<KestrelException> { run("CREATE INDEX idxAge ON users (id)") }
    }

    @Test
    fun indexOnUnknownColumnIsRejected() {
        seed()
        assertFailsWith<KestrelException> { run("CREATE INDEX bad ON users (missing)") }
    }

    @Test
    fun droppingUnknownIndexIsRejected() {
        assertFailsWith<KestrelException> { run("DROP INDEX ghost") }
    }
}
