package kestrel.engine

import kestrel.KestrelException
import kestrel.sql.ast.CubeSpec
import kestrel.sql.ast.Expression
import kestrel.sql.ast.GroupingSetsSpec
import kestrel.sql.ast.GroupingSpec
import kestrel.sql.ast.RollupSpec

object GroupingSetExpansion {
    private const val MAX_CUBE_COLUMNS = 16

    fun setsFor(spec: GroupingSpec?, groupBy: List<Expression>): List<List<Expression>> = when (spec) {
        null -> listOf(groupBy)
        is RollupSpec -> rollupSets(spec.expressions)
        is CubeSpec -> cubeSets(spec.expressions)
        is GroupingSetsSpec -> spec.sets
    }

    private fun rollupSets(expressions: List<Expression>): List<List<Expression>> =
        (expressions.size downTo 0).map { expressions.subList(0, it) }

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
