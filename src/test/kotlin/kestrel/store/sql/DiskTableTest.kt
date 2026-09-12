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

