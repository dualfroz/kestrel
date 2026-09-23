package kestrel.store.sql

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

class DiskExecutorDistinctTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun int(value: Long) = Value.IntegerValue(value)

    private fun text(value: String) = Value.TextValue(value)

    private class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
            private set
        var executor = DiskExecutor(catalog)
            private set

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun exec(sql: String): StatementResult = run(sql).single()
        fun select(sql: String): SelectResult = exec(sql) as SelectResult

        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-distinct-data"), tempFile("kestrel-disk-distinct-wal"))

    private fun Database.seedSales() {
        run("CREATE TABLE sales (dept TEXT, region TEXT, amount INTEGER)")
        run(
            "INSERT INTO sales VALUES " +
                "('eng', 'eu', 100), ('eng', 'us', 200), ('eng', 'eu', 100), " +
                "('ops', 'eu', 50), ('ops', 'eu', 70), ('ops', 'eu', 50)"
        )
    }

    @Test
    fun distinctRemovesDuplicateOutputRows() {
        val db = newDatabase()
        db.seedSales()
        val result = db.select("SELECT DISTINCT dept FROM sales ORDER BY dept")
        assertEquals(listOf(listOf(text("eng")), listOf(text("ops"))), result.rows)
    }

    @Test
    fun distinctStarDedupsOnEveryColumn() {
        val db = newDatabase()
        db.seedSales()
        val result = db.select("SELECT DISTINCT * FROM sales ORDER BY dept, region, amount")
        assertEquals(4, result.rows.size)
    }

    @Test
    fun distinctWithLimitCountsDistinctRowsNotSourceRows() {
        val db = newDatabase()
        db.seedSales()
        val result = db.select("SELECT DISTINCT dept FROM sales ORDER BY dept LIMIT 1")
        assertEquals(listOf(listOf(text("eng"))), result.rows)
    }

    @Test
    fun distinctWithOffsetSkipsDistinctRowsNotSourceRows() {
        val db = newDatabase()
        db.seedSales()
        val result = db.select("SELECT DISTINCT dept FROM sales ORDER BY dept LIMIT 1 OFFSET 1")
        assertEquals(listOf(listOf(text("ops"))), result.rows)
    }

    @Test
    fun distinctCombinesWithGroupBy() {
        val db = newDatabase()
        db.run("CREATE TABLE t (a INTEGER, b INTEGER)")
        db.run("INSERT INTO t VALUES (1, 10), (1, 20), (2, 10)")
        val result = db.select("SELECT DISTINCT a FROM t GROUP BY a, b ORDER BY a")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), result.rows)
    }

    @Test
    fun distinctOnKeepsFirstRowPerGroupInOrderByOrder() {
        val db = newDatabase()
        db.seedSales()
        val result = db.select("SELECT DISTINCT ON (dept) dept, amount FROM sales ORDER BY dept, amount DESC")
        assertEquals(listOf(listOf(text("eng"), int(200)), listOf(text("ops"), int(70))), result.rows)
    }

    @Test
    fun distinctOnWithoutOrderByUsesScanOrder() {
        val db = newDatabase()
        db.run("CREATE TABLE t (k TEXT, v INTEGER)")
        db.run("INSERT INTO t VALUES ('a', 1), ('a', 2), ('b', 3)")
        val result = db.select("SELECT DISTINCT ON (k) k, v FROM t")
        assertEquals(listOf(listOf(text("a"), int(1)), listOf(text("b"), int(3))), result.rows)
    }

    @Test
    fun distinctOnRequiresOrderByToStartWithItsOwnExpressions() {
        val db = newDatabase()
        db.seedSales()
        assertFailsWith<DiskExecutorException> {
            db.select("SELECT DISTINCT ON (dept) dept, amount FROM sales ORDER BY amount")
        }
    }

    @Test
    fun distinctOnRejectsGroupBy() {
        val db = newDatabase()
        db.seedSales()
        assertFailsWith<DiskExecutorException> {
            db.select("SELECT DISTINCT ON (dept) dept, SUM(amount) FROM sales GROUP BY dept")
        }
    }

    @Test
    fun distinctOnRejectsAWindowFunction() {
        val db = newDatabase()
        db.seedSales()
        assertFailsWith<DiskExecutorException> {
            db.select("SELECT DISTINCT ON (ROW_NUMBER() OVER (ORDER BY amount)) dept FROM sales")
        }
    }

    @Test
    fun distinctOnRejectsAnUnknownColumn() {
        val db = newDatabase()
        db.seedSales()
        assertFailsWith<DiskExecutorException> {
            db.select("SELECT DISTINCT ON (nope) dept FROM sales")
        }
    }

    @Test
    fun distinctSurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.seedSales()
        db.reopen()
        val result = db.select("SELECT DISTINCT dept FROM sales ORDER BY dept")
        assertEquals(listOf(listOf(text("eng")), listOf(text("ops"))), result.rows)
    }

    @Test
    fun distinctOnSurvivesClosingAndReopeningTheCatalog() {
        val db = newDatabase()
        db.seedSales()
        db.reopen()
        val result = db.select("SELECT DISTINCT ON (dept) dept, amount FROM sales ORDER BY dept, amount DESC")
        assertEquals(listOf(listOf(text("eng"), int(200)), listOf(text("ops"), int(70))), result.rows)
    }
}
