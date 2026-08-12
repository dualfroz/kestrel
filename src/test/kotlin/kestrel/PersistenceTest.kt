package kestrel

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import java.nio.file.Files
import kestrel.cli.Repl
import kestrel.persistence.Persistence
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersistenceTest {
    private fun tempDir(): File = Files.createTempDirectory("kestrelData").toFile()

    private fun runScript(dir: File, script: String): String {
        val output = StringBuilder()
        Repl(output, output, Persistence(dir)).run(script.reader().buffered())
        return output.toString()
    }

    /**
     * Delivers [text] like a normal [StringReader], but throws instead of ever signalling EOF -
     * simulating a process that vanished (killed, crashed, power loss) right after its last
     * statement ran, never reaching either `.exit` or a graceful end of input. [Repl.run] only
     * checkpoints (see [Repl.finish]) on one of those two paths, so this is the one way to drive it
     * through every statement in [text] while proving NOTHING beyond the incremental WAL write each
     * statement already triggered on its own (via [kestrel.cli.Repl.recordForPersistence]) ever
     * makes it to disk - exactly the scenario a TEMP table's persistence exclusion must hold up
     * under, not just the far more common clean-`.exit`-then-checkpoint path every other test here
     * exercises.
     */
    private class CrashingReader(text: String) : Reader() {
        private val delegate = StringReader(text)
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            val read = delegate.read(cbuf, off, len)
            if (read == -1) throw IOException("simulated crash: process died before EOF")
            return read
        }
        override fun close() = delegate.close()
    }

    private fun runScriptThatCrashesBeforeItCanCheckpoint(dir: File, script: String) {
        val output = StringBuilder()
        assertFailsWith<IOException> {
            Repl(output, output, Persistence(dir)).run(BufferedReader(CrashingReader(script)))
        }
    }

    @Test
    fun dataSurvivesReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER);
            INSERT INTO users VALUES (1, 'ada', 36), (2, 'linus', 54);
            CREATE INDEX idxAge ON users (age);
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT name FROM users ORDER BY id ASC;\n.indexes\n.exit\n")
        assertContains(reopened, "ada")
        assertContains(reopened, "linus")
        assertContains(reopened, "idxAge")
    }

    @Test
    fun updatesAndDeletesSurviveReopen() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE t (id INTEGER PRIMARY KEY, score INTEGER);
            INSERT INTO t VALUES (1, 10), (2, 20), (3, 30);
            UPDATE t SET score = 99 WHERE id = 2;
            DELETE FROM t WHERE id = 3;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT id, score FROM t ORDER BY id ASC;\n.exit\n")
        assertContains(reopened, "99")
        assertTrue(!reopened.contains("30"), "deleted row must not come back")
    }

    @Test
    fun committedTransactionSurvivesButRollbackDoesNot() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE t (id INTEGER PRIMARY KEY);
            BEGIN;
            INSERT INTO t VALUES (1);
            COMMIT;
            BEGIN;
            INSERT INTO t VALUES (2);
            ROLLBACK;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT id FROM t ORDER BY id ASC;\n.exit\n")
        assertContains(reopened, "1")
        assertTrue(!reopened.contains("2"), "rolled back insert must not persist")
    }

    @Test
    fun aMergeSurvivesReopenWithBothItsUpdateAndItsInsertIntact() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER);
            CREATE TABLE staging (id INTEGER, amount INTEGER);
            INSERT INTO accounts VALUES (1, 100);
            INSERT INTO staging VALUES (1, 10), (2, 5);
            MERGE INTO accounts USING staging ON accounts.id = staging.id
                WHEN MATCHED THEN UPDATE SET balance = accounts.balance + staging.amount
                WHEN NOT MATCHED THEN INSERT (id, balance) VALUES (staging.id, staging.amount);
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT id, balance FROM accounts ORDER BY id ASC;\n.exit\n")
        assertContains(reopened, "110")
        assertContains(reopened, "5")
    }

    @Test
    fun rollbackToSavepointInsideACommittedTransactionDoesNotPersistTheUndoneInsert() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE t (id INTEGER PRIMARY KEY);
            BEGIN;
            INSERT INTO t VALUES (111);
            SAVEPOINT s1;
            INSERT INTO t VALUES (222);
            ROLLBACK TO SAVEPOINT s1;
            INSERT INTO t VALUES (333);
            COMMIT;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT id FROM t ORDER BY id ASC;\n.exit\n")
        assertContains(reopened, "111")
        assertContains(reopened, "333")
        assertTrue(!reopened.contains("222"), "insert undone by ROLLBACK TO SAVEPOINT must not persist")
    }

    @Test
    fun insertOnConflictDoUpdateSurvivesReopen() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER);
            INSERT INTO accounts VALUES (1, 100);
            INSERT INTO accounts VALUES (1, 50) ON CONFLICT (id) DO UPDATE SET balance = accounts.balance + excluded.balance;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT balance FROM accounts WHERE id = 1;\n.exit\n")
        assertContains(reopened, "150")
    }

    @Test
    fun walReplayReconstructsAnInsertOnConflictStatementCorrectly() {
        val dir = tempDir()
        val persistence = Persistence(dir)
        persistence.appendWal(
            listOf(
                "CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER);",
                "INSERT INTO accounts VALUES (1, 100);",
                "INSERT INTO accounts VALUES (1, 50) ON CONFLICT (id) DO UPDATE SET balance = accounts.balance + excluded.balance;"
            )
        )
        val output = StringBuilder()
        Repl(output, output, Persistence(dir)).run("SELECT balance FROM accounts WHERE id = 1;\n.exit\n".reader().buffered())
        assertContains(output.toString(), "150")
    }

    @Test
    fun walReplayRecoversWithoutCheckpoint() {
        val dir = tempDir()
        val persistence = Persistence(dir)
        persistence.appendWal(
            listOf(
                "CREATE TABLE t (id INTEGER);",
                "INSERT INTO t VALUES (1), (2);"
            )
        )
        val output = StringBuilder()
        Repl(output, output, Persistence(dir)).run("SELECT id FROM t ORDER BY id ASC;\n.exit\n".reader().buffered())
        assertContains(output.toString(), "1")
        assertContains(output.toString(), "2")
    }

    @Test
    fun viewDefinitionSurvivesReopenAfterCheckpointAndStaysLiveOverNewData() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER);
            INSERT INTO users VALUES (1, 'ada', 36), (2, 'linus', 54);
            CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 40;
            .exit
            """.trimIndent()
        )
        val afterCheckpoint = runScript(
            dir,
            """
            SELECT name FROM adults ORDER BY id ASC;
            INSERT INTO users VALUES (3, 'grace', 85);
            .exit
            """.trimIndent()
        )
        assertContains(afterCheckpoint, "linus")
        assertTrue(!afterCheckpoint.contains("ada"), "view predicate must exclude rows under the age threshold")

        val afterInsert = runScript(dir, "SELECT name FROM adults ORDER BY id ASC;\n.views\n.exit\n")
        assertContains(afterInsert, "grace")
        assertContains(afterInsert, "adults")
    }

    @Test
    fun dropViewSurvivesReopenWithoutACheckpoint() {
        val dir = tempDir()
        val persistence = Persistence(dir)
        persistence.appendWal(
            listOf(
                "CREATE TABLE t (id INTEGER);",
                "CREATE VIEW v AS SELECT id FROM t;",
                "DROP VIEW v;"
            )
        )
        val output = StringBuilder()
        Repl(output, output, Persistence(dir)).run(".views\n.exit\n".reader().buffered())
        assertContains(output.toString(), "no views")
    }

    @Test
    fun updatesUsingScalarFunctionsAndPredicatesSurviveReopen() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, bio TEXT);
            INSERT INTO users VALUES (1, 'ada', 'mathematician'), (2, 'linus', NULL), (3, 'grace', 'admiral');
            UPDATE users SET name = UPPER(name) WHERE bio IS NOT NULL;
            DELETE FROM users WHERE name IN ('linus') OR bio LIKE '%admiral';
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT name FROM users ORDER BY id ASC;\n.exit\n")
        assertContains(reopened, "ADA")
        assertTrue(!reopened.contains("linus"), "row matching NOT IN predicate must stay deleted")
        assertTrue(!reopened.contains("grace") && !reopened.contains("GRACE"), "row matching LIKE predicate must stay deleted")
    }

    @Test
    fun decimalAndDateColumnsSurviveReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE orders (id INTEGER PRIMARY KEY, amount DECIMAL(10, 2), placed DATE);
            INSERT INTO orders VALUES (1, 19.9, CAST('2024-03-01' AS DATE));
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT amount, placed FROM orders;\n.exit\n")
        assertContains(reopened, "19.90")
        assertContains(reopened, "2024-03-01")
    }

    @Test
    fun decimalAndDateColumnsSurviveReopenWithoutACheckpointViaWalReplay() {
        val dir = tempDir()
        val persistence = Persistence(dir)
        persistence.appendWal(
            listOf(
                "CREATE TABLE orders (id INTEGER PRIMARY KEY, amount DECIMAL(10, 2), placed DATE);",
                "INSERT INTO orders VALUES (1, CAST('42.5' AS DECIMAL(10, 2)), CAST('2024-03-01' AS DATE));"
            )
        )
        val output = StringBuilder()
        Repl(output, output, Persistence(dir)).run("SELECT amount, placed FROM orders;\n.exit\n".reader().buffered())
        assertContains(output.toString(), "42.50")
        assertContains(output.toString(), "2024-03-01")
    }

    @Test
    fun foreignKeyAndCheckConstraintsSurviveReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT);
            CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id) ON DELETE CASCADE, age INTEGER CHECK (age > 0));
            INSERT INTO departments VALUES (1, 'eng');
            INSERT INTO employees VALUES (1, 1, 30);
            .exit
            """.trimIndent()
        )
        val afterConstraintChecks = runScript(
            dir,
            """
            INSERT INTO employees VALUES (2, 99, 30);
            .exit
            """.trimIndent()
        )
        assertContains(afterConstraintChecks, "Error")
        assertContains(afterConstraintChecks, "FOREIGN KEY")

        val afterCascade = runScript(
            dir,
            """
            DELETE FROM departments WHERE id = 1;
            SELECT * FROM employees;
            .exit
            """.trimIndent()
        )
        assertContains(afterCascade, "0 rows")
    }

    @Test
    fun uniqueConstraintsSurviveReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE, a INTEGER, b INTEGER, UNIQUE (a, b));
            INSERT INTO users VALUES (1, 'ada@example.com', 1, 1);
            .exit
            """.trimIndent()
        )
        val afterReopen = runScript(
            dir,
            """
            INSERT INTO users VALUES (2, 'ada@example.com', 2, 2);
            .exit
            """.trimIndent()
        )
        assertContains(afterReopen, "Error")
        assertContains(afterReopen, "UNIQUE constraint")

        val afterCompositeViolation = runScript(
            dir,
            """
            INSERT INTO users VALUES (3, 'grace@example.com', 1, 1);
            .exit
            """.trimIndent()
        )
        assertContains(afterCompositeViolation, "Error")
        assertContains(afterCompositeViolation, "UNIQUE constraint")
    }

    @Test
    fun uniqueIndexSurvivesReopenAfterCheckpointAndKeepsEnforcingAndAccelerating() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT);
            INSERT INTO users VALUES (1, 'ada@example.com');
            CREATE UNIQUE INDEX idx_email ON users (email);
            .exit
            """.trimIndent()
        )
        val afterReopen = runScript(
            dir,
            """
            INSERT INTO users VALUES (2, 'ada@example.com');
            EXPLAIN SELECT id FROM users WHERE email = 'ada@example.com';
            .schema users
            .exit
            """.trimIndent()
        )
        assertContains(afterReopen, "Error")
        assertContains(afterReopen, "UNIQUE constraint")
        assertContains(afterReopen, "Index Scan using idx_email")
        assertContains(afterReopen, "CREATE UNIQUE INDEX idx_email ON users (email);")
    }

    @Test
    fun compositeUniqueIndexSurvivesReopenAfterCheckpointAndKeepsEnforcingAndAccelerating() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE orders (id INTEGER PRIMARY KEY, region TEXT, seq INTEGER);
            INSERT INTO orders VALUES (1, 'EU', 1);
            CREATE UNIQUE INDEX idx_region_seq ON orders (region, seq);
            .exit
            """.trimIndent()
        )
        val afterReopen = runScript(
            dir,
            """
            INSERT INTO orders VALUES (2, 'EU', 1);
            INSERT INTO orders VALUES (3, 'US', 1);
            EXPLAIN SELECT id FROM orders WHERE region = 'EU' AND seq = 1;
            .schema orders
            .exit
            """.trimIndent()
        )
        assertContains(afterReopen, "Error")
        assertContains(afterReopen, "UNIQUE constraint")
        assertContains(afterReopen, "Index Scan using idx_region_seq")
        assertContains(afterReopen, "CREATE UNIQUE INDEX idx_region_seq ON orders (region, seq);")
    }

    @Test
    fun compositePrimaryKeyAndForeignKeySurviveReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE orders (region TEXT, order_no INTEGER, PRIMARY KEY (region, order_no));
            CREATE TABLE line_items (region TEXT, order_no INTEGER, sku TEXT, FOREIGN KEY (region, order_no) REFERENCES orders (region, order_no) ON DELETE CASCADE);
            INSERT INTO orders VALUES ('eu', 1);
            INSERT INTO line_items VALUES ('eu', 1, 'SKU1');
            .exit
            """.trimIndent()
        )
        val duplicateRejected = runScript(dir, "INSERT INTO orders VALUES ('eu', 1);\n.exit\n")
        assertContains(duplicateRejected, "Error")
        assertContains(duplicateRejected, "UNIQUE constraint")

        val afterCascade = runScript(
            dir,
            """
            DELETE FROM orders WHERE region = 'eu';
            SELECT * FROM line_items;
            .exit
            """.trimIndent()
        )
        assertTrue(!afterCascade.contains("SKU1"), "cascade delete across a composite FK must survive reopen")
    }

    @Test
    fun alterTableAddColumnSurvivesReopenAfterCheckpointAndBackfillsAndDefaultsFutureInserts() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            INSERT INTO users VALUES (1, 'ada'), (2, 'linus');
            ALTER TABLE users ADD COLUMN status TEXT NOT NULL DEFAULT 'active';
            INSERT INTO users (id, name) VALUES (3, 'grace');
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT name, status FROM users ORDER BY id ASC;\n.exit\n")
        assertContains(reopened, "ada")
        assertContains(reopened, "active")
        val statusCount = Regex("active").findAll(reopened).count()
        assertEquals(3, statusCount, "all three rows (backfilled and freshly inserted) must carry the DEFAULT")
    }

    @Test
    fun generatedColumnSurvivesReopenAfterCheckpointAndKeepsComputingFutureInserts() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED);
            INSERT INTO t (a) VALUES (5);
            .exit
            """.trimIndent()
        )
        runScript(dir, "INSERT INTO t (a) VALUES (7);\n.exit\n")
        val reopened = runScript(dir, "SELECT b FROM t ORDER BY a ASC;\n.exit\n")
        assertContains(reopened, "10")
        assertContains(reopened, "14")
    }

    @Test
    fun generatedColumnSurvivesReopenWithoutACheckpointViaWalReplay() {
        val dir = tempDir()
        val persistence = Persistence(dir)
        persistence.appendWal(
            listOf(
                "CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED);",
                "INSERT INTO t (a) VALUES (5);"
            )
        )
        val output = StringBuilder()
        Repl(output, output, Persistence(dir)).run("SELECT b FROM t WHERE a = 5;\n.exit\n".reader().buffered())
        assertContains(output.toString(), "10")
    }

    @Test
    fun alterTableRenameAndDropColumnSurviveReopenWithoutACheckpointViaWalReplay() {
        val dir = tempDir()
        val persistence = Persistence(dir)
        persistence.appendWal(
            listOf(
                "CREATE TABLE t (id INTEGER PRIMARY KEY, bio TEXT, scratch TEXT);",
                "INSERT INTO t VALUES (1, 'mathematician', 'temp');",
                "ALTER TABLE t RENAME COLUMN bio TO biography;",
                "ALTER TABLE t DROP COLUMN scratch;"
            )
        )
        val output = StringBuilder()
        Repl(output, output, Persistence(dir)).run("SELECT biography FROM t WHERE id = 1;\n.exit\n".reader().buffered())
        assertContains(output.toString(), "mathematician")
    }

    @Test
    fun alterTableRenameToSurvivesReopenWithoutACheckpointViaWalReplay() {
        val dir = tempDir()
        val persistence = Persistence(dir)
        persistence.appendWal(
            listOf(
                "CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT);",
                "INSERT INTO t VALUES (1, 'ada');",
                "ALTER TABLE t RENAME TO people;"
            )
        )
        val output = StringBuilder()
        Repl(output, output, Persistence(dir)).run("SELECT name FROM people WHERE id = 1;\n.tables\n.exit\n".reader().buffered())
        val text = output.toString()
        assertContains(text, "ada")
        val tableLines = text.lines().map { it.trim() }
        assertTrue(tableLines.contains("people"), "renamed table must appear in .tables")
        assertTrue(tableLines.none { it.equals("t", ignoreCase = true) }, "old table name must not linger in .tables")
    }

    @Test
    fun alterTableRenameToPropagatesIntoADependentViewAcrossACheckpointReopen() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT);
            INSERT INTO departments VALUES (1, 'eng');
            CREATE VIEW v AS SELECT id, name FROM departments;
            ALTER TABLE departments RENAME TO divisions;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT * FROM v;\n.exit\n")
        assertContains(reopened, "eng")
    }

    @Test
    fun stringAggViewSurvivesReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE tags (item TEXT, label TEXT);
            INSERT INTO tags VALUES ('widget', 'blue'), ('widget', 'small'), ('gadget', 'red');
            CREATE VIEW item_labels AS SELECT item, STRING_AGG(label, ', ' ORDER BY label) AS labels FROM tags GROUP BY item;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT item, labels FROM item_labels ORDER BY item;\n.exit\n")
        assertContains(reopened, "blue, small")
        assertContains(reopened, "red")
    }

    /**
     * `COUNT(DISTINCT ...)`'s `distinct` flag is a field on [kestrel.sql.ast.FunctionExpression] just
     * like STRING_AGG's `separator`/`orderBy` above - this proves [kestrel.persistence.SqlWriter]
     * actually dumps the `DISTINCT` keyword back out (not just the wrapped argument), so a view built
     * on it does not silently turn into a plain, non-deduplicating `COUNT` after reopen.
     */
    @Test
    fun countDistinctViewSurvivesReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE sales (dept TEXT, amount INTEGER);
            INSERT INTO sales VALUES ('eng', 100), ('eng', 100), ('eng', 200), ('ops', 100), ('ops', 100);
            CREATE VIEW dept_distinct_amounts AS SELECT dept, COUNT(DISTINCT amount) AS n FROM sales GROUP BY dept;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(
            dir,
            "SELECT n FROM dept_distinct_amounts WHERE dept = 'eng';\n" +
                "SELECT n FROM dept_distinct_amounts WHERE dept = 'ops';\n.exit\n"
        )
        assertContains(reopened, "| 2 |")
        assertContains(reopened, "| 1 |")
    }

    /**
     * `FILTER (WHERE ...)`'s predicate is a field on [kestrel.sql.ast.FunctionExpression] just like
     * STRING_AGG's `separator`/`orderBy` and COUNT's `distinct` above - this proves
     * [kestrel.persistence.SqlWriter] actually dumps the `FILTER (WHERE ...)` clause back out, so a
     * view built on it does not silently turn into an unfiltered aggregate after reopen.
     */
    @Test
    fun filteredAggregateViewSurvivesReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE sales (dept TEXT, amount INTEGER);
            INSERT INTO sales VALUES ('eng', 100), ('eng', 200), ('ops', 100), ('ops', 100);
            CREATE VIEW dept_high_value_counts AS SELECT dept, COUNT(*) FILTER (WHERE amount > 100) AS n FROM sales GROUP BY dept;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(
            dir,
            "SELECT n FROM dept_high_value_counts WHERE dept = 'eng';\n" +
                "SELECT n FROM dept_high_value_counts WHERE dept = 'ops';\n.exit\n"
        )
        assertContains(reopened, "| 1 |")
        assertContains(reopened, "| 0 |")
    }

    /**
     * [kestrel.catalog.RelationRename] rewrites a renamed table's name everywhere it could appear in a
     * dependent view's stored query, including inside a nested subquery - this proves that recursion
     * now also reaches a subquery sitting inside an aggregate's `FILTER (WHERE ...)` predicate
     * specifically (a [kestrel.sql.ast.FunctionExpression] field distinct from `argument`, easy to miss
     * when only `argument` gets rewritten), not just a subquery in WHERE/projection.
     */
    @Test
    fun renameTablePropagatesIntoAViewsAggregateFilterSubquery() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE items (id INTEGER PRIMARY KEY, amount INTEGER);
            INSERT INTO items VALUES (1, 100), (2, 200);
            CREATE TABLE config (id INTEGER PRIMARY KEY, threshold INTEGER);
            INSERT INTO config VALUES (1, 150);
            CREATE VIEW joined AS SELECT COUNT(*) FILTER (WHERE amount > (SELECT threshold FROM config WHERE id = 1)) AS n FROM items;
            ALTER TABLE config RENAME TO settings;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT n FROM joined;\n.exit\n")
        assertContains(reopened, "| 1 |")
    }

    /**
     * [kestrel.catalog.RelationRename] rewrites a renamed table's name everywhere it could appear in a
     * dependent view's stored query, including inside a nested subquery - this proves that recursion
     * now also reaches a subquery sitting inside STRING_AGG's `separator` argument specifically (a
     * [kestrel.sql.ast.FunctionExpression] field distinct from `argument`, easy to miss when only
     * `argument` gets rewritten), not just a subquery in WHERE/projection.
     */
    @Test
    fun renameTablePropagatesIntoAViewsStringAggSeparatorSubquery() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE items (id INTEGER PRIMARY KEY, label TEXT);
            INSERT INTO items VALUES (1, 'b'), (2, 'a');
            CREATE TABLE config (id INTEGER PRIMARY KEY, sep TEXT);
            INSERT INTO config VALUES (1, ',');
            CREATE VIEW joined AS SELECT STRING_AGG(label, (SELECT sep FROM config WHERE id = 1) ORDER BY label) AS labels FROM items;
            ALTER TABLE config RENAME TO settings;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT labels FROM joined;\n.exit\n")
        assertContains(reopened, "a,b")
    }

    @Test
    fun distinctViewSurvivesReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE sales (dept TEXT);
            INSERT INTO sales VALUES ('eng'), ('eng'), ('ops');
            CREATE VIEW depts AS SELECT DISTINCT dept FROM sales;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT dept FROM depts ORDER BY dept;\n.exit\n")
        assertContains(reopened, "2 rows")
        assertContains(reopened, "eng")
        assertContains(reopened, "ops")
    }

    @Test
    fun limitAndOffsetViewSurvivesReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE sales (dept TEXT, amount INTEGER);
            INSERT INTO sales VALUES ('eng', 300), ('eng', 200), ('ops', 100);
            CREATE VIEW second_highest AS SELECT dept, amount FROM sales ORDER BY amount DESC LIMIT 1 OFFSET 1;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT dept, amount FROM second_highest;\n.exit\n")
        assertContains(reopened, "1 row")
        assertContains(reopened, "200")
        assertTrue(!reopened.contains("300"), "amount=300 was skipped by OFFSET and must not survive")
    }

    @Test
    fun distinctOnViewSurvivesReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE sales (dept TEXT, amount INTEGER);
            INSERT INTO sales VALUES ('eng', 100), ('eng', 200), ('ops', 50);
            CREATE VIEW top_per_dept AS SELECT DISTINCT ON (dept) dept, amount FROM sales ORDER BY dept, amount DESC;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT dept, amount FROM top_per_dept ORDER BY dept;\n.exit\n")
        assertContains(reopened, "2 rows")
        assertContains(reopened, "200")
        assertTrue(!reopened.contains("100"), "amount=100 lost the DISTINCT ON tie-break and must not survive")
    }

    /**
     * Mirrors [renameTablePropagatesIntoAViewsStringAggSeparatorSubquery] for the newly added
     * [kestrel.sql.ast.SelectStatement.distinctOn] field - [kestrel.catalog.RelationRename] must walk
     * into it exactly like it already does for `groupBy`/`having`, or a subquery hiding inside
     * `DISTINCT ON (...)` would silently keep pointing at a table name that no longer exists after
     * reopen (the rewritten AST, not the original text, is what SqlWriter dumps on checkpoint).
     */
    @Test
    fun renameTablePropagatesIntoAViewsDistinctOnSubquery() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE items (dept TEXT, amount INTEGER);
            INSERT INTO items VALUES ('eng', 100), ('eng', 200), ('ops', 50);
            CREATE TABLE config (id INTEGER PRIMARY KEY, threshold INTEGER);
            INSERT INTO config VALUES (1, 0);
            CREATE VIEW top_per_dept AS SELECT DISTINCT ON (dept, (SELECT threshold FROM config WHERE id = 1)) dept, amount FROM items ORDER BY dept, (SELECT threshold FROM config WHERE id = 1), amount DESC;
            ALTER TABLE config RENAME TO settings;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT dept, amount FROM top_per_dept ORDER BY dept;\n.exit\n")
        assertContains(reopened, "2 rows")
        assertContains(reopened, "200")
        assertContains(reopened, "50")
    }

    /**
     * A trigger created before its table's data would refire on every reload (SqlWriter.dump
     * intentionally writes CREATE TRIGGER only after every table's rows) - this proves reopening
     * does NOT duplicate the audit row the trigger already produced during the first session,
     * while a genuinely new INSERT after reopening still fires it normally.
     */
    @Test
    fun triggerCreationIsDumpedAfterDataSoReloadDoesNotRefireForAlreadyPersistedRows() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE orders (id INTEGER PRIMARY KEY, amount INTEGER);
            CREATE TABLE audit (order_id INTEGER);
            CREATE TRIGGER log_insert AFTER INSERT ON orders FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END;
            INSERT INTO orders VALUES (1, 100);
            .exit
            """.trimIndent()
        )
        val afterFirstReopen = runScript(dir, "SELECT order_id FROM audit;\n.exit\n")
        assertContains(afterFirstReopen, "1 row")
        val afterSecondReopen = runScript(
            dir,
            "INSERT INTO orders VALUES (2, 200);\nSELECT order_id FROM audit ORDER BY order_id;\n.exit\n"
        )
        assertContains(afterSecondReopen, "2 rows")
    }

    /**
     * The dumped `CREATE SEQUENCE` must bake in the sequence's CURRENT position (via `START WITH`),
     * not the one it was originally created with - otherwise a checkpoint reopen would reissue
     * already-handed-out values, breaking the one guarantee a sequence exists to provide.
     */
    @Test
    fun sequencePositionSurvivesReopenAfterCheckpointInsteadOfRestarting() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE SEQUENCE seq;
            CREATE TABLE t (id INTEGER);
            INSERT INTO t VALUES (NEXTVAL('seq')), (NEXTVAL('seq'));
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "INSERT INTO t VALUES (NEXTVAL('seq'));\nSELECT id FROM t WHERE id = 3;\n.exit\n")
        // A restart-from-scratch bug would reissue 1 as the third value (1, 2, 1) instead of
        // continuing to 3 - this query would then find zero rows instead of one.
        assertContains(reopened, "1 row")
    }

    @Test
    fun sequenceCreationAndNextvalDefaultSurviveReopenWithoutACheckpointViaWalReplay() {
        val dir = tempDir()
        val persistence = Persistence(dir)
        persistence.appendWal(
            listOf(
                "CREATE SEQUENCE order_ids;",
                "CREATE TABLE orders (id INTEGER DEFAULT NEXTVAL('order_ids'), item TEXT);",
                "INSERT INTO orders (item) VALUES ('a');"
            )
        )
        val output = StringBuilder()
        Repl(output, output, Persistence(dir)).run(
            "INSERT INTO orders (item) VALUES ('b');\nSELECT item FROM orders WHERE id = 1;\nSELECT item FROM orders WHERE id = 2;\n.exit\n"
                .reader().buffered()
        )
        val text = output.toString()
        assertContains(text, "a")
        assertContains(text, "b")
        assertEquals(2, text.lines().count { it.trim() == "1 row" })
    }

    @Test
    fun dropSequenceSurvivesReopenAfterCheckpoint() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE SEQUENCE seq;
            DROP SEQUENCE seq;
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, ".sequences\n.exit\n")
        assertContains(reopened, "no sequences")
    }

    /**
     * PREPARE/EXECUTE themselves are session-scoped and never written to the WAL (see
     * PrepareStatement's own doc) - but an INSERT run THROUGH EXECUTE is real data and must survive
     * a reopen exactly like one typed literally would. This is the whole reason
     * [kestrel.cli.Repl.executeBuffer] persists EXECUTE's RESOLVED statement instead of its literal
     * `EXECUTE name(...)` text: that literal text would be meaningless to replay against a fresh
     * session that never re-ran the PREPARE.
     */
    @Test
    fun executeOfAPreparedInsertPersistsTheResolvedRowAcrossReopen() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER);
            PREPARE addUser AS INSERT INTO users VALUES ($1, $2, $3);
            EXECUTE addUser(1, 'ada', 36);
            EXECUTE addUser(2, 'linus', 54);
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT name, age FROM users ORDER BY id ASC;\n.exit\n")
        assertContains(reopened, "ada")
        assertContains(reopened, "linus")
    }

    @Test
    fun preparedStatementsDoNotSurviveReopen() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            PREPARE addUser AS INSERT INTO users VALUES ($1, $2);
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "EXECUTE addUser(1, 'ada');\n.exit\n")
        assertContains(reopened, "Error: no such prepared statement: addUser")
    }

    @Test
    fun temporaryTableDoesNotSurviveReopenAfterCheckpointButARegularTableAlongsideItDoes() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE regular (id INTEGER PRIMARY KEY, note TEXT);
            CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY, note TEXT);
            INSERT INTO regular VALUES (1, 'keepsMe');
            INSERT INTO scratch VALUES (1, 'losesMe');
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT note FROM regular;\nSELECT note FROM scratch;\n.exit\n")
        assertContains(reopened, "keepsMe")
        assertContains(reopened, "Error: no such table: scratch")
        assertTrue(!reopened.contains("losesMe"), "a TEMP table's rows must not survive reopen")
    }

    @Test
    fun renamingATemporaryTableKeepsItTemporarySoTheRenamedNameAlsoDoesNotSurviveReopen() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY);
            ALTER TABLE scratch RENAME TO renamedScratch;
            INSERT INTO renamedScratch VALUES (1);
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT id FROM renamedScratch;\n.exit\n")
        assertContains(reopened, "Error: no such table: renamedScratch")
    }

    @Test
    fun addColumnAndDropColumnOnATemporaryTableKeepItTemporaryAcrossReopen() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY, a INTEGER);
            ALTER TABLE scratch ADD COLUMN b TEXT;
            ALTER TABLE scratch DROP COLUMN a;
            INSERT INTO scratch VALUES (1, 'x');
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT id FROM scratch;\n.exit\n")
        assertContains(reopened, "Error: no such table: scratch")
    }

    @Test
    fun renamingAColumnOnATemporaryTableKeepsItTemporaryAcrossReopen() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY, a INTEGER);
            ALTER TABLE scratch RENAME COLUMN a TO b;
            INSERT INTO scratch VALUES (1, 2);
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT id FROM scratch;\n.exit\n")
        assertContains(reopened, "Error: no such table: scratch")
    }

    @Test
    fun aTemporaryTablesForeignKeyBeingDroppedByAnUnrelatedCascadeKeepsItTemporaryAcrossReopen() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TABLE parent (id INTEGER PRIMARY KEY);
            CREATE TEMP TABLE child (id INTEGER PRIMARY KEY, parentId INTEGER, FOREIGN KEY (parentId) REFERENCES parent(id));
            DROP TABLE parent CASCADE;
            INSERT INTO child VALUES (1, NULL);
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT id FROM child;\n.exit\n")
        assertContains(reopened, "Error: no such table: child")
    }

    @Test
    fun anIndexOnATemporaryTableNeverPersists() {
        val dir = tempDir()
        runScript(
            dir,
            """
            CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY, tag TEXT);
            CREATE INDEX scratchTag ON scratch (tag);
            INSERT INTO scratch VALUES (1, 'x');
            .exit
            """.trimIndent()
        )
        val reopened = runScript(dir, "SELECT id FROM scratch;\n.indexes\n.exit\n")
        assertContains(reopened, "Error: no such table: scratch")
        assertTrue(!reopened.contains("scratchTag"), "an index on a TEMP table must not survive reopen either")
    }

    @Test
    fun droppingAnIndexOnATemporaryTableNeverReachesTheIncrementalWalEitherSideOfIt() {
        val dir = tempDir()
        runScriptThatCrashesBeforeItCanCheckpoint(
            dir,
            """
            CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY, tag TEXT);
            CREATE INDEX scratchTag ON scratch (tag);
            DROP INDEX scratchTag;
            INSERT INTO scratch VALUES (1, 'x');
            """.trimIndent() + "\n"
        )
        val reopened = runScript(dir, "SELECT id FROM scratch;\n.indexes\n.exit\n")
        assertContains(reopened, "Error: no such table: scratch")
        assertTrue(!reopened.contains("scratchTag"))
    }

    @Test
    fun aTemporaryTableNeverReachesTheIncrementalWriteAheadLogEvenBeforeACheckpoint() {
        val dir = tempDir()
        runScriptThatCrashesBeforeItCanCheckpoint(
            dir,
            """
            CREATE TABLE regular (id INTEGER PRIMARY KEY);
            CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY);
            INSERT INTO regular VALUES (111);
            INSERT INTO scratch VALUES (222);
            """.trimIndent() + "\n"
        )
        // No checkpoint ever ran, so this reopen replays raw incremental WAL entries only - if the
        // TEMP table's CREATE/INSERT had leaked into that log, replaying "INSERT INTO scratch..."
        // would either resurrect it or (if its CREATE alone were skipped but not its INSERT) throw
        // "no such table" loudly enough to show up here.
        val reopened = runScript(dir, "SELECT id FROM regular;\nSELECT id FROM scratch;\n.exit\n")
        assertContains(reopened, "111")
        assertContains(reopened, "Error: no such table: scratch")
        assertTrue(!reopened.contains("222"), "a TEMP table's row must never reach the WAL, crash or not")
    }

    @Test
    fun aTruncateNamingBothATemporaryAndARegularTableStillPersistsTheRegularTablesTruncationBeforeACheckpoint() {
        val dir = tempDir()
        runScriptThatCrashesBeforeItCanCheckpoint(
            dir,
            """
            CREATE TABLE regular (id INTEGER);
            CREATE TEMP TABLE scratch (id INTEGER);
            INSERT INTO regular VALUES (111), (222);
            INSERT INTO scratch VALUES (333);
            TRUNCATE TABLE regular, scratch;
            """.trimIndent() + "\n"
        )
        // If the TEMP name were not stripped out of the persisted TRUNCATE text, replaying
        // "TRUNCATE TABLE regular, scratch" against a reload where scratch was never recreated would
        // throw before truncating ANYTHING (Catalog.truncateTables validates every name up front) -
        // silently leaving regular's rows intact instead of empty, since replay errors are swallowed
        // (see Repl.printResult's `if (replaying) return`).
        val reopened = runScript(dir, "SELECT id FROM regular;\n.exit\n")
        assertTrue(!reopened.contains("111") && !reopened.contains("222"), "regular's TRUNCATE must survive the crash-before-checkpoint replay")
    }
}
