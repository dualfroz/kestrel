package kestrel.engine.function

import java.math.RoundingMode
import java.time.LocalDate
import kestrel.KestrelException
import kestrel.storage.Value
import kestrel.storage.ValueOrdering
import kestrel.storage.typeName
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

class ScalarFunction(
    val name: String,
    private val minArgs: Int,
    private val maxArgs: Int,
    private val nullPropagating: Boolean,
    private val body: (List<Value>) -> Value
) {
    fun apply(arguments: List<Value>): Value {
        if (arguments.size < minArgs || arguments.size > maxArgs) {
            val expected = if (minArgs == maxArgs) "$minArgs" else "$minArgs to $maxArgs"
            throw KestrelException("function $name expects $expected argument(s) but got ${arguments.size}")
        }
        if (nullPropagating && arguments.any { it.isNull }) return Value.NullValue
        return body(arguments)
    }
}

object ScalarFunctions {
    private val registry: Map<String, ScalarFunction> = buildRegistry()

    fun lookup(name: String): ScalarFunction? = registry[name.uppercase()]

    private fun buildRegistry(): Map<String, ScalarFunction> {
        val functions = mutableListOf<ScalarFunction>()
        fun register(
            name: String,
            minArgs: Int,
            maxArgs: Int = minArgs,
            nullPropagating: Boolean = true,
            body: (List<Value>) -> Value
        ) {
            functions.add(ScalarFunction(name, minArgs, maxArgs, nullPropagating, body))
        }

        register("UPPER", 1) { args -> Value.TextValue(text(args[0], "UPPER").uppercase()) }
        register("LOWER", 1) { args -> Value.TextValue(text(args[0], "LOWER").lowercase()) }
        register("LENGTH", 1) { args -> Value.IntegerValue(text(args[0], "LENGTH").length.toLong()) }
        register("TRIM", 1) { args -> Value.TextValue(text(args[0], "TRIM").trim()) }
        register("LTRIM", 1) { args -> Value.TextValue(text(args[0], "LTRIM").trimStart()) }
        register("RTRIM", 1) { args -> Value.TextValue(text(args[0], "RTRIM").trimEnd()) }
        register("SUBSTR", 2, 3) { args -> substr(args) }
        register("SUBSTRING", 2, 3) { args -> substr(args) }
        register("REPLACE", 3) { args ->
            Value.TextValue(text(args[0], "REPLACE").replace(text(args[1], "REPLACE"), text(args[2], "REPLACE")))
        }
        register("CONCAT", 2, Int.MAX_VALUE) { args -> Value.TextValue(args.joinToString("") { text(it, "CONCAT") }) }
        register("REVERSE", 1) { args -> Value.TextValue(text(args[0], "REVERSE").reversed()) }
        register("LEFT", 2) { args -> Value.TextValue(edge(args, "LEFT", fromStart = true)) }
        register("RIGHT", 2) { args -> Value.TextValue(edge(args, "RIGHT", fromStart = false)) }
        register("ABS", 1) { args -> absoluteValue(args[0]) }
        register("ROUND", 1, 2) { args -> round(args) }
        register("CEIL", 1) { args -> Value.IntegerValue(ceilValue(args[0])) }
        register("CEILING", 1) { args -> Value.IntegerValue(ceilValue(args[0])) }
        register("FLOOR", 1) { args -> Value.IntegerValue(floorValue(args[0])) }
        register("COALESCE", 1, Int.MAX_VALUE, nullPropagating = false) { args ->
            args.firstOrNull { !it.isNull } ?: Value.NullValue
        }
        register("IFNULL", 2, nullPropagating = false) { args -> if (!args[0].isNull) args[0] else args[1] }
        register("NULLIF", 2, nullPropagating = false) { args ->
            if (!args[0].isNull && !args[1].isNull && ValueOrdering.compare(args[0], args[1]) == 0) {
                Value.NullValue
            } else {
                args[0]
            }
        }
        register("TYPEOF", 1, nullPropagating = false) { args -> Value.TextValue(args[0].typeName) }
        register("CURRENT_DATE", 0, nullPropagating = false) { Value.DateValue(LocalDate.now()) }
        register("YEAR", 1) { args -> Value.IntegerValue(date(args[0], "YEAR").year.toLong()) }
        register("MONTH", 1) { args -> Value.IntegerValue(date(args[0], "MONTH").monthValue.toLong()) }
        register("DAY", 1) { args -> Value.IntegerValue(date(args[0], "DAY").dayOfMonth.toLong()) }
        register("DAYOFWEEK", 1) { args -> Value.IntegerValue(date(args[0], "DAYOFWEEK").dayOfWeek.value.toLong()) }
        register("DATE_ADD", 2) { args -> Value.DateValue(date(args[0], "DATE_ADD").plusDays(integer(args[1], "DATE_ADD"))) }
        register("DATE_SUB", 2) { args -> Value.DateValue(date(args[0], "DATE_SUB").minusDays(integer(args[1], "DATE_SUB"))) }
        register("DATEDIFF", 2) { args ->
            Value.IntegerValue(date(args[0], "DATEDIFF").toEpochDay() - date(args[1], "DATEDIFF").toEpochDay())
        }
        register("REPEAT", 2) { args ->
            val count = integer(args[1], "REPEAT")
            Value.TextValue(if (count <= 0) "" else text(args[0], "REPEAT").repeat(count.toInt()))
        }
        register("LPAD", 2, 3) { args -> pad(args, "LPAD", left = true) }
        register("RPAD", 2, 3) { args -> pad(args, "RPAD", left = false) }
        register("INSTR", 2) { args -> Value.IntegerValue((text(args[0], "INSTR").indexOf(text(args[1], "INSTR")) + 1).toLong()) }
        register("POWER", 2) { args -> power(args[0], args[1]) }
        register("MOD", 2) { args -> mod(args[0], args[1]) }
        register("SIGN", 1) { args -> Value.IntegerValue(sign(args[0]).toLong()) }
        register("TRUNC", 1, 2) { args -> trunc(args) }
        register("GREATEST", 2, Int.MAX_VALUE) { args -> args.maxWith(ValueOrdering) }
        register("LEAST", 2, Int.MAX_VALUE) { args -> args.minWith(ValueOrdering) }

        return functions.associateBy { it.name }
    }

