package kestrel.store.sql

import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

class ProbeTest {
    @Test
    fun probe() {
        val dataFile = Files.createTempFile("probe-data", ".db").toFile().also { it.deleteOnExit() }
        val walFile = Files.createTempFile("probe-wal", ".db").toFile().also { it.deleteOnExit() }
        val catalog = DiskCatalog.open(dataFile, walFile)
        val executor = DiskExecutor(catalog)
        fun run(sql: String) = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        run("CREATE TABLE t (amount DECIMAL(10,2) PRIMARY KEY)")
        run("INSERT INTO t VALUES (1.00)")
        val table = catalog.table("t")
        val before = table.pointLookupCount
        val result = run("SELECT * FROM t WHERE amount = 1")
        println("RESULT: " + result)
        println("pointLookupCount before=$before after=${table.pointLookupCount}")
    }
}
