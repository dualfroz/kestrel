package kestrel.store.sql

import kestrel.store.codec.ByteReader
import kestrel.store.codec.ByteWriter

object TriggerDirectoryEntryCodec {
    fun encode(entry: TriggerDirectoryEntry): ByteArray {
        val writer = ByteWriter()
        writer.writeLengthPrefixed(entry.triggerName.toByteArray(Charsets.UTF_8))
        writer.writeLengthPrefixed(entry.statementText.toByteArray(Charsets.UTF_8))
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): TriggerDirectoryEntry {
        val reader = ByteReader(bytes)
        val triggerName = String(reader.readLengthPrefixed(), Charsets.UTF_8)
        val statementText = String(reader.readLengthPrefixed(), Charsets.UTF_8)
        return TriggerDirectoryEntry(triggerName, statementText)
    }
}
