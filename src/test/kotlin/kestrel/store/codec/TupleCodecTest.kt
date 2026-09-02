package kestrel.store.codec

import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals

class TupleCodecTest {
    @Test
    fun roundTripsAllValueTypes() {
        val row = listOf(
            Value.IntegerValue(42),
            Value.TextValue("hello world"),
            Value.BooleanValue(true),
            Value.RealValue(2.5),
            Value.DecimalValue(BigDecimal("19.99")),
            Value.DateValue(LocalDate.of(2024, 1, 15)),
            Value.NullValue
        )
        assertEquals(row, TupleCodec.decode(TupleCodec.encode(row)))
    }

    @Test
    fun roundTripsDecimalExactlyPreservingScaleAndSign() {
        val row = listOf(
            Value.DecimalValue(BigDecimal.ZERO),
            Value.DecimalValue(BigDecimal("-19.990")),
            Value.DecimalValue(BigDecimal("1200")),
            Value.DecimalValue(BigDecimal(BigInteger("123456789012345678901234567890"), 10))
        )
        val decoded = TupleCodec.decode(TupleCodec.encode(row))
        assertEquals(row, decoded)
        // Exact scale round-trip (not just numeric equality): -19.990 must stay 3-decimal, not collapse to -19.99.
        assertEquals(3, (decoded[1] as Value.DecimalValue).value.scale())
    }

    @Test
    fun roundTripsDatesIncludingBeforeTheEpoch() {
        val row = listOf(
            Value.DateValue(LocalDate.of(1970, 1, 1)),
            Value.DateValue(LocalDate.of(1900, 1, 1)),
            Value.DateValue(LocalDate.of(2999, 12, 31))
        )
        assertEquals(row, TupleCodec.decode(TupleCodec.encode(row)))
    }

    @Test
    fun roundTripsNegativeAndExtremeIntegers() {
        val row = listOf(Value.IntegerValue(Long.MIN_VALUE), Value.IntegerValue(Long.MAX_VALUE), Value.IntegerValue(-1))
        assertEquals(row, TupleCodec.decode(TupleCodec.encode(row)))
    }

    @Test
    fun roundTripsAllNullRow() {
        val row = listOf(Value.NullValue, Value.NullValue, Value.NullValue)
        assertEquals(row, TupleCodec.decode(TupleCodec.encode(row)))
    }

    @Test
    fun roundTripsEmptyRow() {
        assertEquals(emptyList(), TupleCodec.decode(TupleCodec.encode(emptyList())))
    }

    @Test
    fun nullBitmapSpansMultipleBytesForWideRows() {
        val row = (0 until 20).map { if (it % 3 == 0) Value.NullValue else Value.IntegerValue(it.toLong()) }
        assertEquals(row, TupleCodec.decode(TupleCodec.encode(row)))
    }

    @Test
    fun roundTripsUnicodeText() {
        val row = listOf(Value.TextValue("zolw kruk ósmórog 🐦"))
        assertEquals(row, TupleCodec.decode(TupleCodec.encode(row)))
    }

    @Test
    fun roundTripsEmptyText() {
        val row = listOf(Value.TextValue(""))
        assertEquals(row, TupleCodec.decode(TupleCodec.encode(row)))
    }

    @Test
    fun encodingIsCompactForSmallIntegers() {
        val encoded = TupleCodec.encode(listOf(Value.IntegerValue(5)))
        // 1 byte column count + 1 byte null bitmap + 1 byte tag + 1 byte zigzag payload
        assertEquals(4, encoded.size)
    }
}
