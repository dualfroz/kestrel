package kestrel.store.sql

import kestrel.catalog.Column
import kestrel.catalog.UniqueConstraint
import kestrel.storage.ColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DirectoryEntryCodecTest {
    @Test
    fun roundTripsATableWithNoIndexes() {
        val entry = TableDirectoryEntry(
            tableName = "widgets",
            columns = listOf(Column("id", ColumnType.Integer, notNull = true, primaryKey = false)),
            dataHeaderPageId = 3L,
            primaryKeyIndexRootPageId = null,
            secondaryIndexes = emptyList()
        )
        assertEquals(entry, DirectoryEntryCodec.decode(DirectoryEntryCodec.encode(entry)))
    }

    @Test
    fun roundTripsATableWithAPrimaryKeyAndSecondaryIndexes() {
        val entry = TableDirectoryEntry(
            tableName = "orders",
            columns = listOf(
                Column("id", ColumnType.Integer, notNull = true, primaryKey = true),
                Column("customer", ColumnType.Text, notNull = false, primaryKey = false),
                Column("total", ColumnType.Real, notNull = true, primaryKey = false),
                Column("shipped", ColumnType.Boolean, notNull = false, primaryKey = false)
            ),
            dataHeaderPageId = 10L,
            primaryKeyIndexRootPageId = 11L,
            secondaryIndexes = listOf(
                SecondaryIndexEntry("idx_customer", listOf("customer"), 12L),
                SecondaryIndexEntry("idx_total", listOf("total"), 13L)
            )
        )
        assertEquals(entry, DirectoryEntryCodec.decode(DirectoryEntryCodec.encode(entry)))
    }

    @Test
    fun roundTripsDecimalColumnsWithAndWithoutExplicitPrecisionAndDateColumns() {
        val entry = TableDirectoryEntry(
            tableName = "priced",
            columns = listOf(
                Column("id", ColumnType.Integer, notNull = true, primaryKey = true),
                Column("bare", ColumnType.Decimal, notNull = false, primaryKey = false),
                Column("scaled", ColumnType.Decimal, notNull = false, primaryKey = false, precision = 12, scale = 4),
                Column("placed", ColumnType.Date, notNull = false, primaryKey = false)
            ),
            dataHeaderPageId = 4L,
            primaryKeyIndexRootPageId = 5L,
            secondaryIndexes = emptyList()
        )
        val decoded = DirectoryEntryCodec.decode(DirectoryEntryCodec.encode(entry))
        assertEquals(entry, decoded)
        assertEquals(null, decoded.columns[1].precision)
        assertEquals(12, decoded.columns[2].precision)
        assertEquals(4, decoded.columns[2].scale)
    }

    @Test
    fun roundTripsATableWithACompositePrimaryKeyAndEmptyOnesDoNotAddAnyBytes() {
        val withComposite = TableDirectoryEntry(
            tableName = "order_lines",
            columns = listOf(
                Column("order_id", ColumnType.Integer, notNull = true, primaryKey = false),
                Column("line_no", ColumnType.Integer, notNull = true, primaryKey = false),
                Column("sku", ColumnType.Text, notNull = false, primaryKey = false)
            ),
            dataHeaderPageId = 7L,
            primaryKeyIndexRootPageId = 8L,
            secondaryIndexes = emptyList(),
            compositePrimaryKey = listOf("order_id", "line_no")
        )
        val decoded = DirectoryEntryCodec.decode(DirectoryEntryCodec.encode(withComposite))
        assertEquals(withComposite, decoded)
        assertEquals(listOf("order_id", "line_no"), decoded.compositePrimaryKey)

        val withoutComposite = withComposite.copy(compositePrimaryKey = emptyList())
        assertEquals(withoutComposite, DirectoryEntryCodec.decode(DirectoryEntryCodec.encode(withoutComposite)))
    }

    @Test
    fun roundTripsAUniqueSecondaryIndexFlagAndDeclarativeUniqueConstraints() {
        val entry = TableDirectoryEntry(
            tableName = "users",
            columns = listOf(
                Column("id", ColumnType.Integer, notNull = true, primaryKey = true),
                Column("email", ColumnType.Text, notNull = false, primaryKey = false),
                Column("event_id", ColumnType.Integer, notNull = false, primaryKey = false),
                Column("user_id", ColumnType.Integer, notNull = false, primaryKey = false)
            ),
            dataHeaderPageId = 20L,
            primaryKeyIndexRootPageId = 21L,
            secondaryIndexes = listOf(SecondaryIndexEntry("idx_email", listOf("email"), 22L, unique = true)),
            uniqueConstraints = listOf(
                UniqueConstraint("uq_email_named", listOf("email")),
                UniqueConstraint(null, listOf("event_id", "user_id"))
            )
        )
        val decoded = DirectoryEntryCodec.decode(DirectoryEntryCodec.encode(entry))
        assertEquals(entry, decoded)
        assertTrue(decoded.secondaryIndexes.single().unique)
        assertEquals(listOf("event_id", "user_id"), decoded.uniqueConstraints[1].columns)
        assertEquals(null, decoded.uniqueConstraints[1].name)
    }

    @Test
    fun aNonUniqueSecondaryIndexAndEmptyUniqueConstraintsDoNotAddSpuriousFlagsOnDecode() {
        val entry = TableDirectoryEntry(
            tableName = "widgets",
            columns = listOf(Column("id", ColumnType.Integer, notNull = true, primaryKey = false)),
            dataHeaderPageId = 1L,
            primaryKeyIndexRootPageId = null,
            secondaryIndexes = listOf(SecondaryIndexEntry("idx_id", listOf("id"), 2L))
        )
        val decoded = DirectoryEntryCodec.decode(DirectoryEntryCodec.encode(entry))
        assertEquals(false, decoded.secondaryIndexes.single().unique)
        assertTrue(decoded.uniqueConstraints.isEmpty())
    }

    @Test
    fun roundTripsACompositeMultiColumnSecondaryIndex() {
        val entry = TableDirectoryEntry(
            tableName = "order_lines",
            columns = listOf(
                Column("order_id", ColumnType.Integer, notNull = true, primaryKey = false),
                Column("sku", ColumnType.Text, notNull = false, primaryKey = false)
            ),
            dataHeaderPageId = 5L,
            primaryKeyIndexRootPageId = null,
            secondaryIndexes = listOf(SecondaryIndexEntry("idx_order_sku", listOf("order_id", "sku"), 6L, unique = true))
        )
        val decoded = DirectoryEntryCodec.decode(DirectoryEntryCodec.encode(entry))
        assertEquals(entry, decoded)
        assertEquals(listOf("order_id", "sku"), decoded.secondaryIndexes.single().columnNames)
    }

    @Test
    fun decodingAnUnknownColumnTypeFails() {
        val entry = TableDirectoryEntry("t", listOf(Column("c", ColumnType.Integer, false, false)), 0L, null, emptyList())
        val bytes = DirectoryEntryCodec.encode(entry).copyOf()
        var offset = 0
        val nameLength = bytes[offset].toInt() and 0x7F
        offset += 1 + nameLength
        offset += 1
        val colNameLength = bytes[offset].toInt() and 0x7F
        offset += 1 + colNameLength
        val typeLength = bytes[offset].toInt() and 0x7F
        for (i in 0 until typeLength) bytes[offset + 1 + i] = 'Z'.code.toByte()
        assertThrowsCodecException { DirectoryEntryCodec.decode(bytes) }
    }

    private fun assertThrowsCodecException(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected decode to fail")
        } catch (e: kestrel.store.codec.CodecException) {
        }
    }
}
