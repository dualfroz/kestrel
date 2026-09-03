package kestrel.store.sql

import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.ast.Statement
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `expr COLLATE BINARY|NOCASE|RTRIM` against the disk engine, mirroring a subset of
 * [kestrel.CollateTest]'s in-memory scenarios (equality, RTRIM, ORDER BY, IN) to prove the shared
 * [kestrel.engine.Evaluator]/[kestrel.engine.compareOrderKeyValues] code [kestrel.store.sql.
 * DiskExecutor] reuses directly behaves identically here, plus one disk-only scenario: a
 * COLLATE-bearing WHERE clause typed straight into the disk engine (never routed through
 * [kestrel.persistence.SqlWriter] - the disk engine has no text WAL to round-trip through) keeps
 * comparing the same way after a real file close/reopen.
 */
class DiskExecutorCollateTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun parse(sql: String): List<Statement> = Parser(Lexer(sql).tokenize()).parseProgram()

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)
    private fun bool(value: Boolean) = Value.BooleanValue(value)

    private class Database(val dataFile: File, val walFile: File) {
        var catalog = DiskCatalog.open(dataFile, walFile)
        var executor = DiskExecutor(catalog)

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult

        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-collate-data"), tempFile("kestrel-disk-collate-wal"))

    private fun Database.seedNames() {
        run("CREATE TABLE people (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO people VALUES (1, 'Bob'), (2, 'bob'), (3, 'Alice'), (4, 'alice  '), (5, 'Zoe')")
    }

    @Test
    fun equalityWithNocaseIgnoresAsciiCaseButPlainEqualityDoesNot() {
        val db = newDatabase()
        db.seedNames()
        assertEquals(bool(true), db.select("SELECT name = 'BOB' COLLATE NOCASE FROM people WHERE id = 1").rows.single().single())
        assertEquals(bool(false), db.select("SELECT name = 'BOB' FROM people WHERE id = 1").rows.single().single())
    }

    @Test
    fun rtrimIgnoresOnlyTrailingSpaces() {
        val db = newDatabase()
        db.seedNames()
        assertEquals(bool(true), db.select("SELECT name = 'alice' COLLATE RTRIM FROM people WHERE id = 4").rows.single().single())
        assertEquals(bool(false), db.select("SELECT name = 'alice' FROM people WHERE id = 4").rows.single().single())
    }

    @Test
    fun orderByCollateNocaseInterleavesUpperAndLowerCaseNames() {
        val db = newDatabase()
        db.seedNames()
        val result = db.select("SELECT name FROM people ORDER BY name COLLATE NOCASE, id")
        assertEquals(listOf("Alice", "alice  ", "Bob", "bob", "Zoe").map { listOf(text(it)) }, result.rows)
    }

    @Test
    fun inListHonorsTheOperandsExplicitCollate() {
        val db = newDatabase()
        db.seedNames()
        assertEquals(
            bool(true),
            db.select("SELECT name COLLATE NOCASE IN ('BOB', 'other') FROM people WHERE id = 1").rows.single().single()
        )
        assertEquals(
            bool(false),
            db.select("SELECT name IN ('BOB', 'other') FROM people WHERE id = 1").rows.single().single()
        )
    }

    @Test
    fun aCollateComparisonKeepsComparingTheSameWayAfterARealFileCloseAndReopen() {
        val db = newDatabase()
        db.seedNames()
        assertEquals(
            listOf(int(1), int(2)),
            db.select("SELECT id FROM people WHERE name COLLATE NOCASE = 'BOB' ORDER BY id").rows.map { it.single() }
        )
        db.reopen()
        assertEquals(
            listOf(int(1), int(2)),
            db.select("SELECT id FROM people WHERE name COLLATE NOCASE = 'BOB' ORDER BY id").rows.map { it.single() }
        )
    }
}
