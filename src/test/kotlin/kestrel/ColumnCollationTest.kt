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

    @Test
    fun declaredCollationAppliesToASetOperationsOrderByThroughAStarProjection() {
        seedNames()
        val result = select(
            "SELECT * FROM people WHERE id <= 2 UNION ALL SELECT * FROM people WHERE id > 2 ORDER BY name"
        )
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

    @Test
    fun intersectAllMatchesCaseVariantsThroughTheColumnsDeclaredCollationAndPreservesDuplicateCounts() {
        run("CREATE TABLE dup (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("INSERT INTO dup VALUES (1, 'Bob'), (2, 'Bob'), (3, 'bob'), (4, 'Zoe')")
        val result = select(
            "SELECT name FROM dup WHERE id IN (1, 2, 3) INTERSECT ALL SELECT name FROM dup WHERE id IN (2, 3)"
        )
        assertEquals(listOf(listOf(text("Bob")), listOf(text("Bob"))), result.rows)
    }

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

    @Test
    fun indexedEqualityHonorsAnRtrimDeclaredCollation() {
        run("CREATE TABLE tags (id INTEGER PRIMARY KEY, tag TEXT COLLATE RTRIM)")
        run("INSERT INTO tags VALUES (1, 'a'), (2, 'a  '), (3, 'b')")
        run("CREATE INDEX idxTag ON tags (tag)")
        val plan = explain("EXPLAIN SELECT id FROM tags WHERE tag = 'a'")
        assertTrue(plan.any { it.contains("Index Scan using idxTag") }, "expected index scan, got $plan")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), select("SELECT id FROM tags WHERE tag = 'a' ORDER BY id").rows)
    }

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

    @Test
    fun explicitCollateOverrideOnAnIndexedColumnFallsBackToASeqScanInsteadOfMisusingTheIndex() {
        seedNames()
        run("CREATE INDEX idxName ON people (name)")
        val plan = explain("EXPLAIN SELECT id FROM people WHERE name COLLATE BINARY = 'BOB'")
        assertTrue(plan.any { it.contains("Seq Scan") }, "expected seq scan fallback, got $plan")
        assertEquals(emptyList(), select("SELECT id FROM people WHERE name COLLATE BINARY = 'BOB'").rows)
    }

    @Test
    fun indexedEqualityWithNoDeclaredCollationStaysBinary() {
        run("CREATE TABLE plain3 (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO plain3 VALUES (1, 'Bob'), (2, 'bob')")
        run("CREATE INDEX idxPlain3 ON plain3 (name)")
        val plan = explain("EXPLAIN SELECT id FROM plain3 WHERE name = 'Bob'")
        assertTrue(plan.any { it.contains("Index Scan using idxPlain3") }, "got $plan")
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM plain3 WHERE name = 'Bob'").rows)
    }

    @Test
    fun primaryKeyUniquenessHonorsTheColumnsDeclaredCollation() {
        run("CREATE TABLE pk1 (name TEXT COLLATE NOCASE PRIMARY KEY)")
        run("INSERT INTO pk1 VALUES ('Bob')")
        assertFailsWith<KestrelException> { run("INSERT INTO pk1 VALUES ('bob')") }
    }

    @Test
    fun uniqueConstraintHonorsTheColumnsDeclaredCollation() {
        run("CREATE TABLE u1 (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE UNIQUE)")
        run("INSERT INTO u1 VALUES (1, 'Bob')")
        assertFailsWith<KestrelException> { run("INSERT INTO u1 VALUES (2, 'bob')") }
    }

    @Test
    fun createUniqueIndexHonorsTheColumnsDeclaredCollationAgainstPreexistingRows() {
        run("CREATE TABLE u2 (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("INSERT INTO u2 VALUES (1, 'Bob'), (2, 'bob')")
        assertFailsWith<KestrelException> { run("CREATE UNIQUE INDEX idxU2 ON u2 (name)") }
    }

    @Test
    fun uniqueIndexHonorsTheColumnsDeclaredCollationOnInsertAfterCreation() {
        run("CREATE TABLE u5 (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE)")
        run("CREATE UNIQUE INDEX idxU5 ON u5 (name)")
        run("INSERT INTO u5 VALUES (1, 'Bob')")
        assertFailsWith<KestrelException> { run("INSERT INTO u5 VALUES (2, 'bob')") }
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM u5").rows)
    }

    @Test
    fun uniqueConstraintWithNoDeclaredCollationStaysBinary() {
        run("CREATE TABLE u3 (id INTEGER PRIMARY KEY, name TEXT UNIQUE)")
        run("INSERT INTO u3 VALUES (1, 'Bob')")
        run("INSERT INTO u3 VALUES (2, 'bob')")
        assertEquals(2, select("SELECT id FROM u3").rows.size)
    }
}
