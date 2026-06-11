package kestrel

import java.io.File
import java.nio.file.Files
import kestrel.cli.Repl
import kestrel.persistence.Persistence
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class PersistenceTest {
    private fun tempDir(): File = Files.createTempDirectory("kestrelData").toFile()

    private fun runScript(dir: File, script: String): String {
        val output = StringBuilder()
        Repl(output, output, Persistence(dir)).run(script.reader().buffered())
        return output.toString()
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
