package kestrel.store.sql

import kestrel.catalog.TableSchema
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.LiteralExpression
import kestrel.storage.Value

/**
 * How [DiskExecutor] should fetch candidate rows for a WHERE predicate on a
 * [DiskTable]: a primary-key point/range lookup, a non-unique secondary-index
 * lookup, or (when nothing in the predicate is indexed) a full heap scan.
 * Mirrors [kestrel.engine.IndexAccessPlanner]'s role for the in-memory
 * engine, but returns disk-backed lookup parameters instead of row indices,
 * since [DiskTable] exposes [DiskTable.findByPrimaryKey]/[DiskTable.rangeByPrimaryKey]/
 * [DiskTable.findBySecondary] directly rather than an in-memory [kestrel.catalog.SortedIndex].
 *
 * Whichever plan is chosen only NARROWS the candidate set - [DiskExecutor]
 * still evaluates the full WHERE predicate against every candidate
 * afterwards, exactly like the in-memory engine's `primaryRows()` +
 * `combined.filter { ... }` split. This keeps the planner safe to extend
 * (it never has to prove it captured every conjunct) and correct even for a
 * predicate with extra conjuncts beyond the indexed column, e.g.
 * `WHERE id = 5 AND status = 'active'`.
 */
sealed class DiskAccessPlan {
    data object FullScan : DiskAccessPlan()
    class PrimaryKeyPoint(val key: Value) : DiskAccessPlan()
    class PrimaryKeyRange(
        val lower: Value?,
        val lowerInclusive: Boolean,
        val upper: Value?,
        val upperInclusive: Boolean
    ) : DiskAccessPlan()
    /**
     * Equality on every column of a table-level composite PRIMARY KEY, e.g.
     * `WHERE order_id = 1 AND line_no = 2` against `PRIMARY KEY (order_id, line_no)`. [keys] is
     * ordered to match [kestrel.catalog.TableSchema.primaryKeyColumns], the order [DiskTable]'s
     * composite key encoding expects. A partial match (equality on only some of the composite
     * PRIMARY KEY's columns) is deliberately not planned as a prefix range scan - that scope cut
     * falls back to [Secondary]/[FullScan] like any other unindexable predicate.
     */
    class CompositePrimaryKeyPoint(val keys: List<Value>) : DiskAccessPlan()
    /**
     * Range predicate on the LAST column of a table-level composite PRIMARY KEY, after an
     * equality prefix on every column before it, e.g. `WHERE order_id = 1 AND line_no > 2` against
     * `PRIMARY KEY (order_id, line_no)`. [prefix] holds the equality values bound so far, in
     * [kestrel.catalog.TableSchema.primaryKeyColumns] order (one shorter than the full column
     * count, since the ranged column itself is excluded). Unlike [kestrel.store.sql.DiskAccessPlan.SecondaryRange],
     * which may range on a column with further index columns still unconstrained after it (leaning
     * on the secondary entry's own RID suffix to bound the ambiguity), a PRIMARY KEY entry has no
     * such suffix - filling in [prefix] plus the range's own bound value must therefore always
     * produce a COMPLETE, full-arity key, otherwise an inclusive upper bound could not distinguish
     * "this exact prefix, nothing more" from every real row extending past it with further PRIMARY
     * KEY columns. Ranging on any column before the last one - with columns after it left
     * unconstrained - is deliberately not planned for this reason; that scope cut falls back to
     * [Secondary]/[SecondaryRange]/[FullScan] like any other unindexable predicate.
     */
    class CompositePrimaryKeyRange(
        val prefix: List<Value>,
        val lower: Value?,
        val lowerInclusive: Boolean,
        val upper: Value?,
        val upperInclusive: Boolean
    ) : DiskAccessPlan()
    /**
     * Equality on every column of a secondary index, single-column or composite - [keys] is
     * ordered to match [SecondaryIndexHandle.columnNames]. A partial match (equality on only some
     * of a composite index's columns) is deliberately not planned as a prefix range scan, the same
     * scope cut as [CompositePrimaryKeyPoint] - it falls back to another index or [FullScan].
     */
    class Secondary(val indexName: String, val keys: List<Value>) : DiskAccessPlan()
    /**
     * Range predicate (`<`, `<=`, `>`, `>=`, or both bounds together) on the column right after the
     * longest leading equality run of a secondary index, single-column or composite - mirrors
     * [kestrel.engine.IndexAccessPlanner]'s combined "prefix + range" logic for the in-memory
     * engine's [kestrel.catalog.SortedIndex]-backed indexes. [prefix] holds the equality values
     * bound so far, in [SecondaryIndexHandle.columnNames] order - empty for a single-column index,
     * or for a composite index whose very first column is the one being ranged (no column can be
     * skipped over unbound: an index sorted by its first column first cannot narrow a range on its
     * second column without first pinning the first one down). Columns past the ranged one are left
     * entirely unconstrained, the same restriction [CompositePrimaryKeyPoint] documents for equality.
     */
    class SecondaryRange(
        val indexName: String,
        val prefix: List<Value>,
        val lower: Value?,
        val lowerInclusive: Boolean,
        val upper: Value?,
        val upperInclusive: Boolean
    ) : DiskAccessPlan()
}

