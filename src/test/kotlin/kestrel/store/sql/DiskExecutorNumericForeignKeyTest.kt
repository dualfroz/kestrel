package kestrel.store.sql

import kestrel.KestrelException
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiskExecutorNumericForeignKeyTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun int(value: Long) = Value.IntegerValue(value)

    private fun dec(value: String) = Value.DecimalValue(BigDecimal(value))

    private class Database(dataFile: File, walFile: File) {
        val catalog = DiskCatalog.open(dataFile, walFile)
        val executor = DiskExecutor(catalog)

        fun run(sql: String): List<StatementResult> = Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }
        fun select(sql: String): SelectResult = run(sql).single() as SelectResult
    }

    private fun newDatabase(): Database = Database(tempFile("kestrel-numeric-fk-data"), tempFile("kestrel-numeric-fk-wal"))

    private class NumericType(val sql: String, val two: String, val three: String, val fraction: String?)

    private val integerType = NumericType("INTEGER", "2", "3", null)
    private val realType = NumericType("REAL", "2.0", "3.0", "2.5")
    private val decimalType = NumericType("DECIMAL(10,2)", "2.00", "3.00", "2.50")
    private val numericTypes = listOf(integerType, realType, decimalType)

    private val mixedPairs = numericTypes.flatMap { parent -> numericTypes.filter { it !== parent }.map { parent to it } }

    private fun Database.seed(parent: NumericType, child: NumericType, actions: String = "", childIndex: Boolean = true) {
        run("CREATE TABLE parents (id ${parent.sql} PRIMARY KEY)")
        run("CREATE TABLE children (id INTEGER PRIMARY KEY, parent_id ${child.sql} REFERENCES parents(id) $actions)")
        if (childIndex) run("CREATE INDEX idx_children_parent ON children(parent_id)")
        run("INSERT INTO parents VALUES (${parent.two}), (${parent.three})")
    }

    private fun Database.lookups(table: String): Int {
        val diskTable = catalog.table(table)
        return diskTable.pointLookupCount + diskTable.secondaryLookupCount + diskTable.rangeScanCount
    }

    private fun Database.scans(table: String): Int = catalog.table(table).scanCount

    private fun Database.childKeys(): List<Value> = select("SELECT parent_id FROM children ORDER BY id").rows.map { it.single() }

    private fun assertForeignKeyFailure(block: () -> Unit) {
        val error = assertFailsWith<KestrelException> { block() }
        assertTrue(error.message!!.contains("FOREIGN KEY"), error.message)
    }

    @Test
    fun insertFindsAParentOfAnotherNumericTypeThroughItsPrimaryKey() {
        for ((parent, child) in mixedPairs) {
            val db = newDatabase()
            db.seed(parent, child)
            val lookupsBefore = db.lookups("parents")
            val scansBefore = db.scans("parents")
            db.run("INSERT INTO children VALUES (1, ${child.two})")
            assertEquals(lookupsBefore + 1, db.lookups("parents"), "${child.sql} -> ${parent.sql}")
            assertEquals(scansBefore, db.scans("parents"), "${child.sql} -> ${parent.sql}")
            assertForeignKeyFailure { db.run("INSERT INTO children VALUES (2, ${child.two.replace('2', '7')})") }
        }
    }

    @Test
    fun insertRejectsAFractionalKeyThatNoIntegerParentCanMatch() {
        for (child in listOf(realType, decimalType)) {
            val db = newDatabase()
            db.seed(integerType, child)
            assertForeignKeyFailure { db.run("INSERT INTO children VALUES (1, ${child.fraction})") }
            assertEquals(emptyList(), db.childKeys())
        }
    }

    @Test
    fun insertMatchesAFractionalParentKeyFromARealOrDecimalChild() {
        for ((parent, child) in listOf(realType to decimalType, decimalType to realType)) {
            val db = newDatabase()
            db.seed(parent, child)
            db.run("INSERT INTO parents VALUES (${parent.fraction})")
            db.run("INSERT INTO children VALUES (1, ${child.fraction})")
            assertEquals(1, db.childKeys().size, "${child.sql} -> ${parent.sql}")
        }
    }

    @Test
    fun insertFindsAParentThroughASecondaryIndexOnAUniqueColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE parents (id INTEGER PRIMARY KEY, code DECIMAL(10,2) UNIQUE)")
        db.run("CREATE INDEX idx_parents_code ON parents(code)")
        db.run("CREATE TABLE children (id INTEGER PRIMARY KEY, code INTEGER REFERENCES parents(code))")
        db.run("INSERT INTO parents VALUES (1, 2.00), (2, 3.50)")
        val lookupsBefore = db.lookups("parents")
        val scansBefore = db.scans("parents")
        db.run("INSERT INTO children VALUES (1, 2)")
        assertEquals(lookupsBefore + 1, db.lookups("parents"))
        assertEquals(scansBefore, db.scans("parents"))
        assertForeignKeyFailure { db.run("INSERT INTO children VALUES (2, 3)") }
    }

    @Test
    fun insertFindsAParentWithoutAnyIndexOnTheReferencedColumn() {
        val db = newDatabase()
        db.run("CREATE TABLE parents (id INTEGER PRIMARY KEY, code DECIMAL(10,2) UNIQUE)")
        db.run("CREATE TABLE children (id INTEGER PRIMARY KEY, code INTEGER REFERENCES parents(code))")
        db.run("INSERT INTO parents VALUES (1, 2.00)")
        db.run("INSERT INTO children VALUES (1, 2)")
        assertForeignKeyFailure { db.run("INSERT INTO children VALUES (2, 3)") }
        assertEquals(listOf(int(2)), db.select("SELECT code FROM children").rows.map { it.single() })
    }

    @Test
    fun aMultiRowInsertFindsASiblingParentOfAnotherNumericType() {
        val db = newDatabase()
        db.run("CREATE TABLE nodes (id DECIMAL(10,2) PRIMARY KEY, parent INTEGER REFERENCES nodes(id))")
        db.run("INSERT INTO nodes VALUES (1.00, NULL), (2.00, 1)")
        assertEquals(2, db.select("SELECT id FROM nodes").rows.size)
        assertForeignKeyFailure { db.run("INSERT INTO nodes VALUES (3.50, NULL), (4.00, 3)") }
    }

    @Test
    fun updatingTheChildKeyFindsTheParentThroughItsPrimaryKey() {
        for ((parent, child) in mixedPairs) {
            val db = newDatabase()
            db.seed(parent, child)
            db.run("INSERT INTO children VALUES (1, ${child.two})")
            val lookupsBefore = db.lookups("parents")
            db.run("UPDATE children SET parent_id = ${child.three} WHERE id = 1")
            assertTrue(db.lookups("parents") > lookupsBefore, "${child.sql} -> ${parent.sql}")
            assertForeignKeyFailure { db.run("UPDATE children SET parent_id = ${child.two.replace('2', '7')} WHERE id = 1") }
        }
    }

    @Test
    fun deleteIsRestrictedByChildrenOfAnotherNumericType() {
        for ((parent, child) in mixedPairs) {
            val db = newDatabase()
            db.seed(parent, child)
            db.run("INSERT INTO children VALUES (1, ${child.two})")
            assertForeignKeyFailure { db.run("DELETE FROM parents WHERE id = ${parent.two}") }
            assertEquals(2, db.select("SELECT id FROM parents").rows.size, "${child.sql} -> ${parent.sql}")
            db.run("DELETE FROM parents WHERE id = ${parent.three}")
            assertEquals(1, db.select("SELECT id FROM parents").rows.size, "${child.sql} -> ${parent.sql}")
        }
    }

    @Test
    fun deleteCascadesToChildrenOfAnotherNumericTypeThroughTheirIndex() {
        for ((parent, child) in mixedPairs) {
            val db = newDatabase()
            db.seed(parent, child, "ON DELETE CASCADE")
            db.run("INSERT INTO children VALUES (1, ${child.two}), (2, ${child.three}), (3, ${child.two})")
            val lookupsBefore = db.lookups("children")
            val scansBefore = db.scans("children")
            db.run("DELETE FROM parents WHERE id = ${parent.two}")
            assertTrue(db.lookups("children") > lookupsBefore, "${child.sql} -> ${parent.sql}")
            assertEquals(scansBefore, db.scans("children"), "${child.sql} -> ${parent.sql}")
            assertEquals(listOf(listOf(int(2))), db.select("SELECT id FROM children").rows, "${child.sql} -> ${parent.sql}")
        }
    }

    @Test
    fun deleteCascadesToChildrenOfAnotherNumericTypeWithoutAnIndex() {
        for ((parent, child) in mixedPairs) {
            val db = newDatabase()
            db.seed(parent, child, "ON DELETE CASCADE", childIndex = false)
            db.run("INSERT INTO children VALUES (1, ${child.two}), (2, ${child.three})")
            db.run("DELETE FROM parents WHERE id = ${parent.two}")
            assertEquals(listOf(listOf(int(2))), db.select("SELECT id FROM children").rows, "${child.sql} -> ${parent.sql}")
        }
    }

    @Test
    fun deleteSetsChildrenOfAnotherNumericTypeToNull() {
        val db = newDatabase()
        db.seed(decimalType, integerType, "ON DELETE SET NULL")
        db.run("INSERT INTO children VALUES (1, 2), (2, 3)")
        db.run("DELETE FROM parents WHERE id = 2.00")
        assertEquals(listOf(Value.NullValue, int(3)), db.childKeys())
    }

    @Test
    fun cascadedDeleteFollowsAChainOfMixedNumericTypes() {
        val db = newDatabase()
        db.run("CREATE TABLE parents (id DECIMAL(10,2) PRIMARY KEY)")
        db.run("CREATE TABLE children (id REAL PRIMARY KEY, parent_id INTEGER REFERENCES parents(id) ON DELETE CASCADE)")
        db.run("CREATE TABLE grandchildren (id INTEGER PRIMARY KEY, child_id INTEGER REFERENCES children(id) ON DELETE CASCADE)")
        db.run("INSERT INTO parents VALUES (1.00), (2.00)")
        db.run("INSERT INTO children VALUES (10.0, 1), (20.0, 2)")
        db.run("INSERT INTO grandchildren VALUES (1, 10), (2, 20)")
        db.run("DELETE FROM parents WHERE id = 1")
        assertEquals(listOf(listOf(Value.RealValue(20.0))), db.select("SELECT id FROM children").rows)
        assertEquals(listOf(listOf(int(2))), db.select("SELECT id FROM grandchildren").rows)
    }

    @Test
    fun updatingTheParentKeyIsRestrictedByChildrenOfAnotherNumericType() {
        for ((parent, child) in mixedPairs) {
            val db = newDatabase()
            db.seed(parent, child)
            db.run("INSERT INTO children VALUES (1, ${child.two})")
            assertForeignKeyFailure { db.run("UPDATE parents SET id = ${parent.two.replace('2', '7')} WHERE id = ${parent.two}") }
        }
    }

    @Test
    fun updatingTheParentKeyCascadesInTheChildColumnType() {
        val decimalParent = newDatabase()
        decimalParent.seed(decimalType, integerType, "ON UPDATE CASCADE")
        decimalParent.run("INSERT INTO children VALUES (1, 2), (2, 3)")
        decimalParent.run("UPDATE parents SET id = 7.00 WHERE id = 2")
        val cascaded = decimalParent.childKeys()
        assertEquals(listOf(int(7), int(3)), cascaded)
        assertIs<Value.IntegerValue>(cascaded.first())
        assertEquals(1, decimalParent.select("SELECT id FROM children WHERE parent_id = 7").rows.size)

        val integerParent = newDatabase()
        integerParent.seed(integerType, decimalType, "ON UPDATE CASCADE")
        integerParent.run("INSERT INTO children VALUES (1, 2.00)")
        integerParent.run("UPDATE parents SET id = 9 WHERE id = 2")
        assertEquals(listOf(dec("9.00")), integerParent.childKeys())
        assertEquals("9.00", (integerParent.childKeys().single() as Value.DecimalValue).value.toPlainString())
    }

    @Test
    fun updatingTheParentKeyRejectsACascadeTheChildTypeCannotHold() {
        val db = newDatabase()
        db.seed(decimalType, integerType, "ON UPDATE CASCADE")
        db.run("INSERT INTO children VALUES (1, 2)")
        assertForeignKeyFailure { db.run("UPDATE parents SET id = 7.50 WHERE id = 2") }
        assertEquals(listOf(dec("2"), dec("3")), db.select("SELECT id FROM parents ORDER BY id").rows.map { it.single() })
        assertEquals(listOf(int(2)), db.childKeys())
    }

    @Test
    fun updatingTheParentKeySetsChildrenOfAnotherNumericTypeToNull() {
        val db = newDatabase()
        db.seed(integerType, decimalType, "ON UPDATE SET NULL")
        db.run("INSERT INTO children VALUES (1, 2.00), (2, 3.00)")
        db.run("UPDATE parents SET id = 8 WHERE id = 2")
        assertEquals(listOf(Value.NullValue, dec("3")), db.childKeys())
    }
}
