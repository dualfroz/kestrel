package kestrel.engine

import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.OrderKey
import kestrel.storage.Collation
import kestrel.storage.Value
import kestrel.storage.ValueOrdering

/**
 * Compares two already-evaluated ORDER BY key values, shared by every sort in both the in-memory
 * and disk engines (top-level ORDER BY, GROUP BY's own ORDER BY, STRING_AGG's inner ORDER BY, and
 * a window function's `OVER (... ORDER BY ...)`) so `NULLS FIRST`/`NULLS LAST` behaves identically
 * everywhere an [OrderKey] is honored.
 *
 * `NULLS FIRST`/`NULLS LAST` is an explicit override: when either side is NULL, [OrderKey.descending]
 * plays no part - only [OrderKey.nullsFirst] decides, matching the SQL standard (unlike a plain
 * value comparison, negating the whole result for DESC would also flip which end NULLs land on,
 * which is not what an explicit NULLS clause means). When [OrderKey.nullsFirst] is unset (`null`,
 * meaning the query never wrote `NULLS FIRST`/`LAST`), it defaults to `!descending` - the exact
 * behavior every ORDER BY in this engine had before this clause existed, since [ValueOrdering]
 * ranks NULL as the smallest value and the old code just negated that for DESC.
 *
 * [key]'s own [OrderKey.expression] also decides which [Collation] two [Value.TextValue]s are
 * compared with, in the same two-tier precedence [kestrel.engine.Evaluator] uses for a binary
 * comparison: (1) an explicit `COLLATE` - `ORDER BY name COLLATE NOCASE` reaches here as a plain
 * [OrderKey] whose expression is a [CollateExpression]; (2) failing that, a bare [ColumnExpression]
 * key's own declared `COLLATE` (see [kestrel.catalog.Column.collation]), reported by
 * [declaredCollationOf] - `{ null }` by default so every pre-existing call site (one with no
 * schema to consult, such as a UNION/INTERSECT/EXCEPT's synthetic output row) keeps behaving
 * exactly as before; (3) [Collation.Binary] otherwise.
 */
fun compareOrderKeyValues(
    left: Value,
    right: Value,
    key: OrderKey,
    declaredCollationOf: (ColumnExpression) -> Collation? = { null }
): Int {
    val leftNull = left is Value.NullValue
    val rightNull = right is Value.NullValue
    if (leftNull || rightNull) {
        if (leftNull && rightNull) return 0
        val nullsFirst = key.nullsFirst ?: !key.descending
        return if (leftNull == nullsFirst) -1 else 1
    }
    val collation = resolveCollation(key.expression, declaredCollationOf)
    val comparison = ValueOrdering.compare(left, right, collation)
    return if (key.descending) -comparison else comparison
}
