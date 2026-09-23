package kestrel.store.sql

import kestrel.KestrelException
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

class DiskExecutorIndexTypeMismatchTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private class Database(dataFile: File, walFile: File) {
        val catalog = DiskCatalog.open(dataFile, walFile)
        val executor = DiskExecutor(catalog)

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-mismatch-data"), tempFile("kestrel-mismatch-wal"))

    private class ColumnShape(val type: String, val stored: String, val castedLiteral: String)

    private val shapes = listOf(
        ColumnShape("INTEGER", "5", "CAST(5 AS INTEGER)"),
        ColumnShape("REAL", "2.5", "CAST(2.5 AS REAL)"),
        ColumnShape("DECIMAL(10,2)", "CAST('2.50' AS DECIMAL(10,2))", "CAST('2.50' AS DECIMAL(10,2))"),
        ColumnShape("TEXT", "'5'", "CAST('5' AS TEXT)"),
        ColumnShape("BOOLEAN", "TRUE", "CAST(TRUE AS BOOLEAN)"),
        ColumnShape("DATE", "CAST('2024-01-05' AS DATE)", "CAST('2024-01-05' AS DATE)")
    )

    private val numericTypes = setOf("INTEGER", "REAL", "DECIMAL(10,2)")

    private fun comparable(column: ColumnShape, argument: ColumnShape): Boolean =
        column.type == argument.type || (column.type in numericTypes && argument.type in numericTypes)

    private val tables = listOf("keyed", "indexed", "plain")

    private fun Database.seed(shape: ColumnShape) {
        run("CREATE TABLE keyed (v ${shape.type} PRIMARY KEY, n INTEGER)")
        run("CREATE TABLE indexed (v ${shape.type}, n INTEGER)")
        run("CREATE INDEX idx_indexed_v ON indexed(v)")
        run("CREATE TABLE plain (v ${shape.type}, n INTEGER)")
        for (table in tables) run("INSERT INTO $table VALUES (${shape.stored}, 1)")
    }

    private fun Database.outcome(sql: String): String =
        try {
            "rows " + select(sql).rows.toString()
        } catch (error: KestrelException) {
            "error " + error.message
        }

    private fun Database.assertSameOutcome(predicate: String) {
        val expected = outcome("SELECT v FROM plain WHERE $predicate")
        assertTrue(expected.startsWith("error cannot compare"), "plain should reject: $predicate -> $expected")
        for (table in listOf("keyed", "indexed")) {
            assertEquals(expected, outcome("SELECT v FROM $table WHERE $predicate"), "$table: $predicate")
        }
    }

    @Test
    fun boundParametersOfEveryNonNumericMismatchFailTheSameWayWithAndWithoutAnIndex() {
        for (column in shapes) {
            val db = newDatabase()
            db.seed(column)
            val operators = mapOf("equal" to "v = \$1", "above" to "\$1 < v", "atMost" to "v <= \$1", "different" to "v <> \$1")
            for (table in tables) {
                for ((statement, predicate) in operators) db.run("PREPARE ${statement}_$table AS SELECT v FROM $table WHERE $predicate")
            }
            for (argument in shapes.filterNot { comparable(column, it) }) {
                for (statement in operators.keys) {
                    val expected = db.outcome("EXECUTE ${statement}_plain(${argument.castedLiteral})")
                    assertTrue(expected.startsWith("error cannot compare"), "${column.type} vs ${argument.type}: $expected")
                    for (table in listOf("keyed", "indexed")) {
                        assertEquals(
                            expected,
                            db.outcome("EXECUTE ${statement}_$table(${argument.castedLiteral})"),
                            "$table ${column.type} vs ${argument.type} ($statement)"
                        )
                    }
                }
            }
        }
    }

    @Test
    fun plainLiteralsOfAMismatchedTypeFailTheSameWayOnATextColumn() {
        val db = newDatabase()
        db.seed(shapes.single { it.type == "TEXT" })
        for (literal in listOf("5", "5.0", "TRUE")) {
            db.assertSameOutcome("v = $literal")
            db.assertSameOutcome("$literal = v")
            db.assertSameOutcome("v > $literal")
        }
    }

