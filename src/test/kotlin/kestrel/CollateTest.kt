package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.persistence.SqlWriter
import kestrel.sql.ast.Statement
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `expr COLLATE BINARY|NOCASE|RTRIM` (SQL:1999), the explicit, expression-level form - see
 * [kestrel.ColumnCollationTest] for a column's own declared `COLLATE` (`CREATE TABLE ... col TYPE
 * COLLATE name`), which only ever changes what a *bare* column reference compares with and is
 * always overridden by an explicit annotation like the one covered here. Covers every comparison
 * site [kestrel.engine.Evaluator] threads a resolved [kestrel.storage.Collation] through (`=`/`<>`/
 * `<`/`<=`/`>`/`>=`, `IN`, `BETWEEN`, a searched-`CASE`'s operand match) plus every sort that shares
 * [kestrel.engine.compareOrderKeyValues] (top-level ORDER BY, a window's `OVER (... ORDER BY
 * ...)`), and a persistence round-trip through [SqlWriter]. [kestrel.store.sql.DiskExecutorCollateTest]
 * mirrors the comparison and ORDER BY cases against the disk engine, proving the shared
 * [kestrel.engine.Evaluator]/[kestrel.engine.compareOrderKeyValues] code is genuinely shared, not
 * reimplemented per engine.
 */
class CollateTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun parseOne(sql: String): Statement = Parser(Lexer(sql).tokenize()).parseProgram().single()

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)
    private fun bool(value: Boolean) = Value.BooleanValue(value)

    private fun seedNames() {
        run("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO people VALUES (1, 'Bob'), (2, 'bob'), (3, 'Alice'), (4, 'alice  '), (5, 'Zoe')")
    }

    @Test
    fun equalityWithNocaseIgnoresAsciiCaseButPlainEqualityDoesNot() {
        seedNames()
        assertEquals(bool(true), select("SELECT name = 'BOB' COLLATE NOCASE FROM people WHERE id = 1").rows.single().single())
        assertEquals(bool(false), select("SELECT name = 'BOB' FROM people WHERE id = 1").rows.single().single())
    }

    @Test
    fun explicitCollateOnTheLeftOperandTakesPrecedenceOverTheRight() {
        seedNames()
        assertEquals(
            bool(true),
            select("SELECT name COLLATE NOCASE = 'BOB' COLLATE BINARY FROM people WHERE id = 1").rows.single().single()
        )
    }

    @Test
    fun explicitCollateOnTheRightOperandAppliesWhenTheLeftHasNone() {
        seedNames()
        assertEquals(bool(true), select("SELECT 'BOB' = name COLLATE NOCASE FROM people WHERE id = 1").rows.single().single())
    }

    @Test
    fun rtrimIgnoresOnlyTrailingSpacesNotLeadingOrInteriorOnes() {
        seedNames()
        assertEquals(
            bool(true),
            select("SELECT name = 'alice' COLLATE RTRIM FROM people WHERE id = 4").rows.single().single()
        )
        assertEquals(
            bool(false),
            select("SELECT name = ' alice' COLLATE RTRIM FROM people WHERE id = 4").rows.single().single()
        )
    }

    @Test
    fun lessThanAndGreaterThanHonorNocase() {
        seedNames()
        assertEquals(bool(false), select("SELECT 'APPLE' COLLATE NOCASE < 'apple' FROM people WHERE id = 1").rows.single().single())
        assertEquals(bool(true), select("SELECT 'APPLE' COLLATE NOCASE < 'banana' FROM people WHERE id = 1").rows.single().single())
    }

    @Test
    fun orderByCollateNocaseInterleavesUpperAndLowerCaseNamesByAsciiFoldedOrder() {
        seedNames()
        val result = select("SELECT name FROM people ORDER BY name COLLATE NOCASE, id")
        assertEquals(
            listOf("Alice", "alice  ", "Bob", "bob", "Zoe").map { listOf(text(it)) },
            result.rows
        )
    }

    @Test
    fun plainOrderByWithoutCollateStaysAsciiCodepointOrder() {
        seedNames()
        val result = select("SELECT name FROM people ORDER BY name")
        assertEquals(
            listOf("Alice", "Bob", "Zoe", "alice  ", "bob").map { listOf(text(it)) },
            result.rows
        )
    }

    @Test
    fun windowFunctionOverOrderByHonorsAnExplicitCollateClause() {
        seedNames()
        val result = select(
            "SELECT id, ROW_NUMBER() OVER (ORDER BY name COLLATE NOCASE) AS rn FROM people ORDER BY id"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(3)), listOf(int(2), int(4)), listOf(int(3), int(1)),
                listOf(int(4), int(2)), listOf(int(5), int(5))
            ),
            result.rows
        )
    }

    @Test
    fun inListHonorsTheOperandsExplicitCollate() {
        seedNames()
        assertEquals(
            bool(true),
            select("SELECT name COLLATE NOCASE IN ('BOB', 'other') FROM people WHERE id = 1").rows.single().single()
        )
        assertEquals(
            bool(false),
            select("SELECT name IN ('BOB', 'other') FROM people WHERE id = 1").rows.single().single()
        )
    }

    @Test
    fun betweenHonorsCollateIndependentlyOnEachSide() {
        run("CREATE TABLE words (id INTEGER PRIMARY KEY, w TEXT)")
        run("INSERT INTO words VALUES (1, 'M')")
        assertEquals(
            bool(true),
            select("SELECT w COLLATE NOCASE BETWEEN 'a' AND 'z' FROM words WHERE id = 1").rows.single().single()
        )
        assertEquals(
            bool(false),
            select("SELECT w BETWEEN 'a' AND 'z' FROM words WHERE id = 1").rows.single().single()
        )
    }

    @Test
    fun searchedCaseOperandMatchHonorsCollate() {
        run("CREATE TABLE words (id INTEGER PRIMARY KEY, w TEXT)")
        run("INSERT INTO words VALUES (1, 'YES')")
        assertEquals(
            listOf(listOf(text("matched"))),
            select("SELECT CASE w COLLATE NOCASE WHEN 'yes' THEN 'matched' ELSE 'no' END FROM words").rows
        )
        assertEquals(
            listOf(listOf(text("no"))),
            select("SELECT CASE w WHEN 'yes' THEN 'matched' ELSE 'no' END FROM words").rows
        )
    }

    @Test
    fun collateOnANonTextValueIsAcceptedButHasNoEffect() {
        run("CREATE TABLE nums (id INTEGER PRIMARY KEY, n INTEGER)")
        run("INSERT INTO nums VALUES (1, 5)")
        assertEquals(bool(true), select("SELECT n COLLATE NOCASE = 5 FROM nums").rows.single().single())
    }

    @Test
    fun collateExpressionInAWhereClauseRoundTripsThroughSqlWriterAndKeepsItsComparisonBehavior() {
        seedNames()
        val statement = parseOne("UPDATE people SET name = 'MATCHED' WHERE name COLLATE NOCASE = 'BOB'")
        val rendered = SqlWriter.write(statement)
        executor.execute(parseOne(rendered))
        assertEquals(
            listOf(listOf(int(1), text("MATCHED")), listOf(int(2), text("MATCHED"))),
            select("SELECT id, name FROM people WHERE name = 'MATCHED' ORDER BY id").rows
        )
    }
}
