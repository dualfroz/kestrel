package kestrel.storage

object ValueOrdering : Comparator<Value> {
    override fun compare(left: Value, right: Value): Int = when {
        left is Value.NullValue && right is Value.NullValue -> 0
        left is Value.NullValue -> -1
        right is Value.NullValue -> 1
        left is Value.IntegerValue && right is Value.IntegerValue -> left.value.compareTo(right.value)
        isNumeric(left) && isNumeric(right) -> asDouble(left).compareTo(asDouble(right))
        left is Value.TextValue && right is Value.TextValue -> left.value.compareTo(right.value)
        left is Value.BooleanValue && right is Value.BooleanValue -> left.value.compareTo(right.value)
        else -> rank(left).compareTo(rank(right))
    }

    private fun isNumeric(value: Value): Boolean = value is Value.IntegerValue || value is Value.RealValue

    private fun asDouble(value: Value): Double = when (value) {
        is Value.IntegerValue -> value.value.toDouble()
        is Value.RealValue -> value.value
        else -> 0.0
    }

    private fun rank(value: Value): Int = when (value) {
        is Value.NullValue -> 0
        is Value.BooleanValue -> 1
        is Value.IntegerValue -> 2
        is Value.RealValue -> 2
        is Value.TextValue -> 3
    }
}
