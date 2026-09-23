package kestrel.store.sql

import kestrel.catalog.Column
import kestrel.catalog.UniqueConstraint
import kestrel.storage.ColumnType
import kestrel.storage.Row
import kestrel.storage.Value
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiskTableTest {
    private fun tempFile(prefix: String): File = Files.createTempFile(prefix, ".db").toFile().also { it.deleteOnExit() }

    private fun newCatalog(): DiskCatalog = DiskCatalog.open(tempFile("kestrel-sql-data"), tempFile("kestrel-sql-wal"))

    private val usersColumns = listOf(
        Column("id", ColumnType.Integer, notNull = true, primaryKey = true),
        Column("name", ColumnType.Text, notNull = true, primaryKey = false),
        Column("age", ColumnType.Integer, notNull = false, primaryKey = false)
    )

    private fun row(id: Long, name: String, age: Long?) =
        Row(listOf(Value.IntegerValue(id), Value.TextValue(name), age?.let { Value.IntegerValue(it) } ?: Value.NullValue))

    @Test
    fun insertGetAndScanRoundTripValues() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        val txn = catalog.beginTransaction()

        val rid = table.insert(txn, row(1, "alice", 30))
        assertEquals(row(1, "alice", 30), table.get(txn, rid))
        assertEquals(listOf(rid to row(1, "alice", 30)), table.scan(txn))

        catalog.commit(txn)
    }

    @Test
    fun duplicatePrimaryKeyIsRejectedAndLeavesTheIndexUntouched() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        val txn = catalog.beginTransaction()
        table.insert(txn, row(1, "alice", 30))

        assertFailsWith<DiskTableException> { table.insert(txn, row(1, "bob", 40)) }

        val (_, found) = table.findByPrimaryKey(txn, Value.IntegerValue(1))!!
        assertEquals("alice", (found.values[1] as Value.TextValue).value)
        catalog.commit(txn)
    }

    @Test
    fun rangeByPrimaryKeyReturnsOrderedRowsWithinBounds() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        val txn = catalog.beginTransaction()
        for (id in 1..10L) table.insert(txn, row(id, "user$id", id))

        val middle = table.rangeByPrimaryKey(txn, Value.IntegerValue(3), true, Value.IntegerValue(6), true)
        assertEquals((3..6L).toList(), middle.map { (it.second.values[0] as Value.IntegerValue).value })

        catalog.commit(txn)
    }

    @Test
    fun secondaryIndexFindsAllRowsSharingAValue() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        catalog.createIndex("users", "idx_age", "age")
        val txn = catalog.beginTransaction()
        table.insert(txn, row(1, "alice", 30))
        table.insert(txn, row(2, "bob", 30))
        table.insert(txn, row(3, "carol", 40))

        val thirtyYearOlds = table.findBySecondary(txn, "idx_age", Value.IntegerValue(30))
        assertEquals(setOf("alice", "bob"), thirtyYearOlds.map { (it.second.values[1] as Value.TextValue).value }.toSet())

        catalog.commit(txn)
    }

    @Test
    fun createIndexBackfillsExistingRows() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        val setup = catalog.beginTransaction()
        table.insert(setup, row(1, "alice", 30))
        table.insert(setup, row(2, "bob", 30))
        catalog.commit(setup)

        catalog.createIndex("users", "idx_age", "age")

        val txn = catalog.beginTransaction()
        val found = table.findBySecondary(txn, "idx_age", Value.IntegerValue(30))
        assertEquals(2, found.size)
        catalog.commit(txn)
    }

    @Test
    fun updateMovesPrimaryAndSecondaryIndexEntriesToTheNewValues() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        catalog.createIndex("users", "idx_age", "age")
        val txn = catalog.beginTransaction()
        val rid = table.insert(txn, row(1, "alice", 30))

        val newRid = table.update(txn, rid, row(2, "alice", 31))

        assertNull(table.findByPrimaryKey(txn, Value.IntegerValue(1)))
        assertEquals(row(2, "alice", 31), table.findByPrimaryKey(txn, Value.IntegerValue(2))!!.second)
        assertTrue(table.findBySecondary(txn, "idx_age", Value.IntegerValue(30)).isEmpty())
        assertEquals(listOf(newRid), table.findBySecondary(txn, "idx_age", Value.IntegerValue(31)).map { it.first })

        catalog.commit(txn)
    }

    @Test
    fun updateThatWouldCollideWithAnotherRowsPrimaryKeyIsRejected() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        val txn = catalog.beginTransaction()
        val rid = table.insert(txn, row(1, "alice", 30))
        table.insert(txn, row(2, "bob", 40))

        assertFailsWith<DiskTableException> { table.update(txn, rid, row(2, "alice", 30)) }
        catalog.commit(txn)
    }

    @Test
    fun deleteRemovesTheRowAndBothIndexEntries() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        catalog.createIndex("users", "idx_age", "age")
        val txn = catalog.beginTransaction()
        val rid = table.insert(txn, row(1, "alice", 30))

        table.delete(txn, rid)

        assertTrue(table.scan(txn).isEmpty())
        assertNull(table.findByPrimaryKey(txn, Value.IntegerValue(1)))
        assertTrue(table.findBySecondary(txn, "idx_age", Value.IntegerValue(30)).isEmpty())
        catalog.commit(txn)
    }

    @Test
    fun updateRelocatesARowThatNoLongerFitsInItsSlotAndIndexesFollowTheNewRecordId() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        val txn = catalog.beginTransaction()
        val rid = table.insert(txn, row(1, "a", 1))

        val bigName = "x".repeat(3000)
        val newRid = table.update(txn, rid, row(1, bigName, 1))

        assertTrue(newRid != rid, "growing the row past its slot capacity should relocate it")
        assertEquals(bigName, table.findByPrimaryKey(txn, Value.IntegerValue(1))!!.second.values[1].let { (it as Value.TextValue).value })
        catalog.commit(txn)
    }

    @Test
    fun abortingATransactionUndoesInsertsAndIndexEntries() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        val committed = catalog.beginTransaction()
        table.insert(committed, row(1, "alice", 30))
        catalog.commit(committed)

        val doomed = catalog.beginTransaction()
        table.insert(doomed, row(2, "bob", 40))
        catalog.abort(doomed)

        val reader = catalog.beginTransaction()
        assertEquals(listOf(1L), table.scan(reader).map { (it.second.values[0] as Value.IntegerValue).value })
        assertNull(table.findByPrimaryKey(reader, Value.IntegerValue(2)))
        catalog.commit(reader)
    }

    @Test
    fun notNullConstraintIsEnforcedAtInsert() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        val txn = catalog.beginTransaction()
        assertFailsWith<DiskTableException> {
            table.insert(txn, Row(listOf(Value.IntegerValue(1), Value.NullValue, Value.NullValue)))
        }
        catalog.abort(txn)
    }

    private val orderLineColumns = listOf(
        Column("order_id", ColumnType.Integer, notNull = true, primaryKey = false),
        Column("line_no", ColumnType.Integer, notNull = true, primaryKey = false),
        Column("sku", ColumnType.Text, notNull = false, primaryKey = false)
    )

    private fun orderLine(orderId: Long, lineNo: Long, sku: String?) =
        Row(listOf(Value.IntegerValue(orderId), Value.IntegerValue(lineNo), sku?.let { Value.TextValue(it) } ?: Value.NullValue))

    @Test
    fun compositePrimaryKeyAcceptsDistinctCombinationsAndRejectsDuplicates() {
        val catalog = newCatalog()
        val table = catalog.createTable("order_lines", orderLineColumns, compositePrimaryKey = listOf("order_id", "line_no"))
        val txn = catalog.beginTransaction()
        table.insert(txn, orderLine(1, 1, "widget"))
        table.insert(txn, orderLine(1, 2, "gadget"))
        table.insert(txn, orderLine(2, 1, "widget"))

        assertFailsWith<DiskTableException> { table.insert(txn, orderLine(1, 1, "duplicate")) }

        assertEquals(3, table.scan(txn).size)
        catalog.commit(txn)
    }

    @Test
    fun compositePrimaryKeyPointLookupFindsTheExactRowByAllColumns() {
        val catalog = newCatalog()
        val table = catalog.createTable("order_lines", orderLineColumns, compositePrimaryKey = listOf("order_id", "line_no"))
        val txn = catalog.beginTransaction()
        table.insert(txn, orderLine(1, 1, "widget"))
        table.insert(txn, orderLine(1, 2, "gadget"))

        val found = table.findByPrimaryKey(txn, listOf(Value.IntegerValue(1), Value.IntegerValue(2)))!!
        assertEquals("gadget", (found.second.values[2] as Value.TextValue).value)
        assertEquals(null, table.findByPrimaryKey(txn, listOf(Value.IntegerValue(1), Value.IntegerValue(99))))
        catalog.commit(txn)
    }

    @Test
    fun compositePrimaryKeyUpdateAndDeleteMoveOrRemoveTheIndexEntry() {
        val catalog = newCatalog()
        val table = catalog.createTable("order_lines", orderLineColumns, compositePrimaryKey = listOf("order_id", "line_no"))
        val txn = catalog.beginTransaction()
        val rid = table.insert(txn, orderLine(1, 1, "widget"))

        val newRid = table.update(txn, rid, orderLine(1, 2, "widget"))
        assertNull(table.findByPrimaryKey(txn, listOf(Value.IntegerValue(1), Value.IntegerValue(1))))
        assertEquals("widget", table.findByPrimaryKey(txn, listOf(Value.IntegerValue(1), Value.IntegerValue(2)))!!.second.values[2].let { (it as Value.TextValue).value })

        table.delete(txn, newRid)
        assertNull(table.findByPrimaryKey(txn, listOf(Value.IntegerValue(1), Value.IntegerValue(2))))
        assertTrue(table.scan(txn).isEmpty())
        catalog.commit(txn)
    }

    @Test
    fun compositePrimaryKeyUpdateThatCollidesWithAnotherRowIsRejected() {
        val catalog = newCatalog()
        val table = catalog.createTable("order_lines", orderLineColumns, compositePrimaryKey = listOf("order_id", "line_no"))
        val txn = catalog.beginTransaction()
        val rid = table.insert(txn, orderLine(1, 1, "widget"))
        table.insert(txn, orderLine(1, 2, "gadget"))

        assertFailsWith<DiskTableException> { table.update(txn, rid, orderLine(1, 2, "widget")) }
        catalog.commit(txn)
    }

    @Test
    fun decimalAndDateColumnsRoundTripThroughTheFullDiskBackedStack() {
        val catalog = newCatalog()
        val columns = listOf(
            Column("id", ColumnType.Integer, notNull = true, primaryKey = true),
            Column("amount", ColumnType.Decimal, notNull = false, primaryKey = false, precision = 12, scale = 4),
            Column("placed", ColumnType.Date, notNull = false, primaryKey = false)
        )
        val table = catalog.createTable("orders", columns)
        val txn = catalog.beginTransaction()
        val amount = BigDecimal("123456.7890")
        val placed = LocalDate.of(2024, 1, 15)
        val inserted = Row(listOf(Value.IntegerValue(1), Value.DecimalValue(amount), Value.DateValue(placed)))
        val rid = table.insert(txn, inserted)
        assertEquals(inserted, table.get(txn, rid))
        catalog.commit(txn)

        val reader = catalog.beginTransaction()
        assertEquals(inserted, table.get(reader, rid))
        catalog.commit(reader)
    }

    @Test
    fun uniqueSecondaryIndexRejectsAnInsertSharingAnExistingNonNullValue() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        catalog.createIndex("users", "idx_name", "name", unique = true)
        val txn = catalog.beginTransaction()
        table.insert(txn, row(1, "alice", 30))

        assertFailsWith<DiskTableException> { table.insert(txn, row(2, "alice", 40)) }

        assertEquals(1, table.scan(txn).size)
        catalog.commit(txn)
    }

    @Test
    fun uniqueSecondaryIndexAllowsMultipleNullValues() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        catalog.createIndex("users", "idx_age", "age", unique = true)
        val txn = catalog.beginTransaction()
        table.insert(txn, row(1, "alice", null))
        table.insert(txn, row(2, "bob", null))

        assertEquals(2, table.scan(txn).size)
        catalog.commit(txn)
    }

    @Test
    fun uniqueSecondaryIndexRejectsAnUpdateThatWouldCollideWithAnotherRow() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        catalog.createIndex("users", "idx_name", "name", unique = true)
        val txn = catalog.beginTransaction()
        val rid = table.insert(txn, row(1, "alice", 30))
        table.insert(txn, row(2, "bob", 40))

        assertFailsWith<DiskTableException> { table.update(txn, rid, row(1, "bob", 30)) }
        assertEquals("alice", table.get(txn, rid).values[1].let { (it as Value.TextValue).value })
        catalog.commit(txn)
    }

    @Test
    fun uniqueSecondaryIndexAllowsAnUpdateThatDoesNotChangeTheConstrainedColumn() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        catalog.createIndex("users", "idx_name", "name", unique = true)
        val txn = catalog.beginTransaction()
        val rid = table.insert(txn, row(1, "alice", 30))

        table.update(txn, rid, row(1, "alice", 31))

        assertEquals(31L, (table.get(txn, rid).values[2] as Value.IntegerValue).value)
        catalog.commit(txn)
    }

    @Test
    fun creatingAUniqueIndexOverPreExistingDuplicateValuesFailsAndAttachesNoIndex() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        val txn = catalog.beginTransaction()
        table.insert(txn, row(1, "alice", 30))
        table.insert(txn, row(2, "alice", 40))
        catalog.commit(txn)

        assertFailsWith<DiskTableException> { catalog.createIndex("users", "idx_name", "name", unique = true) }
        assertTrue(table.secondaryIndexes.isEmpty())
    }

    @Test
    fun compositeSecondaryIndexFindsRowsByEqualityOnEveryColumn() {
        val catalog = newCatalog()
        val table = catalog.createTable("users", usersColumns)
        catalog.createIndex("users", "idx_both", listOf("name", "age"))
        val txn = catalog.beginTransaction()
        val rid = table.insert(txn, row(1, "alice", 30))
        table.insert(txn, row(2, "alice", 40))

        val found = table.findBySecondary(txn, "idx_both", listOf(Value.TextValue("alice"), Value.IntegerValue(30)))

        assertEquals(listOf(rid to row(1, "alice", 30)), found)
        catalog.commit(txn)
    }

    @Test
    fun uniqueCompositeSecondaryIndexRejectsADuplicateColumnCombinationOnInsertButAllowsAPartialOverlap() {
        val catalog = newCatalog()
        val table = catalog.createTable("registrations", registrationColumns)
        catalog.createIndex("registrations", "idx_reg", listOf("event_id", "user_id"), unique = true)
        val txn = catalog.beginTransaction()
        table.insert(txn, registration(1, 100))

        assertFailsWith<DiskTableException> { table.insert(txn, registration(1, 100)) }
        table.insert(txn, registration(1, 200))

        assertEquals(2, table.scan(txn).size)
        catalog.commit(txn)
    }

    @Test
    fun uniqueCompositeSecondaryIndexAllowsMultipleRowsWithANullInEitherColumn() {
        val catalog = newCatalog()
        val table = catalog.createTable("registrations", registrationColumns)
        catalog.createIndex("registrations", "idx_reg", listOf("event_id", "user_id"), unique = true)
        val txn = catalog.beginTransaction()
        table.insert(txn, registration(1, null))
        table.insert(txn, registration(1, null))
        table.insert(txn, registration(null, 100))
        table.insert(txn, registration(null, 100))

        assertEquals(4, table.scan(txn).size)
        catalog.commit(txn)
    }

    @Test
    fun uniqueCompositeSecondaryIndexRejectsAnUpdateThatWouldCollideWithAnotherRowsCombination() {
        val catalog = newCatalog()
        val table = catalog.createTable("registrations", registrationColumns)
        catalog.createIndex("registrations", "idx_reg", listOf("event_id", "user_id"), unique = true)
        val txn = catalog.beginTransaction()
        table.insert(txn, registration(1, 100))
        val rid = table.insert(txn, registration(1, 200))

        assertFailsWith<DiskTableException> { table.update(txn, rid, registration(1, 100)) }
        catalog.commit(txn)
    }

    @Test
    fun creatingAUniqueCompositeIndexOverPreExistingDuplicateCombinationsFailsAndAttachesNoIndex() {
        val catalog = newCatalog()
        val table = catalog.createTable("registrations", registrationColumns)
        val txn = catalog.beginTransaction()
        table.insert(txn, registration(1, 100))
        table.insert(txn, registration(1, 100))
        catalog.commit(txn)

        assertFailsWith<DiskTableException> { catalog.createIndex("registrations", "idx_reg", listOf("event_id", "user_id"), unique = true) }
        assertTrue(table.secondaryIndexes.isEmpty())
    }

    private val registrationColumns = listOf(
        Column("event_id", ColumnType.Integer, notNull = false, primaryKey = false),
        Column("user_id", ColumnType.Integer, notNull = false, primaryKey = false)
    )

    private fun registration(eventId: Long?, userId: Long?) = Row(
        listOf(eventId?.let { Value.IntegerValue(it) } ?: Value.NullValue, userId?.let { Value.IntegerValue(it) } ?: Value.NullValue)
    )

    @Test
    fun uniqueConstraintRejectsADuplicateColumnCombinationButAllowsPartialOverlap() {
        val catalog = newCatalog()
        val table = catalog.createTable(
            "registrations", registrationColumns,
            uniqueConstraints = listOf(UniqueConstraint(null, listOf("event_id", "user_id")))
        )
        val txn = catalog.beginTransaction()
        table.insert(txn, registration(1, 100))

        assertFailsWith<DiskTableException> { table.insert(txn, registration(1, 100)) }
        table.insert(txn, registration(1, 200))
        table.insert(txn, registration(2, 100))

        assertEquals(3, table.scan(txn).size)
        catalog.commit(txn)
    }

    @Test
    fun uniqueConstraintExemptsRowsWhereAnyConstrainedColumnIsNull() {
        val catalog = newCatalog()
        val table = catalog.createTable(
            "registrations", registrationColumns,
            uniqueConstraints = listOf(UniqueConstraint(null, listOf("event_id", "user_id")))
        )
        val txn = catalog.beginTransaction()
        table.insert(txn, registration(1, null))
        table.insert(txn, registration(1, null))

        assertEquals(2, table.scan(txn).size)
        catalog.commit(txn)
    }

    @Test
    fun uniqueConstraintRejectsAnUpdateThatWouldCollideWithAnotherRow() {
        val catalog = newCatalog()
        val table = catalog.createTable(
            "registrations", registrationColumns,
            uniqueConstraints = listOf(UniqueConstraint(null, listOf("event_id", "user_id")))
        )
        val txn = catalog.beginTransaction()
        val rid = table.insert(txn, registration(1, 100))
        table.insert(txn, registration(1, 200))

        assertFailsWith<DiskTableException> { table.update(txn, rid, registration(1, 200)) }
        catalog.commit(txn)
    }
}
