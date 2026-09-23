package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.ExplainResult
import kestrel.engine.Executor
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompositeIndexTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun explain(sql: String): List<String> = (run(sql).single() as ExplainResult).plan

    private fun ids(result: SelectResult): List<Long> = result.rows.map { (it.single() as kestrel.storage.Value.IntegerValue).value }

    private fun seedPairs() {
        run("CREATE TABLE pairs (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER)")
        run(
            "INSERT INTO pairs VALUES " +
                "(1, 1, 10), (2, 1, 20), (3, 1, 30), (4, 2, 10), (5, 2, 20), (6, 3, 10)"
        )
    }

    private fun seedRanked() {
        run("CREATE TABLE ranked (id INTEGER PRIMARY KEY, age INTEGER, name TEXT)")
        run(
            "INSERT INTO ranked VALUES " +
                "(1, 30, 'amy'), (2, 30, 'bob'), (3, 40, 'cy'), (4, 40, 'dan'), (5, 50, 'eve')"
        )
    }

    private fun seedSales() {
        run("CREATE TABLE sales (id INTEGER PRIMARY KEY, region TEXT, amount INTEGER, note TEXT)")
        run(
            "INSERT INTO sales VALUES " +
                "(1, 'EU', 50, 'x'), (2, 'EU', 100, 'y'), (3, 'EU', 150, 'z'), (4, 'EU', 200, 'w'), " +
                "(5, 'US', 150, 'v'), (6, 'US', 300, 'u')"
        )
    }

    @Test
    fun parsesMultiColumnCreateIndexAndCreateUniqueIndex() {
        run("CREATE TABLE t (a INTEGER, b INTEGER, c INTEGER)")
        run("CREATE INDEX idx ON t (a, b, c)")
        run("CREATE UNIQUE INDEX uidx ON t (b, a)")
        val schema = explain("EXPLAIN SELECT a FROM t").joinToString("\n")
        assertTrue(schema.isNotEmpty())
    }

    @Test
    fun compositeUniqueIndexRejectsDuplicateTupleButAllowsPartialReuse() {
        seedPairs()
        run("CREATE UNIQUE INDEX uidx ON pairs (a, b)")
        run("INSERT INTO pairs VALUES (7, 1, 99)")
        run("INSERT INTO pairs VALUES (8, 9, 10)")
        assertFailsWith<KestrelException> { run("INSERT INTO pairs VALUES (9, 1, 10)") }
    }

    @Test
    fun compositeUniqueIndexRejectsDuplicateTupleOnUpdate() {
        seedPairs()
        run("CREATE UNIQUE INDEX uidx ON pairs (a, b)")
        assertFailsWith<KestrelException> { run("UPDATE pairs SET a = 1, b = 20 WHERE id = 1") }
    }

    @Test
    fun compositeUniqueIndexAllowsMultipleNullsInEitherColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER)")
        run("CREATE UNIQUE INDEX uidx ON t (a, b)")
        run("INSERT INTO t VALUES (1, NULL, 5), (2, NULL, 5), (3, 5, NULL), (4, 5, NULL)")
        assertEquals(4, select("SELECT id FROM t").rows.size)
    }

    @Test
    fun creatingACompositeUniqueIndexOverExistingDuplicatesFailsWithoutLeavingHalfState() {
        seedPairs()
        run("INSERT INTO pairs VALUES (7, 1, 10)")
        assertFailsWith<KestrelException> { run("CREATE UNIQUE INDEX uidx ON pairs (a, b)") }
        run("CREATE INDEX uidx ON pairs (a, b)")
    }

    @Test
    fun indexOnUnknownColumnInCompositeListIsRejected() {
        seedPairs()
        val error = assertFailsWith<KestrelException> { run("CREATE INDEX bad ON pairs (a, missing)") }
        assertTrue(error.message!!.contains("missing"), error.message)
    }

    @Test
    fun plannerUsesCompositeIndexForFullEqualityMatch() {
        seedPairs()
        val scanned = select("SELECT id FROM pairs WHERE a = 1 AND b = 20").rows
        run("CREATE INDEX idx ON pairs (a, b)")
        val plan = explain("EXPLAIN SELECT id FROM pairs WHERE a = 1 AND b = 20")
        assertTrue(plan.any { it.contains("Index Scan using idx") }, "got $plan")
        val indexed = select("SELECT id FROM pairs WHERE a = 1 AND b = 20").rows
        assertEquals(scanned, indexed)
        assertEquals(listOf(2L), ids(select("SELECT id FROM pairs WHERE a = 1 AND b = 20")))
    }

    @Test
    fun plannerUsesCompositeIndexAsEqualityPrefixScan() {
        seedPairs()
        val scanned = select("SELECT id FROM pairs WHERE a = 1").rows
        run("CREATE INDEX idx ON pairs (a, b)")
        val plan = explain("EXPLAIN SELECT id FROM pairs WHERE a = 1")
        assertTrue(plan.any { it.contains("Index Scan using idx") }, "got $plan")
        val indexed = select("SELECT id FROM pairs WHERE a = 1").rows
        assertEquals(scanned, indexed)
        assertEquals(listOf(1L, 2L, 3L), ids(select("SELECT id FROM pairs WHERE a = 1")))
    }

    @Test
    fun plannerIgnoresCompositeIndexWhenOnlyTrailingColumnIsBound() {
        seedPairs()
        run("CREATE INDEX idx ON pairs (a, b)")
        val plan = explain("EXPLAIN SELECT id FROM pairs WHERE b = 10")
        assertTrue(plan.any { it.contains("Seq Scan on pairs") }, "got $plan")
        assertFalse(plan.any { it.contains("Index Scan") }, "got $plan")
    }

    @Test
    fun plannerUsesCompositeIndexForRangeOnLeadingColumnAlone() {
        seedRanked()
        val scanned = select("SELECT id FROM ranked WHERE age > 30").rows
        run("CREATE INDEX idxAgeName ON ranked (age, name)")
        val plan = explain("EXPLAIN SELECT id FROM ranked WHERE age > 30")
        assertTrue(plan.any { it.contains("Index Scan using idxAgeName") }, "got $plan")
        val indexed = select("SELECT id FROM ranked WHERE age > 30").rows
        assertEquals(scanned, indexed)
        assertEquals(listOf(3L, 4L, 5L), ids(select("SELECT id FROM ranked WHERE age > 30")))
    }

    @Test
    fun plannerUsesCompositeIndexForTwoSidedRangeOnLeadingColumn() {
        seedRanked()
        val scanned = select("SELECT id FROM ranked WHERE age > 30 AND age < 50").rows
        run("CREATE INDEX idxAgeName ON ranked (age, name)")
        val indexed = select("SELECT id FROM ranked WHERE age > 30 AND age < 50").rows
        assertEquals(scanned, indexed)
        assertEquals(listOf(3L, 4L), ids(select("SELECT id FROM ranked WHERE age > 30 AND age < 50")))
    }

    @Test
    fun plannerUsesCompositeIndexForEqualityPrefixPlusRangeOnNextColumnWithFreeTail() {
        seedSales()
        val scanned = select("SELECT id FROM sales WHERE region = 'EU' AND amount > 100").rows
        run("CREATE INDEX idxRegionAmount ON sales (region, amount, note)")
        val plan = explain("EXPLAIN SELECT id FROM sales WHERE region = 'EU' AND amount > 100")
        assertTrue(plan.any { it.contains("Index Scan using idxRegionAmount") }, "got $plan")
        val indexed = select("SELECT id FROM sales WHERE region = 'EU' AND amount > 100").rows
        assertEquals(scanned, indexed)
        assertEquals(listOf(3L, 4L), ids(select("SELECT id FROM sales WHERE region = 'EU' AND amount > 100")))
    }

    @Test
    fun plannerUsesCompositeIndexForEqualityPrefixPlusInclusiveUpperBound() {
        seedSales()
        run("CREATE INDEX idxRegionAmount ON sales (region, amount, note)")
        val indexed = ids(select("SELECT id FROM sales WHERE region = 'EU' AND amount <= 100"))
        assertEquals(listOf(1L, 2L), indexed)
    }

    @Test
    fun plannerPrefersTheMoreSelectiveCompositeIndexOverASingleColumnIndex() {
        seedSales()
        run("CREATE INDEX idxAmount ON sales (amount)")
        run("CREATE INDEX idxRegionAmount ON sales (region, amount)")
        val plan = explain("EXPLAIN SELECT id FROM sales WHERE region = 'EU' AND amount = 100")
        assertTrue(plan.any { it.contains("Index Scan using idxRegionAmount") }, "got $plan")
        assertFalse(plan.any { it.contains("idxAmount") }, "got $plan")
    }

    @Test
    fun dropCompositeIndexRemovesItFromPlanner() {
        seedPairs()
        run("CREATE INDEX idx ON pairs (a, b)")
        run("DROP INDEX idx")
        val plan = explain("EXPLAIN SELECT id FROM pairs WHERE a = 1 AND b = 20")
        assertTrue(plan.any { it.contains("Seq Scan on pairs") }, "got $plan")
    }
}