class DiskIndexAccessPlanner {

    /**
     * Picks the most selective single access path for [predicate] over
     * [schema]'s primary key and [secondaryIndexes]. Preference order: an
     * equality match on the primary key (a single-row point lookup, the most
     * selective possible plan), then a range predicate on the primary key,
     * then (for a table-level composite primary key) an equality prefix on
     * every column but the last plus a range on that last column, then an
     * equality match on any secondary index, then a prefix-equality
     * (possibly empty) plus a range predicate on the column right after it for any
     * secondary index, then [DiskAccessPlan.FullScan] - equality always
     * outranks a range on the very same column, since a range visits at least as many
     * rows as the point it would otherwise narrow to.
     * A composite predicate naming more than one indexable column (e.g. both
     * the primary key and an indexed column) always prefers the primary key,
     * since a point lookup can never examine more rows than any other plan.
     */
    fun plan(schema: TableSchema, secondaryIndexes: List<SecondaryIndexHandle>, predicate: Expression?, alias: String? = null): DiskAccessPlan {
        if (predicate == null) return DiskAccessPlan.FullScan
        val byColumn = LinkedHashMap<String, MutableList<Pair<BinaryOperator, Value>>>()
        for (conjunct in conjuncts(predicate)) {
            val simple = asColumnPredicate(conjunct, alias, schema) ?: continue
            byColumn.getOrPut(simple.first) { mutableListOf() }.add(simple.second to simple.third)
        }
        val pkColumnName = schema.primaryKeyIndex?.let { schema.columns[it].name }
        if (pkColumnName != null) {
            val predicates = byColumn[pkColumnName]
            if (predicates != null) {
                val equal = predicates.firstOrNull { it.first == BinaryOperator.Equal }
                if (equal != null) return DiskAccessPlan.PrimaryKeyPoint(equal.second)
                val range = predicates.filter { it.first != BinaryOperator.Equal }
                if (range.isNotEmpty()) return rangePlan(range)
            }
        }
        val pkColumns = schema.primaryKeyColumns
        if (pkColumns.size > 1) {
            val equalities = pkColumns.map { name -> byColumn[name]?.firstOrNull { it.first == BinaryOperator.Equal } }
            if (equalities.all { it != null }) return DiskAccessPlan.CompositePrimaryKeyPoint(equalities.map { it!!.second })
            val prefix = mutableListOf<Value>()
            for (name in pkColumns) {
                val equal = byColumn[name]?.firstOrNull { it.first == BinaryOperator.Equal } ?: break
                prefix.add(equal.second)
            }
            if (prefix.size == pkColumns.size - 1) {
                val range = byColumn[pkColumns.last()]?.filter { it.first != BinaryOperator.Equal }.orEmpty()
                if (range.isNotEmpty()) {
                    val bounds = rangeBounds(range)
                    return DiskAccessPlan.CompositePrimaryKeyRange(prefix.toList(), bounds.lower, bounds.lowerInclusive, bounds.upper, bounds.upperInclusive)
                }
            }
        }
        for (handle in secondaryIndexes) {
            val equalities = handle.columnNames.map { name -> byColumn[name]?.firstOrNull { it.first == BinaryOperator.Equal } }
            if (equalities.all { it != null }) return DiskAccessPlan.Secondary(handle.name, equalities.map { it!!.second })
        }
        for (handle in secondaryIndexes) {
            val prefix = mutableListOf<Value>()
            for (columnName in handle.columnNames) {
                val equal = byColumn[columnName]?.firstOrNull { it.first == BinaryOperator.Equal } ?: break
                prefix.add(equal.second)
            }
            if (prefix.size == handle.columnNames.size) continue // full equality already tried above
            val nextColumn = handle.columnNames[prefix.size]
            val range = byColumn[nextColumn]?.filter { it.first != BinaryOperator.Equal }.orEmpty()
            if (range.isEmpty()) continue
            val bounds = rangeBounds(range)
            return DiskAccessPlan.SecondaryRange(handle.name, prefix.toList(), bounds.lower, bounds.lowerInclusive, bounds.upper, bounds.upperInclusive)
        }
        return DiskAccessPlan.FullScan
    }

