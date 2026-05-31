package kestrel.cli

import kestrel.engine.SelectResult
import kestrel.storage.Value

object TableRenderer {
    fun render(result: SelectResult): String {
        val headers = result.columns
        val cells = result.rows.map { row -> row.map { formatValue(it) } }
        val widths = IntArray(headers.size) { headers[it].length }
        for (row in cells) {
            for (index in row.indices) {
                widths[index] = maxOf(widths[index], row[index].length)
            }
        }
        val separator = buildSeparator(widths)
        val builder = StringBuilder()
        builder.appendLine(separator)
        builder.appendLine(renderRow(headers, widths))
        builder.appendLine(separator)
        for (row in cells) {
            builder.appendLine(renderRow(row, widths))
        }
        builder.appendLine(separator)
        builder.append(rowsWord(cells.size))
        return builder.toString()
    }

    fun formatValue(value: Value): String = when (value) {
        is Value.IntegerValue -> value.value.toString()
        is Value.RealValue -> value.value.toString()
        is Value.TextValue -> value.value
        is Value.BooleanValue -> value.value.toString()
        is Value.NullValue -> "NULL"
    }

    private fun renderRow(cells: List<String>, widths: IntArray): String =
        cells.mapIndexed { index, cell -> cell.padEnd(widths[index]) }
            .joinToString(prefix = "| ", separator = " | ", postfix = " |")

    private fun buildSeparator(widths: IntArray): String =
        widths.joinToString(prefix = "+", separator = "+", postfix = "+") { "-".repeat(it + 2) }

    private fun rowsWord(count: Int): String = if (count == 1) "1 row" else "$count rows"
}
