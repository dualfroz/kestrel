package kestrel.catalog

import kestrel.KestrelException

class Sequence(val definition: SequenceDefinition, initialValue: Long = definition.startValue, initialCalled: Boolean = false) {
    private var value: Long = initialValue
    private var called: Boolean = initialCalled

    fun peekNext(): Long = if (called) advance(value) else value

    fun next(): Long = peekNext().also {
        value = it
        called = true
    }

    fun snapshotPosition(): Pair<Long, Boolean> = value to called

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