    @Test
    fun plainTextLiteralsFailTheSameWayOnNumericBooleanAndDateColumns() {
        for (column in shapes.filter { it.type != "TEXT" }) {
            val db = newDatabase()
            db.seed(column)
            db.assertSameOutcome("v = '5'")
            db.assertSameOutcome("'5' >= v")
        }
    }

    @Test
    fun aMismatchNextToAnIndexableConjunctStillFailsLikeTheFullScan() {
        val db = newDatabase()
        db.run("CREATE TABLE keyed (id INTEGER PRIMARY KEY, label TEXT)")
        db.run("CREATE TABLE indexed (id INTEGER, label TEXT)")
        db.run("CREATE INDEX idx_indexed_id ON indexed(id)")
        db.run("CREATE TABLE plain (id INTEGER, label TEXT)")
        for (table in tables) db.run("INSERT INTO $table VALUES (1, 'one'), (2, 'two')")
        db.assertSameOutcome("id = 99 AND label = 5")
        db.assertSameOutcome("label = 5 AND id = 99")
        db.assertSameOutcome("id > 50 AND label <> 5")
        db.assertSameOutcome("id = 1 AND label = TRUE")
    }

    @Test
    fun aMismatchAgainstAnEmptyTableReturnsNoRowsEverywhere() {
        val db = newDatabase()
        db.run("CREATE TABLE keyed (v TEXT PRIMARY KEY)")
        db.run("CREATE TABLE indexed (v TEXT)")
        db.run("CREATE INDEX idx_indexed_v ON indexed(v)")
        db.run("CREATE TABLE plain (v TEXT)")
        for (table in tables) assertEquals(emptyList(), db.select("SELECT v FROM $table WHERE v = 5").rows, table)
    }

    @Test
    fun aMismatchOnlyAgainstNullValuesReturnsNoRowsEverywhere() {
        val db = newDatabase()
        db.run("CREATE TABLE indexed (v TEXT)")
        db.run("CREATE INDEX idx_indexed_v ON indexed(v)")
        db.run("CREATE TABLE plain (v TEXT)")
        for (table in listOf("indexed", "plain")) {
            db.run("INSERT INTO $table VALUES (NULL)")
            assertEquals(emptyList(), db.select("SELECT v FROM $table WHERE v = 5").rows, table)
        }
    }

    @Test
    fun updateAndDeleteWithAMismatchFailLikeTheFullScanAndChangeNothing() {
        val db = newDatabase()
        db.seed(shapes.single { it.type == "TEXT" })
        for (statement in listOf("UPDATE %s SET n = 2 WHERE v = 5", "DELETE FROM %s WHERE v = 5")) {
            val expected = assertFailsWith<KestrelException> { db.run(statement.format("plain")) }.message
            for (table in listOf("keyed", "indexed")) {
                val actual = assertFailsWith<KestrelException>("$table: $statement") { db.run(statement.format(table)) }.message
                assertEquals(expected, actual, "$table: $statement")
            }
        }
        for (table in tables) {
            assertEquals(listOf(listOf(Value.TextValue("5"), Value.IntegerValue(1))), db.select("SELECT v, n FROM $table").rows, table)
        }
    }

    @Test
    fun compatibleComparisonsStillUseTheIndex() {
        val db = newDatabase()
        db.seed(shapes.single { it.type == "TEXT" })
        for (table in listOf("keyed", "indexed")) {
            val diskTable = db.catalog.table(table)
            val before = diskTable.pointLookupCount + diskTable.secondaryLookupCount
            assertEquals(listOf(listOf(Value.TextValue("5"))), db.select("SELECT v FROM $table WHERE v = '5'").rows)
            assertEquals(before + 1, diskTable.pointLookupCount + diskTable.secondaryLookupCount, table)
        }
    }
}
