package kestrel.store.sql

import kestrel.KestrelException
import kestrel.engine.InsertResult
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
import kotlin.test.assertTrue

class DiskExecutorOnConflictTest {
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
        fun insertCount(sql: String): Int = (exec(sql) as InsertResult).count

        fun reopen() {
            catalog.close()
            catalog = DiskCatalog.open(dataFile, walFile)
            executor = DiskExecutor(catalog)
        }
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-disk-onconflict-data"), tempFile("kestrel-disk-onconflict-wal"))

    private fun Database.seed() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL)")
        run("INSERT INTO accounts VALUES (1, 'ada', 100)")
    }

    @Test
    fun noConflictInsertsNormallyAndReportsOneRow() {
        val db = newDatabase()
        db.seed()
        val count = db.insertCount("INSERT INTO accounts VALUES (2, 'linus', 200) ON CONFLICT (id) DO NOTHING")
        assertEquals(1, count)
        assertEquals(2, db.select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun conflictOnPrimaryKeyDoesNothingAndReportsZero() {
        val db = newDatabase()
        db.seed()
        val count = db.insertCount("INSERT INTO accounts VALUES (1, 'ignored', 999) ON CONFLICT (id) DO NOTHING")
        assertEquals(0, count)
        assertEquals(int(100), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals("ada", (db.select("SELECT name FROM accounts WHERE id = 1").rows[0][0] as Value.TextValue).value)
    }

    @Test
    fun conflictWithBareOnConflictWithNoTargetCatchesAnyUniqueViolation() {
        val db = newDatabase()
        db.seed()
        db.run("CREATE UNIQUE INDEX idxName ON accounts (name)")
        val count = db.insertCount("INSERT INTO accounts VALUES (2, 'ada', 300) ON CONFLICT DO NOTHING")
        assertEquals(0, count)
        assertEquals(1, db.select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun conflictDoUpdateAppliesTheAssignments() {
        val db = newDatabase()
        db.seed()
        db.insertCount("INSERT INTO accounts VALUES (1, 'ada', 999) ON CONFLICT (id) DO UPDATE SET balance = 999")
        assertEquals(int(999), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals(1, db.select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun conflictDoUpdateCanReferenceExcludedForTheProposedRow() {
        val db = newDatabase()
        db.seed()
        db.insertCount("INSERT INTO accounts VALUES (1, 'ada', 50) ON CONFLICT (id) DO UPDATE SET balance = accounts.balance + excluded.balance")
        assertEquals(int(150), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
    }

    @Test
    fun conflictDoUpdateWithFailingWhereBehavesLikeDoNothingForThatRow() {
        val db = newDatabase()
        db.seed()
        val count = db.insertCount(
            "INSERT INTO accounts VALUES (1, 'ada', 999) ON CONFLICT (id) DO UPDATE SET balance = 999 WHERE excluded.balance < 0"
        )
        assertEquals(0, count)
        assertEquals(int(100), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
    }

    @Test
    fun conflictDoUpdateWithPassingWhereApplies() {
        val db = newDatabase()
        db.seed()
        val count = db.insertCount(
            "INSERT INTO accounts VALUES (1, 'ada', 999) ON CONFLICT (id) DO UPDATE SET balance = 999 WHERE excluded.balance > 0"
        )
        assertEquals(1, count)
        assertEquals(int(999), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
    }

    @Test
    fun multiRowInsertHandlesEachRowsConflictIndependently() {
        val db = newDatabase()
        db.seed()
        val count = db.insertCount(
            """
            INSERT INTO accounts VALUES (1, 'ada', 5), (2, 'linus', 200), (3, 'grace', 300)
            ON CONFLICT (id) DO UPDATE SET balance = accounts.balance + excluded.balance
            """.trimIndent()
        )
        assertEquals(3, count)
        assertEquals(int(105), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals(int(200), db.select("SELECT balance FROM accounts WHERE id = 2").rows[0][0])
        assertEquals(int(300), db.select("SELECT balance FROM accounts WHERE id = 3").rows[0][0])
    }

    @Test
    fun laterRowInTheSameStatementSeesAnEarlierRowsInsert() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, hits INTEGER NOT NULL)")
        val count = db.insertCount(
            """
            INSERT INTO t VALUES (1, 1), (1, 1)
            ON CONFLICT (id) DO UPDATE SET hits = t.hits + excluded.hits
            """.trimIndent()
        )
        assertEquals(2, count)
        assertEquals(int(2), db.select("SELECT hits FROM t WHERE id = 1").rows[0][0])
    }

    @Test
    fun conflictTargetMustMatchAnExistingUniqueOrPrimaryKey() {
        val db = newDatabase()
        db.seed()
        val error = assertFailsWith<KestrelException> {
            db.run("INSERT INTO accounts VALUES (2, 'linus', 200) ON CONFLICT (name) DO NOTHING")
        }
        assertTrue(error.message!!.contains("no unique or primary key constraint"), error.message!!)
    }

    @Test
    fun conflictTargetMatchesADeclaredUniqueConstraintNotJustThePrimaryKey() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE, name TEXT)")
        db.run("INSERT INTO users VALUES (1, 'ada@example.com', 'ada')")
        val count = db.insertCount(
            "INSERT INTO users VALUES (2, 'ada@example.com', 'ada2') ON CONFLICT (email) DO UPDATE SET name = excluded.name"
        )
        assertEquals(1, count)
        assertEquals("ada2", (db.select("SELECT name FROM users WHERE email = 'ada@example.com'").rows[0][0] as Value.TextValue).value)
        assertEquals(1, db.select("SELECT * FROM users").rows.size)
    }

    @Test
    fun conflictOnADifferentUnrelatedUniqueColumnStillThrows() {
        val db = newDatabase()
        db.run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE, tag TEXT UNIQUE)")
        db.run("INSERT INTO users VALUES (1, 'ada@example.com', 'x')")
        val error = assertFailsWith<KestrelException> {
            db.run("INSERT INTO users VALUES (2, 'new@example.com', 'x') ON CONFLICT (email) DO NOTHING")
        }
        assertTrue(error.message!!.contains("UNIQUE constraint"), error.message!!)
    }

    @Test
    fun conflictDoUpdateEnforcesNotNullOnTheResultingRow() {
        val db = newDatabase()
        db.seed()
        val error = assertFailsWith<KestrelException> {
            db.run("INSERT INTO accounts VALUES (1, 'ada', 100) ON CONFLICT (id) DO UPDATE SET name = NULL")
        }
        assertTrue(error.message!!.contains("NOT NULL"), error.message!!)
    }

    @Test
    fun conflictDoUpdateEnforcesCheckConstraintsOnTheResultingRow() {
        val db = newDatabase()
        db.run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER CHECK (balance >= 0))")
        db.run("INSERT INTO accounts VALUES (1, 100)")
        val error = assertFailsWith<KestrelException> {
            db.run("INSERT INTO accounts VALUES (1, 100) ON CONFLICT (id) DO UPDATE SET balance = -5")
        }
        assertTrue(error.message!!.contains("CHECK constraint"), error.message!!)
    }

    @Test
    fun conflictDoUpdateRunsForeignKeyValidationOnTheUpdatedRow() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO departments VALUES (1)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept INTEGER REFERENCES departments(id))")
        db.run("INSERT INTO employees VALUES (1, 1)")
        val error = assertFailsWith<KestrelException> {
            db.run("INSERT INTO employees VALUES (1, 1) ON CONFLICT (id) DO UPDATE SET dept = 999")
        }
        assertTrue(error.message!!.contains("FOREIGN KEY"), error.message!!)
    }

    @Test
    fun conflictDoUpdateCascadesToChildTablesWhenTheReferencedKeyChanges() {
        val db = newDatabase()
        db.run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        db.run("INSERT INTO departments VALUES (1)")
        db.run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept INTEGER REFERENCES departments(id) ON UPDATE CASCADE)")
        db.run("INSERT INTO employees VALUES (1, 1)")
        db.insertCount("INSERT INTO departments VALUES (1) ON CONFLICT (id) DO UPDATE SET id = 2")
        assertEquals(int(2), db.select("SELECT dept FROM employees WHERE id = 1").rows[0][0])
    }

    @Test
    fun compositePrimaryKeyConflictTargetIsRecognised() {
        val db = newDatabase()
        db.run("CREATE TABLE grades (student INTEGER, course INTEGER, score INTEGER, PRIMARY KEY (student, course))")
        db.run("INSERT INTO grades VALUES (1, 10, 50)")
        val count = db.insertCount(
            "INSERT INTO grades VALUES (1, 10, 90) ON CONFLICT (student, course) DO UPDATE SET score = excluded.score"
        )
        assertEquals(1, count)
        assertEquals(int(90), db.select("SELECT score FROM grades WHERE student = 1 AND course = 10").rows[0][0])
    }

    @Test
    fun conflictDoUpdateDefaultKeywordFallsBackToColumnDefault() {
        val db = newDatabase()
        db.run("CREATE TABLE t (id INTEGER PRIMARY KEY, note TEXT DEFAULT 'fresh')")
        db.run("INSERT INTO t VALUES (1, 'stale')")
        db.insertCount("INSERT INTO t VALUES (1, 'stale') ON CONFLICT (id) DO UPDATE SET note = DEFAULT")
        assertEquals("fresh", (db.select("SELECT note FROM t WHERE id = 1").rows[0][0] as Value.TextValue).value)
    }

    @Test
    fun onConflictInsideAnExplicitTransactionParticipatesInItsCommit() {
        val db = newDatabase()
        db.seed()
        db.run("BEGIN")
        db.insertCount("INSERT INTO accounts VALUES (1, 'ada', 999) ON CONFLICT (id) DO UPDATE SET balance = excluded.balance")
        db.insertCount("INSERT INTO accounts VALUES (2, 'linus', 200) ON CONFLICT (id) DO NOTHING")
        db.run("ROLLBACK")
        assertEquals(int(100), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals(1, db.select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun conflictResolutionSurvivesAReopen() {
        val db = newDatabase()
        db.seed()
        db.insertCount(
            """
            INSERT INTO accounts VALUES (1, 'ada', 999), (2, 'linus', 200)
            ON CONFLICT (id) DO UPDATE SET balance = excluded.balance
            """.trimIndent()
        )
        db.reopen()
        assertEquals(int(999), db.select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals(int(200), db.select("SELECT balance FROM accounts WHERE id = 2").rows[0][0])
        assertEquals(2, db.select("SELECT * FROM accounts").rows.size)
    }
}
