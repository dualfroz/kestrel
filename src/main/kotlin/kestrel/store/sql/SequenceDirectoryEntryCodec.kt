package kestrel.store.sql

import kestrel.store.codec.ByteReader
import kestrel.store.codec.ByteWriter

/**
 * Encodes a [SequenceDirectoryEntry] as a self-contained byte payload, mirroring
 * [DirectoryEntryCodec]/[ViewDirectoryEntryCodec]. Every numeric field uses [ByteWriter.writeZigZag]
 * rather than [ByteWriter.writeFixedLong] because MINVALUE/MAXVALUE/START WITH routinely hold
 * negative values (a descending sequence's default MAXVALUE is `-1`, and either bound can be
 * [Long.MIN_VALUE]/[Long.MAX_VALUE]) - zigzag keeps the common case (small increments, page ids)
 * compact while still round-tripping the full `Long` range.
 */
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
