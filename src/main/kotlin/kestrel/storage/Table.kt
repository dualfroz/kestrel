package kestrel.storage

import kestrel.catalog.TableSchema

class Table(val schema: TableSchema) {
    val rows: MutableList<Row> = mutableListOf()
}
