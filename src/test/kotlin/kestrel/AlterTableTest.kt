package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.AlterTableResult
import kestrel.engine.ExplainResult
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.engine.UpdateResult
import kestrel.persistence.SqlWriter
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AlterTableTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun explain(sql: String): List<String> = (exec(sql) as ExplainResult).plan

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)
    private fun real(value: Double) = Value.RealValue(value)
    private fun decimal(value: String) = Value.DecimalValue(BigDecimal(value))

    @Test
    fun addColumnAppendsNullToExistingRowsWhenNoDefaultIsGiven() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO t VALUES (1, 'ada')")
        val result = exec("ALTER TABLE t ADD COLUMN age INTEGER")
        assertEquals(AlterTableResult("t"), result)
        assertEquals(listOf(listOf(int(1), text("ada"), Value.NullValue)), select("SELECT * FROM t").rows)
    }

    @Test
    fun addColumnBackfillsExistingRowsWithDefaultValue() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO t VALUES (1, 'ada'), (2, 'linus')")
        run("ALTER TABLE t ADD COLUMN status TEXT DEFAULT 'active'")
        assertEquals(
            listOf(listOf(text("active")), listOf(text("active"))),
            select("SELECT status FROM t ORDER BY id ASC").rows
        )
    }

    @Test
    fun addColumnRejectsNotNullWithoutDefaultWhenTableHasRows() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ADD COLUMN name TEXT NOT NULL") }
        assertTrue(error.message!!.contains("NOT NULL"))
    }

    @Test
    fun addColumnAllowsNotNullWithoutDefaultWhenTableIsEmpty() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("ALTER TABLE t ADD COLUMN name TEXT NOT NULL")
        run("INSERT INTO t VALUES (1, 'ada')")
        assertEquals(listOf(listOf(text("ada"))), select("SELECT name FROM t").rows)
        assertFailsWith<KestrelException> { run("INSERT INTO t (id) VALUES (2)") }
    }

    @Test
    fun addColumnWithNotNullAndDefaultBackfillsAndKeepsEnforcingGoingForward() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        run("ALTER TABLE t ADD COLUMN score INTEGER NOT NULL DEFAULT 0")
        assertEquals(listOf(listOf(int(0))), select("SELECT score FROM t").rows)
        assertFailsWith<KestrelException> { run("INSERT INTO t (id, score) VALUES (2, NULL)") }
    }

    @Test
    fun addColumnRejectsDuplicateColumnName() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ADD COLUMN name TEXT") }
        assertTrue(error.message!!.contains("duplicate column"))
    }

    @Test
    fun addColumnRequiresTableToExist() {
        assertFailsWith<KestrelException> { run("ALTER TABLE missing ADD COLUMN x INTEGER") }
    }

    @Test
    fun newColumnParticipatesInInsertUpdateWhereAndSelect() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO t VALUES (1, 'ada')")
        run("ALTER TABLE t ADD COLUMN age INTEGER DEFAULT 0")
        run("INSERT INTO t VALUES (2, 'linus', 54)")
        run("UPDATE t SET age = 36 WHERE id = 1")
        assertEquals(
            listOf(listOf(text("linus"))),
            select("SELECT name FROM t WHERE age > 40").rows
        )
    }

    @Test
    fun defaultExpressionCanBeAnArithmeticConstantNotJustALiteral() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        run("ALTER TABLE t ADD COLUMN adjustment INTEGER DEFAULT -1 + 10")
        assertEquals(listOf(listOf(int(9))), select("SELECT adjustment FROM t").rows)
    }

    @Test
    fun addColumnRejectsPrimaryKeyReferencesUniqueAndCheckConstraints() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        assertFailsWith<ParseException> { run("ALTER TABLE t ADD COLUMN id2 INTEGER PRIMARY KEY") }
        assertFailsWith<ParseException> { run("ALTER TABLE t ADD COLUMN dept_id INTEGER REFERENCES departments(id)") }
        assertFailsWith<ParseException> { run("ALTER TABLE t ADD COLUMN email TEXT UNIQUE") }
        assertFailsWith<ParseException> { run("ALTER TABLE t ADD COLUMN age INTEGER CHECK (age > 0)") }
    }

    @Test
    fun dropColumnRemovesColumnFromSchemaAndData() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, bio TEXT)")
        run("INSERT INTO t VALUES (1, 'ada', 'mathematician')")
        run("ALTER TABLE t DROP COLUMN bio")
        val result = select("SELECT * FROM t")
        assertEquals(listOf("id", "name"), result.columns)
        assertEquals(listOf(listOf(int(1), text("ada"))), result.rows)
    }

    @Test
    fun dropColumnRejectsWhenItIsTheOnlyColumn() {
        run("CREATE TABLE t (id INTEGER)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t DROP COLUMN id") }
        assertTrue(error.message!!.contains("no columns"))
    }

    @Test
    fun dropColumnRejectsPrimaryKeyColumnEvenWithCascade() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t DROP COLUMN id CASCADE") }
        assertTrue(error.message!!.contains("PRIMARY KEY"))
    }

    @Test
    fun dropColumnRestrictsWhenReferencedByAnotherTablesForeignKey() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, code TEXT UNIQUE)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_code TEXT REFERENCES departments(code))")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE departments DROP COLUMN code") }
        assertTrue(error.message!!.contains("FOREIGN KEY"))
    }

    @Test
    fun dropColumnCascadeRemovesTheReferencingForeignKey() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, code TEXT UNIQUE)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_code TEXT REFERENCES departments(code))")
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("ALTER TABLE departments DROP COLUMN code CASCADE")
        run("INSERT INTO employees VALUES (1, 'ghost')")
        assertEquals(listOf(listOf(text("ghost"))), select("SELECT dept_code FROM employees").rows)
    }

    @Test
    fun dropColumnRestrictsWhenUsedByItsOwnForeignKey() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE employees DROP COLUMN dept_id") }
        assertTrue(error.message!!.contains("FOREIGN KEY"))
    }

    @Test
    fun dropColumnCascadeRemovesItsOwnForeignKey() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        run("ALTER TABLE employees DROP COLUMN dept_id CASCADE")
        assertEquals(listOf("id"), select("SELECT * FROM employees").columns)
    }

    @Test
    fun dropColumnRestrictsWhenUsedByAUniqueConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t DROP COLUMN email") }
        assertTrue(error.message!!.contains("UNIQUE"))
    }

    @Test
    fun dropColumnCascadeRemovesTheUniqueConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        run("INSERT INTO t VALUES (1, 'a@x.com')")
        run("ALTER TABLE t DROP COLUMN email CASCADE")
        run("INSERT INTO t VALUES (2)")
        assertEquals(2, select("SELECT * FROM t").rows.size)
    }

    @Test
    fun dropColumnRestrictsWhenUsedByACheckConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t DROP COLUMN age") }
        assertTrue(error.message!!.contains("CHECK"))
    }

    @Test
    fun dropColumnCascadeRemovesTheCheckConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        run("ALTER TABLE t DROP COLUMN age CASCADE")
        run("INSERT INTO t VALUES (1)")
        assertEquals(1, select("SELECT * FROM t").rows.size)
    }

    @Test
    fun dropColumnRestrictsWhenUsedByAnIndex() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        run("CREATE INDEX idx_age ON t (age)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t DROP COLUMN age") }
        assertTrue(error.message!!.contains("idx_age"))
    }

    @Test
    fun dropColumnCascadeRemovesTheIndex() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        run("CREATE INDEX idx_age ON t (age)")
        run("ALTER TABLE t DROP COLUMN age CASCADE")
        assertTrue(catalog.indexNames.isEmpty())
    }

    @Test
    fun renameColumnRenamesSchemaAndPreservesData() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, bio TEXT)")
        run("INSERT INTO t VALUES (1, 'mathematician')")
        run("ALTER TABLE t RENAME COLUMN bio TO biography")
        val result = select("SELECT * FROM t")
        assertEquals(listOf("id", "biography"), result.columns)
        assertEquals(listOf(listOf(int(1), text("mathematician"))), result.rows)
    }

    @Test
    fun renameColumnRejectsUnknownColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        assertFailsWith<KestrelException> { run("ALTER TABLE t RENAME COLUMN missing TO whatever") }
    }

    @Test
    fun renameColumnRejectsWhenNewNameAlreadyExists() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t RENAME COLUMN name TO id") }
        assertTrue(error.message!!.contains("already exists"))
    }

    @Test
    fun renameColumnUpdatesCompositePrimaryKeyListing() {
        run("CREATE TABLE t (region TEXT, seq INTEGER, PRIMARY KEY (region, seq))")
        run("INSERT INTO t VALUES ('eu', 1)")
        run("ALTER TABLE t RENAME COLUMN region TO area")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES ('eu', 1)") }
        run("INSERT INTO t VALUES ('eu', 2)")
        assertEquals(2, select("SELECT * FROM t").rows.size)
    }

    @Test
    fun renameColumnUpdatesItsOwnForeignKeyColumns() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))")
        run("INSERT INTO departments VALUES (1)")
        run("ALTER TABLE employees RENAME COLUMN dept_id TO department_id")
        assertFailsWith<KestrelException> { run("INSERT INTO employees VALUES (1, 99)") }
        run("INSERT INTO employees VALUES (1, 1)")
        assertEquals(1, select("SELECT * FROM employees").rows.size)
    }

    @Test
    fun renameColumnPropagatesToOtherTablesForeignKeyReferencedColumns() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id) ON DELETE CASCADE)")
        run("INSERT INTO departments VALUES (1)")
        run("INSERT INTO employees VALUES (1, 1)")
        run("ALTER TABLE departments RENAME COLUMN id TO dept_pk")
        run("DELETE FROM departments WHERE dept_pk = 1")
        assertTrue(select("SELECT * FROM employees").rows.isEmpty(), "cascade must still work through the renamed referenced column")
    }

    @Test
    fun renameColumnUpdatesAUniqueConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE)")
        run("INSERT INTO t VALUES (1, 'a@x.com')")
        run("ALTER TABLE t RENAME COLUMN email TO contact_email")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (2, 'a@x.com')") }
    }

    @Test
    fun renameColumnUpdatesACheckConstraintAndStillEnforcesIt() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0))")
        run("ALTER TABLE t RENAME COLUMN age TO years")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (1, -5)") }
        run("INSERT INTO t VALUES (1, 30)")
        assertEquals(listOf(listOf(int(30))), select("SELECT years FROM t").rows)
    }

    @Test
    fun renameColumnUpdatesAnIndexAndThePlannerStillUsesIt() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        run("INSERT INTO t VALUES (1, 30), (2, 40)")
        run("CREATE INDEX idx_age ON t (age)")
        run("ALTER TABLE t RENAME COLUMN age TO years")
        val plan = explain("EXPLAIN SELECT id FROM t WHERE years = 30").joinToString("\n")
        assertTrue(plan.contains("Index Scan using idx_age"), plan)
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM t WHERE years = 30").rows)
    }

    @Test
    fun renameTableRenamesAndPreservesData() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO t VALUES (1, 'ada')")
        val result = exec("ALTER TABLE t RENAME TO people")
        assertEquals(AlterTableResult("people"), result)
        assertEquals(listOf(listOf(int(1), text("ada"))), select("SELECT * FROM people").rows)
        assertFailsWith<KestrelException> { run("SELECT * FROM t") }
        assertEquals(listOf("people"), catalog.tableNames)
    }

    @Test
    fun renameTableRejectsWhenNewNameAlreadyExistsAsTable() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE other (id INTEGER PRIMARY KEY)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t RENAME TO other") }
        assertTrue(error.message!!.contains("already exists"))
    }

    @Test
    fun renameTableRejectsWhenNewNameAlreadyExistsAsView() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE other (id INTEGER PRIMARY KEY)")
        run("CREATE VIEW v AS SELECT * FROM other")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t RENAME TO v") }
        assertTrue(error.message!!.contains("already exists"))
    }

    @Test
    fun renameTableRejectsRenamingAView() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("CREATE VIEW v AS SELECT * FROM t")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE v RENAME TO w") }
        assertTrue(error.message!!.contains("cannot modify view"))
    }

    @Test
    fun renameTablePropagatesToOtherTablesForeignKeyReference() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id) ON DELETE CASCADE)")
        run("INSERT INTO departments VALUES (1)")
        run("INSERT INTO employees VALUES (1, 1)")
        run("ALTER TABLE departments RENAME TO divisions")
        run("INSERT INTO divisions VALUES (2)")
        run("INSERT INTO employees VALUES (2, 2)")
        assertFailsWith<KestrelException> { run("INSERT INTO employees VALUES (3, 99)") }
        run("DELETE FROM divisions WHERE id = 1")
        assertEquals(listOf(listOf(int(2))), select("SELECT id FROM employees").rows)
    }

    @Test
    fun renameTableUpdatesItsOwnSelfReferencingForeignKey() {
        run(
            "CREATE TABLE staff (id INTEGER PRIMARY KEY, manager_id INTEGER REFERENCES staff(id))"
        )
        run("INSERT INTO staff VALUES (1, NULL)")
        run("ALTER TABLE staff RENAME TO personnel")
        run("INSERT INTO personnel VALUES (2, 1)")
        assertFailsWith<KestrelException> { run("INSERT INTO personnel VALUES (3, 99)") }
        assertEquals(2, select("SELECT * FROM personnel").rows.size)
    }

    @Test
    fun renameTableUpdatesAnIndexAndThePlannerStillUsesIt() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        run("INSERT INTO t VALUES (1, 30), (2, 40)")
        run("CREATE INDEX idx_age ON t (age)")
        run("ALTER TABLE t RENAME TO people")
        val plan = explain("EXPLAIN SELECT id FROM people WHERE age = 30").joinToString("\n")
        assertTrue(plan.contains("Index Scan using idx_age on people"), plan)
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM people WHERE age = 30").rows)
    }

    @Test
    fun renameTablePropagatesIntoASimpleViewDefinition() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        run("INSERT INTO t VALUES (1, 'ada')")
        run("CREATE VIEW v AS SELECT * FROM t")
        run("ALTER TABLE t RENAME TO people")
        assertEquals(listOf(listOf(int(1), text("ada"))), select("SELECT * FROM v").rows)
    }

    @Test
    fun renameTablePropagatesIntoAViewJoinAndASubqueryExpression() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("INSERT INTO employees VALUES (1, 1)")
        run(
            "CREATE VIEW v AS SELECT e.id FROM employees e JOIN departments d ON e.dept_id = d.id " +
                "WHERE d.id IN (SELECT id FROM departments)"
        )
        run("ALTER TABLE departments RENAME TO divisions")
        assertEquals(listOf(listOf(int(1))), select("SELECT * FROM v").rows)
    }

    @Test
    fun renameTablePropagatesIntoADerivedTableAndAUnion() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1), (2)")
        run(
            "CREATE VIEW v AS SELECT * FROM (SELECT id FROM t) sub " +
                "UNION SELECT id FROM t WHERE id = 2"
        )
        run("ALTER TABLE t RENAME TO renamed")
        assertEquals(listOf(listOf(int(1)), listOf(int(2))), select("SELECT * FROM v ORDER BY id").rows)
    }

    @Test
    fun renameTableDoesNotTouchATableSourceShadowedByACteOfTheSameName() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        run("CREATE VIEW v AS WITH t AS (SELECT 99 AS id FROM t) SELECT * FROM t")
        run("ALTER TABLE t RENAME TO renamed")
        assertEquals(listOf(listOf(int(99))), select("SELECT * FROM v").rows)
        assertEquals(listOf(listOf(int(1))), select("SELECT * FROM renamed").rows)
    }

    @Test
    fun renameTableRenamingToItsOwnNameIsANoOp() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        run("INSERT INTO t VALUES (1)")
        run("ALTER TABLE t RENAME TO t")
        assertEquals(listOf(listOf(int(1))), select("SELECT * FROM t").rows)
    }

    @Test
    fun insertUsesColumnDefaultWhenColumnOmittedFromColumnList() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'pending')")
        run("INSERT INTO t (id) VALUES (1)")
        assertEquals(listOf(listOf(text("pending"))), select("SELECT status FROM t").rows)
    }

    @Test
    fun insertDoesNotUseDefaultWhenExplicitNullIsGiven() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'pending')")
        run("INSERT INTO t (id, status) VALUES (1, NULL)")
        assertEquals(listOf(listOf(Value.NullValue)), select("SELECT status FROM t").rows)
    }

    @Test
    fun defaultAppliesIndependentlyToEachOmittedColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'new', score INTEGER DEFAULT 0)")
        run("INSERT INTO t (id) VALUES (1)")
        val result = exec("INSERT INTO t (id) VALUES (2)")
        assertEquals(InsertResult(1), result)
        assertEquals(
            listOf(listOf(text("new"), int(0)), listOf(text("new"), int(0))),
            select("SELECT status, score FROM t ORDER BY id ASC").rows
        )
    }

    @Test
    fun createTableRejectsColumnReferenceInDefault() {
        val error = assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER PRIMARY KEY, twice INTEGER DEFAULT id)")
        }
        assertTrue(error.message!!.contains("DEFAULT"))
    }

    @Test
    fun createTableRejectsSubqueryInDefault() {
        run("CREATE TABLE other (x INTEGER)")
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER DEFAULT (SELECT COUNT(*) FROM other))")
        }
    }

    @Test
    fun createTableRejectsAggregateInDefault() {
        val error = assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER DEFAULT COUNT(*))")
        }
        assertTrue(error.message!!.contains("aggregate"))
    }

    @Test
    fun createTableValidatesDefaultTypeEagerly() {
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER DEFAULT 'not a number')")
        }
    }

    @Test
    fun updateSetToDefaultResetsTheColumnToItsDeclaredDefault() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'pending')")
        run("INSERT INTO t VALUES (1, 'shipped')")
        val result = exec("UPDATE t SET status = DEFAULT WHERE id = 1")
        assertEquals(UpdateResult(1), result)
        assertEquals(listOf(listOf(text("pending"))), select("SELECT status FROM t").rows)
    }

    @Test
    fun updateSetToDefaultFallsBackToNullWhenTheColumnHasNoDeclaredDefault() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, note TEXT)")
        run("INSERT INTO t VALUES (1, 'keep me')")
        run("UPDATE t SET note = DEFAULT WHERE id = 1")
        assertEquals(listOf(listOf(Value.NullValue)), select("SELECT note FROM t").rows)
    }

    @Test
    fun updateSetToDefaultIsEvaluatedFreshPerRowJustLikeInsert() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, score INTEGER DEFAULT 0)")
        run("INSERT INTO t VALUES (1, 10), (2, 20), (3, 30)")
        val result = exec("UPDATE t SET score = DEFAULT WHERE id IN (1, 2)")
        assertEquals(UpdateResult(2), result)
        assertEquals(
            listOf(listOf(int(1), int(0)), listOf(int(2), int(0)), listOf(int(3), int(30))),
            select("SELECT id, score FROM t ORDER BY id ASC").rows
        )
    }

    @Test
    fun updateCanMixADefaultAssignmentWithARegularAssignmentInTheSameStatement() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, status TEXT DEFAULT 'pending')")
        run("INSERT INTO t VALUES (1, 'ada', 'shipped')")
        run("UPDATE t SET name = 'grace', status = DEFAULT WHERE id = 1")
        assertEquals(listOf(listOf(text("grace"), text("pending"))), select("SELECT name, status FROM t").rows)
    }

    @Test
    fun updateSetToDefaultFailsNotNullValidationWhenThereIsNoDefaultToFallBackOn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT NOT NULL)")
        run("INSERT INTO t VALUES (1, 'shipped')")
        val error = assertFailsWith<KestrelException> {
            run("UPDATE t SET status = DEFAULT WHERE id = 1")
        }
        assertTrue(error.message!!.contains("status"), error.message!!)
    }

    @Test
    fun updateSetToDefaultRoundTripsThroughSqlWriter() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'pending')")
        run("INSERT INTO t VALUES (1, 'shipped')")
        val statement = Parser(Lexer("UPDATE t SET status = DEFAULT WHERE id = 1").tokenize()).parseProgram().single()
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("status = DEFAULT"), rendered)
        run(rendered)
        assertEquals(listOf(listOf(text("pending"))), select("SELECT status FROM t").rows)
    }

    @Test
    fun insertValuesWithLiteralDefaultUsesTheColumnsDeclaredDefault() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'pending')")
        val result = exec("INSERT INTO t VALUES (1, DEFAULT)")
        assertEquals(InsertResult(1), result)
        assertEquals(listOf(listOf(text("pending"))), select("SELECT status FROM t").rows)
    }

    @Test
    fun insertValuesWithLiteralDefaultFallsBackToNullWhenTheColumnHasNoDeclaredDefault() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, note TEXT)")
        run("INSERT INTO t VALUES (1, DEFAULT)")
        assertEquals(listOf(listOf(Value.NullValue)), select("SELECT note FROM t").rows)
    }

    @Test
    fun insertValuesLiteralDefaultIsDistinctFromAnExplicitNullLiteral() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'pending')")
        run("INSERT INTO t VALUES (1, NULL)")
        assertEquals(listOf(listOf(Value.NullValue)), select("SELECT status FROM t").rows)
    }

    @Test
    fun insertValuesLiteralDefaultAppliesIndependentlyToEachRowInAMultiRowInsert() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, score INTEGER DEFAULT 0)")
        run("INSERT INTO t VALUES (1, DEFAULT), (2, 20), (3, DEFAULT)")
        assertEquals(
            listOf(listOf(int(1), int(0)), listOf(int(2), int(20)), listOf(int(3), int(0))),
            select("SELECT id, score FROM t ORDER BY id ASC").rows
        )
    }

    @Test
    fun insertValuesLiteralDefaultWorksWithAnExplicitColumnList() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, status TEXT DEFAULT 'pending')")
        run("INSERT INTO t (id, status, name) VALUES (1, DEFAULT, 'ada')")
        assertEquals(listOf(listOf(text("ada"), text("pending"))), select("SELECT name, status FROM t").rows)
    }

    @Test
    fun insertValuesLiteralDefaultFailsNotNullValidationWhenThereIsNoDefaultToFallBackOn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT NOT NULL)")
        val error = assertFailsWith<KestrelException> {
            run("INSERT INTO t VALUES (1, DEFAULT)")
        }
        assertTrue(error.message!!.contains("status"), error.message!!)
    }

    @Test
    fun insertValuesLiteralDefaultRoundTripsThroughSqlWriter() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'pending')")
        val statement = Parser(Lexer("INSERT INTO t VALUES (1, DEFAULT)").tokenize()).parseProgram().single()
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("DEFAULT"), rendered)
        run(rendered)
        assertEquals(listOf(listOf(text("pending"))), select("SELECT status FROM t").rows)
    }

    @Test
    fun addConstraintCheckSucceedsWhenEveryExistingRowSatisfiesIt() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        run("INSERT INTO t VALUES (1, 30), (2, 40)")
        val result = exec("ALTER TABLE t ADD CONSTRAINT age_positive CHECK (age > 0)")
        assertEquals(AlterTableResult("t"), result)
        val error = assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (3, -1)") }
        assertTrue(error.message!!.contains("age_positive"), error.message!!)
    }

    @Test
    fun addConstraintCheckRejectsWhenAnExistingRowAlreadyViolatesIt() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        run("INSERT INTO t VALUES (1, 30), (2, -5)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ADD CONSTRAINT age_positive CHECK (age > 0)") }
        assertTrue(error.message!!.contains("age_positive"), error.message!!)
        run("INSERT INTO t VALUES (3, -1)")
        assertEquals(2, select("SELECT id FROM t WHERE age < 0").rows.size)
    }

    @Test
    fun addConstraintCheckRejectsAReferenceToAnUnknownColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ADD CONSTRAINT c1 CHECK (bogus > 0)") }
        assertTrue(error.message!!.contains("bogus"), error.message!!)
    }

    @Test
    fun addConstraintCheckNameRoundTripsThroughSqlWriterDumpAndReplay() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER)")
        run("ALTER TABLE t ADD CONSTRAINT age_positive CHECK (age > 0)")
        val dumped = SqlWriter.dump(catalog)
        assertTrue(dumped.contains("CONSTRAINT age_positive CHECK"), dumped)
        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        for (statement in Parser(Lexer(dumped).tokenize()).parseProgram()) freshExecutor.execute(statement)
        val error = assertFailsWith<KestrelException> {
            freshExecutor.execute(Parser(Lexer("INSERT INTO t VALUES (5, -1)").tokenize()).parseProgram().single())
        }
        assertTrue(error.message!!.contains("age_positive"), error.message!!)
    }

    @Test
    fun addConstraintUniqueSucceedsWhenNoExistingRowsAreDuplicates() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT)")
        run("INSERT INTO t VALUES (1, 'a@x.com'), (2, 'b@x.com')")
        exec("ALTER TABLE t ADD CONSTRAINT uq_email UNIQUE (email)")
        run("INSERT INTO t VALUES (3, 'c@x.com')")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (4, 'c@x.com')") }
        assertTrue(error.message!!.contains("uq_email"), error.message!!)
    }

    @Test
    fun addConstraintUniqueRejectsWhenExistingRowsAreAlreadyDuplicated() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT)")
        run("INSERT INTO t VALUES (1, 'a@x.com'), (2, 'a@x.com')")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ADD CONSTRAINT uq_email UNIQUE (email)") }
        assertTrue(error.message!!.contains("uq_email"), error.message!!)
    }

    @Test
    fun addConstraintUniqueIgnoresNullValuesAmongExistingRows() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT)")
        run("INSERT INTO t VALUES (1, NULL), (2, NULL)")
        exec("ALTER TABLE t ADD CONSTRAINT uq_email UNIQUE (email)")
        run("INSERT INTO t VALUES (3, NULL)")
        assertEquals(3, select("SELECT id FROM t").rows.size)
    }

    @Test
    fun addConstraintUniqueRejectsAReferenceToAnUnknownColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ADD CONSTRAINT uq_bogus UNIQUE (bogus)") }
        assertTrue(error.message!!.contains("bogus"), error.message!!)
    }

    @Test
    fun addConstraintForeignKeySucceedsWhenEveryExistingRowHasAMatchOrIsNull() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("INSERT INTO employees VALUES (1, 1), (2, NULL)")
        exec("ALTER TABLE employees ADD CONSTRAINT fk_dept FOREIGN KEY (dept_id) REFERENCES departments (id)")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO employees VALUES (3, 99)") }
        assertTrue(error.message!!.contains("FOREIGN KEY constraint failed"), error.message!!)
    }

    @Test
    fun addConstraintForeignKeyRejectsWhenAnExistingRowIsAlreadyOrphaned() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("INSERT INTO employees VALUES (1, 1), (2, 99)")
        val error = assertFailsWith<KestrelException> {
            run("ALTER TABLE employees ADD CONSTRAINT fk_dept FOREIGN KEY (dept_id) REFERENCES departments (id)")
        }
        assertTrue(error.message!!.contains("FOREIGN KEY constraint failed"), error.message!!)
    }

    @Test
    fun addConstraintForeignKeySelfReferenceValidatesAgainstExistingSiblingRows() {
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, manager_id INTEGER)")
        run("INSERT INTO employees VALUES (1, NULL), (2, 1), (3, 99)")
        assertFailsWith<KestrelException> {
            run("ALTER TABLE employees ADD CONSTRAINT fk_manager FOREIGN KEY (manager_id) REFERENCES employees (id)")
        }
        run("UPDATE employees SET manager_id = 1 WHERE id = 3")
        exec("ALTER TABLE employees ADD CONSTRAINT fk_manager FOREIGN KEY (manager_id) REFERENCES employees (id)")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO employees VALUES (4, 100)") }
        assertTrue(error.message!!.contains("FOREIGN KEY constraint failed"), error.message!!)
    }

    @Test
    fun addConstraintForeignKeyRejectsWhenReferencedColumnsAreNotEnforcedUnique() {
        run("CREATE TABLE departments (id INTEGER, name TEXT)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER)")
        val error = assertFailsWith<KestrelException> {
            run("ALTER TABLE employees ADD CONSTRAINT fk_dept FOREIGN KEY (dept_id) REFERENCES departments (id)")
        }
        assertTrue(error.message!!.contains("PRIMARY KEY or UNIQUE"), error.message!!)
    }

    @Test
    fun addConstraintForeignKeyRejectsAColumnCountMismatch() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER)")
        val error = assertFailsWith<KestrelException> {
            run("ALTER TABLE employees ADD CONSTRAINT fk_bad FOREIGN KEY (dept_id) REFERENCES departments (id, name)")
        }
        assertTrue(error.message!!.contains("column(s)"), error.message!!)
    }

    @Test
    fun addConstraintForeignKeyOnDeleteCascadeAppliesAfterBeingAdded() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER)")
        run("INSERT INTO departments VALUES (1, 'eng')")
        run("INSERT INTO employees VALUES (1, 1), (2, 1)")
        exec("ALTER TABLE employees ADD CONSTRAINT fk_dept FOREIGN KEY (dept_id) REFERENCES departments (id) ON DELETE CASCADE")
        run("DELETE FROM departments WHERE id = 1")
        assertEquals(emptyList(), select("SELECT id FROM employees").rows)
    }

    @Test
    fun addConstraintRejectsANameAlreadyUsedByADifferentKindOfConstraintOnTheSameTable() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER, email TEXT)")
        run("ALTER TABLE t ADD CONSTRAINT c1 CHECK (age > 0)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ADD CONSTRAINT c1 UNIQUE (email)") }
        assertTrue(error.message!!.contains("c1"), error.message!!)
    }

    @Test
    fun createTableAcceptsANamedTableLevelCheckConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER, CONSTRAINT chk_age CHECK (age > 0))")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (1, -1)") }
        assertTrue(error.message!!.contains("chk_age"), error.message!!)
    }

    @Test
    fun createTableAcceptsANamedTableLevelUniqueConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT, CONSTRAINT uq_email UNIQUE (email))")
        run("INSERT INTO t VALUES (1, 'a@x.com')")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (2, 'a@x.com')") }
        assertTrue(error.message!!.contains("uq_email"), error.message!!)
    }

    @Test
    fun createTableAcceptsANamedTableLevelForeignKeyConstraint() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER, " +
                "CONSTRAINT fk_dept FOREIGN KEY (dept_id) REFERENCES departments (id))"
        )
        run("INSERT INTO departments VALUES (1)")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO employees VALUES (1, 99)") }
        assertTrue(error.message!!.contains("FOREIGN KEY constraint failed"), error.message!!)
    }

    @Test
    fun dropConstraintRemovesANamedCheckConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER, CONSTRAINT chk_age CHECK (age > 0))")
        exec("ALTER TABLE t DROP CONSTRAINT chk_age")
        run("INSERT INTO t VALUES (1, -5)")
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM t").rows)
    }

    @Test
    fun dropConstraintRemovesANamedUniqueConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT, CONSTRAINT uq_email UNIQUE (email))")
        run("INSERT INTO t VALUES (1, 'a@x.com')")
        exec("ALTER TABLE t DROP CONSTRAINT uq_email")
        run("INSERT INTO t VALUES (2, 'a@x.com')")
        assertEquals(2, select("SELECT id FROM t").rows.size)
    }

    @Test
    fun dropConstraintRemovesANamedForeignKeyConstraint() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER, " +
                "CONSTRAINT fk_dept FOREIGN KEY (dept_id) REFERENCES departments (id))"
        )
        exec("ALTER TABLE employees DROP CONSTRAINT fk_dept")
        run("INSERT INTO employees VALUES (1, 99)")
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM employees").rows)
    }

    @Test
    fun dropConstraintFailsForAnUnknownName() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t DROP CONSTRAINT nope") }
        assertTrue(error.message!!.contains("no such constraint"), error.message!!)
    }

    @Test
    fun dropConstraintRestrictsWhenAnotherTablesForeignKeyDependsOnTheUniqueConstraint() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, code TEXT, CONSTRAINT uq_code UNIQUE (code))")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_code TEXT, " +
                "CONSTRAINT fk_dept_code FOREIGN KEY (dept_code) REFERENCES departments (code))"
        )
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE departments DROP CONSTRAINT uq_code") }
        assertTrue(error.message!!.contains("employees"), error.message!!)
    }

    @Test
    fun dropConstraintCascadeRemovesTheDependentForeignKeyOnAnotherTable() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, code TEXT, CONSTRAINT uq_code UNIQUE (code))")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_code TEXT, " +
                "CONSTRAINT fk_dept_code FOREIGN KEY (dept_code) REFERENCES departments (code))"
        )
        exec("ALTER TABLE departments DROP CONSTRAINT uq_code CASCADE")
        run("INSERT INTO employees VALUES (1, 'nonexistent')")
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM employees").rows)
    }

    @Test
    fun dropConstraintRestrictsASelfReferencingForeignKeyDependentOnItsOwnUniqueConstraint() {
        run(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, code TEXT, parent_code TEXT, " +
                "CONSTRAINT uq_code UNIQUE (code), CONSTRAINT fk_parent FOREIGN KEY (parent_code) REFERENCES t (code))"
        )
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t DROP CONSTRAINT uq_code") }
        assertTrue(error.message!!.contains("FOREIGN KEY"), error.message!!)
    }

    @Test
    fun dropConstraintCascadeRemovesASelfReferencingForeignKeyThatDependedOnTheDroppedUniqueConstraint() {
        run(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, code TEXT, parent_code TEXT, " +
                "CONSTRAINT uq_code UNIQUE (code), CONSTRAINT fk_parent FOREIGN KEY (parent_code) REFERENCES t (code))"
        )
        exec("ALTER TABLE t DROP CONSTRAINT uq_code CASCADE")
        run("INSERT INTO t VALUES (1, 'a', 'nonexistent')")
        assertEquals(listOf(listOf(int(1))), select("SELECT id FROM t").rows)
    }

    @Test
    fun dropConstraintAllowsDroppingAUniqueConstraintWhenAnotherRemainingGroupStillCoversTheSameColumns() {
        run(
            "CREATE TABLE departments (id INTEGER PRIMARY KEY, code TEXT, " +
                "CONSTRAINT uq_code_a UNIQUE (code), CONSTRAINT uq_code_b UNIQUE (code))"
        )
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_code TEXT, " +
                "CONSTRAINT fk_dept_code FOREIGN KEY (dept_code) REFERENCES departments (code))"
        )
        exec("ALTER TABLE departments DROP CONSTRAINT uq_code_a")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO employees VALUES (1, 'nonexistent')") }
        assertTrue(error.message!!.contains("FOREIGN KEY constraint failed"), error.message!!)
    }

    @Test
    fun changeColumnTypeWideningIntegerToRealCoercesExistingRowsWithNoExplicitUsing() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, score INTEGER)")
        run("INSERT INTO t VALUES (1, 10), (2, 20)")
        val result = exec("ALTER TABLE t ALTER COLUMN score TYPE REAL")
        assertEquals(AlterTableResult("t"), result)
        assertEquals(listOf(listOf(real(10.0)), listOf(real(20.0))), select("SELECT score FROM t ORDER BY id ASC").rows)
    }

    @Test
    fun changeColumnTypeWideningIntegerToDecimalCoercesExistingRows() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("INSERT INTO t VALUES (1, 42)")
        exec("ALTER TABLE t ALTER COLUMN amount TYPE DECIMAL(10, 2)")
        assertEquals(listOf(listOf(decimal("42.00"))), select("SELECT amount FROM t").rows)
    }

    @Test
    fun changeColumnTypeWithUsingConvertsTextToIntegerViaCast() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, code TEXT)")
        run("INSERT INTO t VALUES (1, '100'), (2, '200')")
        exec("ALTER TABLE t ALTER COLUMN code TYPE INTEGER USING CAST(code AS INTEGER)")
        assertEquals(listOf(listOf(int(100)), listOf(int(200))), select("SELECT code FROM t ORDER BY id ASC").rows)
    }

    @Test
    fun changeColumnTypeWithUsingCanReferenceTheRowsOtherColumns() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, price INTEGER, quantity INTEGER)")
        run("INSERT INTO t VALUES (1, 10, 3)")
        exec("ALTER TABLE t ALTER COLUMN price TYPE INTEGER USING price * quantity")
        assertEquals(listOf(listOf(int(30))), select("SELECT price FROM t").rows)
    }

    @Test
    fun changeColumnTypeRejectsImplicitCoercionWhenTypesAreIncompatibleAndNoUsingIsGiven() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, code TEXT)")
        run("INSERT INTO t VALUES (1, 'abc')")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ALTER COLUMN code TYPE INTEGER") }
        assertTrue(error.message!!.contains("type mismatch"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsUnknownColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ALTER COLUMN missing TYPE TEXT") }
        assertTrue(error.message!!.contains("no such column"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsGeneratedColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, price INTEGER, doubled INTEGER GENERATED ALWAYS AS (price * 2) STORED)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ALTER COLUMN doubled TYPE REAL") }
        assertTrue(error.message!!.contains("GENERATED"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsPrimaryKeyColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ALTER COLUMN id TYPE TEXT") }
        assertTrue(error.message!!.contains("PRIMARY KEY"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsColumnUsedByItsOwnForeignKey() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY)")
        run("CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_id INTEGER, FOREIGN KEY (dept_id) REFERENCES departments (id))")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE employees ALTER COLUMN dept_id TYPE TEXT") }
        assertTrue(error.message!!.contains("FOREIGN KEY"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsColumnReferencedByAnotherTablesForeignKey() {
        run("CREATE TABLE departments (id INTEGER PRIMARY KEY, code TEXT, CONSTRAINT uq_code UNIQUE (code))")
        run(
            "CREATE TABLE employees (id INTEGER PRIMARY KEY, dept_code TEXT, " +
                "FOREIGN KEY (dept_code) REFERENCES departments (code))"
        )
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE departments ALTER COLUMN code TYPE INTEGER") }
        assertTrue(error.message!!.contains("employees"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsColumnUsedByAUniqueConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT, CONSTRAINT uq_email UNIQUE (email))")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ALTER COLUMN email TYPE TEXT") }
        assertTrue(error.message!!.contains("UNIQUE"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsColumnWithASecondaryIndex() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT)")
        run("CREATE INDEX idx_name ON t (name)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ALTER COLUMN name TYPE TEXT") }
        assertTrue(error.message!!.contains("idx_name"), error.message!!)
    }

    @Test
    fun changeColumnTypeRejectsWhenUsingProducesNullForANotNullColumnAndLeavesTheTableUnchanged() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, code TEXT NOT NULL)")
        run("INSERT INTO t VALUES (1, '100'), (2, '')")
        val error = assertFailsWith<KestrelException> {
            run("ALTER TABLE t ALTER COLUMN code TYPE INTEGER USING CASE WHEN code = '' THEN NULL ELSE CAST(code AS INTEGER) END")
        }
        assertTrue(error.message!!.contains("NOT NULL"), error.message!!)
        assertEquals(listOf(listOf(text("100")), listOf(text(""))), select("SELECT code FROM t ORDER BY id ASC").rows)
    }

    @Test
    fun changeColumnTypeRevalidatesACheckConstraintAgainstTheNewValuesAndLeavesTheTableUnchangedOnFailure() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER, CHECK (amount <= 100))")
        run("INSERT INTO t VALUES (1, 10), (2, 60)")
        val error = assertFailsWith<KestrelException> {
            run("ALTER TABLE t ALTER COLUMN amount TYPE INTEGER USING amount * 2")
        }
        assertTrue(error.message!!.contains("CHECK constraint"), error.message!!)
        assertEquals(
            listOf(listOf(int(1), int(10)), listOf(int(2), int(60))),
            select("SELECT id, amount FROM t ORDER BY id ASC").rows
        )
    }

    @Test
    fun changeColumnTypeSucceedsAndAppliesUsingToEveryRowWhenAllSatisfyAnExistingCheckConstraint() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER, CHECK (amount <= 100))")
        run("INSERT INTO t VALUES (1, 10), (2, 20)")
        exec("ALTER TABLE t ALTER COLUMN amount TYPE INTEGER USING amount * 2")
        assertEquals(listOf(listOf(int(20)), listOf(int(40))), select("SELECT amount FROM t ORDER BY id ASC").rows)
    }

    @Test
    fun changeColumnTypeRejectsAnIncompatibleDefaultExpressionUpFront() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'pending')")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t ALTER COLUMN status TYPE INTEGER USING 0") }
        assertTrue(error.message!!.contains("DEFAULT"), error.message!!)
    }

    @Test
    fun changeColumnTypePreservesNotNullAndStillEnforcesItGoingForward() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)")
        run("INSERT INTO t VALUES (1, 10)")
        exec("ALTER TABLE t ALTER COLUMN amount TYPE REAL")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (2, NULL)") }
        assertTrue(error.message!!.contains("NOT NULL"), error.message!!)
    }

    @Test
    fun changeColumnTypeAcceptsAnOptionalColumnKeyword() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("INSERT INTO t VALUES (1, 5)")
        exec("ALTER TABLE t ALTER amount TYPE REAL")
        assertEquals(listOf(listOf(real(5.0))), select("SELECT amount FROM t").rows)
    }

    @Test
    fun changeColumnTypeDumpReflectsTheNewDeclaredType() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("INSERT INTO t VALUES (1, 5)")
        exec("ALTER TABLE t ALTER COLUMN amount TYPE DECIMAL(10, 2)")
        val dumped = SqlWriter.dump(catalog)
        assertTrue(dumped.contains("amount DECIMAL(10, 2)"), dumped)
    }

    @Test
    fun changeColumnTypeRedoLogTextRoundTripsThroughSqlWriter() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, amount INTEGER)")
        run("INSERT INTO t VALUES (1, 5)")
        val statement = Parser(Lexer("ALTER TABLE t ALTER COLUMN amount TYPE REAL USING amount").tokenize()).parseProgram().single()
        val rendered = SqlWriter.write(statement)
        assertTrue(rendered.contains("ALTER COLUMN amount TYPE REAL USING amount"), rendered)
        run(rendered)
        assertEquals(listOf(listOf(real(5.0))), select("SELECT amount FROM t").rows)
    }
}