    private fun text(value: Value, function: String): String = when (value) {
        is Value.TextValue -> value.value
        else -> throw KestrelException("function $function requires a TEXT argument but got ${value.typeName}")
    }

    private fun date(value: Value, function: String): LocalDate = when (value) {
        is Value.DateValue -> value.value
        else -> throw KestrelException("function $function requires a DATE argument but got ${value.typeName}")
    }

    private fun integer(value: Value, function: String): Long = when (value) {
        is Value.IntegerValue -> value.value
        else -> throw KestrelException("function $function requires an INTEGER argument but got ${value.typeName}")
    }

    private fun number(value: Value, function: String): Double = when (value) {
        is Value.IntegerValue -> value.value.toDouble()
        is Value.RealValue -> value.value
        else -> throw KestrelException("function $function requires a numeric argument but got ${value.typeName}")
    }

    private fun absoluteValue(value: Value): Value = when (value) {
        is Value.IntegerValue -> Value.IntegerValue(abs(value.value))
        is Value.RealValue -> Value.RealValue(abs(value.value))
        is Value.DecimalValue -> Value.DecimalValue(value.value.abs())
        else -> throw KestrelException("function ABS requires a numeric argument but got ${value.typeName}")
    }

    private fun ceilValue(value: Value): Long = when (value) {
        is Value.DecimalValue -> value.value.setScale(0, RoundingMode.CEILING).toLong()
        else -> ceil(number(value, "CEIL")).toLong()
    }

    private fun floorValue(value: Value): Long = when (value) {
        is Value.DecimalValue -> value.value.setScale(0, RoundingMode.FLOOR).toLong()
        else -> floor(number(value, "FLOOR")).toLong()
    }

