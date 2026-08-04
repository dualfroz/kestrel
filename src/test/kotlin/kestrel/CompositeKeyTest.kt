package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.InsertResult
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.persistence.SqlWriter
import kestrel.sql.ast.CreateTableStatement
import kestrel.sql.ast.Statement
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Multi-column PRIMARY KEY (`PRIMARY KEY (a, b, ...)`) and multi-column FOREIGN KEY
 * (`FOREIGN KEY (a, b) REFERENCES parent(x, y)`), the last documented "not yet supported" gap
 * left in TableSchema/ForeignKeyEnforcer after single-column UNIQUE landed.
 */
class CompositeKeyTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun exec(sql: String): StatementResult = run(sql).single()

    private fun parseOne(sql: String): Statement = Parser(Lexer(sql).tokenize()).parseProgram().single()

    private fun select(sql: String): SelectResult = exec(sql) as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)

    // ---- parsing ----

    @Test
    fun parsesTableLevelCompositePrimaryKey() {
        val statement = parseOne(
            "CREATE TABLE line_items (order_id INTEGER, sku TEXT, qty INTEGER, PRIMARY KEY (order_id, sku))"
        ) as CreateTableStatement
        assertEquals(listOf("order_id", "sku"), statement.compositePrimaryKey)
        assertTrue(statement.columns.none { it.primaryKey })
    }

    @Test
    fun parsesTableLevelCompositeForeignKey() {
        val statement = parseOne(
            "CREATE TABLE t (a INTEGER, b INTEGER, " +
                "FOREIGN KEY (a, b) REFERENCES parent (x, y) ON DELETE CASCADE)"
        ) as CreateTableStatement
        val fk = statement.foreignKeys.single()
        assertEquals(listOf("a", "b"), fk.columns)
        assertEquals("parent", fk.referencedTable)
        assertEquals(listOf("x", "y"), fk.referencedColumns)
    }

    @Test
    fun mismatchedForeignKeyColumnCountIsRejectedAtParseTime() {
        assertFailsWith<Exception> {
            parseOne("CREATE TABLE t (a INTEGER, b INTEGER, FOREIGN KEY (a, b) REFERENCES parent (x))")
        }
    }

    // ---- CREATE TABLE validation ----

    @Test
    fun declaringBothInlineAndTableLevelPrimaryKeyIsRejected() {
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (a INTEGER PRIMARY KEY, b INTEGER, PRIMARY KEY (a, b))")
        }
    }

    @Test
    fun duplicateColumnWithinCompositePrimaryKeyIsRejected() {
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (a INTEGER, b INTEGER, PRIMARY KEY (a, a))")
        }
    }

    @Test
    fun compositePrimaryKeyReferringToUnknownColumnIsRejected() {
        assertFailsWith<KestrelException> {
            run("CREATE TABLE t (a INTEGER, b INTEGER, PRIMARY KEY (a, missing))")
        }
    }

    @Test
    fun compositePrimaryKeyImpliesNotNullOnEveryListedColumn() {
        run("CREATE TABLE t (a INTEGER, b INTEGER, PRIMARY KEY (a, b))")
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (1, NULL)") }
        assertFailsWith<KestrelException> { run("INSERT INTO t VALUES (NULL, 1)") }
    }

    @Test
    fun foreignKeyMustReferenceAnExactEnforcedUniqueGroupNotAnArbitraryPair() {
        run("CREATE TABLE parent (a INTEGER, b INTEGER, c INTEGER, PRIMARY KEY (a, b))")
        assertFailsWith<KestrelException> {
            run("CREATE TABLE child (x INTEGER, y INTEGER, FOREIGN KEY (x, y) REFERENCES parent (b, c))")
        }
    }

    @Test
    fun foreignKeyMayReferenceACompositePrimaryKeyInAnyColumnOrder() {
        run("CREATE TABLE parent (a INTEGER, b INTEGER, PRIMARY KEY (a, b))")
        run("CREATE TABLE child (x INTEGER, y INTEGER, FOREIGN KEY (x, y) REFERENCES parent (b, a))")
    }

    // ---- composite PRIMARY KEY uniqueness ----

    @Test
    fun compositePrimaryKeyRejectsAnExactDuplicateCombination() {
        run("CREATE TABLE line_items (order_id INTEGER, sku TEXT, qty INTEGER, PRIMARY KEY (order_id, sku))")
        run("INSERT INTO line_items VALUES (1, 'A', 3)")
        assertFailsWith<KestrelException> { run("INSERT INTO line_items VALUES (1, 'A', 5)") }
    }

    @Test
    fun compositePrimaryKeyAllowsDifferentCombinationsSharingOneColumnValue() {
        run("CREATE TABLE line_items (order_id INTEGER, sku TEXT, qty INTEGER, PRIMARY KEY (order_id, sku))")
        run("INSERT INTO line_items VALUES (1, 'A', 3)")
        val result = exec("INSERT INTO line_items VALUES (1, 'B', 3)")
        assertEquals(InsertResult(1), result)
    }

    @Test
    fun updateThatWouldCollideWithAnotherRowsCompositePrimaryKeyIsRejected() {
        run("CREATE TABLE line_items (order_id INTEGER, sku TEXT, qty INTEGER, PRIMARY KEY (order_id, sku))")
        run("INSERT INTO line_items VALUES (1, 'A', 3), (1, 'B', 5)")
        assertFailsWith<KestrelException> { run("UPDATE line_items SET sku = 'A' WHERE sku = 'B'") }
    }

    // ---- composite FOREIGN KEY: existence checks ----

    private fun seedOrdersAndLineItems() {
        run("CREATE TABLE orders (region TEXT, order_no INTEGER, PRIMARY KEY (region, order_no))")
        run(
            "CREATE TABLE line_items (" +
                "region TEXT, order_no INTEGER, sku TEXT, qty INTEGER, " +
                "FOREIGN KEY (region, order_no) REFERENCES orders (region, order_no) " +
                "ON DELETE CASCADE ON UPDATE CASCADE)"
        )
        run("INSERT INTO orders VALUES ('eu', 1), ('us', 1)")
    }

    @Test
    fun compositeForeignKeyRejectsAPartialMatch() {
        seedOrdersAndLineItems()
        // ('eu', 1) and ('us', 1) both exist individually but not as the pair ('us', 2).
        assertFailsWith<KestrelException> {
            run("INSERT INTO line_items VALUES ('us', 2, 'SKU1', 3)")
        }
    }

    @Test
    fun compositeForeignKeyAcceptsAFullMatch() {
        seedOrdersAndLineItems()
        val result = exec("INSERT INTO line_items VALUES ('eu', 1, 'SKU1', 3)")
        assertEquals(InsertResult(1), result)
    }

    @Test
    fun compositeForeignKeyMatchSimpleSkipsCheckWhenAnyLocalColumnIsNull() {
        seedOrdersAndLineItems()
        val result = exec("INSERT INTO line_items VALUES ('eu', NULL, 'SKU1', 3)")
        assertEquals(InsertResult(1), result)
    }

    @Test
    fun compositeForeignKeyOnDeleteCascadeRemovesMatchingChildren() {
        seedOrdersAndLineItems()
        run("INSERT INTO line_items VALUES ('eu', 1, 'SKU1', 3), ('us', 1, 'SKU2', 4)")
        run("DELETE FROM orders WHERE region = 'eu'")
        val remaining = select("SELECT sku FROM line_items")
        assertEquals(listOf(listOf(Value.TextValue("SKU2"))), remaining.rows)
    }

    @Test
    fun compositeForeignKeyOnUpdateCascadePropagatesBothColumnsTogether() {
        seedOrdersAndLineItems()
        run("INSERT INTO line_items VALUES ('eu', 1, 'SKU1', 3)")
        run("UPDATE orders SET order_no = 7 WHERE region = 'eu'")
        val updated = select("SELECT region, order_no FROM line_items WHERE sku = 'SKU1'")
        assertEquals(listOf(listOf(Value.TextValue("eu"), int(7))), updated.rows)
    }

    @Test
    fun compositeForeignKeyOnDeleteRestrictBlocksByDefaultWithoutCascade() {
        run("CREATE TABLE orders (region TEXT, order_no INTEGER, PRIMARY KEY (region, order_no))")
        run(
            "CREATE TABLE line_items (region TEXT, order_no INTEGER, sku TEXT, " +
                "FOREIGN KEY (region, order_no) REFERENCES orders (region, order_no))"
        )
        run("INSERT INTO orders VALUES ('eu', 1)")
        run("INSERT INTO line_items VALUES ('eu', 1, 'SKU1')")
        assertFailsWith<KestrelException> { run("DELETE FROM orders WHERE region = 'eu'") }
    }

    @Test
    fun compositeForeignKeyOnDeleteSetNullNullsEveryLocalColumn() {
        run("CREATE TABLE orders (region TEXT, order_no INTEGER, PRIMARY KEY (region, order_no))")
        run(
            "CREATE TABLE line_items (region TEXT, order_no INTEGER, sku TEXT, " +
                "FOREIGN KEY (region, order_no) REFERENCES orders (region, order_no) ON DELETE SET NULL)"
        )
        run("INSERT INTO orders VALUES ('eu', 1)")
        run("INSERT INTO line_items VALUES ('eu', 1, 'SKU1')")
        run("DELETE FROM orders WHERE region = 'eu'")
        val row = select("SELECT region, order_no FROM line_items WHERE sku = 'SKU1'").rows.single()
        assertEquals(listOf(Value.NullValue, Value.NullValue), row)
    }

    @Test
    fun rollbackAfterCompositeCascadeDeleteRestoresBothTables() {
        seedOrdersAndLineItems()
        run("INSERT INTO line_items VALUES ('eu', 1, 'SKU1', 3)")
        run("BEGIN")
        run("DELETE FROM orders WHERE region = 'eu'")
        run("ROLLBACK")
        assertEquals(2, select("SELECT * FROM orders").rows.size)
        assertEquals(1, select("SELECT * FROM line_items").rows.size)
    }

    // ---- composite FOREIGN KEY referencing a composite UNIQUE (not the PRIMARY KEY) ----

    @Test
    fun compositeForeignKeyMayReferenceACompositeUniqueConstraintInsteadOfThePrimaryKey() {
        run(
            "CREATE TABLE products (id INTEGER PRIMARY KEY, brand TEXT, model TEXT, UNIQUE (brand, model))"
        )
        run(
            "CREATE TABLE reviews (id INTEGER PRIMARY KEY, brand TEXT, model TEXT, stars INTEGER, " +
                "FOREIGN KEY (brand, model) REFERENCES products (brand, model))"
        )
        run("INSERT INTO products VALUES (1, 'acme', 'x100')")
        val result = exec("INSERT INTO reviews VALUES (1, 'acme', 'x100', 5)")
        assertEquals(InsertResult(1), result)
        assertFailsWith<KestrelException> {
            run("INSERT INTO reviews VALUES (2, 'acme', 'missing', 4)")
        }
    }

    // ---- self-referencing composite FOREIGN KEY ----

    @Test
    fun selfReferencingCompositeForeignKeySeedableInASingleMultiRowInsert() {
        run(
            "CREATE TABLE edges (" +
                "tenant TEXT, node INTEGER, parent_tenant TEXT, parent_node INTEGER, " +
                "PRIMARY KEY (tenant, node), " +
                "FOREIGN KEY (parent_tenant, parent_node) REFERENCES edges (tenant, node))"
        )
        // The root's parent columns are NULL (no parent); child references the root, all seeded
        // together in one INSERT so the child must see its not-yet-committed sibling.
        run(
            "INSERT INTO edges VALUES " +
                "('t1', 1, NULL, NULL), ('t1', 2, 't1', 1), ('t1', 3, 't1', 2)"
        )
        assertEquals(3, select("SELECT * FROM edges").rows.size)
    }

    // ---- SqlWriter round-trip ----

    @Test
    fun createTableWithCompositePrimaryKeyRoundTripsThroughSqlWriter() {
        val statement = parseOne(
            "CREATE TABLE line_items (order_id INTEGER, sku TEXT, qty INTEGER, PRIMARY KEY (order_id, sku))"
        )
        run(SqlWriter.write(statement))
        run("INSERT INTO line_items VALUES (1, 'A', 3)")
        assertFailsWith<KestrelException> { run("INSERT INTO line_items VALUES (1, 'A', 9)") }
    }

    @Test
    fun createTableWithCompositeForeignKeyRoundTripsThroughSqlWriter() {
        run(SqlWriter.write(parseOne("CREATE TABLE orders (region TEXT, order_no INTEGER, PRIMARY KEY (region, order_no))")))
        run(
            SqlWriter.write(
                parseOne(
                    "CREATE TABLE line_items (region TEXT, order_no INTEGER, sku TEXT, " +
                        "FOREIGN KEY (region, order_no) REFERENCES orders (region, order_no) ON DELETE CASCADE)"
                )
            )
        )
        run("INSERT INTO orders VALUES ('eu', 1)")
        run("INSERT INTO line_items VALUES ('eu', 1, 'SKU1')")
        run("DELETE FROM orders WHERE region = 'eu'")
        assertTrue(select("SELECT * FROM line_items").rows.isEmpty())
    }

    @Test
    fun dumpAndReplayPreservesCompositePrimaryKeyAndForeignKey() {
        seedOrdersAndLineItems()
        run("INSERT INTO line_items VALUES ('eu', 1, 'SKU1', 3)")
        val dump = SqlWriter.dump(catalog)

        val freshCatalog = Catalog()
        val freshExecutor = Executor(freshCatalog)
        Parser(Lexer(dump).tokenize()).parseProgram().forEach { freshExecutor.execute(it) }

        assertFailsWith<KestrelException> {
            Parser(Lexer("INSERT INTO orders VALUES ('eu', 1)").tokenize()).parseProgram()
                .forEach { freshExecutor.execute(it) }
        }
        Parser(Lexer("DELETE FROM orders WHERE region = 'eu'").tokenize()).parseProgram()
            .forEach { freshExecutor.execute(it) }
        val remaining = freshExecutor.execute(
            Parser(Lexer("SELECT * FROM line_items").tokenize()).parseProgram().single()
        ) as SelectResult
        assertTrue(remaining.rows.isEmpty())
    }
}
