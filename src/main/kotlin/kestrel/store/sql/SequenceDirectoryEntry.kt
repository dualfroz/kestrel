package kestrel.store.sql

data class SequenceDirectoryEntry(
    val name: String,
    val incrementBy: Long,
    val minValue: Long,
    val maxValue: Long,
    val startValue: Long,
    val cycle: Boolean,
    val positionPageId: Long
)
