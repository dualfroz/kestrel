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

    @Test
    fun declaredCollationAppliesInATriggerWhenClauseOnNew() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("CREATE TABLE hits (id INTEGER PRIMARY KEY, count INTEGER)")
        run("INSERT INTO t VALUES (1, 'Bob')")
        run("INSERT INTO hits VALUES (1, 0)")
        run(
            "CREATE TRIGGER bump AFTER UPDATE ON t FOR EACH ROW WHEN NEW.name = 'BOB' " +
                "BEGIN UPDATE hits SET count = count + 100 WHERE id = NEW.id; END"
        )
        run("UPDATE t SET name = 'bob' WHERE id = 1")
        assertEquals(int(100), select("SELECT count FROM hits WHERE id = 1").rows.single().single())
    }

    /**
     * `UNION`/`INTERSECT`/`EXCEPT`'s own `ORDER BY` ([kestrel.engine.SelectRunner.
     * declaredSetOperationCollation]) - the last named gap left by #66 ("multiKeyComparator-owa
     * sciezka UNION/INTERSECT/EXCEPT ORDER BY dalej binarna"). A `SELECT *` branch's declared
     * collation is traced back to the real table each output column comes from.
     */
    @Test
    fun declaredCollationAppliesToASetOperationsOrderByThroughAStarProjection() {
        seedNames()
        val result = select(
            "SELECT * FROM people WHERE id <= 2 UNION ALL SELECT * FROM people WHERE id > 2 ORDER BY name"
        )
        // Combined pre-sort order is (1,Bob),(2,bob),(3,Alice),(4,Zoe) - NOCASE ranks Bob/bob equal,
        // so the stable sort keeps Bob (scanned first) ahead of bob.
        assertEquals(
            listOf(int(3) to "Alice", int(1) to "Bob", int(2) to "bob", int(4) to "Zoe").map { (id, name) -> listOf(id, text(name)) },
            result.rows
        )
    }

    @Test
    fun declaredCollationAppliesToASetOperationsOrderByThroughAnAliasedProjection() {
        seedNames()
        val result = select(
            "SELECT name AS n FROM people WHERE id <= 2 UNION ALL SELECT name AS n FROM people WHERE id > 2 ORDER BY n"
        )
        assertEquals(listOf("Alice", "Bob", "bob", "Zoe").map { listOf(text(it)) }, result.rows)
    }

    @Test
    fun explicitCollateInASetOperationsOrderByOverridesTheColumnsDeclaredCollation() {
        seedNames()
        val result = select(
            "SELECT * FROM people WHERE id <= 2 UNION ALL SELECT * FROM people WHERE id > 2 ORDER BY name COLLATE BINARY"
        )
        assertEquals(listOf("Alice", "Bob", "Zoe", "bob"), result.rows.map { (it[1] as Value.TextValue).value })
    }

    /**
     * The declared collation of an output column joined from a second `FROM` source is found past
     * the first source's own column count - proves `declaredSetOperationCollation`'s per-source
     * offset walk, not just the single-table case above.
     */
    @Test
    fun declaredCollationAppliesToASetOperationsOrderByAcrossAJoinedSecondTable() {
        run("CREATE TABLE ids (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE names (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("INSERT INTO ids VALUES (1), (2), (3), (4)")
        run("INSERT INTO names VALUES (1, 'Bob'), (2, 'bob'), (3, 'Alice'), (4, 'Zoe')")
        val result = select(
            "SELECT * FROM ids JOIN names ON ids.id = names.id WHERE ids.id <= 2 " +
                "UNION ALL SELECT * FROM ids JOIN names ON ids.id = names.id WHERE ids.id > 2 " +
                "ORDER BY name"
        )
        assertEquals(listOf("Alice", "Bob", "bob", "Zoe"), result.rows.map { (it[2] as Value.TextValue).value })
    }

    /**
     * Still-open scope cut (see [kestrel.engine.SelectRunner.declaredSetOperationCollation]'s own
     * doc comment): a set operation's left branch reading through a derived table rather than a
     * real table directly reports no declared collation, so its ORDER BY stays binary even though
     * the underlying table declares NOCASE - symmetric with the pre-existing view/CTE scope cut for
     * a plain SELECT's own ORDER BY.
     */
    @Test
    fun declaredCollationDoesNotReachThroughADerivedTableInASetOperationsLeftBranch() {
        seedNames()
        val result = select(
            "SELECT * FROM (SELECT * FROM people) sub WHERE id <= 2 " +
                "UNION ALL SELECT * FROM (SELECT * FROM people) sub2 WHERE id > 2 " +
                "ORDER BY name"
        )
        assertEquals(listOf("Alice", "Bob", "Zoe", "bob"), result.rows.map { (it[1] as Value.TextValue).value })
    }

    /**
     * `UNION`/`INTERSECT`/`EXCEPT`'s own multiset dedup ([kestrel.engine.SelectRunner.
     * runSetOperation]) - the largest piece of the scope-cut #67/#68 left open ("UNION/CTE dedup ...
     * dalej uzywaja bajtowej rownosci"). Reuses [kestrel.engine.SelectRunner.declaredSetOperationCollation],
     * the same resolver the ORDER BY tests above already exercise, but now folded into the dedup key
     * itself rather than only the comparator.
     */
    @Test
    fun plainUnionDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        seedNames()
        val result = select("SELECT name FROM people WHERE id = 1 UNION SELECT name FROM people WHERE id = 2")
        assertEquals(listOf(listOf(text("Bob"))), result.rows)
    }

    @Test
    fun plainUnionWithNoDeclaredCollationKeepsCaseVariantsDistinct() {
        run("CREATE TABLE plain2 (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO plain2 VALUES (1, 'Bob'), (2, 'bob')")
        val result = select("SELECT name FROM plain2 WHERE id = 1 UNION SELECT name FROM plain2 WHERE id = 2")
        assertEquals(listOf(listOf(text("Bob")), listOf(text("bob"))), result.rows)
    }

    @Test
    fun intersectMatchesCaseVariantsThroughTheColumnsDeclaredCollation() {
        seedNames()
        val result = select("SELECT name FROM people WHERE id = 1 INTERSECT SELECT name FROM people WHERE id = 2")
        assertEquals(listOf(listOf(text("Bob"))), result.rows)
    }

    @Test
    fun exceptExcludesCaseVariantsThroughTheColumnsDeclaredCollation() {
        seedNames()
        val result = select("SELECT name FROM people WHERE id IN (1, 4) EXCEPT SELECT name FROM people WHERE id = 2")
        assertEquals(listOf(listOf(text("Zoe"))), result.rows)
    }

    /**
     * `INTERSECT ALL`/`EXCEPT ALL` preserve duplicate counts through [kestrel.engine.SelectRunner.
     * multisetFilter], which grouped its right-hand side by raw `Value` equality before this session
     * - proving the count itself (not just presence/absence) is now grouped through the declared
     * collation: 3 case-variant 'Bob' rows on the left, 2 on the right, min(3,2)=2 kept.
     */
    @Test
    fun intersectAllMatchesCaseVariantsThroughTheColumnsDeclaredCollationAndPreservesDuplicateCounts() {
        run("CREATE TABLE dup (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("INSERT INTO dup VALUES (1, 'Bob'), (2, 'Bob'), (3, 'bob'), (4, 'Zoe')")
        val result = select(
            "SELECT name FROM dup WHERE id IN (1, 2, 3) INTERSECT ALL SELECT name FROM dup WHERE id IN (2, 3)"
        )
        assertEquals(listOf(listOf(text("Bob")), listOf(text("Bob"))), result.rows)
    }

    /**
     * A recursive CTE's own fixed-point dedup ([kestrel.engine.SelectRunner.buildCteScope]), folded
     * through [kestrel.engine.SelectRunner.declaredRecursiveCteCollation] resolved against the
     * anchor (base) term - mirrors [declaredSetOperationCollation] but for the base/recursive-term
     * split instead of a set operation's left/right. `steps` chains 'Bob' -> 'Carol' -> 'bob'; without
     * collation-aware dedup 'bob' would be treated as a new row (it differs from 'Bob' byte-for-byte)
     * and the walk would keep going instead of recognizing it as the already-visited start node.
     */
    @Test
    fun recursiveCteWithUnionDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        run("CREATE TABLE origin (name TEXT COLLATE NOCASE)")
        run("INSERT INTO origin VALUES ('Bob')")
        run("CREATE TABLE steps (name TEXT COLLATE NOCASE, next_name TEXT COLLATE NOCASE)")
        run("INSERT INTO steps VALUES ('Bob', 'Carol'), ('Carol', 'bob')")
        val result = select(
            "WITH RECURSIVE walk(name) AS (" +
                "SELECT name FROM origin " +
                "UNION " +
                "SELECT steps.next_name FROM steps JOIN walk ON steps.name = walk.name" +
                ") SELECT name FROM walk ORDER BY name"
        )
        assertEquals(listOf(listOf(text("Bob")), listOf(text("Carol"))), result.rows)
    }

    @Test
    fun createTableColumnCollationRoundTripsThroughSqlWriter() {
        val createStatement = parseOne("CREATE TABLE people2 (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)") as CreateTableStatement
        val rendered = SqlWriter.write(createStatement)
        check("COLLATE NOCASE" in rendered) { "expected COLLATE NOCASE in rendered CREATE TABLE: $rendered" }
        executor.execute(parseOne(rendered))
        run("INSERT INTO people2 VALUES (1, 'Bob')")
        assertEquals(bool(true), select("SELECT name = 'BOB' FROM people2").rows.single().single())
    }

    /**
     * A real correctness bug, not a documented scope-cut: [kestrel.catalog.SortedIndex] used to
     * sort/compare its keys with plain byte order regardless of the indexed column's own declared
     * `COLLATE` (see [kestrel.catalog.Catalog.collationsFor]/[kestrel.catalog.IndexKeyOrdering]),
     * so once [kestrel.engine.IndexAccessPlanner] chose to serve `WHERE name = 'BOB'` from an index
     * on a `NOCASE`-declared `name` column, the lookup silently missed a stored `'bob'` row - unlike
     * every other collation gap in this codebase (all documented scope-cuts, never wrong answers),
     * this one returned fewer rows than a full scan of the exact same table/predicate would, because
     * [kestrel.engine.SelectRunner.materialize]'s post-scan `WHERE` filter only re-checks candidates
     * the chosen access path already produced - it cannot recover a row the index never handed back.
     */
    @Test
    fun indexedEqualityHonorsTheColumnsDeclaredCollation() {
        seedNames()
        val scanned = select("SELECT id FROM people WHERE name = 'BOB' ORDER BY id").rows
        run("CREATE INDEX idxName ON people (name)")
        val plan = explain("EXPLAIN SELECT id FROM people WHERE name = 'BOB'")
        assertTrue(plan.any { it.contains("Index Scan using idxName") }, "expected index scan, got $plan")
        val indexed = select("SELECT id FROM people WHERE name = 'BOB' ORDER BY id").rows
        assertEquals(scanned, indexed)
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), indexed)
    }

    /** Mirrors [indexedEqualityHonorsTheColumnsDeclaredCollation] for a trailing-space collation instead of case. */
    @Test
    fun indexedEqualityHonorsAnRtrimDeclaredCollation() {
        run("CREATE TABLE tags (id INTEGER PRIMARY KEY, tag TEXT COLLATE RTRIM)")
        run("INSERT INTO tags VALUES (1, 'a'), (2, 'a  '), (3, 'b')")
        run("CREATE INDEX idxTag ON tags (tag)")
        val plan = explain("EXPLAIN SELECT id FROM tags WHERE tag = 'a'")
        assertTrue(plan.any { it.contains("Index Scan using idxTag") }, "expected index scan, got $plan")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), select("SELECT id FROM tags WHERE tag = 'a' ORDER BY id").rows)
    }

    /**
     * A composite index's [kestrel.catalog.IndexKeyOrdering] carries one [kestrel.storage.Collation]
     * per column position - `first` is `NOCASE` (matches both rows) but `last` stays plain `BINARY`
     * (only `'Smith'` exactly matches), so only row 1 should survive both conjuncts. This only passes
     * if each column position is folded through its OWN collation independently rather than, say, the
     * whole composite key sharing a single collation.
     */
    @Test
    fun compositeIndexEqualityHonorsPerColumnDeclaredCollationIndependently() {
        run("CREATE TABLE contacts (id INTEGER PRIMARY KEY, first TEXT COLLATE NOCASE, last TEXT)")
        run("INSERT INTO contacts VALUES (1, 'Bob', 'Smith'), (2, 'bob', 'SMITH')")
        run("CREATE INDEX idxContact ON contacts (first, last)")
        val plan = explain("EXPLAIN SELECT id FROM contacts WHERE first = 'BOB' AND last = 'Smith'")
        assertTrue(plan.any { it.contains("Index Scan using idxContact") }, "got $plan")
        assertEquals(
            listOf(listOf(int(1))),
            select("SELECT id FROM contacts WHERE first = 'BOB' AND last = 'Smith'").rows
        )
    }

    /**
     * An explicit `COLLATE` at the query level overrides the column's own declared collation (see
     * [kestrel.engine.pairCollation]) - [kestrel.engine.IndexAccessPlanner.asColumnPredicate] only
     * recognizes a bare [kestrel.sql.ast.ColumnExpression], so a `COLLATE`-wrapped operand is simply
     * excluded from pushdown rather than being (wrongly) served by an index built for the column's
     * OWN collation. Proves that safety net: falling back to `Seq Scan` here, not silently pushing
     * the query's `BINARY` override through a `NOCASE`-sorted index.
     */
    @Test
    fun explicitCollateOverrideOnAnIndexedColumnFallsBackToASeqScanInsteadOfMisusingTheIndex() {
        seedNames()
        run("CREATE INDEX idxName ON people (name)")
        val plan = explain("EXPLAIN SELECT id FROM people WHERE name COLLATE BINARY = 'BOB'")
        assertTrue(plan.any { it.contains("Seq Scan") }, "expected seq scan fallback, got $plan")
        assertEquals(emptyList(), select("SELECT id FROM people WHERE name COLLATE BINARY = 'BOB'").rows)
    }

    /** Control negative: an index over a column with no declared `COLLATE` behaves exactly as before this session. */
    @Test
    fun indexedEqualityWithNoDeclaredCollationStaysBinary() {
        run("CREATE TABLE plain3 (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO plain3 VALUES (1, 'Bob'), (2, 'bob')")
        run("CREATE INDEX idxPlain3 ON plain3 (name)")
        val plan = explain("EXPLAIN SELECT id FROM plain3 WHERE name = 'Bob'")
        assertTrue(plan.any { it.contains("Index Scan using idxPlain3") }, "got $plan")
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM plain3 WHERE name = 'Bob'").rows)
    }

    /**
     * PRIMARY KEY uniqueness ([kestrel.engine.Executor.enforcePrimaryKeyUniqueness]) folded through
     * [kestrel.engine.collationFoldedUniquenessKey] - 'Bob'/'bob' now collide under a NOCASE-declared
     * PRIMARY KEY exactly like they already do for a plain equality comparison.
     */
    @Test
    fun primaryKeyUniquenessHonorsTheColumnsDeclaredCollation() {
        run("CREATE TABLE pk1 (name TEXT COLLATE NOCASE PRIMARY KEY)")
        run("INSERT INTO pk1 VALUES ('Bob')")
        assertFailsWith<KestrelException> { run("INSERT INTO pk1 VALUES ('bob')") }
    }

    /** [kestrel.engine.Executor.enforceUniqueConstraints], the declarative `UNIQUE` counterpart of the PK test above. */
    @Test
    fun uniqueConstraintHonorsTheColumnsDeclaredCollation() {
        run("CREATE TABLE u1 (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE UNIQUE)")
        run("INSERT INTO u1 VALUES (1, 'Bob')")
        assertFailsWith<KestrelException> { run("INSERT INTO u1 VALUES (2, 'bob')") }
    }

    /**
     * `CREATE UNIQUE INDEX` ([kestrel.engine.Executor.enforceUniqueIndexes]) checked against
     * pre-existing rows before the index is registered - two rows already differing only by case
     * under a `NOCASE`-declared column must now be rejected, matching what inserting either of them
     * fresh under an already-`UNIQUE`-constrained column would do.
     */
    @Test
    fun createUniqueIndexHonorsTheColumnsDeclaredCollationAgainstPreexistingRows() {
        run("CREATE TABLE u2 (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("INSERT INTO u2 VALUES (1, 'Bob'), (2, 'bob')")
        assertFailsWith<KestrelException> { run("CREATE UNIQUE INDEX idxU2 ON u2 (name)") }
    }

    /**
     * [kestrel.engine.Executor.enforceUniqueIndexes] guarding an ordinary `INSERT` against an
     * ALREADY-REGISTERED `CREATE UNIQUE INDEX` - distinct from
     * [createUniqueIndexHonorsTheColumnsDeclaredCollationAgainstPreexistingRows]'s backfill-time
     * check: here the index already exists and a fresh case-variant row is what must be rejected,
     * BEFORE the row is appended to the table (see `enforceUniqueIndexes`'s own doc comment on why
     * it does not just rely on [kestrel.catalog.SortedIndex.add]'s own, likewise collation-aware,
     * uniqueness check to catch this instead).
     */
    @Test
    fun uniqueIndexHonorsTheColumnsDeclaredCollationOnInsertAfterCreation() {
        run("CREATE TABLE u5 (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("CREATE UNIQUE INDEX idxU5 ON u5 (name)")
        run("INSERT INTO u5 VALUES (1, 'Bob')")
        assertFailsWith<KestrelException> { run("INSERT INTO u5 VALUES (2, 'bob')") }
        // Proves enforceUniqueIndexes itself rejected the row BEFORE table.rows.addAll ran, not
        // merely that some exception surfaced - u5 has no trigger, so withStatementAtomicity takes
        // no catalog snapshot here and could not have rolled back a row that got appended first.
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM u5").rows)
    }

    /** Control negative: a `UNIQUE` column with no declared `COLLATE` behaves exactly as before this session. */
    @Test
    fun uniqueConstraintWithNoDeclaredCollationStaysBinary() {
        run("CREATE TABLE u3 (id INTEGER PRIMARY KEY, name TEXT UNIQUE)")
        run("INSERT INTO u3 VALUES (1, 'Bob')")
        run("INSERT INTO u3 VALUES (2, 'bob')")
        assertEquals(2, select("SELECT id FROM u3").rows.size)
    }
}
