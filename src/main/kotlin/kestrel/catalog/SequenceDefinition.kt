package kestrel.catalog

/**
 * A sequence's static configuration - separate from [Sequence]'s mutable counter so that a
 * `CREATE SEQUENCE`/`ALTER SEQUENCE`/`DROP SEQUENCE` (all fully transactional, unlike a NEXTVAL
 * advance - see [Sequence]'s own doc) can be represented and rolled back like any other catalog
 * object, by simply installing or removing an immutable value rather than mutating shared state.
 *
 * [startValue] is never changed by `ALTER SEQUENCE` (Postgres has no way to do this either) - it is
 * only ever the value a bare `RESTART` (no `WITH n`) resets the counter back to, recorded once at
 * `CREATE SEQUENCE` time and carried forward unchanged by every later `ALTER SEQUENCE` (see
 * [Sequence.altered]).
 */
data class SequenceDefinition(
    val name: String,
    val incrementBy: Long,
    val minValue: Long,
    val maxValue: Long,
    val startValue: Long,
    val cycle: Boolean
) {
    init {
        require(incrementBy != 0L) { "sequence '$name' INCREMENT BY cannot be zero" }
        require(minValue <= maxValue) { "sequence '$name' MINVALUE ($minValue) must not exceed MAXVALUE ($maxValue)" }
    }
}
