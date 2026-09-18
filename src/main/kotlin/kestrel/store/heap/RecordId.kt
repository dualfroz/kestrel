package kestrel.store.heap

data class RecordId(val pageId: Long, val slotId: Int) {
    fun encode(): Long = (pageId shl 16) or (slotId.toLong() and 0xFFFF)

    companion object {
        fun decode(encoded: Long): RecordId = RecordId(encoded ushr 16, (encoded and 0xFFFF).toInt())
    }
}
