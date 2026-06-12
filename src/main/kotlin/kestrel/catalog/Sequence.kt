package kestrel.catalog

import kestrel.KestrelException

/**
 * Mirrors Postgres' own internal sequence representation (`pg_sequences.last_value`/`is_called`)
 * rather than "the next value to hand out" directly, because the two ALTER SEQUENCE behaviors this
 * engine supports need genuinely different arithmetic: a plain `ALTER SEQUENCE ... INCREMENT BY n`
 * (no RESTART) must recompute the NEXT value from the LAST one actually returned using the NEW
 * increment (so incrementing by 100 right after NEXTVAL returned 1 gives 101, not "whatever was
 * precomputed under the old increment"), while `RESTART [WITH n]` must instead make the very next
 * NEXTVAL return `n` directly, bypassing that arithmetic entirely for one call - exactly the same
 * shape as a fresh sequence's first-ever NEXTVAL returning `START WITH` untouched. [called] `false`
 * captures both of those "return this value as-is, don't advance from it" cases uniformly.
 *
 * [value]/[called] live entirely in plain `var`s deliberately NOT covered by [Catalog]'s
 * snapshot/restore copying of its `sequences` map entries - mirrors Postgres: a ROLLBACK undoes
 * `CREATE`/`DROP`/`ALTER SEQUENCE` (the catalog entry itself, since [Catalog.restore] reinstalls
 * whichever [Sequence] *instance* a snapshot captured a reference to) but never undoes a NEXTVAL
 * advance, even one made by the very transaction being rolled back - the whole point of a sequence
 * is to hand out values that never repeat, even across an aborted transaction, so two concurrent
 * transactions can both call NEXTVAL without either blocking on the other's eventual
 * commit/rollback decision. [Catalog.alterSequence] relies on this same identity trick in reverse:
 * it always installs a brand new [Sequence] instance (see [altered]) rather than mutating this one
 * in place, so ALTER SEQUENCE (unlike NEXTVAL) rolls back cleanly too.
 */
class Sequence(val definition: SequenceDefinition, initialValue: Long = definition.startValue, initialCalled: Boolean = false) {
    private var value: Long = initialValue
    private var called: Boolean = initialCalled

    /** What the next call to [next] would return, without consuming it - used by [kestrel.persistence.SqlWriter] to dump a sequence's current position so a reload resumes from there instead of restarting at [SequenceDefinition.startValue]. */
    fun peekNext(): Long = if (called) advance(value) else value

    fun next(): Long = peekNext().also {
        value = it
        called = true
    }

    /**
     * The raw `(value, called)` pair this instance currently holds, with no arithmetic and so no way
     * to throw - unlike [peekNext], which may run [advance] and hit an exhausted-without-CYCLE bound.
     * Used by [kestrel.store.sql.DiskCatalog] to persist a NEXTVAL advance (or an ALTER SEQUENCE's
     * resulting position) exactly as it stands, rather than one step further ahead: computing that
     * next step eagerly - right after a [next] call that legitimately landed exactly on
     * [kestrel.catalog.SequenceDefinition.maxValue]/[SequenceDefinition.minValue] - would spuriously
     * raise the "exhausted" exception a whole [next] call early, before anything ever actually asked
     * for a value beyond the bound.
     */
    fun snapshotPosition(): Pair<Long, Boolean> = value to called

    /**
     * Builds the [Sequence] `ALTER SEQUENCE` installs in place of this one: [newDefinition] takes
     * over immediately. With [restartAt] given (Postgres' `RESTART [WITH n]`, resolved to
     * [SequenceDefinition.startValue] by the caller for a bare `RESTART`), the next NEXTVAL returns
     * exactly that value; otherwise this sequence's own last-returned value and "has it ever been
     * called" flag both carry over unchanged, so the next NEXTVAL keeps advancing from where this
     * one left off - just using [newDefinition]'s (possibly different) increment/bounds.
     */
    fun altered(newDefinition: SequenceDefinition, restartAt: Long? = null): Sequence =
        if (restartAt != null) Sequence(newDefinition, restartAt, initialCalled = false)
        else Sequence(newDefinition, value, called)

    private fun advance(previous: Long): Long {
        val ascending = definition.incrementBy > 0
        val candidate = runCatching { Math.addExact(previous, definition.incrementBy) }.getOrNull()
        val exhausted = candidate == null ||
            (ascending && candidate > definition.maxValue) ||
            (!ascending && candidate < definition.minValue)
        if (!exhausted) return candidate!!
        if (!definition.cycle) {
            val bound = if (ascending) "maximum value (${definition.maxValue})" else "minimum value (${definition.minValue})"
            throw KestrelException("sequence '${definition.name}' has reached its $bound and does not CYCLE")
        }
        return if (ascending) definition.minValue else definition.maxValue
    }
}
