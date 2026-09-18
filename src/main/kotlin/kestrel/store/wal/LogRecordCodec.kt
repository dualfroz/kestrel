package kestrel.store.wal

import kestrel.KestrelException
import kestrel.store.codec.ByteReader
import kestrel.store.codec.ByteWriter

class WalCorruptionException(message: String) : KestrelException(message)

object LogRecordCodec {
    private const val TYPE_BEGIN = 1
    private const val TYPE_COMMIT = 2
    private const val TYPE_ABORT = 3
    private const val TYPE_UPDATE = 4
    private const val TYPE_CHECKPOINT = 5
    private const val TYPE_COMPENSATION_UPDATE = 6

    fun encode(record: LogRecord): ByteArray {
        val writer = ByteWriter()
        when (record) {
            is LogRecord.Begin -> {
                writer.writeByte(TYPE_BEGIN)
                writer.writeFixedLong(record.txnId)
            }
            is LogRecord.Commit -> {
                writer.writeByte(TYPE_COMMIT)
                writer.writeFixedLong(record.txnId)
            }
            is LogRecord.Abort -> {
                writer.writeByte(TYPE_ABORT)
                writer.writeFixedLong(record.txnId)
            }
            is LogRecord.Update -> {
                writer.writeByte(TYPE_UPDATE)
                writer.writeFixedLong(record.txnId)
                writer.writeFixedLong(record.prevLsn)
                writer.writeFixedLong(record.pageId)
                writer.writeLengthPrefixed(record.before)
                writer.writeLengthPrefixed(record.after)
            }
            is LogRecord.Checkpoint -> {
                writer.writeByte(TYPE_CHECKPOINT)
                writer.writeVarInt(record.activeTxnIds.size.toLong())
                for (id in record.activeTxnIds) writer.writeFixedLong(id)
            }
            is LogRecord.CompensationUpdate -> {
                writer.writeByte(TYPE_COMPENSATION_UPDATE)
                writer.writeFixedLong(record.txnId)
                writer.writeFixedLong(record.prevLsn)
                writer.writeFixedLong(record.pageId)
                writer.writeLengthPrefixed(record.undoImage)
                writer.writeFixedLong(record.undoNextLsn)
            }
        }
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): LogRecord {
        val reader = ByteReader(bytes)
        return when (val type = reader.readByte()) {
            TYPE_BEGIN -> LogRecord.Begin(reader.readFixedLong())
            TYPE_COMMIT -> LogRecord.Commit(reader.readFixedLong())
            TYPE_ABORT -> LogRecord.Abort(reader.readFixedLong())
            TYPE_UPDATE -> LogRecord.Update(
                txnId = reader.readFixedLong(),
                prevLsn = reader.readFixedLong(),
                pageId = reader.readFixedLong(),
                before = reader.readLengthPrefixed(),
                after = reader.readLengthPrefixed()
            )
            TYPE_CHECKPOINT -> {
                val count = reader.readVarInt().toInt()
                LogRecord.Checkpoint((0 until count).map { reader.readFixedLong() })
            }
            TYPE_COMPENSATION_UPDATE -> LogRecord.CompensationUpdate(
                txnId = reader.readFixedLong(),
                prevLsn = reader.readFixedLong(),
                pageId = reader.readFixedLong(),
                undoImage = reader.readLengthPrefixed(),
                undoNextLsn = reader.readFixedLong()
            )
            else -> throw WalCorruptionException("unknown log record type $type")
        }
    }
}
