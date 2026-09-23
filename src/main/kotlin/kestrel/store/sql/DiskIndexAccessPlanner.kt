package kestrel.store.sql

import kestrel.catalog.TableSchema
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.LiteralExpression
import kestrel.storage.ColumnType
import kestrel.storage.Value
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

sealed class DiskAccessPlan {
    data object FullScan : DiskAccessPlan()
    class PrimaryKeyPoint(val key: Value) : DiskAccessPlan()
    class PrimaryKeyRange(
        val lower: Value?,
        val lowerInclusive: Boolean,
        val upper: Value?,
        val upperInclusive: Boolean
    ) : DiskAccessPlan()
    class CompositePrimaryKeyPoint(val keys: List<Value>) : DiskAccessPlan()
    class CompositePrimaryKeyRange(
        val prefix: List<Value>,
        val lower: Value?,
        val lowerInclusive: Boolean,
        val upper: Value?,
        val upperInclusive: Boolean
    ) : DiskAccessPlan()
    class Secondary(val indexName: String, val keys: List<Value>) : DiskAccessPlan()
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

    fun plan(schema: TableSchema, secondaryIndexes: List<SecondaryIndexHandle>, predicate: Expression?, alias: String? = null): DiskAccessPlan {
        if (predicate == null) return DiskAccessPlan.FullScan
        val byColumn = LinkedHashMap<String, MutableList<Pair<BinaryOperator, Value>>>()
        for (conjunct in conjuncts(predicate)) {
            val (name, operator, literal) = asColumnPredicate(conjunct, alias, schema) ?: continue
            val columnType = schema.columns[schema.columnIndex(name)!!].type
            if (!comparable(columnType, literal)) return DiskAccessPlan.FullScan
            if (operator !in rangeOperators) continue
            val (keyOperator, key) = coerceToKey(columnType, operator, literal) ?: continue
            byColumn.getOrPut(name) { mutableListOf() }.add(keyOperator to key)
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
            if (prefix.size == handle.columnNames.size) continue
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
        if (operator !in comparisonOperators) return null
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

    private fun comparable(columnType: ColumnType, literal: Value): Boolean =
        literal.type == columnType || (columnType in numericTypes && isNumeric(literal))

    internal fun equalityKey(columnType: ColumnType, value: Value): Value? {
        if (!comparable(columnType, value)) return null
        return coerceToKey(columnType, BinaryOperator.Equal, value)?.second
    }

    private fun coerceToKey(columnType: ColumnType, operator: BinaryOperator, literal: Value): Pair<BinaryOperator, Value>? {
        if (!isNumeric(literal)) return operator to literal
        return when (columnType) {
            ColumnType.Integer -> integerKey(operator, literal)
            ColumnType.Real -> realKey(literal)?.let { operator to it }
            ColumnType.Decimal -> decimalOf(literal)?.let { operator to Value.DecimalValue(it) }
            else -> operator to literal
        }
    }

    private fun integerKey(operator: BinaryOperator, literal: Value): Pair<BinaryOperator, Value>? {
        if (literal is Value.IntegerValue) return operator to literal
        if (literal is Value.RealValue && abs(literal.value) >= exactDoubleLimit) return null
        val exact = decimalOf(literal) ?: return null
        val integral = exact.setScale(0, RoundingMode.DOWN).compareTo(exact) == 0
        val (keyOperator, rounded) = when {
            integral -> operator to exact
            operator == BinaryOperator.Greater || operator == BinaryOperator.GreaterEqual ->
                BinaryOperator.GreaterEqual to exact.setScale(0, RoundingMode.CEILING)
            operator == BinaryOperator.Less || operator == BinaryOperator.LessEqual ->
                BinaryOperator.LessEqual to exact.setScale(0, RoundingMode.FLOOR)
            else -> return null
        }
        if (rounded < longMin || rounded > longMax) return null
        return keyOperator to Value.IntegerValue(rounded.longValueExact())
    }

    private fun realKey(literal: Value): Value? = when (literal) {
        is Value.RealValue -> literal
        is Value.IntegerValue -> Value.RealValue(literal.value.toDouble())
        is Value.DecimalValue -> {
            val approximation = literal.value.toDouble()
            if (approximation.isFinite() && BigDecimal.valueOf(approximation).compareTo(literal.value) == 0) Value.RealValue(approximation) else null
        }
        else -> null
    }

    private fun decimalOf(literal: Value): BigDecimal? = when (literal) {
        is Value.DecimalValue -> literal.value
        is Value.IntegerValue -> BigDecimal(literal.value)
        is Value.RealValue -> if (literal.value.isFinite()) BigDecimal.valueOf(literal.value) else null
        else -> null
    }

    private fun isNumeric(value: Value): Boolean =
        value is Value.IntegerValue || value is Value.RealValue || value is Value.DecimalValue

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
        val rangeOperators = setOf(
            BinaryOperator.Equal, BinaryOperator.Less, BinaryOperator.LessEqual,
            BinaryOperator.Greater, BinaryOperator.GreaterEqual
        )
        val comparisonOperators = rangeOperators + BinaryOperator.NotEqual
        val numericTypes = setOf(ColumnType.Integer, ColumnType.Real, ColumnType.Decimal)
        const val exactDoubleLimit = 9007199254740992.0
        val longMin: BigDecimal = BigDecimal.valueOf(Long.MIN_VALUE)
        val longMax: BigDecimal = BigDecimal.valueOf(Long.MAX_VALUE)
    }
}
