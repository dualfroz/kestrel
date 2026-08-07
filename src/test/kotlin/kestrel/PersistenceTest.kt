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
}