    private fun substr(args: List<Value>): Value {
        val source = text(args[0], "SUBSTR")
        val requestedStart = (args[1] as? Value.IntegerValue)?.value
            ?: throw KestrelException("function SUBSTR requires an INTEGER start position")
        val start = (if (requestedStart > 0) requestedStart - 1 else 0).coerceAtMost(source.length.toLong()).toInt()
        val length = if (args.size == 3) {
            (args[2] as? Value.IntegerValue)?.value
                ?: throw KestrelException("function SUBSTR requires an INTEGER length")
        } else {
            null
        }
        val end = if (length != null) (start + length.coerceAtLeast(0)).coerceAtMost(source.length.toLong()).toInt() else source.length
        return Value.TextValue(if (start >= end) "" else source.substring(start, end))
    }

    private fun edge(args: List<Value>, function: String, fromStart: Boolean): String {
        val source = text(args[0], function)
        val count = integer(args[1], function).coerceIn(0, source.length.toLong()).toInt()
        return if (fromStart) source.substring(0, count) else source.substring(source.length - count)
    }

    private fun pad(args: List<Value>, function: String, left: Boolean): Value {
        val source = text(args[0], function)
        val targetLength = integer(args[1], function)
        if (targetLength <= 0) return Value.TextValue("")
        val length = targetLength.toInt()
        if (length <= source.length) return Value.TextValue(source.substring(0, length))
        val padString = if (args.size == 3) text(args[2], function) else " "
        if (padString.isEmpty()) throw KestrelException("function $function requires a non-empty pad string")
        val needed = length - source.length
        val padding = buildString {
            while (this.length < needed) append(padString)
        }.substring(0, needed)
        return Value.TextValue(if (left) padding + source else source + padding)
    }

    private fun power(base: Value, exponent: Value): Value {
        if (base is Value.IntegerValue && exponent is Value.IntegerValue && exponent.value >= 0) {
            var result = 1L
            repeat(exponent.value.toInt()) { result *= base.value }
            return Value.IntegerValue(result)
        }
        return Value.RealValue(number(base, "POWER").pow(number(exponent, "POWER")))
    }

    private fun mod(dividend: Value, divisor: Value): Value {
        if (dividend is Value.IntegerValue && divisor is Value.IntegerValue) {
            if (divisor.value == 0L) throw KestrelException("division by zero")
            return Value.IntegerValue(dividend.value % divisor.value)
        }
        val divisorNumber = number(divisor, "MOD")
        if (divisorNumber == 0.0) throw KestrelException("division by zero")
        return Value.RealValue(number(dividend, "MOD") % divisorNumber)
    }

    private fun round(args: List<Value>): Value {
        val digits = if (args.size == 2) {
            (args[1] as? Value.IntegerValue)?.value?.toInt()
                ?: throw KestrelException("function ROUND requires an INTEGER digit count")
        } else {
            0
        }
        if (args[0] is Value.IntegerValue && digits >= 0) return args[0]
        val decimal = args[0] as? Value.DecimalValue
        if (decimal != null) return Value.DecimalValue(decimal.value.setScale(digits, RoundingMode.HALF_UP))
        val value = number(args[0], "ROUND")
        val factor = 10.0.pow(digits)
        return Value.RealValue((value * factor).roundToLong() / factor)
    }

    private fun sign(value: Value): Int = when (value) {
        is Value.IntegerValue -> if (value.value > 0) 1 else if (value.value < 0) -1 else 0
        is Value.RealValue -> if (value.value > 0) 1 else if (value.value < 0) -1 else 0
        is Value.DecimalValue -> value.value.signum()
        else -> throw KestrelException("function SIGN requires a numeric argument but got ${value.typeName}")
    }

    private fun trunc(args: List<Value>): Value {
        val digits = if (args.size == 2) {
            (args[1] as? Value.IntegerValue)?.value?.toInt()
                ?: throw KestrelException("function TRUNC requires an INTEGER digit count")
        } else {
            0
        }
        if (args[0] is Value.IntegerValue && digits >= 0) return args[0]
        val decimal = args[0] as? Value.DecimalValue
        if (decimal != null) return Value.DecimalValue(decimal.value.setScale(digits, RoundingMode.DOWN))
        val value = number(args[0], "TRUNC")
        val factor = 10.0.pow(digits)
        val scaled = value * factor
        return Value.RealValue((if (scaled >= 0) floor(scaled) else ceil(scaled)) / factor)
    }
}
