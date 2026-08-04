package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.ExplainResult
import kestrel.engine.Executor
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.persistence.SqlWriter
import kestrel.sql.ast.CreateTableStatement
import kestrel.sql.ast.Statement
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `col TYPE COLLATE name` - a column's own declared collating sequence (see
 * [kestrel.catalog.Column.collation]), which a *bare* reference to it compares with whenever the
 * query itself writes no explicit `COLLATE` - see [kestrel.CollateTest] for that explicit,
 * expression-level form, which always overrides this one. Threaded through
 * [kestrel.engine.ColumnResolver.declaredCollation] into every resolver built directly over a
 * table's own schema: plain `WHERE`/`SELECT`/`HAVING` (via [kestrel.engine.SelectRunner]'s
 * per-source lookup, including across a JOIN), `INSERT ... ON CONFLICT DO UPDATE` (EXCLUDED-aware),
 * `MERGE`'s `ON`/`WHEN` clauses, and a trigger's `NEW`/`OLD`. [kestrel.store.sql.
 * DiskExecutorColumnCollationTest] mirrors the core cases against the disk engine, including a
 * real file close/reopen (the declared collation is persisted in [kestrel.store.sql.
 * DirectoryEntryCodec], not just this engine's text dump).
 */
class ColumnCollationTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun parseOne(sql: String): Statement = Parser(Lexer(sql).tokenize()).parseProgram().single()

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun explain(sql: String): List<String> = (run(sql).single() as ExplainResult).plan

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)
    private fun bool(value: Boolean) = Value.BooleanValue(value)

    private fun seedNames() {
        run("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("INSERT INTO people VALUES (1, 'Bob'), (2, 'bob'), (3, 'Alice'), (4, 'Zoe')")
    }

    @Test
    fun bareEqualityHonorsTheColumnsDeclaredCollationWithoutAnExplicitClause() {
        seedNames()
        assertEquals(bool(true), select("SELECT name = 'BOB' FROM people WHERE id = 1").rows.single().single())
        assertEquals(
            listOf(int(1), int(2)),
            select("SELECT id FROM people WHERE name = 'BOB' ORDER BY id").rows.map { it.single() }
        )
    }

    @Test
    fun explicitCollateInTheQueryOverridesTheColumnsDeclaredCollation() {
        seedNames()
        assertEquals(bool(false), select("SELECT name COLLATE BINARY = 'BOB' FROM people WHERE id = 1").rows.single().single())
    }

    @Test
    fun explicitCollateOnTheOtherOperandStillOverridesADeclaredCollationOnThisOne() {
        seedNames()
        // 'BOB' COLLATE BINARY on the right wins over people.name's own declared NOCASE on the left.
        assertEquals(bool(false), select("SELECT name = 'BOB' COLLATE BINARY FROM people WHERE id = 1").rows.single().single())
    }

    @Test
    fun columnWithNoDeclaredCollationStaysBinaryByDefault() {
        run("CREATE TABLE plain (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO plain VALUES (1, 'Bob')")
        assertEquals(bool(false), select("SELECT name = 'BOB' FROM plain WHERE id = 1").rows.single().single())
    }

    @Test
    fun declaredCollationAppliesInAnInList() {
        seedNames()
        assertEquals(bool(true), select("SELECT name IN ('BOB', 'other') FROM people WHERE id = 1").rows.single().single())
    }

    @Test
    fun declaredCollationAppliesInBetween() {
        run("CREATE TABLE words (id INTEGER PRIMARY KEY, w TEXT COLLATE NOCASE)")
        run("INSERT INTO words VALUES (1, 'm')")
        assertEquals(bool(true), select("SELECT w BETWEEN 'A' AND 'Z' FROM words").rows.single().single())
    }

    @Test
    fun declaredCollationAppliesToASearchedCaseOperandMatch() {
        run("CREATE TABLE words (id INTEGER PRIMARY KEY, w TEXT COLLATE NOCASE)")
        run("INSERT INTO words VALUES (1, 'YES')")
        assertEquals(
            listOf(listOf(text("matched"))),
            select("SELECT CASE w WHEN 'yes' THEN 'matched' ELSE 'no' END FROM words").rows
        )
    }

    @Test
    fun declaredCollationAppliesToDefaultOrderBySortOrder() {
        seedNames()
        // No explicit `ORDER BY name COLLATE NOCASE` needed - the column's own declared NOCASE
        // already interleaves case. 'Bob' (id 1) and 'bob' (id 2) compare equal under NOCASE, so
        // the sort (stable) keeps them in their original scan-order relative position.
        val result = select("SELECT name FROM people ORDER BY name")
        assertEquals(listOf("Alice", "Bob", "bob", "Zoe").map { listOf(text(it)) }, result.rows)
    }

    @Test
    fun explicitCollateInOrderByOverridesTheColumnsDeclaredCollation() {
        seedNames()
        val result = select("SELECT name FROM people ORDER BY name COLLATE BINARY")
        assertEquals(listOf("Alice", "Bob", "Zoe", "bob").map { listOf(text(it)) }, result.rows)
    }

    @Test
    fun declaredCollationAppliesInAWindowFunctionsOrderBy() {
        seedNames()
        val result = select("SELECT name, ROW_NUMBER() OVER (ORDER BY name) AS rn FROM people ORDER BY rn")
        assertEquals(
            listOf("Alice" to 1L, "Bob" to 2L, "bob" to 3L, "Zoe" to 4L).map { (name, rn) -> listOf(text(name), int(rn)) },
            result.rows
        )
    }

    @Test
    fun declaredCollationAppliesInGroupBysOwnOrderBy() {
        seedNames()
        // GROUP BY itself now also dedups through name's declared NOCASE (closing the scope cut this
        // test used to document), so 'Bob' and 'bob' collapse into a single group - the group keeps
        // whichever row it saw first in scan order ('Bob', id 1). Only three groups remain, and the
        // ordering of those groups (GroupAggregation's own ORDER BY handling) is still under test.
        val result = select("SELECT name FROM people GROUP BY name ORDER BY name")
        assertEquals(listOf("Alice", "Bob", "Zoe").map { listOf(text(it)) }, result.rows)
    }

    @Test
    fun groupByDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        seedNames()
        val result = select("SELECT name, COUNT(*) FROM people GROUP BY name ORDER BY name")
        assertEquals(
            listOf("Alice" to 1L, "Bob" to 2L, "Zoe" to 1L).map { (name, count) -> listOf(text(name), int(count)) },
            result.rows
        )
    }

    @Test
    fun groupByWithNoDeclaredCollationStillDedupsByBinaryEquality() {
        run("CREATE TABLE plain (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO plain VALUES (1, 'Bob'), (2, 'bob')")
        val result = select("SELECT name, COUNT(*) FROM plain GROUP BY name ORDER BY name")
        assertEquals(listOf("Bob" to 1L, "bob" to 1L).map { (name, count) -> listOf(text(name), int(count)) }, result.rows)
    }

    @Test
    fun explicitGroupByCollateOverridesTheColumnsDeclaredCollation() {
        seedNames()
        val result = select("SELECT name COLLATE BINARY, COUNT(*) FROM people GROUP BY name COLLATE BINARY ORDER BY name COLLATE BINARY")
        assertEquals(
            listOf("Alice" to 1L, "Bob" to 1L, "Zoe" to 1L, "bob" to 1L).map { (name, count) -> listOf(text(name), int(count)) },
            result.rows
        )
    }

    @Test
    fun selectDistinctDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        seedNames()
        val result = select("SELECT DISTINCT name FROM people ORDER BY name")
        assertEquals(listOf("Alice", "Bob", "Zoe").map { listOf(text(it)) }, result.rows)
    }

    @Test
    fun selectDistinctStarDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        // A single-column table so DISTINCT *'s whole-row key is driven entirely by name's own
        // declared NOCASE - with more columns involved, any other differing column (like people's
        // own id) would already make every row distinct regardless of collation.
        run("CREATE TABLE tags (label TEXT COLLATE NOCASE)")
        run("INSERT INTO tags VALUES ('Bob'), ('bob'), ('Alice')")
        val result = select("SELECT DISTINCT * FROM tags ORDER BY label")
        assertEquals(listOf("Alice", "Bob").map { listOf(text(it)) }, result.rows)
    }

    @Test
    fun selectDistinctWithNoDeclaredCollationStaysBinary() {
        run("CREATE TABLE plain (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO plain VALUES (1, 'Bob'), (2, 'bob')")
        val result = select("SELECT DISTINCT name FROM plain ORDER BY name")
        assertEquals(listOf("Bob", "bob").map { listOf(text(it)) }, result.rows)
    }

    @Test
    fun selectDistinctOnDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        run("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE, hits INTEGER)")
        run("INSERT INTO people VALUES (1, 'Bob', 10), (2, 'bob', 20), (3, 'Alice', 5)")
        val result = select("SELECT DISTINCT ON (name) name, hits FROM people ORDER BY name, id")
        assertEquals(
            listOf("Alice" to 5L, "Bob" to 10L).map { (name, hits) -> listOf(text(name), int(hits)) },
            result.rows
        )
    }

    @Test
    fun countDistinctDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        seedNames()
        assertEquals(int(3), select("SELECT COUNT(DISTINCT name) FROM people").rows.single().single())
    }

    /**
     * `<>` forces [kestrel.engine.SelectRunner]'s nested-loop join path (only a `BinaryExpression`
     * `=` condition can hash-join at all), which reuses the same collation-aware
     * [kestrel.engine.Evaluator] as `WHERE`. See the `...InAnEqualityHashJoin` tests below for the
     * `=` counterpart, which takes the hash-join fast path instead.
     */
    @Test
    fun joiningOnColumnsWhereOnlyTheLeftSideDeclaresCollationHonorsItInANonEqualityJoinCondition() {
        run("CREATE TABLE left_t (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("CREATE TABLE right_t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO left_t VALUES (1, 'Bob'), (2, 'Zoe')")
        run("INSERT INTO right_t VALUES (1, 'BOB')")
        assertEquals(
            listOf(listOf(int(2))),
            select("SELECT left_t.id FROM left_t JOIN right_t ON left_t.name <> right_t.name").rows
        )
    }

    @Test
    fun joiningOnColumnsWhereOnlyTheRightSideDeclaresCollationFallsBackToTheRightWhenTheLeftHasNone() {
        run("CREATE TABLE left_t (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE right_t (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("INSERT INTO left_t VALUES (1, 'Bob'), (2, 'Zoe')")
        run("INSERT INTO right_t VALUES (1, 'BOB')")
        assertEquals(
            listOf(listOf(int(2))),
            select("SELECT left_t.id FROM left_t JOIN right_t ON left_t.name <> right_t.name").rows
        )
    }

    /**
     * `=` takes [kestrel.engine.SelectRunner]'s hash-join fast path (`hashJoinKeys`/`executeJoin`),
     * which must bucket [Value.TextValue] keys through the exact same [kestrel.engine.pairCollation]
     * the nested-loop `<>` tests above resolve through [kestrel.engine.Evaluator] - otherwise the
     * "optimization" would silently change which rows match. `right_t`'s undeclared `name` still
     * only matches its own literal case ('bob'), so this also proves the fast path is not simply
     * ignoring case altogether.
     */
    @Test
    fun joiningOnColumnsWhereOnlyTheLeftSideDeclaresCollationHonorsItInAnEqualityHashJoin() {
        run("CREATE TABLE left_t (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("CREATE TABLE right_t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO left_t VALUES (1, 'Bob'), (2, 'Zoe')")
        run("INSERT INTO right_t VALUES (1, 'BOB'), (2, 'bob')")
        assertEquals(
            listOf(listOf(int(1), int(1)), listOf(int(1), int(2))),
            select("SELECT left_t.id, right_t.id FROM left_t JOIN right_t ON left_t.name = right_t.name ORDER BY right_t.id").rows
        )
    }

    @Test
    fun joiningOnColumnsWhereOnlyTheRightSideDeclaresCollationHonorsItInAnEqualityHashJoin() {
        run("CREATE TABLE left_t (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE right_t (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("INSERT INTO left_t VALUES (1, 'BOB'), (2, 'Zoe')")
        run("INSERT INTO right_t VALUES (1, 'Bob')")
        assertEquals(
            listOf(listOf(int(1))),
            select("SELECT left_t.id FROM left_t JOIN right_t ON left_t.name = right_t.name").rows
        )
    }

    @Test
    fun explicitCollateOnAnEqualityHashJoinConditionOverridesTheColumnsDeclaredCollation() {
        run("CREATE TABLE left_t (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("CREATE TABLE right_t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO left_t VALUES (1, 'Bob')")
        run("INSERT INTO right_t VALUES (1, 'BOB')")
        assertEquals(
            emptyList(),
            select("SELECT left_t.id FROM left_t JOIN right_t ON left_t.name COLLATE BINARY = right_t.name").rows
        )
    }

    @Test
    fun equalityHashJoinWithNoDeclaredCollationOnEitherSideStaysBinary() {
        run("CREATE TABLE left_t (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE right_t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO left_t VALUES (1, 'Bob')")
        run("INSERT INTO right_t VALUES (1, 'BOB')")
        assertEquals(
            emptyList(),
            select("SELECT left_t.id FROM left_t JOIN right_t ON left_t.name = right_t.name").rows
        )
    }

    @Test
    fun declaredCollationAppliesInsideAnOnConflictDoUpdateAssignment() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE, was_bob BOOLEAN)")
        run("INSERT INTO t VALUES (1, 'Bob', FALSE)")
        run("INSERT INTO t (id, name) VALUES (1, 'x') ON CONFLICT (id) DO UPDATE SET was_bob = (name = 'BOB')")
        assertEquals(bool(true), select("SELECT was_bob FROM t WHERE id = 1").rows.single().single())
    }

    @Test
    fun declaredCollationAppliesInAMergeOnCondition() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE, hits INTEGER)")
        run("CREATE TABLE staging (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO accounts VALUES (1, 'Bob', 0)")
        run("INSERT INTO staging VALUES (2, 'BOB')")
        run("MERGE INTO accounts USING staging ON accounts.name = staging.name WHEN MATCHED THEN UPDATE SET hits = hits + 1")
        assertEquals(int(1), select("SELECT hits FROM accounts WHERE id = 1").rows.single().single())
    }

