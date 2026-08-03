package kestrel.store.wal

/**
 * A single write-ahead log entry. [Update] carries whole-page before/after
 * images rather than a delta, so redoing it is just overwriting a page with
 * [Update.after]; undoing it is overwriting with [Update.before]. `prevLsn`
 * threads a transaction's updates into a backward-traversable chain, the
 * same role it plays in ARIES.
 */
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

    /**
     * A compensation log record (CLR), ARIES terminology for "this page was just
     * restored to an earlier image by an undo". Written by [kestrel.store.wal.RecoveryManager.rollbackTo]
     * for every [Update] it undoes on the way to a savepoint, so that:
     * - forward redo ([kestrel.store.wal.RecoveryManager.recover]) can reproduce the undo's effect
     *   just like any other logged write, by reapplying [undoImage].
     * - a later undo walk (a full [kestrel.store.wal.RecoveryManager.rollback], or a rollback to an
     *   earlier savepoint) that reaches this record never re-undoes the [Update] it compensates for;
     *   it jumps straight to [undoNextLsn] instead, which is that [Update]'s own `prevLsn` - the
     *   transaction's undo chain skips the already-undone range as if it had never happened, exactly
     *   the property that makes partial rollback safe to repeat and safe to crash through.
     * Chains into the transaction's `prevLsn` thread the same way [Update] does, via [prevLsn] - so a
     * write logged after a savepoint rollback correctly points back at this CLR, not at the undone
     * [Update] it replaced.
     */
    class CompensationUpdate(
        val txnId: Long,
        val prevLsn: Long,
        val pageId: Long,
        val undoImage: ByteArray,
        val undoNextLsn: Long
    ) : LogRecord()

    /** Marks a quiescent checkpoint: written only when no transaction is active. */
    class Checkpoint(val activeTxnIds: List<Long>) : LogRecord()
}
