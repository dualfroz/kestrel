package kestrel.store.wal

sealed class LogRecord {
    class Begin(val txnId: Long) : LogRecord()

    class Commit(val txnId: Long) : LogRecord()

    class Abort(val txnId: Long) : LogRecord()

    class Update(
        val txnId: Long,
        val prevLsn: Long,
        val pageId: Long,
        val before: ByteArray,
        val after: ByteArray
    ) : LogRecord()

    class CompensationUpdate(
        val txnId: Long,
        val prevLsn: Long,
        val pageId: Long,
        val undoImage: ByteArray,
        val undoNextLsn: Long
    ) : LogRecord()

    class Checkpoint(val activeTxnIds: List<Long>) : LogRecord()
}
