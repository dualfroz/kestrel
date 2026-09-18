package kestrel.catalog

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
