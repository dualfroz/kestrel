package kestrel.engine

import kestrel.catalog.TableSchema
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.storage.Collation
import kestrel.storage.Value

/**
 * Resolves which [Collation] governs equality/ordering for [expression]'s own value, with the same
 * two-tier precedence [compareOrderKeyValues] already uses for `ORDER BY`: an explicit `COLLATE` on
 * [expression] itself wins; failing that, a bare [ColumnExpression]'s own declared `COLLATE` (see
 * [kestrel.catalog.Column.collation]), reported by [declaredCollationOf]; [Collation.Binary]
 * otherwise. Shared by [compareOrderKeyValues] and every equality-based dedup below so both sides of
 * the COLLATE story (ordering and equality) agree on which collation a given expression uses.
 */
fun resolveCollation(expression: Expression, declaredCollationOf: (ColumnExpression) -> Collation?): Collation =
    (expression as? CollateExpression)?.collation
        ?: (expression as? ColumnExpression)?.let(declaredCollationOf)
        ?: Collation.Binary

/**
 * Folds [values] (one grouping/`DISTINCT` key, whose entries line up positionally with
 * [expressions]) into a key usable as a [Map]/[Set] bucket or a `distinctBy` selector: a
 * [Value.TextValue] under a collation resolved (via [resolveCollation]) to anything but
 * [Collation.Binary] is rewritten through [Collation.canonicalize] first, so e.g. `'Bob'` and
 * `'bob'` land in the same bucket under a `NOCASE`-declared column, while every other value keeps
 * its own `equals`/`hashCode` - a call site with no declared collation anywhere behaves exactly as
 * it did before this function existed, since [Collation.Binary]'s [Collation.canonicalize] is the
 * identity.
 */
fun collationFoldedKey(
    values: List<Value>,
    expressions: List<Expression>,
    declaredCollationOf: (ColumnExpression) -> Collation?
): List<Value> = values.mapIndexed { index, value ->
    if (value !is Value.TextValue) value
    else Value.TextValue(resolveCollation(expressions[index], declaredCollationOf).canonicalize(value.value))
}

/**
 * [collationFoldedKey]'s counterpart for a PRIMARY KEY / `UNIQUE` constraint / `UNIQUE` index
 * uniqueness check, where the "expression" naming each key column is always just that column
 * itself - a bare index into [schema], not an arbitrary [Expression] tree - so the collation to
 * apply is always [kestrel.catalog.Column.collation] directly rather than something resolved
 * through [resolveCollation]'s explicit-`COLLATE`-wins rule (a `PRIMARY KEY`/`UNIQUE` column list
 * has no expressions to attach an explicit `COLLATE` to in the first place). Used by
 * [kestrel.engine.Executor]'s in-memory `enforcePrimaryKeyUniqueness`/`enforceUniqueConstraints`/
 * `enforceUniqueIndexes`, [kestrel.store.sql.DiskTable]'s heap-scanned
 * `rejectDuplicateUniqueConstraints`/`backfillSecondaryIndex`, and (via
 * [kestrel.store.sql.DiskTable]'s `encodePrimaryKey`/`compositeKey`/`equalRangeFor`) the disk
 * engine's own B+Tree-backed PRIMARY KEY and `CREATE UNIQUE INDEX` key encoding itself - folding
 * only ever changes what bytes a [kestrel.store.codec.KeyCodec] index key encodes to, never the
 * row values a [kestrel.store.heap.RecordId] resolves to, so it is safe for lookup/ordering
 * without corrupting any stored row.
 */
fun collationFoldedUniquenessKey(schema: TableSchema, columnIndices: List<Int>, values: List<Value>): List<Value> =
    values.mapIndexed { position, value ->
        if (value !is Value.TextValue) value
        else Value.TextValue(schema.columns[columnIndices[position]].collation.canonicalize(value.value))
    }

/**
 * Single-column counterpart of [collationFoldedKey], for an aggregate's own `DISTINCT` dedup
 * (`COUNT`/`SUM`/`AVG`/`MIN`/`MAX(DISTINCT ...)`), which has already reduced its argument down to a
 * flat list of values rather than whole rows.
 */
fun List<Value>.distinctByCollation(collation: Collation): List<Value> =
    distinctBy { value -> if (value is Value.TextValue) collation.canonicalize(value.value) else value }

/**
 * SQLite's own two-tier rule for a binary comparison's collating sequence, in decreasing
 * precedence: (1) an explicit `COLLATE` on either operand, left first; (2) either operand's own
 * declared column `COLLATE` (via [declaredCollationOf], with [Collation.Binary] treated the same
 * as "no declaration" so it never out-ranks the *other* side purely by appearing first - see
 * [resolveCollation]'s own doc for the identical reasoning), left first; (3) [Collation.Binary].
 * Kept as two separate passes (not one combined per-side [resolveCollation] check) so an explicit
 * `COLLATE` on the *right* operand still outranks the *left* operand's own declared column
 * collation. Shared by [kestrel.engine.Evaluator]'s `=`/`<>`/`BETWEEN`/`CASE` comparisons and the
 * hash-join fast path in [kestrel.engine.SelectRunner] (`hashJoinKeys`/`executeJoin`), so a
 * `JOIN ... ON a.x = b.x` bucket lookup agrees with what evaluating the exact same condition
 * row-by-row through [kestrel.engine.Evaluator] would have decided.
 */
fun pairCollation(
    left: Expression,
    right: Expression,
    declaredCollationOf: (ColumnExpression) -> Collation?
): Collation {
    fun explicitOf(expression: Expression) = (expression as? CollateExpression)?.collation
    fun declaredOf(expression: Expression) =
        (expression as? ColumnExpression)?.let(declaredCollationOf)?.takeIf { it != Collation.Binary }
    return explicitOf(left) ?: explicitOf(right) ?: declaredOf(left) ?: declaredOf(right) ?: Collation.Binary
}
