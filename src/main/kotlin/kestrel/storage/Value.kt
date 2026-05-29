package kestrel.storage

enum class ColumnType {
    Integer, Text, Boolean, Real;

    val displayName: String
        get() = when (this) {
            Integer -> "INTEGER"
            Text -> "TEXT"
            Boolean -> "BOOLEAN"
            Real -> "REAL"
        }

    companion object {
        fun fromKeyword(keyword: String): ColumnType? = when (keyword.uppercase()) {
            "INTEGER" -> Integer
            "TEXT" -> Text
            "BOOLEAN" -> Boolean
            "REAL" -> Real
            else -> null
        }
    }
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

    object NullValue : Value() {
        override val type: ColumnType? get() = null
    }

    val isNull: Boolean get() = this is NullValue
}

val Value.typeName: String
    get() = type?.displayName ?: "NULL"
