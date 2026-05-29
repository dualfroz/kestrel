package kestrel

open class KestrelException(message: String) : RuntimeException(message)

class LexException(
    message: String,
    val line: Int,
    val column: Int,
    val incomplete: Boolean = false
) : KestrelException("$message at line $line, column $column")

class ParseException(
    message: String,
    val line: Int,
    val column: Int
) : KestrelException("$message at line $line, column $column")
