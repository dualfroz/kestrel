package kestrel.catalog

import kestrel.KestrelException
import kestrel.storage.Row
import kestrel.storage.Table

class CatalogSnapshot(
    val tables: Map<String, Pair<TableSchema, List<Row>>>,
    val indexDefinitions: List<IndexDefinition>
)

class Catalog {
    private val tables = linkedMapOf<String, Table>()
    private val indexes = linkedMapOf<String, SortedIndex>()

    fun createTable(schema: TableSchema) {
        val key = schema.name.lowercase()
        if (tables.containsKey(key)) {
            throw KestrelException("table '${schema.name}' already exists")
        }
        tables[key] = Table(schema)
    }

    fun dropTable(tableName: String) {
        val key = tableName.lowercase()
        if (tables.remove(key) == null) {
            throw KestrelException("no such table: $tableName")
        }
        indexes.entries.removeAll { it.value.definition.tableName.equals(tableName, ignoreCase = true) }
    }

    fun table(tableName: String): Table =
        tables[tableName.lowercase()] ?: throw KestrelException("no such table: $tableName")

    fun tableOrNull(tableName: String): Table? = tables[tableName.lowercase()]

    val tableNames: List<String> get() = tables.values.map { it.schema.name }

    fun createIndex(definition: IndexDefinition) {
        val key = definition.name.lowercase()
        if (indexes.containsKey(key)) {
            throw KestrelException("index '${definition.name}' already exists")
        }
        val table = table(definition.tableName)
        if (table.schema.columnIndex(definition.columnName) == null) {
            throw KestrelException("no such column: ${definition.columnName} in table ${definition.tableName}")
        }
        val index = SortedIndex(definition)
        indexes[key] = index
        reindexOne(table, index)
    }

    fun dropIndex(indexName: String) {
        if (indexes.remove(indexName.lowercase()) == null) {
            throw KestrelException("no such index: $indexName")
        }
    }

    fun indexOn(tableName: String, columnName: String): SortedIndex? = indexes.values.firstOrNull {
        it.definition.tableName.equals(tableName, ignoreCase = true) &&
            it.definition.columnName.equals(columnName, ignoreCase = true)
    }

    val indexNames: List<String> get() = indexes.values.map { it.definition.name }

    fun indexDefinitions(): List<IndexDefinition> = indexes.values.map { it.definition }

    fun reindexTable(tableName: String) {
        val table = table(tableName)
        indexes.values
            .filter { it.definition.tableName.equals(tableName, ignoreCase = true) }
            .forEach { reindexOne(table, it) }
    }

    fun snapshot(): CatalogSnapshot = CatalogSnapshot(
        tables = tables.mapValues { (_, table) -> table.schema to table.rows.toList() },
        indexDefinitions = indexes.values.map { it.definition }
    )

    fun restore(snapshot: CatalogSnapshot) {
        tables.clear()
        indexes.clear()
        for ((key, image) in snapshot.tables) {
            val table = Table(image.first)
            table.rows.addAll(image.second)
            tables[key] = table
        }
        for (definition in snapshot.indexDefinitions) {
            val index = SortedIndex(definition)
            indexes[definition.name.lowercase()] = index
            reindexOne(tables.getValue(definition.tableName.lowercase()), index)
        }
    }

    private fun reindexOne(table: Table, index: SortedIndex) {
        index.clear()
        val columnIndex = table.schema.columnIndex(index.definition.columnName)!!
        table.rows.forEachIndexed { rowIndex, row -> index.add(row.values[columnIndex], rowIndex) }
    }
}
