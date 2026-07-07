package kestrel.storage

/**
 * A text comparison sequence, selected by the `COLLATE` operator (`expr COLLATE name`) - see
 * [kestrel.sql.ast.CollateExpression]. Only the three built-in sequences SQLite ships are
 * supported (no `CREATE COLLATION`, no user-defined comparator); [Binary] is what every text
 * comparison already used before COLLATE existed, so it stays the default everywhere a caller
 * does not thread a collation through at all.
 */
enum class Collation(val sqlName: String) {
    /** Plain UTF-16 code-unit order - [String.compareTo] - identical to this engine's pre-COLLATE behavior. */
    Binary("BINARY"),

    /**
     * Case-insensitive, but only for ASCII `A-Z`/`a-z` - matching SQLite's own NOCASE exactly
     * (not full Unicode case folding, which would depend on locale for a handful of characters
     * like Turkish "I"). Folds both operands to uppercase ASCII before comparing.
     */
    NoCase("NOCASE"),

    /**
     * Ignores trailing ASCII space characters, matching SQLite's built-in RTRIM collation.
     * Leading and interior whitespace still participates in the comparison.
     */
    Rtrim("RTRIM");

    fun compare(left: String, right: String): Int = canonicalize(left).compareTo(canonicalize(right))

    /**
     * [value] rewritten so that plain [String.equals]/[String.hashCode]/[Comparable.compareTo] on
     * the result matches this collation's own [compare] - shared by [compare] itself and by every
     * equality-based dedup (`GROUP BY`, `DISTINCT`, an aggregate's own `DISTINCT`) that needs a
     * hashable key rather than a pairwise comparison. [Binary] returns [value] unchanged, so folding
     * a value under it and using the result as a key is indistinguishable from the pre-COLLATE
     * behavior of comparing/hashing the value directly.
     */
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
