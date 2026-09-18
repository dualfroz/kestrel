package kestrel.store.codec

import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import kestrel.storage.Value

object TupleCodec {
    private const val TAG_BOOLEAN = 1
    private const val TAG_INTEGER = 2
    private const val TAG_REAL = 3
    private const val TAG_TEXT = 4
    private const val TAG_DECIMAL = 5
    private const val TAG_DATE = 6

    fun encode(values: List<Value>): ByteArray {
        val writer = ByteWriter()
        writer.writeVarInt(values.size.toLong())
        writer.writeBytes(nullBitmap(values))
        for (value in values) {
            if (value.isNull) continue
            when (value) {
                is Value.BooleanValue -> {
                    writer.writeByte(TAG_BOOLEAN)
                    writer.writeByte(if (value.value) 1 else 0)
                }
                is Value.IntegerValue -> {
                    writer.writeByte(TAG_INTEGER)
                    writer.writeZigZag(value.value)
                }
                is Value.RealValue -> {
                    writer.writeByte(TAG_REAL)
                    writer.writeFixedDouble(value.value)
                }
                is Value.TextValue -> {
                    writer.writeByte(TAG_TEXT)
                    writer.writeLengthPrefixed(value.value.toByteArray(Charsets.UTF_8))
                }
                is Value.DecimalValue -> {
                    writer.writeByte(TAG_DECIMAL)
                    writer.writeZigZag(value.value.scale().toLong())
                    writer.writeLengthPrefixed(value.value.unscaledValue().toByteArray())
                }
                is Value.DateValue -> {
                    writer.writeByte(TAG_DATE)
                    writer.writeZigZag(value.value.toEpochDay())
                }
                Value.NullValue -> Unit
            }
        }
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): List<Value> {
        val reader = ByteReader(bytes)
        val columnCount = reader.readVarInt().toInt()
        val bitmapBytes = reader.readBytes(bitmapSize(columnCount))
        val result = ArrayList<Value>(columnCount)
        for (index in 0 until columnCount) {
            if (isNullBit(bitmapBytes, index)) {
                result.add(Value.NullValue)
                continue
            }
            result.add(
                when (val tag = reader.readByte()) {
                    TAG_BOOLEAN -> Value.BooleanValue(reader.readByte() != 0)
                    TAG_INTEGER -> Value.IntegerValue(reader.readZigZag())
                    TAG_REAL -> Value.RealValue(reader.readFixedDouble())
                    TAG_TEXT -> Value.TextValue(String(reader.readLengthPrefixed(), Charsets.UTF_8))
                    TAG_DECIMAL -> {
                        val scale = reader.readZigZag().toInt()
                        val unscaled = BigInteger(reader.readLengthPrefixed())
                        Value.DecimalValue(BigDecimal(unscaled, scale))
                    }
                    TAG_DATE -> Value.DateValue(LocalDate.ofEpochDay(reader.readZigZag()))
                    else -> throw CodecException("unknown tuple tag $tag")
                }
            )
        }
        return result
    }

    private fun bitmapSize(columnCount: Int): Int = (columnCount + 7) / 8

    private fun nullBitmap(values: List<Value>): ByteArray {
        val bitmap = ByteArray(bitmapSize(values.size))
        for (index in values.indices) {
            if (values[index].isNull) {
                bitmap[index / 8] = (bitmap[index / 8].toInt() or (1 shl (index % 8))).toByte()
            }
        }
        return bitmap
    }

    private fun isNullBit(bitmap: ByteArray, index: Int): Boolean =
        (bitmap[index / 8].toInt() shr (index % 8)) and 1 == 1
}
