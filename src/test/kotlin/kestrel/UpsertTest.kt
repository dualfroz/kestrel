package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpsertTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun insertCount(sql: String): Int = (exec(sql) as InsertResult).count

    private fun seed() {
        run("CREATE TABLE accounts (id INTEGER PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL)")
        run("INSERT INTO accounts VALUES (1, 'ada', 100)")
    }

    @Test
    fun noConflictInsertsNormallyAndReportsOneRow() {
        seed()
        val count = insertCount("INSERT INTO accounts VALUES (2, 'linus', 200) ON CONFLICT (id) DO NOTHING")
        assertEquals(1, count)
        assertEquals(2, select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun conflictOnPrimaryKeyDoesNothingAndReportsZero() {
        seed()
        val count = insertCount("INSERT INTO accounts VALUES (1, 'ignored', 999) ON CONFLICT (id) DO NOTHING")
        assertEquals(0, count)
        assertEquals(Value.IntegerValue(100), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals("ada", (select("SELECT name FROM accounts WHERE id = 1").rows[0][0] as Value.TextValue).value)
    }

    @Test
    fun conflictWithBareOnConflictWithNoTargetCatchesAnyUniqueViolation() {
        seed()
        run("CREATE UNIQUE INDEX idxName ON accounts (name)")
        val count = insertCount("INSERT INTO accounts VALUES (2, 'ada', 300) ON CONFLICT DO NOTHING")
        assertEquals(0, count)
        assertEquals(1, select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun conflictDoUpdateAppliesTheAssignments() {
        seed()
        insertCount("INSERT INTO accounts VALUES (1, 'ada', 999) ON CONFLICT (id) DO UPDATE SET balance = 999")
        assertEquals(Value.IntegerValue(999), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals(1, select("SELECT * FROM accounts").rows.size)
    }

    @Test
    fun conflictDoUpdateCanReferenceExcludedForTheProposedRow() {
        seed()
        insertCount("INSERT INTO accounts VALUES (1, 'ada', 50) ON CONFLICT (id) DO UPDATE SET balance = accounts.balance + excluded.balance")
        assertEquals(Value.IntegerValue(150), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
    }

    @Test
    fun conflictDoUpdateWithFailingWhereBehavesLikeDoNothingForThatRow() {
        seed()
        val count = insertCount(
            "INSERT INTO accounts VALUES (1, 'ada', 999) ON CONFLICT (id) DO UPDATE SET balance = 999 WHERE excluded.balance < 0"
        )
        assertEquals(0, count)
        assertEquals(Value.IntegerValue(100), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
    }

    @Test
    fun conflictDoUpdateWithPassingWhereApplies() {
        seed()
        val count = insertCount(
            "INSERT INTO accounts VALUES (1, 'ada', 999) ON CONFLICT (id) DO UPDATE SET balance = 999 WHERE excluded.balance > 0"
        )
        assertEquals(1, count)
        assertEquals(Value.IntegerValue(999), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
    }

    @Test
    fun multiRowInsertHandlesEachRowsConflictIndependently() {
        seed()
        val count = insertCount(
            """
            INSERT INTO accounts VALUES (1, 'ada', 5), (2, 'linus', 200), (3, 'grace', 300)
            ON CONFLICT (id) DO UPDATE SET balance = accounts.balance + excluded.balance
            """.trimIndent()
        )
        assertEquals(3, count)
        assertEquals(Value.IntegerValue(105), select("SELECT balance FROM accounts WHERE id = 1").rows[0][0])
        assertEquals(Value.IntegerValue(200), select("SELECT balance FROM accounts WHERE id = 2").rows[0][0])
        assertEquals(Value.IntegerValue(300), select("SELECT balance FROM accounts WHERE id = 3").rows[0][0])
    }

    @Test
    fun laterRowInTheSameStatementSeesAnEarlierRowsInsert() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, hits INTEGER NOT NULL)")
        val count = insertCount(
            """
            INSERT INTO t VALUES (1, 1), (1, 1)
            ON CONFLICT (id) DO UPDATE SET hits = t.hits + excluded.hits
            """.trimIndent()
        )
        assertEquals(2, count)
        assertEquals(Value.IntegerValue(2), select("SELECT hits FROM t WHERE id = 1").rows[0][0])
    }

    @Test
    fun conflictTargetMustMatchAnExistingUniqueOrPrimaryKey() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("INSERT INTO accounts VALUES (2, 'linus', 200) ON CONFLICT (name) DO NOTHING")
        }
        assertTrue(error.message!!.contains("no unique or primary key constraint"), error.message!!)
    }

    @Test
    fun conflictTargetMatchesADeclaredUniqueConstraintNotJustThePrimaryKey() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE, name TEXT)")
        run("INSERT INTO users VALUES (1, 'ada@example.com', 'ada')")
        val count = insertCount(
            "INSERT INTO users VALUES (2, 'ada@example.com', 'ada2') ON CONFLICT (email) DO UPDATE SET name = excluded.name"
        )
        assertEquals(1, count)
        assertEquals("ada2", (select("SELECT name FROM users WHERE email = 'ada@example.com'").rows[0][0] as Value.TextValue).value)
        assertEquals(1, select("SELECT * FROM users").rows.size)
    }

    @Test
    fun conflictOnADifferentUnrelatedUniqueColumnStillThrows() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE, tag TEXT UNIQUE)")
        run("INSERT INTO users VALUES (1, 'ada@example.com', 'x')")
        val error = assertFailsWith<KestrelException> {
            run("INSERT INTO users VALUES (2, 'new@example.com', 'x') ON CONFLICT (email) DO NOTHING")
        }
        assertTrue(error.message!!.contains("UNIQUE constraint"), error.message!!)
    }

    @Test
    fun conflictDoUpdateEnforcesNotNullOnTheResultingRow() {
        seed()
        val error = assertFailsWith<KestrelException> {
            run("INSERT INTO accounts VALUES (1, 'ada', 100) ON CONFLICT (id) DO UPDATE SET name = NULL")
        }
        assertTrue(error.message!!.contains("NOT NULL"), error.message!!)
    }

    @Test
    fun conflictDoUpdateEnforcesCheckConstraintsOnTheResultingRow() {
        run(
            "CREATE TABLE accounts (id INTEGER PRIMARY KEY, balance INTEGER CHECK (balance >= 0))"
        )
        run("INSERT INTO accounts VALUES (1, 100)")
        val error = assertFailsWith<KestrelException> {
            run("INSERT INTO accounts VALUES (1, 100) ON CONFLICT (id) DO UPDATE SET balance = -5")
        }
        assertTrue(error.message!!.contains("CHECK constraint"), error.message!!)
    }

    @Test
    fun conflictDoUpdateRunsForeignKeyValidationOnTheUpdatedRow() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("INSERT INTO departments VALUES (1)")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept INTEGER REFERENCES departments(id))"
        )
        run("INSERT INTO employees VALUES (1, 1)")
        val error = assertFailsWith<KestrelException> {
            run("INSERT INTO employees VALUES (1, 1) ON CONFLICT (id) DO UPDATE SET dept = 999")
        }
        assertTrue(error.message!!.contains("FOREIGN KEY"), error.message!!)
    }

    @Test
    fun conflictDoUpdateCascadesToChildTablesWhenTheReferencedKeyChanges() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("INSERT INTO departments VALUES (1)")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept INTEGER REFERENCES departments(id) ON UPDATE CASCADE)"
        )
        run("INSERT INTO employees VALUES (1, 1)")
        insertCount("INSERT INTO departments VALUES (1) ON CONFLICT (id) DO UPDATE SET id = 2")
        assertEquals(Value.IntegerValue(2), select("SELECT dept FROM employees WHERE id = 1").rows[0][0])
    }

    @Test
    fun compositePrimaryKeyConflictTargetIsRecognised() {
        run(
            "CREATE TABLE grades (student INTEGER, course INTEGER, score INTEGER, PRIMARY KEY (student, course))"
        )
        run("INSERT INTO grades VALUES (1, 10, 50)")
        val count = insertCount(
            "INSERT INTO grades VALUES (1, 10, 90) ON CONFLICT (student, course) DO UPDATE SET score = excluded.score"
        )
        assertEquals(1, count)
        assertEquals(Value.IntegerValue(90), select("SELECT score FROM grades WHERE student = 1 AND course = 10").rows[0][0])
    }

    @Test
    fun conflictDoUpdateDefaultKeywordFallsBackToColumnDefault() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, note TEXT DEFAULT 'fresh')")
        run("INSERT INTO t VALUES (1, 'stale')")
        insertCount("INSERT INTO t VALUES (1, 'stale') ON CONFLICT (id) DO UPDATE SET note = DEFAULT")
        assertEquals("fresh", (select("SELECT note FROM t WHERE id = 1").rows[0][0] as Value.TextValue).value)
    }

    @Test
    fun tableStateAfterAConflictResolutionStillDumpsAndReplaysCleanly() {
        seed()
        run("INSERT INTO accounts VALUES (2, 'linus', 200) ON CONFLICT (id) DO UPDATE SET balance = excluded.balance")
        val dumped = kestrel.persistence.SqlWriter.dump(catalog)
        val replayCatalog = Catalog()
        val replayExecutor = Executor(replayCatalog)
        Parser(Lexer(dumped).tokenize()).parseProgram().forEach { replayExecutor.execute(it) }
        val replaySelect = Parser(Lexer("SELECT * FROM accounts").tokenize()).parseProgram()
            .map { replayExecutor.execute(it) }.single() as SelectResult
        assertEquals(2, replaySelect.rows.size)
    }
}
