package kestrel.store.sql

import kestrel.store.codec.ByteReader
import kestrel.store.codec.ByteWriter

object SequenceDirectoryEntryCodec {
    fun encode(entry: SequenceDirectoryEntry): ByteArray {
        val writer = ByteWriter()
        writer.writeLengthPrefixed(entry.name.toByteArray(Charsets.UTF_8))
        writer.writeZigZag(entry.incrementBy)
        writer.writeZigZag(entry.minValue)
        writer.writeZigZag(entry.maxValue)
        writer.writeZigZag(entry.startValue)
        writer.writeByte(if (entry.cycle) 1 else 0)
        writer.writeZigZag(entry.positionPageId)
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): SequenceDirectoryEntry {
        val reader = ByteReader(bytes)
        val name = String(reader.readLengthPrefixed(), Charsets.UTF_8)
        val incrementBy = reader.readZigZag()
        val minValue = reader.readZigZag()
        val maxValue = reader.readZigZag()
        val startValue = reader.readZigZag()
        val cycle = reader.readByte() != 0
        val positionPageId = reader.readZigZag()
        return SequenceDirectoryEntry(name, incrementBy, minValue, maxValue, startValue, cycle, positionPageId)
    }
}