    private class RangeBounds(val lower: Value?, val lowerInclusive: Boolean, val upper: Value?, val upperInclusive: Boolean)

    private fun rangeBounds(predicates: List<Pair<BinaryOperator, Value>>): RangeBounds {
        var lowerValue: Value? = null
        var lowerInclusive = false
        var upperValue: Value? = null
        var upperInclusive = false
        for ((operator, literal) in predicates) {
            when (operator) {
                BinaryOperator.Greater -> { lowerValue = literal; lowerInclusive = false }
                BinaryOperator.GreaterEqual -> { lowerValue = literal; lowerInclusive = true }
                BinaryOperator.Less -> { upperValue = literal; upperInclusive = false }
                BinaryOperator.LessEqual -> { upperValue = literal; upperInclusive = true }
                else -> Unit
            }
        }
        return RangeBounds(lowerValue, lowerInclusive, upperValue, upperInclusive)
    }

    private fun rangePlan(predicates: List<Pair<BinaryOperator, Value>>): DiskAccessPlan {
        val bounds = rangeBounds(predicates)
        return DiskAccessPlan.PrimaryKeyRange(bounds.lower, bounds.lowerInclusive, bounds.upper, bounds.upperInclusive)
    }

    private fun asColumnPredicate(
        expression: Expression,
        alias: String?,
        schema: TableSchema
    ): Triple<String, BinaryOperator, Value>? {
        if (expression !is BinaryExpression) return null
        val operator = expression.operator
        if (operator !in RANGE_OPERATORS) return null
        val left = expression.left
        val right = expression.right
        fun matches(column: ColumnExpression): String? {
            if (column.qualifier != null && alias != null && !column.qualifier.equals(alias, ignoreCase = true)) return null
            return if (schema.columnIndex(column.name) != null) column.name else null
        }
        if (left is ColumnExpression && right is LiteralExpression && right.value !is Value.NullValue) {
            val name = matches(left) ?: return null
            return Triple(name, operator, right.value)
        }
        if (right is ColumnExpression && left is LiteralExpression && left.value !is Value.NullValue) {
            val name = matches(right) ?: return null
            return Triple(name, flip(operator), left.value)
        }
        return null
    }

    private fun conjuncts(expression: Expression): List<Expression> =
        if (expression is BinaryExpression && expression.operator == BinaryOperator.And) {
            conjuncts(expression.left) + conjuncts(expression.right)
        } else {
            listOf(expression)
        }

    private fun flip(operator: BinaryOperator): BinaryOperator = when (operator) {
        BinaryOperator.Less -> BinaryOperator.Greater
        BinaryOperator.LessEqual -> BinaryOperator.GreaterEqual
        BinaryOperator.Greater -> BinaryOperator.Less
        BinaryOperator.GreaterEqual -> BinaryOperator.LessEqual
        else -> operator
    }

    private companion object {
        val RANGE_OPERATORS = setOf(
            BinaryOperator.Equal, BinaryOperator.Less, BinaryOperator.LessEqual,
            BinaryOperator.Greater, BinaryOperator.GreaterEqual
        )
    }
}
