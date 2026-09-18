package kestrel.storage

import java.math.BigDecimal
import java.time.LocalDate

enum class ColumnType {
    Integer, Text, Boolean, Real, Decimal, Date;

    val displayName: String
        get() = when (this) {
            Integer -> "INTEGER"
            Text -> "TEXT"
            Boolean -> "BOOLEAN"
            Real -> "REAL"
            Decimal -> "DECIMAL"
            Date -> "DATE"
        }

    companion object {
        fun fromKeyword(keyword: String): ColumnType? = when (keyword.uppercase()) {
            "INTEGER" -> Integer
            "TEXT" -> Text
            "BOOLEAN" -> Boolean
            "REAL" -> Real
            "DECIMAL" -> Decimal
            "DATE" -> Date
            else -> null
        }
    }
}

fun ColumnType.displayWith(precision: Int?, scale: Int?): String = when {
    this != ColumnType.Decimal || precision == null -> displayName
    scale == null -> "$displayName($precision)"
    else -> "$displayName($precision, $scale)"
}

sealed class Value {
    abstract val type: ColumnType?

    data class IntegerValue(val value: Long) : Value() {
        override val type get() = ColumnType.Integer
    }

    data class RealValue(val value: Double) : Value() {
        override val type get() = ColumnType.Real
    }

    data class TextValue(val value: String) : Value() {
        override val type get() = ColumnType.Text
    }

    data class BooleanValue(val value: Boolean) : Value() {
        override val type get() = ColumnType.Boolean
    }

    class DecimalValue(val value: BigDecimal) : Value() {
        override val type get() = ColumnType.Decimal

        override fun equals(other: Any?): Boolean =
            other is DecimalValue && value.compareTo(other.value) == 0

        override fun hashCode(): Int {
            val canonical = if (value.signum() == 0) BigDecimal.ZERO else value.stripTrailingZeros()
            return canonical.hashCode()
        }

        override fun toString(): String = "DecimalValue(value=$value)"
    }

    data class DateValue(val value: LocalDate) : Value() {
        override val type get() = ColumnType.Date
    }

    object NullValue : Value() {
        override val type: ColumnType? get() = null
    }

    val isNull: Boolean get() = this is NullValue
}

val Value.typeName: String
    get() = type?.displayName ?: "NULL"
