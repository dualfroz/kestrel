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
    fun mergePrintsHowManyRowsWereInsertedUpdatedAndDeleted() {
        val script = """
            CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER);
            CREATE TABLE staging (id INTEGER, amount INTEGER);
            INSERT INTO accounts VALUES (1, 100), (2, 200);
            INSERT INTO staging VALUES (1, 10), (2, -999), (3, 5);
            MERGE INTO accounts USING staging ON accounts.id = staging.id
                WHEN MATCHED AND accounts.balance + staging.amount < 0 THEN DELETE
                WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount
                WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount);
        """.trimIndent()
        val output = runScript(script)
        assertContains(output, "1 row inserted, 1 row updated, 1 row deleted")
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
    fun metaSchemaListsUniqueIndexesOnTheTable() {
        val output = runScript(
            "CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL);\n" +
                "CREATE UNIQUE INDEX idx_email ON users (email);\n" +
                ".schema users\n"
        )
        assertContains(output, "CREATE UNIQUE INDEX idx_email ON users (email);")
    }

    @Test
    fun metaSchemaListsCompositeIndexesOnTheTable() {
        val output = runScript(
            "CREATE TABLE orders (id INTEGER PRIMARY KEY, region TEXT, seq INTEGER);\n" +
                "CREATE INDEX idx_region_seq ON orders (region, seq);\n" +
                ".schema orders\n"
        )
        assertContains(output, "CREATE INDEX idx_region_seq ON orders (region, seq);")
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

    /**
     * The REPL buffers input line by line and (before this was fixed) treated ANY line ending in
     * `;` as "statement complete" - a trigger body's inner `INSERT ... ;` would have tripped that
     * check and been parsed as an incomplete CREATE TRIGGER, well before the closing `END;` was
     * even typed. Feeding the trigger definition across several `readLine()` calls (rather than as
     * one line) is the point of this test.
     */
    @Test
    fun multiLineCreateTriggerIsNotExecutedPrematurelyOnAnInnerSemicolon() {
        val script = """
            CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER);
            CREATE TABLE audit (order_id INTEGER);
            CREATE TRIGGER log_insert
            AFTER INSERT ON orders
            FOR EACH ROW
            BEGIN
              INSERT INTO audit VALUES (NEW.id);
            END;
            INSERT INTO orders VALUES (1, 100);
            SELECT order_id FROM audit;
        """.trimIndent()
        val output = runScript(script)
        assertTrue(!output.contains("Error:"), output)
        assertContains(output, "Trigger log_insert created")
        assertContains(output, "1 row")
    }

    @Test
    fun metaTriggersListsCreatedTriggers() {
        val output = runScript(
            "CREATE TABLE t (id INTEGER);\n" +
                "CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN DELETE FROM t WHERE 1 = 0; END;\n" +
                ".triggers\n"
        )
        assertContains(output, "log")
    }

    @Test
    fun metaSequencesListsCreatedSequencesAndReportsEmpty() {
        val empty = runScript(".sequences\n")
        assertContains(empty, "no sequences")
        val output = runScript("CREATE SEQUENCE seq;\n.sequences\n")
        assertContains(output, "Sequence seq created")
        assertContains(output, "seq")
    }

    @Test
    fun createAlterAndDropSequencePrintTheirOwnConfirmations() {
        val output = runScript(
            """
            CREATE SEQUENCE seq;
            ALTER SEQUENCE seq RESTART WITH 5;
            DROP SEQUENCE seq;
        """.trimIndent()
        )
        assertContains(output, "Sequence seq created")
        assertContains(output, "Sequence seq altered")
        assertContains(output, "Sequence seq dropped")
    }

    @Test
    fun nextvalDrivenAutoIncrementWorksEndToEndThroughTheRepl() {
        val script = """
            CREATE SEQUENCE order_ids;
            CREATE TABLE orders (id INTEGER PRIMARY KEY DEFAULT NEXTVAL('order_ids'), item TEXT);
            INSERT INTO orders (item) VALUES ('a'), ('b');
            SELECT id, item FROM orders ORDER BY id;
        """.trimIndent()
        val output = runScript(script)
        assertTrue(!output.contains("Error:"), output)
        assertContains(output, "2 rows inserted")
        assertContains(output, "2 rows")
    }

    @Test
    fun insertReturningPrintsTheAffectedRowsAsATableAfterTheInsertConfirmation() {
        val script = """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            INSERT INTO users VALUES (1, 'ada') RETURNING id, name;
        """.trimIndent()
        val output = runScript(script)
        assertContains(output, "1 row inserted")
        assertTrue(
            output.indexOf("1 row inserted") < output.indexOf("ada"),
            "the RETURNING table should be printed after the insert confirmation"
        )
        assertContains(output, "ada")
    }

    @Test
    fun updateAndDeleteReturningAlsoPrintTheirRowsAfterTheirOwnConfirmation() {
        val script = """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            INSERT INTO users VALUES (1, 'ada');
            UPDATE users SET name = 'grace' WHERE id = 1 RETURNING name;
            DELETE FROM users WHERE id = 1 RETURNING id;
        """.trimIndent()
        val output = runScript(script)
        assertContains(output, "1 row updated")
        assertContains(output, "grace")
        assertContains(output, "1 row deleted")
    }

    @Test
    fun mergeReturningPrintsTheAffectedRowsAsATableAfterTheMergeConfirmation() {
        val script = """
            CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER);
            CREATE TABLE staging (id INTEGER, amount INTEGER);
            INSERT INTO accounts VALUES (1, 100);
            INSERT INTO staging VALUES (1, 10), (2, 5);
            MERGE INTO accounts USING staging ON accounts.id = staging.id
                WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount
                WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount)
                RETURNING id, MERGE_ACTION() AS kind;
        """.trimIndent()
        val output = runScript(script)
        assertContains(output, "1 row inserted, 1 row updated, 0 rows deleted")
        assertTrue(
            output.indexOf("1 row inserted, 1 row updated, 0 rows deleted") < output.indexOf("UPDATE"),
            "the RETURNING table should be printed after the merge confirmation"
        )
        assertContains(output, "UPDATE")
        assertContains(output, "INSERT")
    }

    @Test
    fun prepareExecuteAndDeallocatePrintTheirOwnConfirmations() {
        val script = """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            INSERT INTO users VALUES (1, 'ada');
            PREPARE byId AS SELECT name FROM users WHERE id = $1;
            EXECUTE byId(1);
            DEALLOCATE byId;
        """.trimIndent()
        val output = runScript(script)
        assertContains(output, "Prepared statement byId created")
        assertContains(output, "ada")
        assertContains(output, "Prepared statement byId deallocated")
    }

    @Test
    fun metaPreparedListsAndThenForgetsAPreparedStatementName() {
        val before = runScript(
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            PREPARE byId AS SELECT name FROM users WHERE id = $1;
            .prepared
            """.trimIndent()
        )
        assertContains(before, "byId")

        val after = runScript(
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            .prepared
            """.trimIndent()
        )
        assertContains(after, "no prepared statements")
    }
}
