package kestrel.store.sql

import kestrel.engine.ExplainResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
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
 * A column's own declared `COLLATE` (see [kestrel.catalog.Column.collation]) against the disk
 * engine, mirroring a subset of [kestrel.ColumnCollationTest]'s in-memory scenarios to prove the
 * same [kestrel.engine.Evaluator]/[kestrel.engine.ColumnResolver.declaredCollation] machinery
 * [DiskExecutor] reuses behaves identically here, plus the one thing that only matters for this
 * engine: the declared collation is stored as a byte in [DirectoryEntryCodec]'s own binary schema
 * encoding (not this engine's text dump, which does not exist here), so it must survive a real
 * file close/reopen, not just staying alive in the same [DiskCatalog] instance.
 */
class DiskExecutorColumnCollationTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun bool(value: Boolean) = Value.BooleanValue(value)
    private fun int(value: Long) = Value.IntegerValue(value)

    private class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
        var executor = DiskExecutor(catalog)

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult
        fun explain(sql: String): List<String> = (run(sql).single() as ExplainResult).plan

        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-column-collation-data"), tempFile("kestrel-disk-column-collation-wal"))

    private fun Database.seedNames() {
        run("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("INSERT INTO people VALUES (1, 'Bob'), (2, 'bob'), (3, 'Alice'), (4, 'Zoe')")
    }

    @Test
    fun bareEqualityHonorsTheColumnsDeclaredCollationWithoutAnExplicitClause() {
        val db = newDatabase()
        db.seedNames()
        assertEquals(
            listOf(int(1), int(2)),
            db.select("SELECT id FROM people WHERE name = 'BOB' ORDER BY id").rows.map { it.single() }
        )
    }

    @Test
    fun explicitCollateInTheQueryOverridesTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.seedNames()
        assertEquals(bool(false), db.select("SELECT name COLLATE BINARY = 'BOB' FROM people WHERE id = 1").rows.single().single())
    }

    @Test
    fun columnWithNoDeclaredCollationStaysBinaryByDefault() {
        val db = newDatabase()
        db.run("CREATE TABLE plain (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("INSERT INTO plain VALUES (1, 'Bob')")
        assertEquals(bool(false), db.select("SELECT name = 'BOB' FROM plain WHERE id = 1").rows.single().single())
    }

    @Test
    fun declaredCollationAppliesInsideAnOnConflictDoUpdateAssignment() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE, was_bob BOOLEAN)")
        db.run("INSERT INTO t VALUES (1, 'Bob', FALSE)")
        db.run("INSERT INTO t (id, name) VALUES (1, 'x') ON CONFLICT (id) DO UPDATE SET was_bob = (name = 'BOB')")
        assertEquals(bool(true), db.select("SELECT was_bob FROM t WHERE id = 1").rows.single().single())
    }

    @Test
    fun declaredCollationAppliesInAMergeOnCondition() {
        val db = newDatabase()
        db.run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE, hits INTEGER)")
        db.run("CREATE TABLE staging (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("INSERT INTO accounts VALUES (1, 'Bob', 0)")
        db.run("INSERT INTO staging VALUES (2, 'BOB')")
        db.run("MERGE INTO accounts USING staging ON accounts.name = staging.name WHEN MATCHED THEN UPDATE SET hits = hits + 1")
        assertEquals(int(1), db.select("SELECT hits FROM accounts WHERE id = 1").rows.single().single())
    }

    @Test
    fun declaredCollationAppliesInATriggerWhenClauseOnNew() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        db.run("CREATE TABLE hits (id INTEGER PRIMARY KEY, count INTEGER)")
        db.run("INSERT INTO t VALUES (1, 'Bob')")
        db.run("INSERT INTO hits VALUES (1, 0)")
        db.run(
            "CREATE TRIGGER bump AFTER UPDATE ON t FOR EACH ROW WHEN NEW.name = 'BOB' " +
                "BEGIN UPDATE hits SET count = count + 100 WHERE id = NEW.id; END"
        )
        db.run("UPDATE t SET name = 'bob' WHERE id = 1")
        assertEquals(int(100), db.select("SELECT count FROM hits WHERE id = 1").rows.single().single())
    }

    @Test
    fun declaredCollationAppliesToDefaultOrderBySortOrder() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select("SELECT name FROM people ORDER BY name")
        assertEquals(listOf("Alice", "Bob", "bob", "Zoe").map { listOf(Value.TextValue(it)) }, result.rows)
    }

    @Test
    fun explicitCollateInOrderByOverridesTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select("SELECT name FROM people ORDER BY name COLLATE BINARY")
        assertEquals(listOf("Alice", "Bob", "Zoe", "bob").map { listOf(Value.TextValue(it)) }, result.rows)
    }

    @Test
    fun declaredCollationAppliesInAWindowFunctionsOrderBy() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select("SELECT name, ROW_NUMBER() OVER (ORDER BY name) AS rn FROM people ORDER BY rn")
        assertEquals(
            listOf("Alice" to 1L, "Bob" to 2L, "bob" to 3L, "Zoe" to 4L).map { (name, rn) -> listOf(Value.TextValue(name), int(rn)) },
            result.rows
        )
    }

    /** Mirrors [kestrel.ColumnCollationTest]'s set-operation ORDER BY cases against the disk engine. */
    @Test
    fun declaredCollationAppliesToASetOperationsOrderByThroughAStarProjection() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select(
            "SELECT * FROM people WHERE id <= 2 UNION ALL SELECT * FROM people WHERE id > 2 ORDER BY name"
        )
        assertEquals(
            listOf(int(3) to "Alice", int(1) to "Bob", int(2) to "bob", int(4) to "Zoe")
                .map { (id, name) -> listOf(id, Value.TextValue(name)) },
            result.rows
        )
    }

    @Test
    fun explicitCollateInASetOperationsOrderByOverridesTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select(
            "SELECT * FROM people WHERE id <= 2 UNION ALL SELECT * FROM people WHERE id > 2 ORDER BY name COLLATE BINARY"
        )
        assertEquals(listOf("Alice", "Bob", "Zoe", "bob"), result.rows.map { (it[1] as Value.TextValue).value })
    }

    @Test
    fun declaredCollationDoesNotReachThroughADerivedTableInASetOperationsLeftBranch() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select(
            "SELECT * FROM (SELECT * FROM people) sub WHERE id <= 2 " +
                "UNION ALL SELECT * FROM (SELECT * FROM people) sub2 WHERE id > 2 " +
                "ORDER BY name"
        )
        assertEquals(listOf("Alice", "Bob", "Zoe", "bob"), result.rows.map { (it[1] as Value.TextValue).value })
    }

    /** Mirrors [kestrel.ColumnCollationTest.groupByDedupsCaseVariantsThroughTheColumnsDeclaredCollation]. */
    @Test
    fun groupByDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select("SELECT name, COUNT(*) FROM people GROUP BY name ORDER BY name")
        assertEquals(
            listOf("Alice" to 1L, "Bob" to 2L, "Zoe" to 1L).map { (name, count) -> listOf(Value.TextValue(name), int(count)) },
            result.rows
        )
    }

    /** Mirrors [kestrel.ColumnCollationTest.selectDistinctDedupsCaseVariantsThroughTheColumnsDeclaredCollation]. */
    @Test
    fun selectDistinctDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select("SELECT DISTINCT name FROM people ORDER BY name")
        assertEquals(listOf("Alice", "Bob", "Zoe").map { listOf(Value.TextValue(it)) }, result.rows)
    }

    /** Mirrors [kestrel.ColumnCollationTest.selectDistinctStarDedupsCaseVariantsThroughTheColumnsDeclaredCollation]. */
    @Test
    fun selectDistinctStarDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.run("CREATE TABLE tags (label TEXT COLLATE NOCASE)")
        db.run("INSERT INTO tags VALUES ('Bob'), ('bob'), ('Alice')")
        val result = db.select("SELECT DISTINCT * FROM tags ORDER BY label")
        assertEquals(listOf("Alice", "Bob").map { listOf(Value.TextValue(it)) }, result.rows)
    }

    /** Mirrors [kestrel.ColumnCollationTest.selectDistinctOnDedupsCaseVariantsThroughTheColumnsDeclaredCollation]. */
    @Test
    fun selectDistinctOnDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.run("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE, hits INTEGER)")
        db.run("INSERT INTO people VALUES (1, 'Bob', 10), (2, 'bob', 20), (3, 'Alice', 5)")
        val result = db.select("SELECT DISTINCT ON (name) name, hits FROM people ORDER BY name, id")
        assertEquals(
            listOf("Alice" to 5L, "Bob" to 10L).map { (name, hits) -> listOf(Value.TextValue(name), int(hits)) },
            result.rows
        )
    }

    /** Mirrors [kestrel.ColumnCollationTest.countDistinctDedupsCaseVariantsThroughTheColumnsDeclaredCollation]. */
    @Test
    fun countDistinctDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.seedNames()
        assertEquals(int(3), db.select("SELECT COUNT(DISTINCT name) FROM people").rows.single().single())
    }

    /** Mirrors [kestrel.ColumnCollationTest.plainUnionDedupsCaseVariantsThroughTheColumnsDeclaredCollation]. */
    @Test
    fun plainUnionDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select("SELECT name FROM people WHERE id = 1 UNION SELECT name FROM people WHERE id = 2")
        assertEquals(listOf(listOf(Value.TextValue("Bob"))), result.rows)
    }

    /** Mirrors [kestrel.ColumnCollationTest.intersectMatchesCaseVariantsThroughTheColumnsDeclaredCollation]. */
    @Test
    fun intersectMatchesCaseVariantsThroughTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select("SELECT name FROM people WHERE id = 1 INTERSECT SELECT name FROM people WHERE id = 2")
        assertEquals(listOf(listOf(Value.TextValue("Bob"))), result.rows)
    }

    /** Mirrors [kestrel.ColumnCollationTest.exceptExcludesCaseVariantsThroughTheColumnsDeclaredCollation]. */
    @Test
    fun exceptExcludesCaseVariantsThroughTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select("SELECT name FROM people WHERE id IN (1, 4) EXCEPT SELECT name FROM people WHERE id = 2")
        assertEquals(listOf(listOf(Value.TextValue("Zoe"))), result.rows)
    }

    /** Mirrors [kestrel.ColumnCollationTest.recursiveCteWithUnionDedupsCaseVariantsThroughTheColumnsDeclaredCollation]. */
    @Test
    fun recursiveCteWithUnionDedupsCaseVariantsThroughTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.run("CREATE TABLE origin (name TEXT COLLATE NOCASE)")
        db.run("INSERT INTO origin VALUES ('Bob')")
        db.run("CREATE TABLE steps (name TEXT COLLATE NOCASE, next_name TEXT COLLATE NOCASE)")
        db.run("INSERT INTO steps VALUES ('Bob', 'Carol'), ('Carol', 'bob')")
        val result = db.select(
            "WITH RECURSIVE walk(name) AS (" +
                "SELECT name FROM origin " +
                "UNION " +
                "SELECT steps.next_name FROM steps JOIN walk ON steps.name = walk.name" +
                ") SELECT name FROM walk ORDER BY name"
        )
        assertEquals(listOf(listOf(Value.TextValue("Bob")), listOf(Value.TextValue("Carol"))), result.rows)
    }

    /**
     * [DiskTable.rejectDuplicateUniqueConstraints] (declarative `UNIQUE`, heap-scanned on every
     * write, no backing index) folded through [kestrel.engine.collationFoldedUniquenessKey] -
     * mirrors [kestrel.ColumnCollationTest.uniqueConstraintHonorsTheColumnsDeclaredCollation].
     */
    @Test
    fun declarativeUniqueConstraintHonorsTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.run("CREATE TABLE u1 (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE UNIQUE)")
        db.run("INSERT INTO u1 VALUES (1, 'Bob')")
        assertFailsWith<DiskTableException> { db.run("INSERT INTO u1 VALUES (2, 'bob')") }
    }

    /** Control negative: a `UNIQUE` column with no declared `COLLATE` behaves exactly as before this session. */
    @Test
    fun declarativeUniqueConstraintWithNoDeclaredCollationStaysBinary() {
        val db = newDatabase()
        db.run("CREATE TABLE u3 (id INTEGER PRIMARY KEY, name TEXT UNIQUE)")
        db.run("INSERT INTO u3 VALUES (1, 'Bob')")
        db.run("INSERT INTO u3 VALUES (2, 'bob')")
        assertEquals(2, db.select("SELECT id FROM u3").rows.size)
    }

    /**
     * [DiskTable.backfillSecondaryIndex]'s pre-existing-duplicate check, also folded through
     * [kestrel.engine.collationFoldedUniquenessKey] - two rows already differing only by case
     * under a `NOCASE`-declared column must now fail `CREATE UNIQUE INDEX` cleanly instead of
     * silently building an index that lies about uniqueness.
     */
    @Test
    fun createUniqueIndexHonorsTheColumnsDeclaredCollationAgainstPreexistingRows() {
        val db = newDatabase()
        db.run("CREATE TABLE u2 (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        db.run("INSERT INTO u2 VALUES (1, 'Bob'), (2, 'bob')")
        assertFailsWith<DiskTableException> { db.run("CREATE UNIQUE INDEX idxU2 ON u2 (name)") }
    }

    @Test
    fun aDeclaredColumnCollationKeepsComparingTheSameWayAfterARealFileCloseAndReopen() {
        val db = newDatabase()
        db.seedNames()
        assertEquals(
            listOf(int(1), int(2)),
            db.select("SELECT id FROM people WHERE name = 'BOB' ORDER BY id").rows.map { it.single() }
        )
        db.reopen()
        assertEquals(
            listOf(int(1), int(2)),
            db.select("SELECT id FROM people WHERE name = 'BOB' ORDER BY id").rows.map { it.single() }
        )
    }

    /**
     * [DiskIndexAccessPlanner]'s equivalent of [kestrel.ColumnCollationTest.indexedEqualityHonorsTheColumnsDeclaredCollation]:
     * [DiskTable.findBySecondary] used to encode both the inserted row's key AND the search
     * literal straight to bytes via [kestrel.store.codec.KeyCodec], with no regard for the
     * column's declared `COLLATE` - so once `CREATE INDEX` made the planner choose an index scan
     * over the full scan the un-indexed [bareEqualityHonorsTheColumnsDeclaredCollationWithoutAnExplicitClause]
     * exercises, the case-variant row silently dropped out of the result instead of raising an
     * error, since [DiskExecutor] only re-checks WHERE against candidates the chosen access plan
     * already returned.
     */
    @Test
    fun indexedEqualityHonorsTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.seedNames()
        val scanned = db.select("SELECT id FROM people WHERE name = 'BOB' ORDER BY id").rows
        db.run("CREATE INDEX idxName ON people (name)")
        val plan = db.explain("EXPLAIN SELECT id FROM people WHERE name = 'BOB'")
        assertTrue(plan.any { it.contains("Index Scan using idxName") }, "expected index scan, got $plan")
        val indexed = db.select("SELECT id FROM people WHERE name = 'BOB' ORDER BY id").rows
        assertEquals(scanned, indexed)
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), indexed)
    }

    /** Mirrors [indexedEqualityHonorsTheColumnsDeclaredCollation] for a trailing-space collation instead of case. */
    @Test
    fun indexedEqualityHonorsAnRtrimDeclaredCollation() {
        val db = newDatabase()
        db.run("CREATE TABLE tags (id INTEGER PRIMARY KEY, tag TEXT COLLATE RTRIM)")
        db.run("INSERT INTO tags VALUES (1, 'a'), (2, 'a  '), (3, 'b')")
        db.run("CREATE INDEX idxTag ON tags (tag)")
        val plan = db.explain("EXPLAIN SELECT id FROM tags WHERE tag = 'a'")
        assertTrue(plan.any { it.contains("Index Scan using idxTag") }, "expected index scan, got $plan")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), db.select("SELECT id FROM tags WHERE tag = 'a' ORDER BY id").rows)
    }

    /**
     * Mirrors [kestrel.ColumnCollationTest.compositeIndexEqualityHonorsPerColumnDeclaredCollationIndependently]:
     * a composite secondary index's key is `encodeKey(first) ++ encodeKey(last) ++ rid` - each
     * column position must fold through its OWN declared collation independently (`first` is
     * NOCASE, `last` stays BINARY) rather than, say, folding the whole composite key uniformly or
     * not at all.
     */
    @Test
    fun compositeIndexEqualityHonorsPerColumnDeclaredCollationIndependently() {
        val db = newDatabase()
        db.run("CREATE TABLE contacts (id INTEGER PRIMARY KEY, first TEXT COLLATE NOCASE, last TEXT)")
        db.run("INSERT INTO contacts VALUES (1, 'Bob', 'Smith'), (2, 'bob', 'SMITH')")
        db.run("CREATE INDEX idxContact ON contacts (first, last)")
        val plan = db.explain("EXPLAIN SELECT id FROM contacts WHERE first = 'BOB' AND last = 'Smith'")
        assertTrue(plan.any { it.contains("Index Scan using idxContact") }, "got $plan")
        assertEquals(
            listOf(listOf(int(1))),
            db.select("SELECT id FROM contacts WHERE first = 'BOB' AND last = 'Smith'").rows
        )
    }

    /**
     * Safety net mirroring [kestrel.ColumnCollationTest.explicitCollateOverrideOnAnIndexedColumnFallsBackToASeqScanInsteadOfMisusingTheIndex]:
     * [DiskIndexAccessPlanner.asColumnPredicate] only recognizes a bare [kestrel.sql.ast.ColumnExpression],
     * so an explicit `COLLATE`-wrapped operand is excluded from pushdown entirely instead of being
     * (wrongly) served by an index whose physical bytes are folded for the column's OWN collation.
     */
    @Test
    fun explicitCollateOverrideOnAnIndexedColumnFallsBackToASeqScanInsteadOfMisusingTheIndex() {
        val db = newDatabase()
        db.seedNames()
        db.run("CREATE INDEX idxName ON people (name)")
        val plan = db.explain("EXPLAIN SELECT id FROM people WHERE name COLLATE BINARY = 'BOB'")
        assertTrue(plan.any { it.contains("Seq Scan") }, "expected seq scan fallback, got $plan")
        assertEquals(emptyList(), db.select("SELECT id FROM people WHERE name COLLATE BINARY = 'BOB'").rows)
    }

    /** Control negative: an index over a column with no declared `COLLATE` behaves exactly as before this session. */
    @Test
    fun indexedEqualityWithNoDeclaredCollationStaysBinary() {
        val db = newDatabase()
        db.run("CREATE TABLE plain3 (id INTEGER PRIMARY KEY, name TEXT)")
        db.run("INSERT INTO plain3 VALUES (1, 'Bob'), (2, 'bob')")
        db.run("CREATE INDEX idxPlain3 ON plain3 (name)")
        val plan = db.explain("EXPLAIN SELECT id FROM plain3 WHERE name = 'Bob'")
        assertTrue(plan.any { it.contains("Index Scan using idxPlain3") }, "got $plan")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT id FROM plain3 WHERE name = 'Bob'").rows)
    }

    /**
     * [DiskTable.findByPrimaryKey]'s point-lookup counterpart of [indexedEqualityHonorsTheColumnsDeclaredCollation]:
     * a single-column, inline `PRIMARY KEY` is backed by its own B+Tree exactly like a secondary
     * index, so `WHERE name = 'BOB'` against a `NOCASE`-declared `PRIMARY KEY` column must be
     * planned as [DiskAccessPlan.PrimaryKeyPoint] and still find the case-variant row.
     */
    @Test
    fun primaryKeyPointLookupHonorsTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.run("CREATE TABLE pk1 (name TEXT COLLATE NOCASE PRIMARY KEY, hits INTEGER)")
        db.run("INSERT INTO pk1 VALUES ('Bob', 10)")
        val plan = db.explain("EXPLAIN SELECT hits FROM pk1 WHERE name = 'BOB'")
        assertTrue(plan.any { it.contains("Primary Key Scan") }, "expected primary key point lookup, got $plan")
        assertEquals(listOf(listOf(int(10))), db.select("SELECT hits FROM pk1 WHERE name = 'BOB'").rows)
    }

    /**
     * [encodePrimaryKey]'s composite counterpart of [primaryKeyPointLookupHonorsTheColumnsDeclaredCollation]:
     * a table-level composite `PRIMARY KEY (region, code)` folds each position through ITS OWN
     * declared collation independently ([DiskAccessPlan.CompositePrimaryKeyPoint] mirrors
     * [compositeIndexEqualityHonorsPerColumnDeclaredCollationIndependently] but for the PK's own
     * B+Tree rather than a secondary index) - `region` is NOCASE (matches both rows) but `code`
     * stays plain BINARY (only an exact-case match counts), so only row 1 survives both conjuncts.
     */
    @Test
    fun compositePrimaryKeyPointLookupHonorsPerColumnDeclaredCollationIndependently() {
        val db = newDatabase()
        db.run("CREATE TABLE zones (region TEXT COLLATE NOCASE, code TEXT, hits INTEGER, PRIMARY KEY (region, code))")
        db.run("INSERT INTO zones VALUES ('EU', 'west', 1), ('eu', 'WEST', 2)")
        val plan = db.explain("EXPLAIN SELECT hits FROM zones WHERE region = 'eu' AND code = 'west'")
        assertTrue(plan.any { it.contains("Primary Key Scan") }, "expected composite primary key point lookup, got $plan")
        assertEquals(listOf(listOf(int(1))), db.select("SELECT hits FROM zones WHERE region = 'eu' AND code = 'west'").rows)
    }

    /**
     * [DiskTable.rangeByPrimaryKey]'s composite counterpart of [rangeQueryOnAPrimaryKeyHonorsTheColumnsDeclaredCollation]:
     * a table-level composite `PRIMARY KEY (order_id, sku)` ranged on its LAST column (`sku`, the
     * only shape [DiskAccessPlan.CompositePrimaryKeyRange] plans - see its own doc) after an
     * equality prefix on `order_id` (plain BINARY, irrelevant here) still folds the bound through
     * `sku`'s own declared `NOCASE` collation, same as the single-column case - `'Mango'` only
     * survives `sku > 'lemon'` once both sides are canonicalized to `'MANGO' > 'LEMON'`.
     */
    @Test
    fun rangeQueryOnTheLastColumnOfACompositePrimaryKeyAfterAnEqualityPrefixHonorsTheColumnsDeclaredCollation() {
        val db = newDatabase()
        db.run("CREATE TABLE order_lines4 (order_id INTEGER, sku TEXT COLLATE NOCASE, PRIMARY KEY (order_id, sku))")
        db.run("INSERT INTO order_lines4 VALUES (1, 'Apple'), (1, 'Mango'), (1, 'kiwi'), (2, 'Zebra')")
        val plan = db.explain("EXPLAIN SELECT sku FROM order_lines4 WHERE order_id = 1 AND sku > 'lemon'")
        assertTrue(plan.any { it.contains("Primary Key Range Scan") }, "expected primary key range scan, got $plan")
        assertEquals(
            listOf(listOf(Value.TextValue("Mango"))),
            db.select("SELECT sku FROM order_lines4 WHERE order_id = 1 AND sku > 'lemon'").rows
        )
    }

