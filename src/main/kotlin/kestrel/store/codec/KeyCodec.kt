package kestrel.store.codec

import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import kestrel.storage.Value

object KeyCodec {
    private const val TAG_NULL = 0
    private const val TAG_BOOLEAN = 1
    private const val TAG_INTEGER = 2
    private const val TAG_REAL = 3
    private const val TAG_TEXT = 4
    private const val TAG_DECIMAL = 5
    private const val TAG_DATE = 6

    private const val TEXT_ESCAPE_NUL = 0xFF
    private const val TEXT_TERMINATOR_HIGH = 0x00
    private const val TEXT_TERMINATOR_LOW = 0x00

    private const val DECIMAL_SIGN_NEGATIVE = 0
    private const val DECIMAL_SIGN_ZERO = 1
    private const val DECIMAL_SIGN_POSITIVE = 2
    private const val DECIMAL_DIGIT_TERMINATOR = 0

    fun encodeKey(values: List<Value>): ByteArray {
        val writer = ByteWriter()
        for (value in values) encodeInto(writer, value)
        return writer.toByteArray()
    }

    fun encode(value: Value): ByteArray {
        val writer = ByteWriter()
        encodeInto(writer, value)
        return writer.toByteArray()
    }

    fun decodeKey(bytes: ByteArray): List<Value> {
        val reader = ByteReader(bytes)
        val result = ArrayList<Value>()
        while (reader.hasRemaining()) {
            result.add(decodeOne(reader))
        }
        return result
    }

    fun compare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    private fun encodeInto(writer: ByteWriter, value: Value) {
        when (value) {
            Value.NullValue -> writer.writeByte(TAG_NULL)
            is Value.BooleanValue -> {
                writer.writeByte(TAG_BOOLEAN)
                writer.writeByte(if (value.value) 1 else 0)
            }
            is Value.IntegerValue -> {
                writer.writeByte(TAG_INTEGER)
                writer.writeFixedLong(value.value xor Long.MIN_VALUE)
            }
            is Value.RealValue -> {
                writer.writeByte(TAG_REAL)
                writer.writeFixedLong(encodeOrderedDouble(value.value))
            }
            is Value.TextValue -> {
                writer.writeByte(TAG_TEXT)
                writeEscapedText(writer, value.value)
            }
            is Value.DecimalValue -> {
                writer.writeByte(TAG_DECIMAL)
                writeDecimalMagnitudeSign(writer, value.value)
            }
            is Value.DateValue -> {
                writer.writeByte(TAG_DATE)
                writer.writeFixedLong(value.value.toEpochDay() xor Long.MIN_VALUE)
            }
        }
    }

    private fun decodeOne(reader: ByteReader): Value = when (val tag = reader.readByte()) {
        TAG_NULL -> Value.NullValue
        TAG_BOOLEAN -> Value.BooleanValue(reader.readByte() != 0)
        TAG_INTEGER -> Value.IntegerValue(reader.readFixedLong() xor Long.MIN_VALUE)
        TAG_REAL -> Value.RealValue(decodeOrderedDouble(reader.readFixedLong()))
        TAG_TEXT -> Value.TextValue(readEscapedText(reader))
        TAG_DECIMAL -> Value.DecimalValue(readDecimalMagnitudeSign(reader))
        TAG_DATE -> Value.DateValue(LocalDate.ofEpochDay(reader.readFixedLong() xor Long.MIN_VALUE))
        else -> throw CodecException("unknown key tag $tag")
    }

    private fun writeDecimalMagnitudeSign(writer: ByteWriter, decimal: BigDecimal) {
        when {
            decimal.signum() == 0 -> writer.writeByte(DECIMAL_SIGN_ZERO)
            decimal.signum() > 0 -> {
                writer.writeByte(DECIMAL_SIGN_POSITIVE)
                writeDecimalMagnitude(writer, decimal, invert = false)
            }
            else -> {
                writer.writeByte(DECIMAL_SIGN_NEGATIVE)
                writeDecimalMagnitude(writer, decimal.negate(), invert = true)
            }
        }
    }

    private fun writeDecimalMagnitude(writer: ByteWriter, magnitude: BigDecimal, invert: Boolean) {
        val canonical = magnitude.stripTrailingZeros()
        val digits = canonical.unscaledValue().toString()
        val exponent = (digits.length - canonical.scale()).toLong()
        val body = ByteWriter()
        body.writeFixedLong(exponent xor Long.MIN_VALUE)
        for (digit in digits) body.writeByte((digit - '0') + 1)
        body.writeByte(DECIMAL_DIGIT_TERMINATOR)
        val bytes = body.toByteArray()
        writer.writeBytes(if (invert) ByteArray(bytes.size) { (bytes[it].toInt() xor 0xFF).toByte() } else bytes)
    }

    private fun readDecimalMagnitudeSign(reader: ByteReader): BigDecimal = when (val sign = reader.readByte()) {
        DECIMAL_SIGN_ZERO -> BigDecimal.ZERO
        DECIMAL_SIGN_POSITIVE -> readDecimalMagnitude(reader, invert = false)
        DECIMAL_SIGN_NEGATIVE -> readDecimalMagnitude(reader, invert = true).negate()
        else -> throw CodecException("unknown decimal sign tag $sign")
    }

    private fun readDecimalMagnitude(reader: ByteReader, invert: Boolean): BigDecimal {
        fun nextByte(): Int {
            val raw = reader.readByte()
            return if (invert) raw xor 0xFF else raw
        }
        var exponentBits = 0L
        repeat(8) { exponentBits = (exponentBits shl 8) or (nextByte().toLong() and 0xFF) }
        val exponent = exponentBits xor Long.MIN_VALUE
        val digits = StringBuilder()
        while (true) {
            val next = nextByte()
            if (next == DECIMAL_DIGIT_TERMINATOR) break
            digits.append('0' + (next - 1))
        }
        if (digits.isEmpty()) return BigDecimal.ZERO
        val unscaled = BigInteger(digits.toString())
        val scale = (digits.length - exponent).toInt()
        return BigDecimal(unscaled, scale)
    }

    private fun encodeOrderedDouble(value: Double): Long {
        val bits = java.lang.Double.doubleToLongBits(value)
        return if (bits < 0) bits.inv() else bits xor Long.MIN_VALUE
    }

    private fun decodeOrderedDouble(transformed: Long): Double {
        val bits = if (transformed < 0) transformed xor Long.MIN_VALUE else transformed.inv()
        return java.lang.Double.longBitsToDouble(bits)
    }

    private fun writeEscapedText(writer: ByteWriter, text: String) {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xFF
            if (unsigned == 0x00) {
                writer.writeByte(0x00)
                writer.writeByte(TEXT_ESCAPE_NUL)
            } else {
                writer.writeByte(unsigned)
            }
        }
        writer.writeByte(TEXT_TERMINATOR_HIGH)
        writer.writeByte(TEXT_TERMINATOR_LOW)
    }

    private fun readEscapedText(reader: ByteReader): String {
        val out = ByteWriter()
        while (true) {
            val byte = reader.readByte()
            if (byte != 0x00) {
                out.writeByte(byte)
                continue
            }
            val next = reader.readByte()
            if (next == TEXT_ESCAPE_NUL) {
                out.writeByte(0x00)
            } else {
                break
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
