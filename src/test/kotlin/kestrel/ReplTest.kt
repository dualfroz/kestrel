package kestrel

import kestrel.cli.Repl
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ReplTest {
    private fun runScript(script: String): String {
        val output = StringBuilder()
        Repl(output, output).run(script.reader().buffered())
        return output.toString()
    }

    @Test
    fun gateScenarioRendersOrderedResult() {
        val script = """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER);
            INSERT INTO users VALUES (1, 'ada', 36), (2, 'linus', 54), (3, 'grace', 85);
            SELECT name, age FROM users WHERE age > 40 ORDER BY age DESC;
        """.trimIndent()
        val output = runScript(script)
        assertContains(output, "3 rows inserted")
        assertContains(output, "grace")
        assertContains(output, "linus")
        assertTrue(output.indexOf("grace") < output.indexOf("linus"), "grace should be listed before linus")
    }

    @Test
    fun metaTablesListsCreatedTables() {
        val output = runScript("CREATE TABLE alpha (id INTEGER);\nCREATE TABLE beta (id INTEGER);\n.tables\n")
        assertContains(output, "alpha")
        assertContains(output, "beta")
    }

    @Test
    fun metaSchemaPrintsDefinition() {
        val output = runScript("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);\n.schema users\n")
        assertContains(output, "CREATE TABLE users")
        assertContains(output, "id INTEGER PRIMARY KEY")
        assertContains(output, "name TEXT NOT NULL")
    }

    @Test
    fun parseErrorIsReportedAndReplContinues() {
        val output = runScript("SELCT bad;\nCREATE TABLE t (a INTEGER);\n.tables\n")
        assertContains(output, "Error:")
        assertContains(output, "t")
    }

    @Test
    fun exitStopsProcessing() {
        val output = runScript("CREATE TABLE seen (a INTEGER);\n.exit\nCREATE TABLE unseen (a INTEGER);\n")
        assertContains(output, "Table seen created")
        assertTrue(!output.contains("unseen"), "statements after .exit must not run")
    }
}
