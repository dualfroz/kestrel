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

class CompoundQueryTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private fun seed() {
        run("CREATE TABLE dual (x INTEGER)")
        run("INSERT INTO dual VALUES (1)")
        run("CREATE TABLE t1 (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("CREATE TABLE t2 (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("INSERT INTO t1 VALUES (1, 'ada'), (2, 'linus')")
        run("INSERT INTO t2 VALUES (2, 'linus'), (3, 'grace')")
    }

    @Test
    fun unionRemovesDuplicateRows() {
        seed()
        val result = select("SELECT name FROM t1 UNION SELECT name FROM t2 ORDER BY name")
        assertEquals(listOf(listOf(text("ada")), listOf(text("grace")), listOf(text("linus"))), result.rows)
    }

    @Test
    fun unionAllKeepsDuplicateRows() {
        seed()
        val result = select("SELECT name FROM t1 UNION ALL SELECT name FROM t2 ORDER BY name")
        assertEquals(
            listOf(listOf(text("ada")), listOf(text("grace")), listOf(text("linus")), listOf(text("linus"))),
            result.rows
        )
    }

    @Test
    fun intersectReturnsRowsPresentInBothQueries() {
        seed()
        val result = select("SELECT name FROM t1 INTERSECT SELECT name FROM t2")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }

    @Test
    fun exceptReturnsRowsOnlyInTheLeftQuery() {
        seed()
        val result = select("SELECT name FROM t1 EXCEPT SELECT name FROM t2")
        assertEquals(listOf(listOf(text("ada"))), result.rows)
    }

    @Test
    fun intersectAllKeepsDuplicatesUpToTheMinimumCountInEitherSide() {
        run("CREATE TABLE bag_a (v INTEGER)")
        run("CREATE TABLE bag_b (v INTEGER)")
        run("INSERT INTO bag_a VALUES (1), (1), (1), (2)")
        run("INSERT INTO bag_b VALUES (1), (1), (2), (2), (3)")
        val result = select("SELECT v FROM bag_a INTERSECT ALL SELECT v FROM bag_b ORDER BY v")
        assertEquals(listOf(listOf(int(1)), listOf(int(1)), listOf(int(2))), result.rows)
    }

    @Test
    fun exceptAllKeepsLeftDuplicatesNotConsumedByTheRightSide() {
        run("CREATE TABLE bag_a (v INTEGER)")
        run("CREATE TABLE bag_b (v INTEGER)")
        run("INSERT INTO bag_a VALUES (1), (1), (1), (2)")
        run("INSERT INTO bag_b VALUES (1), (1), (2), (2), (3)")
        val result = select("SELECT v FROM bag_a EXCEPT ALL SELECT v FROM bag_b ORDER BY v")
        assertEquals(listOf(listOf(int(1))), result.rows)
    }

    @Test
    fun mismatchedColumnCountsFail() {
        seed()
        assertFailsWith<KestrelException> {
            select("SELECT id, name FROM t1 UNION SELECT id FROM t2")
        }
    }

    @Test
    fun compoundOperatorsChainLeftAssociatively() {
        seed()
        run("CREATE TABLE t3 (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run("INSERT INTO t3 VALUES (2, 'linus')")
        val result = select("SELECT name FROM t1 UNION ALL SELECT name FROM t2 INTERSECT SELECT name FROM t3 ORDER BY name")
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }

    @Test
    fun orderByAndLimitApplyToTheWholeCompoundResult() {
        seed()
        val result = select("SELECT name FROM t1 UNION ALL SELECT name FROM t2 ORDER BY name DESC LIMIT 2")
        assertEquals(listOf(listOf(text("linus")), listOf(text("linus"))), result.rows)
    }

    @Test
    fun offsetSkipsRowsOfTheWholeCompoundResultBeforeLimitApplies() {
        seed()
        val result = select("SELECT name FROM t1 UNION ALL SELECT name FROM t2 ORDER BY name DESC LIMIT 2 OFFSET 1")
        assertEquals(listOf(listOf(text("linus")), listOf(text("grace"))), result.rows)
    }

    @Test
    fun compoundQueryWorksAsDerivedTableInFrom() {
        seed()
        val result = select("SELECT count(*) FROM (SELECT name FROM t1 UNION SELECT name FROM t2) AS u")
        assertEquals(listOf(listOf(int(3))), result.rows)
    }

    @Test
    fun compoundQueryWorksAsViewBody() {
        seed()
        run("CREATE VIEW combined AS SELECT name FROM t1 UNION SELECT name FROM t2")
        val result = select("SELECT name FROM combined ORDER BY name")
        assertEquals(listOf(listOf(text("ada")), listOf(text("grace")), listOf(text("linus"))), result.rows)
        run("INSERT INTO t1 VALUES (4, 'margaret')")
        val refreshed = select("SELECT name FROM combined ORDER BY name")
        assertEquals(4, refreshed.rows.size)
    }

    @Test
    fun explainDescribesCompoundQuery() {
        seed()
        val plan = (run("EXPLAIN SELECT name FROM t1 UNION SELECT name FROM t2").single() as ExplainResult).plan
        assertTrue(plan.isNotEmpty())
        assertTrue(plan.any { it.contains("Union") })
    }

    @Test
    fun withClauseMakesACteAvailableInFrom() {
        seed()
        run("WITH recent AS (SELECT id, name FROM t1) SELECT name FROM recent WHERE id = 1")
        val result = select("WITH recent AS (SELECT id, name FROM t1) SELECT name FROM recent WHERE id = 1")
        assertEquals(listOf(listOf(text("ada"))), result.rows)
    }

    @Test
    fun laterCteCanReferenceAnEarlierCteInTheSameWithClause() {
        seed()
        val result = select(
            "WITH first_cte AS (SELECT id, name FROM t1), second_cte AS (SELECT name FROM first_cte WHERE id = 2) " +
                "SELECT name FROM second_cte"
        )
        assertEquals(listOf(listOf(text("linus"))), result.rows)
    }

    @Test
    fun cteWithExplicitColumnListRenamesOutputColumns() {
        seed()
        val result = select("WITH renamed(label) AS (SELECT name FROM t1) SELECT label FROM renamed ORDER BY label")
        assertEquals(listOf(listOf(text("ada")), listOf(text("linus"))), result.rows)
    }

    @Test
    fun cteColumnListSizeMustMatchQueryColumnCount() {
        seed()
        assertFailsWith<KestrelException> {
            select("WITH bad(a, b, c) AS (SELECT id FROM t1) SELECT * FROM bad")
        }
    }

    @Test
    fun recursiveCteGeneratesABoundedSequence() {
        seed()
        val result = select(
            "WITH RECURSIVE counter(n) AS (" +
                "SELECT 1 FROM dual " +
                "UNION ALL " +
                "SELECT n + 1 FROM counter WHERE n < 5" +
                ") SELECT n FROM counter ORDER BY n"
        )
        assertEquals((1..5).map { listOf(int(it.toLong())) }, result.rows)
    }

    @Test
    fun recursiveCteAccumulatesARunningTotal() {
        seed()
        val result = select(
            "WITH RECURSIVE sums(n, total) AS (" +
                "SELECT 1, 1 FROM dual " +
                "UNION ALL " +
                "SELECT n + 1, total + n + 1 FROM sums WHERE n < 5" +
                ") SELECT total FROM sums WHERE n = 5"
        )
        assertEquals(listOf(listOf(int(15))), result.rows)
    }

    @Test
    fun recursiveCteWithUnionDeduplicatesAndTerminatesOnACycle() {
        seed()
        run("CREATE TABLE edges (from_node INTEGER, to_node INTEGER)")
        run("INSERT INTO edges VALUES (1, 2), (2, 3), (3, 1), (3, 4)")
        val result = select(
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
        seed()
        val result = select(
            "WITH RECURSIVE notActuallyRecursive AS (SELECT 1 AS n FROM dual UNION ALL SELECT 2 FROM dual) " +
                "SELECT n FROM notActuallyRecursive ORDER BY n"
        )
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), result.rows)
    }

    @Test
    fun recursiveCteReferencingItselfOnBothSidesFails() {
        seed()
        assertFailsWith<KestrelException> {
            select(
                "WITH RECURSIVE bad(n) AS (" +
                    "SELECT n FROM bad UNION ALL SELECT n FROM bad" +
                    ") SELECT n FROM bad"
            )
        }
    }

    @Test
    fun nonTerminatingRecursiveCteFailsInsteadOfLoopingForever() {
        seed()
        assertFailsWith<KestrelException> {
            select(
                "WITH RECURSIVE counter(n) AS (" +
                    "SELECT 1 FROM dual " +
                    "UNION ALL " +
                    "SELECT n + 1 FROM counter" +
                    ") SELECT n FROM counter"
            )
        }
    }
}
