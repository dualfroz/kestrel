package kestrel.engine

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kestrel.KestrelException
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.OrderKey
import kestrel.storage.Collation
import kestrel.storage.Value
import kestrel.storage.ValueOrdering
import kestrel.storage.typeName

internal object AggregateFunctions {
    fun compute(
        name: String,
        argument: Expression?,
        star: Boolean,
        rows: List<List<Value>>,
        evaluateRow: (Expression, List<Value>) -> Value,
        separator: Expression? = null,
        orderBy: List<OrderKey> = emptyList(),
        distinct: Boolean = false,
        declaredCollationOf: (ColumnExpression) -> Collation? = { null }
    ): Value {
        val upperName = name.uppercase()
        if (upperName == "COUNT") {
            if (star) return Value.IntegerValue(rows.size.toLong())
            val arg = argument ?: return Value.IntegerValue(rows.size.toLong())
            val nonNull = rows.map { evaluateRow(arg, it) }.filter { it !is Value.NullValue }
            val count = if (distinct) nonNull.distinctByCollation(resolveCollation(arg, declaredCollationOf)).size else nonNull.size
            return Value.IntegerValue(count.toLong())
        }
        if (upperName == "STRING_AGG") return stringAgg(argument, separator, orderBy, rows, evaluateRow, declaredCollationOf)
        val arg = argument ?: throw KestrelException("aggregate function $name requires an argument")
        val nonNullValues = rows.map { evaluateRow(arg, it) }.filter { it !is Value.NullValue }
        val values = if (distinct) nonNullValues.distinctByCollation(resolveCollation(arg, declaredCollationOf)) else nonNullValues
        return when (upperName) {
            "SUM" -> sum(values)
            "AVG" -> average(values)
            "MIN" -> values.minWithOrNull(ValueOrdering) ?: Value.NullValue
            "MAX" -> values.maxWithOrNull(ValueOrdering) ?: Value.NullValue
            else -> throw KestrelException("unknown function: $name")
        }
    }

    private fun stringAgg(
        argument: Expression?,
        separator: Expression?,
        orderBy: List<OrderKey>,
        rows: List<List<Value>>,
        evaluateRow: (Expression, List<Value>) -> Value,
        declaredCollationOf: (ColumnExpression) -> Collation? = { null }
    ): Value {
        val valueArgument = argument ?: throw KestrelException("STRING_AGG requires a value argument")
        val separatorArgument = separator ?: throw KestrelException("STRING_AGG requires a separator argument")
        var entries = rows.mapNotNull { row ->
            val value = evaluateRow(valueArgument, row)
            if (value is Value.NullValue) null else row to text(value, "STRING_AGG")
        }
        if (entries.isEmpty()) return Value.NullValue
        if (orderBy.isNotEmpty()) {
            val comparator = Comparator<Pair<List<Value>, String>> { first, second ->
                var result = 0
                for (key in orderBy) {
                    val comparison = compareOrderKeyValues(
                        evaluateRow(key.expression, first.first),
                        evaluateRow(key.expression, second.first),
                        key,
                        declaredCollationOf
                    )
                    if (comparison != 0) {
                        result = comparison
                        break
                    }
                }
                result
            }
            entries = entries.sortedWith(comparator)
        }
        val separatorText = text(evaluateRow(separatorArgument, entries.first().first), "STRING_AGG")
        return Value.TextValue(entries.joinToString(separatorText) { it.second })
    }

    private fun text(value: Value, function: String): String = when (value) {
        is Value.TextValue -> value.value
        else -> throw KestrelException("function $function requires a TEXT argument but got ${value.typeName}")
    }

    private fun sum(values: List<Value>): Value {
        if (values.isEmpty()) return Value.NullValue
        if (values.all { it is Value.IntegerValue }) {
            return Value.IntegerValue(values.sumOf { (it as Value.IntegerValue).value })
        }
        if (isExactDecimalMix(values)) {
            return Value.DecimalValue(values.fold(BigDecimal.ZERO) { acc, value -> acc + asBigDecimal(value) })
        }
        return Value.RealValue(values.sumOf { numeric(it) })
    }

    private fun average(values: List<Value>): Value {
        if (values.isEmpty()) return Value.NullValue
        if (isExactDecimalMix(values)) {
            val total = values.fold(BigDecimal.ZERO) { acc, value -> acc + asBigDecimal(value) }
            return Value.DecimalValue(total.divide(BigDecimal(values.size), MathContext(DECIMAL_AVERAGE_PRECISION, RoundingMode.HALF_UP)))
        }
        return Value.RealValue(values.sumOf { numeric(it) } / values.size)
    }

    private fun isExactDecimalMix(values: List<Value>): Boolean =
        values.any { it is Value.DecimalValue } && values.all { it is Value.DecimalValue || it is Value.IntegerValue }

    private fun asBigDecimal(value: Value): BigDecimal = when (value) {
        is Value.DecimalValue -> value.value
        is Value.IntegerValue -> BigDecimal(value.value)
        else -> throw KestrelException("aggregate requires numeric operands but got ${value.typeName}")
    }

    private fun numeric(value: Value): Double = when (value) {
        is Value.IntegerValue -> value.value.toDouble()
        is Value.RealValue -> value.value
        is Value.DecimalValue -> value.value.toDouble()
        else -> throw KestrelException("aggregate requires numeric operands but got ${value.typeName}")
    }

    private const val DECIMAL_AVERAGE_PRECISION = 20
}
