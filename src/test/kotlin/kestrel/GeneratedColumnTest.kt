package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.MergeResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `GENERATED ALWAYS AS (expr) STORED` on the in-memory engine - a column whose value is always
 * derived from the rest of its own row, never assigned directly, and recomputed on every
 * INSERT/UPDATE. See [kestrel.store.sql.DiskExecutorGeneratedColumnTest] for the disk engine.
 */
class GeneratedColumnTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)

    // ---- computed on INSERT ----

    @Test
    fun generatedColumnIsComputedWhenOmittedFromInsert() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("INSERT INTO t (a) VALUES (5)")
        assertEquals(listOf(listOf(int(5), int(10))), select("SELECT a, b FROM t").rows)
    }

    @Test
    fun generatedColumnIsComputedWhenOmittedFromPositionalInsertColumnList() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("INSERT INTO t (a, b) VALUES (5, DEFAULT)")
        assertEquals(listOf(listOf(int(5), int(10))), select("SELECT a, b FROM t").rows)
    }

    @Test
    fun insertRejectsAnExplicitValueForAGeneratedColumn() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO t (a, b) VALUES (5, 99)") }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    @Test
    fun generatedColumnRecomputesFreshForEveryRowOfAMultiRowInsert() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("INSERT INTO t (a) VALUES (1), (2), (3)")
        assertEquals(
            listOf(listOf(int(1), int(2)), listOf(int(2), int(4)), listOf(int(3), int(6))),
            select("SELECT a, b FROM t ORDER BY a").rows
        )
    }

    @Test
    fun generatedColumnCanReferenceMultipleOtherColumns() {
        run("CREATE TABLE t (price INTEGER, qty INTEGER, total INTEGER GENERATED ALWAYS AS (price * qty) STORED)")
        run("INSERT INTO t (price, qty) VALUES (7, 3)")
        assertEquals(listOf(listOf(int(21))), select("SELECT total FROM t").rows)
    }

    // ---- computed on UPDATE ----

    @Test
    fun updatingADependencyColumnRecomputesTheGeneratedColumn() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("INSERT INTO t (a) VALUES (5)")
        run("UPDATE t SET a = 9")
        assertEquals(listOf(listOf(int(18))), select("SELECT b FROM t").rows)
    }

    @Test
    fun updateRejectsAnExplicitValueForAGeneratedColumn() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("INSERT INTO t (a) VALUES (5)")
        val error = assertFailsWith<KestrelException> { run("UPDATE t SET b = 1") }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    @Test
    fun updateSetToDefaultOnAGeneratedColumnRecomputesIt() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("INSERT INTO t (a) VALUES (5)")
        run("UPDATE t SET a = 9, b = DEFAULT")
        assertEquals(listOf(listOf(int(18))), select("SELECT b FROM t").rows)
    }

    // ---- ON CONFLICT DO UPDATE ----

    @Test
    fun onConflictDoUpdateRecomputesTheGeneratedColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("INSERT INTO t (id, a) VALUES (1, 5)")
        run("INSERT INTO t (id, a) VALUES (1, 20) ON CONFLICT (id) DO UPDATE SET a = EXCLUDED.a")
        assertEquals(listOf(listOf(int(40))), select("SELECT b FROM t").rows)
    }

    @Test
    fun onConflictDoUpdateRejectsAnExplicitValueForAGeneratedColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("INSERT INTO t (id, a) VALUES (1, 5)")
        assertFailsWith<KestrelException> {
            run("INSERT INTO t (id, a) VALUES (1, 20) ON CONFLICT (id) DO UPDATE SET b = 1")
        }
    }

    // ---- MERGE ----

    @Test
    fun mergeInsertComputesTheGeneratedColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("CREATE TABLE s (id INTEGER, a INTEGER)")
        run("INSERT INTO s VALUES (1, 5)")
        val result = exec(
            "MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN INSERT (id, a) VALUES (s.id, s.a)"
        ) as MergeResult
        assertEquals(MergeResult(1, 0, 0), result)
        assertEquals(listOf(listOf(int(10))), select("SELECT b FROM t").rows)
    }

    @Test
    fun mergeUpdateRecomputesTheGeneratedColumn() {
        run("CREATE TABLE t (id INTEGER PRIMARY KEY, a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("CREATE TABLE s (id INTEGER, a INTEGER)")
        run("INSERT INTO t (id, a) VALUES (1, 5)")
        run("INSERT INTO s VALUES (1, 9)")
        exec("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = s.a")
        assertEquals(listOf(listOf(int(18))), select("SELECT b FROM t").rows)
    }

    // ---- ordering with CHECK / NOT NULL ----

    @Test
    fun checkConstraintSeesTheComputedGeneratedValueNotItsOldOne() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED CHECK (b > 0))")
        assertFailsWith<KestrelException> { run("INSERT INTO t (a) VALUES (-1)") }
        run("INSERT INTO t (a) VALUES (3)")
        assertEquals(listOf(listOf(int(6))), select("SELECT b FROM t").rows)
    }

    @Test
    fun notNullOnAGeneratedColumnRejectsARowWhoseComputedValueIsNull() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a) STORED NOT NULL)")
        assertFailsWith<KestrelException> { run("INSERT INTO t (a) VALUES (NULL)") }
    }

    // ---- validation at CREATE TABLE ----

    @Test
    fun rejectsGeneratedExpressionReferencingANonexistentColumn() {
        val error = assertFailsWith<KestrelException> {
            run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (missing) STORED)")
        }
        assertTrue(error.message!!.contains("no such column"))
    }

    @Test
    fun rejectsGeneratedExpressionContainingAnAggregate() {
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (COUNT(a)) STORED)")
        }
    }

    @Test
    fun rejectsGeneratedExpressionContainingASubquery() {
        run("CREATE TABLE other (x INTEGER)")
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS ((SELECT x FROM other)) STORED)")
        }
    }

    @Test
    fun rejectsGeneratedExpressionContainingNextval() {
        run("CREATE SEQUENCE seq")
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (NEXTVAL('seq')) STORED)")
        }
    }

    @Test
    fun rejectsAGeneratedColumnReferencingAnotherGeneratedColumn() {
        val error = assertFailsWith<KestrelException> {
            run(
                "CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED, " +
                    "c INTEGER GENERATED ALWAYS AS (b + 1) STORED)"
            )
        }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    // ---- RENAME COLUMN / DROP COLUMN interaction ----

    @Test
    fun renameColumnPropagatesIntoADependentGeneratedExpression() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        run("ALTER TABLE t RENAME COLUMN a TO a2")
        run("INSERT INTO t (a2) VALUES (5)")
        assertEquals(listOf(listOf(int(10))), select("SELECT b FROM t").rows)
    }

    @Test
    fun dropColumnRestrictsWhenAGeneratedColumnDependsOnIt() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        val error = assertFailsWith<KestrelException> { run("ALTER TABLE t DROP COLUMN a") }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    @Test
    fun dropColumnCascadeRemovesTheDependentGeneratedColumnToo() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED, c TEXT)")
        run("INSERT INTO t (a, c) VALUES (5, 'x')")
        run("ALTER TABLE t DROP COLUMN a CASCADE")
        val result = select("SELECT * FROM t")
        assertEquals(listOf("c"), result.columns)
        assertEquals(listOf(listOf(text("x"))), result.rows)
    }

    // ---- ALTER TABLE ADD COLUMN GENERATED ALWAYS AS ----

    @Test
    fun alterTableAddColumnGeneratedBackfillsEachExistingRowFromItsOwnOtherColumns() {
        run("CREATE TABLE t (a INTEGER)")
        run("INSERT INTO t (a) VALUES (1), (2), (3)")
        run("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (a * 2) STORED")
        assertEquals(
            listOf(listOf(int(1), int(2)), listOf(int(2), int(4)), listOf(int(3), int(6))),
            select("SELECT a, b FROM t ORDER BY a ASC").rows
        )
    }

    @Test
    fun alterTableAddColumnGeneratedWorksOnAnEmptyTable() {
        run("CREATE TABLE t (a INTEGER)")
        run("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (a * 2) STORED")
        run("INSERT INTO t (a) VALUES (5)")
        assertEquals(listOf(listOf(int(10))), select("SELECT b FROM t").rows)
    }

    @Test
    fun afterAlterTableAddColumnGeneratedAnInsertStillRejectsAnExplicitValue() {
        run("CREATE TABLE t (a INTEGER)")
        run("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (a * 2) STORED")
        val error = assertFailsWith<KestrelException> { run("INSERT INTO t (a, b) VALUES (5, 99)") }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    @Test
    fun afterAlterTableAddColumnGeneratedUpdatingTheDependencyColumnRecomputesIt() {
        run("CREATE TABLE t (a INTEGER)")
        run("INSERT INTO t (a) VALUES (5)")
        run("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (a * 2) STORED")
        run("UPDATE t SET a = 9")
        assertEquals(listOf(listOf(int(18))), select("SELECT b FROM t").rows)
    }

    @Test
    fun alterTableAddColumnGeneratedNotNullSkipsTheDefaultRequirementOnANonEmptyTable() {
        run("CREATE TABLE t (a INTEGER)")
        run("INSERT INTO t (a) VALUES (1), (2)")
        // A plain NOT NULL column with no DEFAULT would be rejected here (see the DEFAULT-less
        // ADD COLUMN rule tested elsewhere) - GENERATED has its own per-row value to backfill with
        // instead, so it needs no DEFAULT at all even though the table already has rows.
        run("ALTER TABLE t ADD COLUMN b INTEGER NOT NULL GENERATED ALWAYS AS (a * 2) STORED")
        assertEquals(listOf(listOf(int(2)), listOf(int(4))), select("SELECT b FROM t ORDER BY a ASC").rows)
    }

    @Test
    fun alterTableAddColumnGeneratedNotNullRejectsARowWhoseBackfilledValueIsNull() {
        run("CREATE TABLE t (a INTEGER)")
        run("INSERT INTO t (a) VALUES (NULL)")
        val error = assertFailsWith<KestrelException> {
            run("ALTER TABLE t ADD COLUMN b INTEGER NOT NULL GENERATED ALWAYS AS (a) STORED")
        }
        assertTrue(error.message!!.contains("NOT NULL"))
    }

    @Test
    fun alterTableAddColumnGeneratedRejectsReferencingANonexistentColumn() {
        run("CREATE TABLE t (a INTEGER)")
        val error = assertFailsWith<KestrelException> {
            run("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (missing) STORED")
        }
        assertTrue(error.message!!.contains("no such column"))
    }

    @Test
    fun alterTableAddColumnGeneratedRejectsReferencingAnAlreadyExistingGeneratedColumn() {
        run("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)")
        val error = assertFailsWith<KestrelException> {
            run("ALTER TABLE t ADD COLUMN c INTEGER GENERATED ALWAYS AS (b + 1) STORED")
        }
        assertTrue(error.message!!.contains("GENERATED"))
    }

    @Test
    fun alterTableAddColumnGeneratedRejectsAnAggregate() {
        run("CREATE TABLE t (a INTEGER)")
        assertFailsWith<KestrelException> {
            run("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (COUNT(a)) STORED")
        }
    }

    private fun text(value: String) = Value.TextValue(value)
}
