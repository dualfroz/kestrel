package kestrel.store.sql

import kestrel.catalog.Column
import kestrel.catalog.ForeignKeyAction
import kestrel.catalog.ForeignKeyConstraint
import kestrel.catalog.UniqueConstraint
import kestrel.persistence.SqlWriter
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.SelectStatement
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.store.codec.ByteReader
import kestrel.store.codec.ByteWriter
import kestrel.store.codec.CodecException
import kestrel.storage.Collation
import kestrel.storage.ColumnType

object DirectoryEntryCodec {
    private const val NO_PAGE = -1L
    private const val NO_PRECISION = -1L

    fun encode(entry: TableDirectoryEntry): ByteArray {
        val writer = ByteWriter()
        writeString(writer, entry.tableName)
        writer.writeVarInt(entry.columns.size.toLong())
        for (column in entry.columns) {
            writeString(writer, column.name)
            writeString(writer, column.type.displayName)
            writer.writeByte(if (column.notNull) 1 else 0)
            writer.writeByte(if (column.primaryKey) 1 else 0)
            writer.writeZigZag(column.precision?.toLong() ?: NO_PRECISION)
            writer.writeZigZag(column.scale?.toLong() ?: NO_PRECISION)
            writeOptionalExpression(writer, column.default)
            writeOptionalExpression(writer, column.generated)
            writer.writeByte(column.collation.ordinal)
        }
        writer.writeFixedLong(entry.dataHeaderPageId)
        writer.writeFixedLong(entry.primaryKeyIndexRootPageId ?: NO_PAGE)
        writer.writeVarInt(entry.secondaryIndexes.size.toLong())
        for (index in entry.secondaryIndexes) {
            writeString(writer, index.name)
            writer.writeVarInt(index.columnNames.size.toLong())
            for (columnName in index.columnNames) writeString(writer, columnName)
            writer.writeFixedLong(index.rootPageId)
            writer.writeByte(if (index.unique) 1 else 0)
        }
        writer.writeVarInt(entry.compositePrimaryKey.size.toLong())
        for (columnName in entry.compositePrimaryKey) writeString(writer, columnName)
        writer.writeVarInt(entry.uniqueConstraints.size.toLong())
        for (unique in entry.uniqueConstraints) {
            writer.writeByte(if (unique.name != null) 1 else 0)
            unique.name?.let { writeString(writer, it) }
            writer.writeVarInt(unique.columns.size.toLong())
            for (columnName in unique.columns) writeString(writer, columnName)
        }
        writer.writeVarInt(entry.checks.size.toLong())
        for (check in entry.checks) {
            writer.writeByte(if (check.name != null) 1 else 0)
            check.name?.let { writeString(writer, it) }
            writeString(writer, check.expressionText)
        }
        writer.writeVarInt(entry.foreignKeys.size.toLong())
        for (fk in entry.foreignKeys) {
            writer.writeByte(if (fk.name != null) 1 else 0)
            fk.name?.let { writeString(writer, it) }
            writer.writeVarInt(fk.columns.size.toLong())
            for (columnName in fk.columns) writeString(writer, columnName)
            writeString(writer, fk.referencedTable)
            writer.writeVarInt(fk.referencedColumns.size.toLong())
            for (columnName in fk.referencedColumns) writeString(writer, columnName)
            writer.writeByte(fk.onDelete.ordinal)
            writer.writeByte(fk.onUpdate.ordinal)
        }
        return writer.toByteArray()
    }

