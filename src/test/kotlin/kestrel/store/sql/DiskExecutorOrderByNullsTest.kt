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
 * `ORDER BY expr [ASC|DESC] [NULLS FIRST|NULLS LAST]` against the disk engine, mirroring
 * [kestrel.OrderByNullsTest]'s in-memory scenarios to prove [kestrel.engine.compareOrderKeyValues]
 * is genuinely shared rather than reimplemented per engine with divergent NULL handling.
 */
class DiskExecutorOrderByNullsTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun parse(sql: String): List<Statement> = Parser(Lexer(sql).tokenize()).parseProgram()

    private fun int(value: Long) = Value.IntegerValue(value)

    private inner class Database(dataFile: File, walFile: File) {
        val catalog = DiskCatalog.open(dataFile, walFile)
        val executor = DiskExecutor(catalog)

        fun run(sql: String): List<StatementResult> = parse(sql).map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-nulls-data"), tempFile("kestrel-disk-nulls-wal"))

    /** ids 2 and 4 are NULL; the rest ascend 10/20/30 out of id order so ASC/DESC actually reorder them. */
    private fun Database.seedScored() {
        run("CREATE TABLE scored (id INTEGER PRIMARY KEY, score INTEGER)")
        run("INSERT INTO scored VALUES (1, 10), (2, NULL), (3, 30), (4, NULL), (5, 20)")
    }

    @Test
    fun ascendingOrderByDefaultsToNullsFirstButNullsLastOverridesIt() {
        val db = newDatabase()
        db.seedScored()
        assertEquals(
            listOf(2, 4, 1, 5, 3).map { listOf(int(it.toLong())) },
            db.select("SELECT id FROM scored ORDER BY score").rows
        )
        assertEquals(
            listOf(1, 5, 3, 2, 4).map { listOf(int(it.toLong())) },
            db.select("SELECT id FROM scored ORDER BY score NULLS LAST").rows
        )
    }

    @Test
    fun descendingOrderByDefaultsToNullsLastButNullsFirstOverridesIt() {
        val db = newDatabase()
        db.seedScored()
        assertEquals(
            listOf(3, 5, 1, 2, 4).map { listOf(int(it.toLong())) },
            db.select("SELECT id FROM scored ORDER BY score DESC").rows
        )
        assertEquals(
            listOf(2, 4, 3, 5, 1).map { listOf(int(it.toLong())) },
            db.select("SELECT id FROM scored ORDER BY score DESC NULLS FIRST").rows
        )
    }

    @Test
    fun windowFunctionOrderByHonorsAnExplicitNullsLastClause() {
        val db = newDatabase()
        db.seedScored()
        val result = db.select(
            "SELECT id, ROW_NUMBER() OVER (ORDER BY score NULLS LAST) AS rn FROM scored ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(1)), listOf(int(2), int(4)), listOf(int(3), int(3)),
                listOf(int(4), int(5)), listOf(int(5), int(2))
            ),
            result.rows
        )
    }
}
