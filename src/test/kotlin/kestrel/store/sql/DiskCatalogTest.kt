package kestrel.store.sql

import kestrel.catalog.Column
import kestrel.catalog.SequenceDefinition
import kestrel.catalog.UniqueConstraint
import kestrel.storage.ColumnType
import kestrel.storage.Row
import kestrel.storage.Value
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiskCatalogTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private val usersColumns = listOf(
        Column("id", ColumnType.Integer, notNull = true, primaryKey = true),
        Column("name", ColumnType.Text, notNull = true, primaryKey = false)
    )

    @Test
    fun creatingTheSameTableTwiceFails() {
        val catalog = DiskCatalog.open(tempFile("kestrel-cat-data"), tempFile("kestrel-cat-wal"))
        catalog.createTable("users", usersColumns)
        assertFailsWith<DiskCatalogException> { catalog.createTable("users", usersColumns) }
    }

    @Test
    fun droppedTableIsNoLongerListedOrQueryable() {
        val catalog = DiskCatalog.open(tempFile("kestrel-cat-data"), tempFile("kestrel-cat-wal"))
        catalog.createTable("users", usersColumns)
        catalog.dropTable("users")

        assertTrue(catalog.tableNames.isEmpty())
        assertFailsWith<DiskCatalogException> { catalog.table("users") }
    }

    @Test
    fun reopeningAFreshProcessSeesTablesAndRowsThroughNewObjectsEntirely() {
        val dataFile = tempFile("kestrel-cat-data")
        val walFile = tempFile("kestrel-cat-wal")

        val catalog1 = DiskCatalog.open(dataFile, walFile)
        val table1 = catalog1.createTable("users", usersColumns)
        val txn1 = catalog1.beginTransaction()
        table1.insert(txn1, Row(listOf(Value.IntegerValue(1), Value.TextValue("alice"))))
        table1.insert(txn1, Row(listOf(Value.IntegerValue(2), Value.TextValue("bob"))))
        catalog1.commit(txn1)
        catalog1.close()

        val catalog2 = DiskCatalog.open(dataFile, walFile)
        assertEquals(listOf("users"), catalog2.tableNames)
        val table2 = catalog2.table("users")
        val reader = catalog2.beginTransaction()
        assertEquals(setOf(1L, 2L), table2.scan(reader).map { (it.second.values[0] as Value.IntegerValue).value }.toSet())
        val (_, aliceRow) = table2.findByPrimaryKey(reader, Value.IntegerValue(1))!!
        assertEquals("alice", (aliceRow.values[1] as Value.TextValue).value)
        catalog2.commit(reader)
    }

    @Test
    fun aCommittedWriteThatNeverFlushedTheBufferPoolSurvivesAReopen() {
        val dataFile = tempFile("kestrel-cat-data")
        val walFile = tempFile("kestrel-cat-wal")

        val catalog1 = DiskCatalog.open(dataFile, walFile)
        val table1 = catalog1.createTable("users", usersColumns)
        val txn = catalog1.beginTransaction()
        table1.insert(txn, Row(listOf(Value.IntegerValue(1), Value.TextValue("alice"))))
        catalog1.commit(txn)
        // No catalog1.close(): the dirty pages may still only live in the buffer pool.

        val catalog2 = DiskCatalog.open(dataFile, walFile)
        val table2 = catalog2.table("users")
        val reader = catalog2.beginTransaction()
        assertEquals("alice", table2.findByPrimaryKey(reader, Value.IntegerValue(1))!!.second.values[1].let { (it as Value.TextValue).value })
        catalog2.commit(reader)
    }

    @Test
    fun aPrimaryKeyIndexThatSplitsSurvivesAReopenWithTheCorrectRoot() {
        val dataFile = tempFile("kestrel-cat-data")
        val walFile = tempFile("kestrel-cat-wal")

        val catalog1 = DiskCatalog.open(dataFile, walFile)
        val table1 = catalog1.createTable("users", usersColumns)
        val txn = catalog1.beginTransaction()
        for (id in 1..300L) table1.insert(txn, Row(listOf(Value.IntegerValue(id), Value.TextValue("user$id"))))
        catalog1.commit(txn)
        catalog1.close()

        val catalog2 = DiskCatalog.open(dataFile, walFile)
        val table2 = catalog2.table("users")
        val reader = catalog2.beginTransaction()
        assertEquals(300, table2.scan(reader).size)
        for (id in listOf(1L, 150L, 300L)) {
            assertEquals("user$id", table2.findByPrimaryKey(reader, Value.IntegerValue(id))!!.second.values[1].let { (it as Value.TextValue).value })
        }
        val range = table2.rangeByPrimaryKey(reader, Value.IntegerValue(100), true, Value.IntegerValue(105), true)
        assertEquals((100..105L).toList(), range.map { (it.second.values[0] as Value.IntegerValue).value })
        catalog2.commit(reader)
    }

    @Test
    fun aSecondaryIndexCreatedBeforeCloseSurvivesAReopen() {
        val dataFile = tempFile("kestrel-cat-data")
        val walFile = tempFile("kestrel-cat-wal")
        val columns = usersColumns + Column("dept", ColumnType.Text, notNull = false, primaryKey = false)

        val catalog1 = DiskCatalog.open(dataFile, walFile)
        val table1 = catalog1.createTable("users", columns)
        catalog1.createIndex("users", "idx_dept", "dept")
        val txn = catalog1.beginTransaction()
        table1.insert(txn, Row(listOf(Value.IntegerValue(1), Value.TextValue("alice"), Value.TextValue("eng"))))
        table1.insert(txn, Row(listOf(Value.IntegerValue(2), Value.TextValue("bob"), Value.TextValue("eng"))))
        catalog1.commit(txn)
        catalog1.close()

        val catalog2 = DiskCatalog.open(dataFile, walFile)
        val table2 = catalog2.table("users")
        val reader = catalog2.beginTransaction()
        val engineers = table2.findBySecondary(reader, "idx_dept", Value.TextValue("eng"))
        assertEquals(setOf("alice", "bob"), engineers.map { (it.second.values[1] as Value.TextValue).value }.toSet())
        catalog2.commit(reader)
    }

    private val orderLineColumns = listOf(
        Column("order_id", ColumnType.Integer, notNull = true, primaryKey = false),
        Column("line_no", ColumnType.Integer, notNull = true, primaryKey = false),
        Column("sku", ColumnType.Text, notNull = false, primaryKey = false)
    )

    @Test
    fun aTableCannotDeclareBothAnInlinePrimaryKeyColumnAndACompositePrimaryKey() {
        val catalog = DiskCatalog.open(tempFile("kestrel-cat-data"), tempFile("kestrel-cat-wal"))
        assertFailsWith<DiskCatalogException> {
            catalog.createTable("users", usersColumns, compositePrimaryKey = listOf("name"))
        }
    }

    @Test
    fun aCompositePrimaryKeyReferencingAnUnknownColumnIsRejected() {
        val catalog = DiskCatalog.open(tempFile("kestrel-cat-data"), tempFile("kestrel-cat-wal"))
        assertFailsWith<DiskCatalogException> {
            catalog.createTable("order_lines", orderLineColumns, compositePrimaryKey = listOf("order_id", "missing"))
        }
    }

    @Test
    fun aCompositePrimaryKeyListingTheSameColumnTwiceIsRejected() {
        val catalog = DiskCatalog.open(tempFile("kestrel-cat-data"), tempFile("kestrel-cat-wal"))
        assertFailsWith<DiskCatalogException> {
            catalog.createTable("order_lines", orderLineColumns, compositePrimaryKey = listOf("order_id", "order_id"))
        }
    }

    @Test
    fun aCompositePrimaryKeyThatSplitsSurvivesAReopenWithTheCorrectRootAndPointLookupsStillWork() {
        val dataFile = tempFile("kestrel-cat-data")
        val walFile = tempFile("kestrel-cat-wal")

        val catalog1 = DiskCatalog.open(dataFile, walFile)
        val table1 = catalog1.createTable("order_lines", orderLineColumns, compositePrimaryKey = listOf("order_id", "line_no"))
        val txn = catalog1.beginTransaction()
        for (orderId in 1..50L) {
            for (lineNo in 1..6L) {
                table1.insert(txn, Row(listOf(Value.IntegerValue(orderId), Value.IntegerValue(lineNo), Value.TextValue("sku-$orderId-$lineNo"))))
            }
        }
        catalog1.commit(txn)
        catalog1.close()

        val catalog2 = DiskCatalog.open(dataFile, walFile)
        val table2 = catalog2.table("order_lines")
        val reader = catalog2.beginTransaction()
        assertEquals(300, table2.scan(reader).size)
        val found = table2.findByPrimaryKey(reader, listOf(Value.IntegerValue(25), Value.IntegerValue(4)))!!
        assertEquals("sku-25-4", (found.second.values[2] as Value.TextValue).value)
        assertEquals(null, table2.findByPrimaryKey(reader, listOf(Value.IntegerValue(25), Value.IntegerValue(99))))
        catalog2.commit(reader)
    }

    @Test
    fun anAbortedTransactionsWritesAreInvisibleAfterAReopen() {
        val dataFile = tempFile("kestrel-cat-data")
        val walFile = tempFile("kestrel-cat-wal")

        val catalog1 = DiskCatalog.open(dataFile, walFile)
        val table1 = catalog1.createTable("users", usersColumns)
        val committed = catalog1.beginTransaction()
        table1.insert(committed, Row(listOf(Value.IntegerValue(1), Value.TextValue("alice"))))
        catalog1.commit(committed)

        val doomed = catalog1.beginTransaction()
        table1.insert(doomed, Row(listOf(Value.IntegerValue(2), Value.TextValue("bob"))))
        catalog1.abort(doomed)
        catalog1.close()

        val catalog2 = DiskCatalog.open(dataFile, walFile)
        val table2 = catalog2.table("users")
        val reader = catalog2.beginTransaction()
        assertEquals(listOf(1L), table2.scan(reader).map { (it.second.values[0] as Value.IntegerValue).value })
        catalog2.commit(reader)
    }

    @Test
    fun aUniqueConstraintReferencingAnUnknownColumnIsRejected() {
        val catalog = DiskCatalog.open(tempFile("kestrel-cat-data"), tempFile("kestrel-cat-wal"))
        assertFailsWith<DiskCatalogException> {
            catalog.createTable("users", usersColumns, uniqueConstraints = listOf(UniqueConstraint(null, listOf("missing"))))
        }
    }

    @Test
    fun aUniqueIndexAndItsEnforcementSurviveAReopen() {
        val dataFile = tempFile("kestrel-cat-data")
        val walFile = tempFile("kestrel-cat-wal")
        val columns = usersColumns + Column("email", ColumnType.Text, notNull = false, primaryKey = false)

        val catalog1 = DiskCatalog.open(dataFile, walFile)
        val table1 = catalog1.createTable("users", columns)
        catalog1.createIndex("users", "idx_email", "email", unique = true)
        val txn = catalog1.beginTransaction()
        table1.insert(txn, Row(listOf(Value.IntegerValue(1), Value.TextValue("alice"), Value.TextValue("a@x.com"))))
        catalog1.commit(txn)
        catalog1.close()

        val catalog2 = DiskCatalog.open(dataFile, walFile)
        val table2 = catalog2.table("users")
        assertTrue(table2.secondaryIndexes.single().unique)
        val reader = catalog2.beginTransaction()
        assertFailsWith<DiskTableException> {
            table2.insert(reader, Row(listOf(Value.IntegerValue(2), Value.TextValue("bob"), Value.TextValue("a@x.com"))))
        }
        catalog2.abort(reader)
    }

    @Test
    fun aUniqueIndexCreatedOverPreExistingDuplicatesFailsWithoutLeavingAPartialDirectoryEntry() {
        val dataFile = tempFile("kestrel-cat-data")
        val walFile = tempFile("kestrel-cat-wal")
        val columns = usersColumns + Column("email", ColumnType.Text, notNull = false, primaryKey = false)

        val catalog = DiskCatalog.open(dataFile, walFile)
        val table = catalog.createTable("users", columns)
        val txn = catalog.beginTransaction()
        table.insert(txn, Row(listOf(Value.IntegerValue(1), Value.TextValue("alice"), Value.TextValue("dup@x.com"))))
        table.insert(txn, Row(listOf(Value.IntegerValue(2), Value.TextValue("bob"), Value.TextValue("dup@x.com"))))
        catalog.commit(txn)

        assertFailsWith<DiskTableException> { catalog.createIndex("users", "idx_email", "email", unique = true) }
        assertTrue(table.secondaryIndexes.isEmpty())

        catalog.close()
        val reopened = DiskCatalog.open(dataFile, walFile)
        assertTrue(reopened.table("users").secondaryIndexes.isEmpty())
    }

    @Test
    fun aCompositeUniqueIndexAndItsEnforcementSurviveAReopenWithPointLookupsStillWorking() {
        val dataFile = tempFile("kestrel-cat-data")
        val walFile = tempFile("kestrel-cat-wal")

        val catalog1 = DiskCatalog.open(dataFile, walFile)
        val table1 = catalog1.createTable("order_lines", orderLineColumns)
        catalog1.createIndex("order_lines", "idx_order_sku", listOf("order_id", "sku"), unique = true)
        val txn = catalog1.beginTransaction()
        table1.insert(txn, Row(listOf(Value.IntegerValue(1), Value.IntegerValue(1), Value.TextValue("widget"))))
        catalog1.commit(txn)
        catalog1.close()

        val catalog2 = DiskCatalog.open(dataFile, walFile)
        val table2 = catalog2.table("order_lines")
        val handle = table2.secondaryIndexes.single()
        assertEquals(listOf("order_id", "sku"), handle.columnNames)
        assertTrue(handle.unique)

        val reader = catalog2.beginTransaction()
        assertFailsWith<DiskTableException> {
            table2.insert(reader, Row(listOf(Value.IntegerValue(1), Value.IntegerValue(2), Value.TextValue("widget"))))
        }
        val found = table2.findBySecondary(reader, "idx_order_sku", listOf(Value.IntegerValue(1), Value.TextValue("widget")))
        assertEquals(1, found.size)
        catalog2.abort(reader)
    }

    private val wideTextPadding = "x".repeat(200)

    private fun DiskCatalog.buildWideTable(name: String): DiskTable {
        val table = createTable(name, usersColumns)
        createIndex(name, "idx_${name}_name", "name")
        val txn = beginTransaction()
        for (id in 1..60L) table.insert(txn, Row(listOf(Value.IntegerValue(id), Value.TextValue("$wideTextPadding-$id"))))
        commit(txn)
        return table
    }

    @Test
    fun droppingATableReclaimsItsPagesForANewTableInsteadOfGrowingTheFile() {
        val catalog = DiskCatalog.open(tempFile("kestrel-cat-data"), tempFile("kestrel-cat-wal"))
        catalog.buildWideTable("wide")
        val peak = catalog.allocatedPageCount()

        catalog.dropTable("wide")
        catalog.buildWideTable("wide2")

        assertTrue(
            catalog.allocatedPageCount() <= peak,
            "expected the dropped table's reclaimed heap/index pages to be reused by the new table instead of " +
                "growing the file further (peak=$peak, now=${catalog.allocatedPageCount()})"
        )
    }

    @Test
    fun droppingASecondaryIndexReclaimsItsPagesForANewIndexOnTheSameTable() {
        val catalog = DiskCatalog.open(tempFile("kestrel-cat-data"), tempFile("kestrel-cat-wal"))
        val table = catalog.createTable("wide", usersColumns)
        val txn = catalog.beginTransaction()
        for (id in 1..60L) table.insert(txn, Row(listOf(Value.IntegerValue(id), Value.TextValue("$wideTextPadding-$id"))))
        catalog.commit(txn)
        catalog.createIndex("wide", "idx_name", "name")
        val peak = catalog.allocatedPageCount()

        catalog.dropIndex("idx_name")
        catalog.createIndex("wide", "idx_name_2", "name")

        assertTrue(
            catalog.allocatedPageCount() <= peak,
            "expected the dropped index's reclaimed pages to be reused by the new index instead of growing the " +
                "file further (peak=$peak, now=${catalog.allocatedPageCount()})"
        )
        assertEquals("idx_name_2", table.secondaryIndexes.single().name)
    }

    @Test
    fun droppingASequenceReclaimsItsCounterPageForANewSequence() {
        val catalog = DiskCatalog.open(tempFile("kestrel-cat-data"), tempFile("kestrel-cat-wal"))
        catalog.createSequence(SequenceDefinition("seq1", incrementBy = 1, minValue = 1, maxValue = Long.MAX_VALUE, startValue = 1, cycle = false))
        val peak = catalog.allocatedPageCount()

        catalog.dropSequence("seq1")
        catalog.createSequence(SequenceDefinition("seq2", incrementBy = 1, minValue = 1, maxValue = Long.MAX_VALUE, startValue = 1, cycle = false))

        assertTrue(
            catalog.allocatedPageCount() <= peak,
            "expected the dropped sequence's counter page to be reused instead of growing the file further " +
                "(peak=$peak, now=${catalog.allocatedPageCount()})"
        )
    }
}
