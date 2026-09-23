package kestrel.store.sql

import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiskExecutorNumericIndexKeyTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun int(value: Long) = Value.IntegerValue(value)

    private fun real(value: Double) = Value.RealValue(value)

    private fun dec(value: String) = Value.DecimalValue(BigDecimal(value))

    private class Database(dataFile: File, walFile: File) {
        val catalog = DiskCatalog.open(dataFile, walFile)
        val executor = DiskExecutor(catalog)

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-numeric-key-data"), tempFile("kestrel-numeric-key-wal"))

    private val indexedTables = listOf("keyed", "indexed")

    private fun Database.seed(columnType: String, literals: List<String>) {
        run("CREATE TABLE keyed (v $columnType PRIMARY KEY)")
        run("CREATE TABLE indexed (v $columnType)")
        run("CREATE INDEX idx_indexed_v ON indexed(v)")
        run("CREATE TABLE plain (v $columnType)")
        val rows = literals.joinToString(", ") { "($it)" }
        for (table in indexedTables + "plain") run("INSERT INTO $table VALUES $rows")
    }

    private fun Database.matching(table: String, predicate: String): List<Value> =
        select("SELECT v FROM $table WHERE $predicate ORDER BY v").rows.map { it.single() }

    private fun Database.indexLookups(table: String): Int {
        val diskTable = catalog.table(table)
        return diskTable.pointLookupCount + diskTable.rangeScanCount + diskTable.secondaryLookupCount
    }

    private fun Database.assertIndexed(predicate: String, expected: List<Value>) {
        assertEquals(expected, matching("plain", predicate), "plain: $predicate")
        for (table in indexedTables) {
            val before = indexLookups(table)
            assertEquals(expected, matching(table, predicate), "$table: $predicate")
            assertTrue(indexLookups(table) > before, "$table did not use its index for: $predicate")
        }
    }

    private fun Database.assertSameAsPlain(predicate: String, expected: List<Value>) {
        for (table in indexedTables + "plain") assertEquals(expected, matching(table, predicate), "$table: $predicate")
    }

    @Test
    fun decimalKeyMatchesIntegerAndRealLiteralsOnEquality() {
        val db = newDatabase()
        db.run("CREATE TABLE t (amount DECIMAL(10,2) PRIMARY KEY)")
        db.run("INSERT INTO t VALUES (1.00)")
        val table = db.catalog.table("t")
        val before = table.pointLookupCount
        assertEquals(listOf(listOf(dec("1.00"))), db.select("SELECT * FROM t WHERE amount = 1").rows)
        assertEquals(listOf(listOf(dec("1.00"))), db.select("SELECT * FROM t WHERE amount = 1.00").rows)
        assertEquals(before + 2, table.pointLookupCount)
    }

    @Test
    fun decimalIndexesAnswerEveryEqualityLiteralShape() {
        val db = newDatabase()
        db.seed("DECIMAL(10,2)", listOf("0.00", "0.50", "1.00", "1.50", "2.00", "2.50"))
        db.assertIndexed("v = 1", listOf(dec("1")))
        db.assertIndexed("v = 1.00", listOf(dec("1")))
        db.assertIndexed("v = 1.0", listOf(dec("1")))
        db.assertIndexed("1 = v", listOf(dec("1")))
        db.assertIndexed("v = 1.5", listOf(dec("1.5")))
        db.assertIndexed("v = 0", listOf(dec("0")))
        db.assertIndexed("v = 1.25", emptyList())
    }

    @Test
    fun decimalIndexesAnswerEveryRangeOperatorWithNumericLiterals() {
        val db = newDatabase()
        db.seed("DECIMAL(10,2)", listOf("0.50", "1.00", "1.50", "2.00", "2.50"))
        db.assertIndexed("v > 1", listOf(dec("1.5"), dec("2"), dec("2.5")))
        db.assertIndexed("v >= 1", listOf(dec("1"), dec("1.5"), dec("2"), dec("2.5")))
        db.assertIndexed("v < 2", listOf(dec("0.5"), dec("1"), dec("1.5")))
        db.assertIndexed("v <= 2", listOf(dec("0.5"), dec("1"), dec("1.5"), dec("2")))
        db.assertIndexed("v > 1.0 AND v < 2.5", listOf(dec("1.5"), dec("2")))
        db.assertIndexed("v >= 1 AND v <= 2.00", listOf(dec("1"), dec("1.5"), dec("2")))
        db.assertIndexed("2 > v", listOf(dec("0.5"), dec("1"), dec("1.5")))
        db.assertIndexed("v > 1.25 AND v <= 2.25", listOf(dec("1.5"), dec("2")))
    }

    @Test
    fun decimalIndexesDriveUpdateAndDeleteWithIntegerLiterals() {
        val db = newDatabase()
        db.seed("DECIMAL(10,2)", listOf("1.00", "2.00", "3.00"))
        for (table in indexedTables) {
            db.run("DELETE FROM $table WHERE v = 2")
            db.run("UPDATE $table SET v = 4 WHERE v >= 3")
            assertEquals(listOf(dec("1"), dec("4")), db.matching(table, "v > 0"))
        }
    }

    @Test
    fun preparedDecimalLookupBindsAnIntegerArgument() {
        val db = newDatabase()
        db.seed("DECIMAL(10,2)", listOf("1.00", "2.00"))
        db.run("PREPARE byAmount AS SELECT v FROM keyed WHERE v = \$1")
        assertEquals(listOf(listOf(dec("2"))), db.select("EXECUTE byAmount(2)").rows)
    }

    @Test
    fun compositeKeysAndIndexesCoerceTheirDecimalColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE ledger (dept TEXT, amount DECIMAL(10,2), PRIMARY KEY (dept, amount))")
        db.run("CREATE TABLE ledger_indexed (dept TEXT, amount DECIMAL(10,2))")
        db.run("CREATE INDEX idx_ledger_dept_amount ON ledger_indexed(dept, amount)")
        for (table in listOf("ledger", "ledger_indexed")) {
            db.run("INSERT INTO $table VALUES ('eng', 1.00), ('eng', 2.00), ('ops', 1.00)")
            val before = db.indexLookups(table)
            assertEquals(
                listOf(listOf(dec("1"))),
                db.select("SELECT amount FROM $table WHERE dept = 'eng' AND amount = 1").rows
            )
            assertEquals(
                listOf(listOf(dec("2"))),
                db.select("SELECT amount FROM $table WHERE dept = 'eng' AND amount > 1").rows
            )
            assertEquals(before + 2, db.indexLookups(table), table)
        }
    }

    @Test
    fun integerIndexesMatchIntegralRealLiterals() {
        val db = newDatabase()
        db.seed("INTEGER", listOf("1", "2", "3", "4"))
        db.assertIndexed("v = 2.0", listOf(int(2)))
        db.assertIndexed("2.0 = v", listOf(int(2)))
        db.assertIndexed("v > 2.0", listOf(int(3), int(4)))
        db.assertIndexed("v <= 2.0", listOf(int(1), int(2)))
    }

    @Test
    fun integerIndexesRoundFractionalRangeBoundsInward() {
        val db = newDatabase()
        db.seed("INTEGER", listOf("1", "2", "3", "4"))
        db.assertIndexed("v > 1.5", listOf(int(2), int(3), int(4)))
        db.assertIndexed("v >= 1.5", listOf(int(2), int(3), int(4)))
        db.assertIndexed("v < 2.5", listOf(int(1), int(2)))
        db.assertIndexed("v <= 2.5", listOf(int(1), int(2)))
        db.assertIndexed("1.5 < v AND v < 3.5", listOf(int(2), int(3)))
        db.assertIndexed("v > 0.5 AND v <= 2", listOf(int(1), int(2)))
        db.assertIndexed("3.5 >= v", listOf(int(1), int(2), int(3)))
    }

    @Test
    fun integerIndexesAreSkippedForAFractionalEquality() {
        val db = newDatabase()
        db.seed("INTEGER", listOf("1", "2", "3"))
        val lookupsBefore = indexedTables.map { db.indexLookups(it) }
        val scansBefore = indexedTables.map { db.catalog.table(it).scanCount }
        db.assertSameAsPlain("v = 2.5", emptyList())
        db.assertSameAsPlain("2.5 = v", emptyList())
        assertEquals(lookupsBefore, indexedTables.map { db.indexLookups(it) })
        assertEquals(scansBefore.map { it + 2 }, indexedTables.map { db.catalog.table(it).scanCount })
    }

    @Test
    fun integerIndexesCoerceBoundDecimalArguments() {
        val db = newDatabase()
        db.seed("INTEGER", listOf("1", "2", "3"))
        db.run("PREPARE equalTo AS SELECT v FROM keyed WHERE v = \$1")
        db.run("PREPARE below AS SELECT v FROM indexed WHERE v < \$1 ORDER BY v")
        assertEquals(listOf(listOf(int(2))), db.select("EXECUTE equalTo(CAST(2.00 AS DECIMAL(4,2)))").rows)
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), db.select("EXECUTE below(CAST(2.50 AS DECIMAL(4,2)))").rows)
    }

    @Test
    fun realIndexesMatchIntegerLiterals() {
        val db = newDatabase()
        db.seed("REAL", listOf("0.5", "1.0", "1.5", "2.0"))
        db.assertIndexed("v = 1", listOf(real(1.0)))
        db.assertIndexed("2 = v", listOf(real(2.0)))
        db.assertIndexed("v > 1", listOf(real(1.5), real(2.0)))
        db.assertIndexed("v >= 1", listOf(real(1.0), real(1.5), real(2.0)))
        db.assertIndexed("v < 2", listOf(real(0.5), real(1.0), real(1.5)))
        db.assertIndexed("v <= 2", listOf(real(0.5), real(1.0), real(1.5), real(2.0)))
        db.assertIndexed("1 < v AND v < 2", listOf(real(1.5)))
    }

    @Test
    fun realIndexesCoerceBoundDecimalArguments() {
        val db = newDatabase()
        db.seed("REAL", listOf("0.5", "1.0", "1.5", "2.0"))
        db.run("PREPARE equalTo AS SELECT v FROM keyed WHERE v = \$1")
        db.run("PREPARE atLeast AS SELECT v FROM indexed WHERE v >= \$1 ORDER BY v")
        assertEquals(listOf(listOf(real(1.5))), db.select("EXECUTE equalTo(CAST(1.50 AS DECIMAL(4,2)))").rows)
        assertEquals(listOf(listOf(real(1.5)), listOf(real(2.0))), db.select("EXECUTE atLeast(CAST(1.5 AS DECIMAL(3,1)))").rows)
    }
}
