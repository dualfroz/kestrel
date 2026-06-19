package kestrel.engine

import kestrel.KestrelException
import kestrel.sql.ast.CubeSpec
import kestrel.sql.ast.Expression
import kestrel.sql.ast.GroupingSetsSpec
import kestrel.sql.ast.GroupingSpec
import kestrel.sql.ast.RollupSpec

/**
 * Turns a [GroupingSpec] into the concrete list of grouping sets [GroupAggregation] groups by - one
 * full pass over the source rows per set, later unioned together. `null` (an ordinary `GROUP BY a, b`
 * or no `GROUP BY` at all) is exactly one implicit set, [groupBy] itself - this is what lets
 * [GroupAggregation] treat the plain case as a special case of the general one instead of a separate
 * code path.
 */
object GroupingSetExpansion {
    /** CUBE(e1..en) is 2^n sets; capped well short of overflow or a runaway allocation from a typo'd column list. */
    private const val MAX_CUBE_COLUMNS = 16

    fun setsFor(spec: GroupingSpec?, groupBy: List<Expression>): List<List<Expression>> = when (spec) {
        null -> listOf(groupBy)
        is RollupSpec -> rollupSets(spec.expressions)
        is CubeSpec -> cubeSets(spec.expressions)
        is GroupingSetsSpec -> spec.sets
    }

    /** ROLLUP(e1, e2, e3) -> {e1,e2,e3}, {e1,e2}, {e1}, {} - each prefix of the declared order, longest first. */
    private fun rollupSets(expressions: List<Expression>): List<List<Expression>> =
        (expressions.size downTo 0).map { expressions.subList(0, it) }

    /** CUBE(e1, e2, e3) -> every one of the 2^3 subsets, largest first; membership of expression i is bit i of a descending bitmask. */
    private fun cubeSets(expressions: List<Expression>): List<List<Expression>> {
        if (expressions.size > MAX_CUBE_COLUMNS) {
            throw KestrelException("CUBE supports at most $MAX_CUBE_COLUMNS expressions")
        }
        val subsetCount = 1 shl expressions.size
        return (subsetCount - 1 downTo 0).map { mask ->
            expressions.filterIndexed { index, _ -> (mask shr index) and 1 == 1 }
        }
    }
}
