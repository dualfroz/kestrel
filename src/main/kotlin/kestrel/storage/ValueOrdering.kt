package kestrel.storage

import java.math.BigDecimal

object ValueOrdering : Comparator<Value> {
    override fun compare(left: Value, right: Value): Int = compare(left, right, Collation.Binary)

    /**
     * Same ordering as the plain two-argument [compare], except two [Value.TextValue]s are
     * compared through [collation] instead of always using [String.compareTo] - see
     * [kestrel.engine.compareOrderKeyValues], the only caller that ever passes a non-[Collation.Binary]
     * value (an explicit `ORDER BY ... COLLATE name`).
     */
    fun compare(left: Value, right: Value, collation: Collation): Int = when {
        left is Value.NullValue && right is Value.NullValue -> 0
        left is Value.NullValue -> -1
        right is Value.NullValue -> 1
        left is Value.IntegerValue && right is Value.IntegerValue -> left.value.compareTo(right.value)
        left is Value.DecimalValue || right is Value.DecimalValue -> compareAsDecimal(left, right)
        isNumeric(left) && isNumeric(right) -> asDouble(left).compareTo(asDouble(right))
        left is Value.TextValue && right is Value.TextValue -> collation.compare(left.value, right.value)
        left is Value.BooleanValue && right is Value.BooleanValue -> left.value.compareTo(right.value)
        left is Value.DateValue && right is Value.DateValue -> left.value.compareTo(right.value)
        else -> rank(left).compareTo(rank(right))
    }

    private fun isNumeric(value: Value): Boolean = value is Value.IntegerValue || value is Value.RealValue

    private fun asDouble(value: Value): Double = when (value) {
        is Value.IntegerValue -> value.value.toDouble()
        is Value.RealValue -> value.value
        else -> 0.0
    }

    /** Falls back to [rank] ordering (never throws) when one side isn't itself numeric. */
    private fun compareAsDecimal(left: Value, right: Value): Int {
        val a = asBigDecimalOrNull(left) ?: return rank(left).compareTo(rank(right))
        val b = asBigDecimalOrNull(right) ?: return rank(left).compareTo(rank(right))
        return a.compareTo(b)
    }

    private fun asBigDecimalOrNull(value: Value): BigDecimal? = when (value) {
        is Value.DecimalValue -> value.value
        is Value.IntegerValue -> BigDecimal(value.value)
        is Value.RealValue -> BigDecimal.valueOf(value.value)
        else -> null
    }

    private fun rank(value: Value): Int = when (value) {
        is Value.NullValue -> 0
        is Value.BooleanValue -> 1
        is Value.IntegerValue -> 2
        is Value.RealValue -> 2
        is Value.DecimalValue -> 2
        is Value.DateValue -> 3
        is Value.TextValue -> 4
    }
}
