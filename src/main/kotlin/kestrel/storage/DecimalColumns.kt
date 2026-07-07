package kestrel.storage

import java.math.BigDecimal
import java.math.RoundingMode
import kestrel.KestrelException

/**
 * Shared DECIMAL(precision, scale) rules used both when a value is coerced into a
 * DECIMAL column (INSERT/UPDATE) and when it is produced by an explicit CAST. Kept as
 * a single place so both call sites agree on rounding mode and overflow behavior.
 */
object DecimalColumns {
    const val DEFAULT_PRECISION = 18
    const val DEFAULT_SCALE = 2

    fun normalize(raw: BigDecimal, precision: Int?, scale: Int?, context: String): BigDecimal {
        val effectiveScale = scale ?: DEFAULT_SCALE
        val effectivePrecision = precision ?: DEFAULT_PRECISION
        val rounded = raw.setScale(effectiveScale, RoundingMode.HALF_UP)
        val digits = rounded.unscaledValue().abs().toString().length
        if (digits > effectivePrecision) {
            throw KestrelException(
                "$context: $rounded has more than $effectivePrecision digits for DECIMAL($effectivePrecision, $effectiveScale)"
            )
        }
        return rounded
    }
}
