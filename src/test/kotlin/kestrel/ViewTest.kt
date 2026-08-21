package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.CreateViewResult
import kestrel.engine.DropViewResult
import kestrel.engine.Executor
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ViewTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private fun seedUsers() {
        run("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER, dept TEXT)")
        run(
            "INSERT INTO users VALUES " +
                "(1, 'ada', 36, 'eng'), (2, 'linus', 54, 'eng'), (3, 'grace', 85, 'navy'), (4, 'alan', 41, NULL)"
        )
    }

    @Test
    fun createViewReturnsResultAndRegistersName() {
        seedUsers()
        val result = exec("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 40") as CreateViewResult
        assertEquals("adults", result.viewName)
        assertEquals(listOf("adults"), catalog.viewNames)
    }

    @Test
    fun queryingAViewRunsItsDefiningQueryFresh() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 40")
        val first = select("SELECT name FROM adults ORDER BY id ASC")
        assertEquals(listOf(text("linus"), text("grace"), text("alan")), first.rows.map { it.single() })

        run("INSERT INTO users VALUES (5, 'margaret', 61, 'navy')")
        val second = select("SELECT name FROM adults ORDER BY id ASC")
        assertEquals(
            listOf(text("linus"), text("grace"), text("alan"), text("margaret")),
            second.rows.map { it.single() }
        )
    }

    @Test
    fun viewCanBeUsedAsAJoinTarget() {
        seedUsers()
        run("CREATE TABLE bonuses (dept TEXT, amount INTEGER)")
        run("INSERT INTO bonuses VALUES ('eng', 1000), ('navy', 500)")
        run("CREATE VIEW eng_staff AS SELECT id, name, dept FROM users WHERE dept = 'eng'")
        val result = select(
            "SELECT eng_staff.name, bonuses.amount FROM eng_staff " +
                "JOIN bonuses ON eng_staff.dept = bonuses.dept ORDER BY eng_staff.id ASC"
        )
        assertEquals(
            listOf(listOf(text("ada"), int(1000)), listOf(text("linus"), int(1000))),
            result.rows
        )
    }

    @Test
    fun viewOverAggregateQueryWorks() {
        seedUsers()
        run("CREATE VIEW dept_counts AS SELECT dept, COUNT(*) AS n FROM users GROUP BY dept")
        val result = select("SELECT dept, n FROM dept_counts WHERE n > 1 ORDER BY dept ASC")
        assertEquals(listOf(listOf(text("eng"), int(2))), result.rows)
    }

    @Test
    fun viewDefinitionCanReferenceAnotherView() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id, name, age FROM users WHERE age >= 40")
        run("CREATE VIEW seniors AS SELECT id, name FROM adults WHERE age >= 60")
        val result = select("SELECT name FROM seniors")
        assertEquals(listOf(listOf(text("grace"))), result.rows)
    }

    @Test
    fun creatingAViewValidatesColumnsEagerly() {
        seedUsers()
        assertFailsWith<KestrelException> { run("CREATE VIEW broken AS SELECT nope FROM users") }
        assertTrue(catalog.viewNames.isEmpty())
    }

    @Test
    fun creatingAViewOverAnUnknownTableFails() {
        assertFailsWith<KestrelException> { run("CREATE VIEW v AS SELECT * FROM missing") }
    }

    @Test
    fun duplicateViewNameFailsWithoutOrReplace() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        assertFailsWith<KestrelException> { run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 21") }
    }

    @Test
    fun createOrReplaceViewSwapsTheDefinition() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        run("CREATE OR REPLACE VIEW adults AS SELECT id FROM users WHERE age >= 50")
        val result = select("SELECT id FROM adults ORDER BY id ASC")
        assertEquals(listOf(listOf(int(2)), listOf(int(3))), result.rows)
    }

    @Test
    fun dropViewRemovesItAndItStopsBeingQueryable() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        val result = exec("DROP VIEW adults") as DropViewResult
        assertEquals("adults", result.viewName)
        assertFailsWith<KestrelException> { run("SELECT * FROM adults") }
    }

    @Test
    fun droppingAnUnknownViewFails() {
        assertFailsWith<KestrelException> { run("DROP VIEW nope") }
    }

    @Test
    fun cannotCreateTableWithSameNameAsAnExistingView() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        assertFailsWith<KestrelException> { run("CREATE TABLE adults (id INTEGER)") }
    }

    @Test
    fun cannotCreateViewWithSameNameAsAnExistingTable() {
        seedUsers()
        assertFailsWith<KestrelException> { run("CREATE VIEW users AS SELECT id FROM users") }
    }

    @Test
    fun dropTableRefusesAViewNameAndDropViewRefusesATableName() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        assertFailsWith<KestrelException> { run("DROP TABLE adults") }
        assertFailsWith<KestrelException> { run("DROP VIEW users") }
    }

    @Test
    fun insertUpdateDeleteAgainstAViewAreRejected() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 18")
        assertFailsWith<KestrelException> { run("INSERT INTO adults VALUES (9, 'x')") }
        assertFailsWith<KestrelException> { run("UPDATE adults SET name = 'x'") }
        assertFailsWith<KestrelException> { run("DELETE FROM adults") }
    }

    @Test
    fun creatingAnIndexOnAViewIsRejected() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 18")
        assertFailsWith<KestrelException> { run("CREATE INDEX idx ON adults (id)") }
    }

    @Test
    fun mutuallyRecursiveViewsFailInsteadOfOverflowingTheStack() {
        seedUsers()
        run("CREATE VIEW a AS SELECT id FROM users")
        run("CREATE VIEW b AS SELECT id FROM a")
        run("CREATE OR REPLACE VIEW a AS SELECT id FROM b")
        assertFailsWith<KestrelException> { run("SELECT * FROM a") }
    }

    @Test
    fun selfReferencingViewFailsCleanly() {
        seedUsers()
        run("CREATE VIEW a AS SELECT id FROM users")
        run("CREATE OR REPLACE VIEW a AS SELECT id FROM a")
        assertFailsWith<KestrelException> { run("SELECT * FROM a") }
    }

    @Test
    fun rollbackUndoesCreateViewAndDropView() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        run("BEGIN")
        run("CREATE VIEW seniors AS SELECT id FROM users WHERE age >= 60")
        run("DROP VIEW adults")
        run("ROLLBACK")
        assertEquals(listOf("adults"), catalog.viewNames)
    }

    @Test
    fun commitKeepsViewChanges() {
        seedUsers()
        run("BEGIN")
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        run("COMMIT")
        assertEquals(listOf("adults"), catalog.viewNames)
    }

    @Test
    fun dropTableWithADependentViewFailsByDefaultAndAlsoWithExplicitRestrict() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        assertFailsWith<KestrelException> { run("DROP TABLE users") }
        assertFailsWith<KestrelException> { run("DROP TABLE users RESTRICT") }
        assertEquals(listOf("users"), catalog.tableNames)
        assertEquals(listOf("adults"), catalog.viewNames)
    }

    @Test
    fun dropTableCascadeDropsTheTableAndItsDependentView() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        run("DROP TABLE users CASCADE")
        assertTrue(catalog.tableNames.isEmpty())
        assertTrue(catalog.viewNames.isEmpty())
    }

    @Test
    fun dropViewWithADependentViewFailsByDefault() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        run("CREATE VIEW seniorAdults AS SELECT id FROM adults WHERE id > 0")
        assertFailsWith<KestrelException> { run("DROP VIEW adults") }
        assertEquals(listOf("adults", "seniorAdults"), catalog.viewNames)
    }

    @Test
    fun dropViewCascadeDropsATransitiveChainOfDependentViews() {
        seedUsers()
        run("CREATE VIEW a AS SELECT id FROM users WHERE age >= 18")
        run("CREATE VIEW b AS SELECT id FROM a WHERE id > 0")
        run("CREATE VIEW c AS SELECT id FROM b WHERE id > 0")
        run("DROP VIEW a CASCADE")
        assertTrue(catalog.viewNames.isEmpty())
    }

    @Test
    fun dropTableCascadeHandlesADiamondOfDependentViewsWithoutFailing() {
        seedUsers()
        run("CREATE VIEW b AS SELECT id FROM users WHERE age >= 18")
        run("CREATE VIEW c AS SELECT id FROM users WHERE age >= 21")
        run("CREATE VIEW d AS SELECT b.id FROM b JOIN c ON b.id = c.id")
        run("DROP TABLE users CASCADE")
        assertTrue(catalog.tableNames.isEmpty())
        assertTrue(catalog.viewNames.isEmpty())
    }

    @Test
    fun rollbackUndoesACascadeDrop() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id FROM users WHERE age >= 18")
        run("BEGIN")
        run("DROP TABLE users CASCADE")
        run("ROLLBACK")
        assertEquals(listOf("users"), catalog.tableNames)
        assertEquals(listOf("adults"), catalog.viewNames)
        val result = select("SELECT id FROM adults ORDER BY id ASC")
        assertTrue(result.rows.isNotEmpty())
    }

    @Test
    fun explainOverAViewDescribesTheOuterQuery() {
        seedUsers()
        run("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 18")
        val explain = exec("EXPLAIN SELECT * FROM adults") as kestrel.engine.ExplainResult
        assertTrue(explain.plan.any { it.contains("adults") })
    }

    @Test
    fun columnTypeOfAViewIsInferredFromActualData() {
        seedUsers()
        run("CREATE VIEW names AS SELECT name FROM users")
        val result = select("SELECT name FROM names WHERE name = 'ada'")
        assertEquals(listOf(listOf(text("ada"))), result.rows)
    }
}
