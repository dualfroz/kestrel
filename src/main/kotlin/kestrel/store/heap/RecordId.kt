package kestrel.store.heap

/**
 * Identifies a record's slot within a heap page. Stable across compaction:
 * only the slot directory entry moves, never the (pageId, slotId) pair, so a
 * B+Tree entry can point at a [RecordId] without tracking page-internal
 * offsets.
 */
data class RecordId(val pageId: Long, val slotId: Int) {
    /** Packs into a single [Long] so it can be stored as a B+Tree leaf value. */
    fun encode(): Long = (pageId shl 16) or (slotId.toLong() and 0xFFFF)

    companion object {
        fun decode(encoded: Long): RecordId = RecordId(encoded ushr 16, (encoded and 0xFFFF).toInt())
    }
}
