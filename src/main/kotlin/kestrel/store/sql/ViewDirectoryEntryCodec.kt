package kestrel.store.sql

import kestrel.store.codec.ByteReader
import kestrel.store.codec.ByteWriter

object ViewDirectoryEntryCodec {
    fun encode(entry: ViewDirectoryEntry): ByteArray {
        val writer = ByteWriter()
        writer.writeLengthPrefixed(entry.viewName.toByteArray(Charsets.UTF_8))
        writer.writeLengthPrefixed(entry.statementText.toByteArray(Charsets.UTF_8))
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): ViewDirectoryEntry {
        val reader = ByteReader(bytes)
        val viewName = String(reader.readLengthPrefixed(), Charsets.UTF_8)
        val statementText = String(reader.readLengthPrefixed(), Charsets.UTF_8)
        return ViewDirectoryEntry(viewName, statementText)
    }
}
