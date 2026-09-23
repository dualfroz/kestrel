package kestrel

import java.io.File
import java.nio.file.Files
import kestrel.cli.Repl
import kestrel.persistence.Persistence
import kestrel.store.sql.DiskCatalog
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplDiskTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun runOnDisk(dataFile: File, walFile: File, script: String): String {
        val output = StringBuilder()
        val catalog = DiskCatalog.open(dataFile, walFile)
        Repl(output, output, diskCatalog = catalog).run(script.reader().buffered())
        return output.toString()
    }

    @Test
    fun gateScenarioRendersOrderedResultThroughTheDiskBackedExecutor() {
        val output = runOnDisk(
            tempFile("kestrel-repl-data"),
            tempFile("kestrel-repl-wal"),
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER);
            INSERT INTO users VALUES (1, 'ada', 36), (2, 'linus', 54), (3, 'grace', 85);
            SELECT name, age FROM users WHERE age > 40 ORDER BY age DESC;
            .exit
            """.trimIndent()
        )
        assertContains(output, "3 rows inserted")
        assertContains(output, "grace")
        assertContains(output, "linus")
        assertTrue(output.indexOf("grace") < output.indexOf("linus"), "grace should be listed before linus")
    }

    @Test
    fun metaCommandsReflectDiskCatalogStateForEveryObjectKind() {
        val output = runOnDisk(
            tempFile("kestrel-repl-data"),
            tempFile("kestrel-repl-wal"),
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT NOT NULL);
            CREATE UNIQUE INDEX idx_email ON users (email);
            CREATE VIEW active_users AS SELECT id FROM users;
            CREATE SEQUENCE user_ids;
            CREATE TRIGGER log_insert AFTER INSERT ON users FOR EACH ROW BEGIN DELETE FROM users WHERE 1 = 0; END;
            .tables
            .indexes
            .views
            .triggers
            .sequences
            .exit
            """.trimIndent()
        )
        assertTrue(!output.contains("Error:"), output)
        assertContains(output, "users")
        assertContains(output, "idx_email")
        assertContains(output, "active_users")
        assertContains(output, "log_insert")
        assertContains(output, "user_ids")
    }

    @Test
    fun schemaPrintsColumnsForeignKeyCheckUniqueAndSecondaryIndexFromTheDiskCatalog() {
        val output = runOnDisk(
            tempFile("kestrel-repl-data"),
            tempFile("kestrel-repl-wal"),
            """
            CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER NOT NULL CHECK (balance >= 0));
            CREATE TABLE orders (
              id INTEGER PRIMARY KEY,
              account_id INTEGER,
              code TEXT,
              UNIQUE (code),
              FOREIGN KEY (account_id) REFERENCES accounts (id)
            );
            CREATE INDEX idx_orders_code ON orders (code);
            .schema orders
            .exit
            """.trimIndent()
        )
        assertContains(output, "CREATE TABLE orders")
        assertContains(output, "id INTEGER PRIMARY KEY")
        assertContains(output, "FOREIGN KEY (account_id) REFERENCES accounts (id)")
        assertContains(output, "UNIQUE (code)")
        assertContains(output, "CREATE INDEX idx_orders_code ON orders (code);")
    }

    @Test
    fun dataSchemaAndIndexesSurviveClosingAndReopeningTheDiskCatalog() {
        val dataFile = tempFile("kestrel-repl-data")
        val walFile = tempFile("kestrel-repl-wal")
        runOnDisk(
            dataFile,
            walFile,
            """
            CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
            CREATE UNIQUE INDEX idx_name ON users (name);
            INSERT INTO users VALUES (1, 'ada'), (2, 'grace');
            .exit
            """.trimIndent()
        )
        val reopened = runOnDisk(
            dataFile,
            walFile,
            "SELECT name FROM users ORDER BY id ASC;\n.indexes\nINSERT INTO users VALUES (3, 'ada');\n.exit\n"
        )
        assertContains(reopened, "ada")
        assertContains(reopened, "grace")
        assertContains(reopened, "idx_name")
        assertContains(
            reopened,
            "Error:",
            message = "the UNIQUE index on name must still reject a duplicate name (new id, old name) after reopen"
        )
    }

    @Test
    fun explicitTransactionLeftOpenAtReplExitIsRolledBackNotSilentlyLost() {
        val dataFile = tempFile("kestrel-repl-data")
        val walFile = tempFile("kestrel-repl-wal")
        runOnDisk(
            dataFile,
            walFile,
            """
            CREATE TABLE t (id INTEGER PRIMARY KEY);
            INSERT INTO t VALUES (1);
            BEGIN;
            INSERT INTO t VALUES (2);
            .exit
            """.trimIndent()
        )
        val reopened = runOnDisk(dataFile, walFile, "SELECT id FROM t ORDER BY id ASC;\n.exit\n")
        assertContains(reopened, "1")
        assertTrue(!reopened.contains("2"), "the uncommitted INSERT inside BEGIN must not survive the abandoned transaction")
    }

    @Test
    fun replRejectsCombiningTextBasedPersistenceWithTheDiskBackedCatalog() {
        val persistenceDir = Files.createTempDirectory("kestrel-repl-persist").toFile().also { it.deleteOnExit() }
        val catalog = DiskCatalog.open(tempFile("kestrel-repl-data"), tempFile("kestrel-repl-wal"))
        assertFailsWith<IllegalArgumentException> {
            Repl(persistence = Persistence(persistenceDir), diskCatalog = catalog)
        }
    }
}
