package kestrel.storage

enum class Collation(val sqlName: String) {
    Binary("BINARY"),

    NoCase("NOCASE"),

    Rtrim("RTRIM");

    fun compare(left: String, right: String): Int = canonicalize(left).compareTo(canonicalize(right))

    fun canonicalize(value: String): String = when (this) {
        Binary -> value
        NoCase -> foldAsciiCase(value)
        Rtrim -> value.trimEnd(' ')
    }

    private fun foldAsciiCase(value: String): String {
        val builder = StringBuilder(value.length)
        for (character in value) {
            builder.append(if (character in 'a'..'z') character.uppercaseChar() else character)
        }
        return builder.toString()
    }

    companion object {
        fun fromName(name: String): Collation? = entries.find { it.sqlName.equals(name, ignoreCase = true) }
    }
}