    fun decode(bytes: ByteArray): TableDirectoryEntry {
        val reader = ByteReader(bytes)
        val tableName = readString(reader)
        val columnCount = reader.readVarInt().toInt()
        val columns = ArrayList<Column>(columnCount)
        repeat(columnCount) {
            val name = readString(reader)
            val type = ColumnType.fromKeyword(readString(reader))
                ?: throw CodecException("unknown column type in directory entry for table $tableName")
            val notNull = reader.readByte() != 0
            val primaryKey = reader.readByte() != 0
            val precision = reader.readZigZag().takeIf { it != NO_PRECISION }?.toInt()
            val scale = reader.readZigZag().takeIf { it != NO_PRECISION }?.toInt()
            val default = readOptionalExpression(reader, name, "DEFAULT")
            val generated = readOptionalExpression(reader, name, "GENERATED")
            val collation = Collation.entries[reader.readByte()]
            columns.add(Column(name, type, notNull, primaryKey, precision, scale, default, generated, collation))
        }
        val dataHeaderPageId = reader.readFixedLong()
        val primaryKeyIndexRootPageId = reader.readFixedLong().takeIf { it != NO_PAGE }
        val indexCount = reader.readVarInt().toInt()
        val secondaryIndexes = ArrayList<SecondaryIndexEntry>(indexCount)
        repeat(indexCount) {
            val name = readString(reader)
            val columnNameCount = reader.readVarInt().toInt()
            val columnNames = ArrayList<String>(columnNameCount)
            repeat(columnNameCount) { columnNames.add(readString(reader)) }
            val rootPageId = reader.readFixedLong()
            val unique = reader.readByte() != 0
            secondaryIndexes.add(SecondaryIndexEntry(name, columnNames, rootPageId, unique))
        }
        val compositePrimaryKeyCount = reader.readVarInt().toInt()
        val compositePrimaryKey = ArrayList<String>(compositePrimaryKeyCount)
        repeat(compositePrimaryKeyCount) { compositePrimaryKey.add(readString(reader)) }
        val uniqueConstraintCount = reader.readVarInt().toInt()
        val uniqueConstraints = ArrayList<UniqueConstraint>(uniqueConstraintCount)
        repeat(uniqueConstraintCount) {
            val name = if (reader.readByte() != 0) readString(reader) else null
            val columnCount = reader.readVarInt().toInt()
            val columns = ArrayList<String>(columnCount)
            repeat(columnCount) { columns.add(readString(reader)) }
            uniqueConstraints.add(UniqueConstraint(name, columns))
        }
        val checkCount = reader.readVarInt().toInt()
        val checks = ArrayList<CheckConstraintEntry>(checkCount)
        repeat(checkCount) {
            val name = if (reader.readByte() != 0) readString(reader) else null
            val expressionText = readString(reader)
            checks.add(CheckConstraintEntry(name, expressionText))
        }
        val foreignKeyCount = reader.readVarInt().toInt()
        val foreignKeys = ArrayList<ForeignKeyConstraint>(foreignKeyCount)
        repeat(foreignKeyCount) {
            val name = if (reader.readByte() != 0) readString(reader) else null
            val columnCount = reader.readVarInt().toInt()
            val columns = ArrayList<String>(columnCount)
            repeat(columnCount) { columns.add(readString(reader)) }
            val referencedTable = readString(reader)
            val referencedColumnCount = reader.readVarInt().toInt()
            val referencedColumns = ArrayList<String>(referencedColumnCount)
            repeat(referencedColumnCount) { referencedColumns.add(readString(reader)) }
            val onDelete = ForeignKeyAction.values()[reader.readByte()]
            val onUpdate = ForeignKeyAction.values()[reader.readByte()]
            foreignKeys.add(ForeignKeyConstraint(columns, referencedTable, referencedColumns, onDelete, onUpdate, name))
        }
        return TableDirectoryEntry(
            tableName, columns, dataHeaderPageId, primaryKeyIndexRootPageId, secondaryIndexes,
            compositePrimaryKey, uniqueConstraints, checks, foreignKeys
        )
    }

    private fun writeString(writer: ByteWriter, text: String) {
        writer.writeLengthPrefixed(text.toByteArray(Charsets.UTF_8))
    }

    private fun readString(reader: ByteReader): String = String(reader.readLengthPrefixed(), Charsets.UTF_8)

    private fun writeOptionalExpression(writer: ByteWriter, expression: Expression?) {
        writer.writeByte(if (expression != null) 1 else 0)
        expression?.let { writeString(writer, SqlWriter.expressionText(it)) }
    }

    private fun readOptionalExpression(reader: ByteReader, columnName: String, label: String): Expression? {
        if (reader.readByte() == 0) return null
        val text = readString(reader)
        val statements = Parser(Lexer("SELECT ($text) FROM directory_entry_placeholder;").tokenize()).parseProgram()
        val statement = statements.singleOrNull() as? SelectStatement
            ?: throw CodecException("corrupt $label expression for column $columnName: $text")
        val projection = statement.projection as? ExpressionProjection
            ?: throw CodecException("corrupt $label expression for column $columnName: $text")
        return projection.items.single().expression
    }
}
