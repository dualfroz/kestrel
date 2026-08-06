package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.DeleteResult
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

class ForeignKeyTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private fun seedDepartmentsAndEmployees() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run(
            "CREATE TABLE employees (" +
                "id INTEGER PRIMARY KEY, name TEXT NOT NULL, dept_id INTEGER REFERENCES departments(id))"
        )
        run("INSERT INTO departments VALUES (1, 'eng'), (2, 'ops')")
    }

    @Test
    fun insertRejectsForeignKeyViolation() {
        seedDepartmentsAndEmployees()
        val error = assertFailsWith<KestrelException> {
            run("INSERT INTO employees VALUES (1, 'ada', 99)")
        }
        assertTrue(error.message!!.contains("FOREIGN KEY"))
    }

    @Test
    fun insertAllowsNullForeignKeyValue() {
        seedDepartmentsAndEmployees()
        val result = exec("INSERT INTO employees VALUES (1, 'ada', NULL)")
        assertEquals(InsertResult(1), result)
    }

    @Test
    fun insertSucceedsWhenReferencedRowExists() {
        seedDepartmentsAndEmployees()
        val result = exec("INSERT INTO employees VALUES (1, 'ada', 1)")
        assertEquals(InsertResult(1), result)
    }

    @Test
    fun updateRejectsForeignKeyViolationOnChildColumn() {
        seedDepartmentsAndEmployees()
        run("INSERT INTO employees VALUES (1, 'ada', 1)")
        assertFailsWith<KestrelException> {
            run("UPDATE employees SET dept_id = 99 WHERE id = 1")
        }
    }

    @Test
    fun foreignKeyRequiresReferencedTableToExist() {
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER REFERENCES missing(id))")
        }
    }

    @Test
    fun foreignKeyRequiresReferencedColumnToBeAPrimaryKey() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER PRIMARY KEY, x TEXT REFERENCES departments(name))")
        }
    }

    @Test
    fun tableLevelForeignKeySyntaxIsEquivalentToColumnLevel() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER, " +
                "FOREIGN KEY (dept_id) REFERENCES departments(id))"
        )
        run("INSERT INTO departments VALUES (1, 'eng')")
        assertEquals(InsertResult(1), exec("INSERT INTO employees VALUES (1, 1)"))
        assertFailsWith<KestrelException> { run("INSERT INTO employees VALUES (2, 99)") }
    }

    @Test
    fun deleteIsRestrictedByDefaultWhenChildrenReferenceTheRow() {
        seedDepartmentsAndEmployees()
        run("INSERT INTO employees VALUES (1, 'ada', 1)")
        val error = assertFailsWith<KestrelException> { run("DELETE FROM departments WHERE id = 1") }
        assertTrue(error.message!!.contains("FOREIGN KEY"))
        assertEquals(1, select("SELECT * FROM departments WHERE id = 1").rows.size)
    }

    @Test
    fun deleteWithoutReferencingChildrenSucceeds() {
        seedDepartmentsAndEmployees()
        assertEquals(DeleteResult(1), exec("DELETE FROM departments WHERE id = 2"))
    }

    private fun seedCascadeDepartments() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                "dept_id INTEGER REFERENCES departments(id) ON DELETE CASCADE ON UPDATE CASCADE)"
        )
        run("INSERT INTO departments VALUES (1, 'eng'), (2, 'ops')")
        run("INSERT INTO employees VALUES (1, 'ada', 1), (2, 'linus', 1), (3, 'grace', 2)")
    }

    @Test
    fun onDeleteCascadeRemovesReferencingChildren() {
        seedCascadeDepartments()
        assertEquals(DeleteResult(1), exec("DELETE FROM departments WHERE id = 1"))
        val remaining = select("SELECT name FROM employees ORDER BY id")
        assertEquals(listOf(listOf(text("grace"))), remaining.rows)
    }

    @Test
    fun onUpdateCascadeChangesChildForeignKeyWhenParentPrimaryKeyChanges() {
        seedCascadeDepartments()
        run("UPDATE departments SET id = 10 WHERE id = 1")
        val depts = select("SELECT dept_id FROM employees ORDER BY id")
        assertEquals(listOf(listOf(int(10)), listOf(int(10)), listOf(int(2))), depts.rows)
    }

    @Test
    fun onUpdateRestrictByDefaultBlocksPrimaryKeyChangeWhenChildrenReference() {
        seedDepartmentsAndEmployees()
        run("INSERT INTO employees VALUES (1, 'ada', 1)")
        assertFailsWith<KestrelException> { run("UPDATE departments SET id = 99 WHERE id = 1") }
    }

    private fun seedSetNullDepartments(childNotNull: Boolean) {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL)")
        val childColumn = if (childNotNull) "dept_id INTEGER NOT NULL" else "dept_id INTEGER"
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                "$childColumn REFERENCES departments(id) ON DELETE SET NULL)"
        )
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("INSERT INTO employees VALUES (1, 'ada', 1)")
    }

    @Test
    fun onDeleteSetNullNullsOutReferencingChildren() {
        seedSetNullDepartments(childNotNull = false)
        exec("DELETE FROM departments WHERE id = 1")
        val row = select("SELECT dept_id FROM employees WHERE id = 1")
        assertEquals(listOf(listOf(Value.NullValue)), row.rows)
    }

    @Test
    fun onDeleteSetNullFailsWhenChildColumnIsNotNull() {
        seedSetNullDepartments(childNotNull = true)
        val error = assertFailsWith<KestrelException> { run("DELETE FROM departments WHERE id = 1") }
        assertTrue(error.message!!.contains("NOT NULL"))
    }

    @Test
    fun selfReferencingForeignKeyIsAllowedAndCascadesThroughMultipleLevels() {
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, name TEXT NOT NULL, " +
                "manager_id INTEGER REFERENCES employees(id) ON DELETE CASCADE)"
        )
        run(
            "INSERT INTO employees VALUES " +
                "(1, 'ceo', NULL), (2, 'vp', 1), (3, 'manager', 2), (4, 'ic', 3)"
        )
        assertEquals(DeleteResult(1), exec("DELETE FROM employees WHERE id = 1"))
        val remaining = select("SELECT id FROM employees")
        assertEquals(emptyList(), remaining.rows)
    }

    @Test
    fun dropTableIsRestrictedByDefaultWhenAForeignKeyReferencesIt() {
        seedDepartmentsAndEmployees()
        val error = assertFailsWith<KestrelException> { run("DROP TABLE departments") }
        assertTrue(error.message!!.contains("FOREIGN KEY"))
    }

    @Test
    fun dropTableCascadeDropsTheConstraintButKeepsTheChildTableAndItsRows() {
        seedDepartmentsAndEmployees()
        run("INSERT INTO employees VALUES (1, 'ada', 1)")
        exec("DROP TABLE departments CASCADE")
        assertEquals(listOf("employees"), catalog.tableNames)
        assertEquals(1, select("SELECT * FROM employees").rows.size)
        // the constraint is gone, so a now-dangling value is accepted
        assertEquals(InsertResult(1), exec("INSERT INTO employees VALUES (2, 'linus', 99)"))
    }

    @Test
    fun rollbackAfterCascadeDeleteRestoresChildRows() {
        seedCascadeDepartments()
        run("BEGIN")
        run("DELETE FROM departments WHERE id = 1")
        assertEquals(1, select("SELECT * FROM employees").rows.size)
        run("ROLLBACK")
        assertEquals(3, select("SELECT * FROM employees").rows.size)
        assertEquals(2, select("SELECT * FROM departments").rows.size)
    }

    @Test
    fun rollbackAfterCascadeDropRestoresTheForeignKeyConstraint() {
        seedDepartmentsAndEmployees()
        run("BEGIN")
        run("DROP TABLE departments CASCADE")
        run("ROLLBACK")
        assertFailsWith<KestrelException> {
            run("INSERT INTO employees VALUES (1, 'ada', 99)")
        }
    }
}
