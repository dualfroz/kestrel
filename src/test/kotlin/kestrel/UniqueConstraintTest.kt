package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.UpdateResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UniqueConstraintTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    @Test
    fun columnLevelUniqueRejectsDuplicateValue() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        run("INSERT INTO users VALUES (1, 'ada@example.com')")
        val error = assertFailsWith<KestrelException> {
            run("INSERT INTO users VALUES (2, 'ada@example.com')")
        }
        assertTrue(error.message!!.contains("UNIQUE constraint"), error.message!!)
    }

    @Test
    fun columnLevelUniqueAllowsMultipleNullValues() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        run("INSERT INTO users VALUES (1, NULL)")
        val result = exec("INSERT INTO users VALUES (2, NULL)")
        assertEquals(InsertResult(1), result)
    }

    @Test
    fun tableLevelCompositeUniqueRejectsDuplicateCombination() {
        run(
            "CREATE TABLE enrollments (" +
                "student_id INTEGER, course_id INTEGER, term TEXT, " +
                "UNIQUE (student_id, course_id, term))"
        )
        run("INSERT INTO enrollments VALUES (1, 10, 'fall')")
        assertFailsWith<KestrelException> {
            run("INSERT INTO enrollments VALUES (1, 10, 'fall')")
        }
    }

    @Test
    fun tableLevelCompositeUniqueAllowsDifferingCombinations() {
        run(
            "CREATE TABLE enrollments (" +
                "student_id INTEGER, course_id INTEGER, term TEXT, " +
                "UNIQUE (student_id, course_id, term))"
        )
        run("INSERT INTO enrollments VALUES (1, 10, 'fall')")
        val bySameStudentDifferentCourse = exec("INSERT INTO enrollments VALUES (1, 11, 'fall')")
        assertEquals(InsertResult(1), bySameStudentDifferentCourse)
        val bySameStudentAndCourseDifferentTerm = exec("INSERT INTO enrollments VALUES (1, 10, 'spring')")
        assertEquals(InsertResult(1), bySameStudentAndCourseDifferentTerm)
    }

    @Test
    fun tableLevelCompositeUniqueExemptsRowsWithAnyNullColumn() {
        run(
            "CREATE TABLE enrollments (" +
                "student_id INTEGER, course_id INTEGER, term TEXT, " +
                "UNIQUE (student_id, course_id, term))"
        )
        run("INSERT INTO enrollments VALUES (1, 10, NULL)")
        val result = exec("INSERT INTO enrollments VALUES (1, 10, NULL)")
        assertEquals(InsertResult(1), result)
    }

    @Test
    fun updateRejectsUniqueViolationAgainstAnotherRow() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        run("INSERT INTO users VALUES (1, 'ada@example.com'), (2, 'grace@example.com')")
        assertFailsWith<KestrelException> {
            run("UPDATE users SET email = 'ada@example.com' WHERE id = 2")
        }
    }

    @Test
    fun updateToSameRowsOwnValueIsNotAViolation() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        run("INSERT INTO users VALUES (1, 'ada@example.com')")
        val result = exec("UPDATE users SET email = 'ada@example.com' WHERE id = 1")
        assertEquals(UpdateResult(1), result)
    }

    @Test
    fun uniqueConstraintRejectsUnknownColumn() {
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER PRIMARY KEY, UNIQUE (missing))")
        }
    }

    @Test
    fun foreignKeyCanReferenceAUniqueNonPrimaryKeyColumn() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        run("CREATE TABLE profiles (id INTEGER PRIMARY KEY, user_email TEXT REFERENCES users(email))")
        run("INSERT INTO users VALUES (1, 'ada@example.com')")
        val result = exec("INSERT INTO profiles VALUES (1, 'ada@example.com')")
        assertEquals(InsertResult(1), result)
        assertFailsWith<KestrelException> {
            run("INSERT INTO profiles VALUES (2, 'nobody@example.com')")
        }
    }

    @Test
    fun foreignKeyRejectsReferencingAColumnWithoutEnforcedUniqueness() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT)")
        val error = assertFailsWith<KestrelException> {
            run("CREATE TABLE profiles (id INTEGER PRIMARY KEY, user_email TEXT REFERENCES users(email))")
        }
        assertTrue(error.message!!.contains("PRIMARY KEY or UNIQUE"), error.message!!)
    }

    @Test
    fun cascadeUpdatePropagatesThroughAUniqueReferencedColumn() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        run(
            "CREATE TABLE profiles (id INTEGER PRIMARY KEY, " +
                "user_email TEXT REFERENCES users(email) ON UPDATE CASCADE)"
        )
        run("INSERT INTO users VALUES (1, 'old@example.com')")
        run("INSERT INTO profiles VALUES (1, 'old@example.com')")
        run("UPDATE users SET email = 'new@example.com' WHERE id = 1")
        val result = select("SELECT user_email FROM profiles WHERE id = 1")
        assertEquals(listOf(listOf(text("new@example.com"))), result.rows)
    }

    @Test
    fun rollbackAfterAFailedInsertLeavesUniqueStateReusable() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        run("BEGIN")
        run("INSERT INTO users VALUES (1, 'ada@example.com')")
        run("ROLLBACK")
        val result = exec("INSERT INTO users VALUES (1, 'ada@example.com')")
        assertEquals(InsertResult(1), result)
        assertEquals(listOf(listOf(int(1), text("ada@example.com"))), select("SELECT id, email FROM users").rows)
    }
}
