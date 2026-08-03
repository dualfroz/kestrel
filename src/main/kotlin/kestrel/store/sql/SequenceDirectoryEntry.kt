package kestrel.store.sql

/**
 * The durable, on-disk description of one SEQUENCE's static configuration - mirrors
 * [kestrel.catalog.SequenceDefinition] field-for-field, plus [positionPageId]: the raw page (see
 * [kestrel.store.engine.StorageEngine.allocateRawPage]) that holds its live counter. Deliberately
 * split from the counter itself for the same reason [kestrel.catalog.Sequence] separates
 * [kestrel.catalog.SequenceDefinition] from its own mutable `value`/`called` fields - CREATE/DROP/
 * ALTER SEQUENCE go through the normal transactional directory heap (see [DiskCatalog]) so they roll
 * back like any other DDL, but a NEXTVAL advance must never be undone by a ROLLBACK, so it lives on
 * its own page written outside any transaction (see [StorageEngine.writeRawPage][kestrel.store.engine.StorageEngine.writeRawPage]).
 */
data class SequenceDirectoryEntry(
    val name: String,
    val incrementBy: Long,
    val minValue: Long,
    val maxValue: Long,
    val startValue: Long,
    val cycle: Boolean,
    val positionPageId: Long
)
