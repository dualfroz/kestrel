package kestrel.engine

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kestrel.KestrelException
import kestrel.catalog.TableSchema
import kestrel.engine.function.ScalarFunctions
import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.ExistsExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.InSubqueryExpression
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.CurrentValueExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.ScalarSubqueryExpression
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UnaryOperator
import kestrel.sql.ast.WindowExpression
import kestrel.storage.Collation
import kestrel.storage.ColumnType
import kestrel.storage.DecimalColumns
import kestrel.storage.Row
import kestrel.storage.Value
import kestrel.storage.typeName

fun interface ColumnResolver {
    fun resolve(expression: ColumnExpression): Value

    /**
     * The [Collation] [expression] was declared with in its `CREATE TABLE ... COLLATE name` (its
     * [kestrel.catalog.Column.collation]), or `null` when this resolver has no such column to
     * report one for - a computed/derived row (a set operation's output, a LATERAL subquery's own
     * result), an ambiguous reference across more than one source, or simply a resolver that never
     * bothered to override this default. [Evaluator.collationOf] only ever calls this for a bare
     * [ColumnExpression] with no [kestrel.sql.ast.CollateExpression] of its own - an explicit
     * `COLLATE` in the query always wins over whatever this returns. A default method (not the SAM
     * member) so every existing lambda-shaped `ColumnResolver { expression -> ... }` construction
     * keeps compiling unchanged and simply reports "no declared collation" - only the resolvers
     * built directly over a [kestrel.catalog.TableSchema] bother to override it.
     */
    fun declaredCollation(expression: ColumnExpression): Collation? = null
}

fun interface SubqueryRunner {
    fun run(select: SelectStatement, outer: ColumnResolver?): SelectResult
}

/**
 * Bridges NEXTVAL/CURRVAL evaluation to whichever [kestrel.catalog.Catalog] and per-session state
 * actually own sequence counters - kept as its own interface (not a `fun interface` like
 * [ColumnResolver]/[SubqueryRunner]) because it needs two independent operations, not one.
 * [kestrel.engine.Executor] is the only implementation; every other [Evaluator] construction site
 * (SELECT projections, WHERE clauses read-side, CHECK constraints, trigger `WHEN` clauses) omits it,
 * so [evaluateNextValue]/[evaluateCurrentValue] throw there exactly like a missing [SubqueryRunner]
 * already does for a subquery - deliberately: a volatile, side-effecting call being evaluated an
 * engine-implementation-defined number of times (once for filtering, again for projecting the same
 * row, ...) is exactly the ambiguity real databases warn about, so this engine only ever allows it
 * where a row's assignment is evaluated exactly once by construction (see
 * [kestrel.engine.Executor.executeInsertInner]/`executeUpdateInner`).
 */
interface SequenceAccess {
    fun nextValue(sequenceName: String): Long
    fun currentValue(sequenceName: String): Long
}

class Evaluator(
    private val resolver: ColumnResolver? = null,
    private val subqueries: SubqueryRunner? = null,
    private val sequences: SequenceAccess? = null,
    /** Bound only by [kestrel.engine.Executor.evaluateMergeReturning]'s per-row [Evaluator] - see [MergeActionExpression]'s own doc for why every other call site leaves this null and lets MERGE_ACTION() throw. */
    private val mergeAction: String? = null
) {
    constructor(schema: TableSchema?, row: Row?, subqueries: SubqueryRunner? = null, sequences: SequenceAccess? = null) : this(
        schema?.let { active ->
            object : ColumnResolver {
                override fun resolve(expression: ColumnExpression): Value {
                    val index = active.columnIndex(expression.name)
                        ?: throw KestrelException("no such column: ${expression.name}")
                    return row!!.values[index]
                }

                override fun declaredCollation(expression: ColumnExpression): Collation? =
                    active.column(expression.name)?.collation
            }
        },
        subqueries,
        sequences
    )

    fun evaluate(expression: Expression): Value = when (expression) {
        is LiteralExpression -> expression.value
        is ColumnExpression -> evaluateColumn(expression)
        is UnaryExpression -> evaluateUnary(expression)
        is CollateExpression -> evaluate(expression.operand)
        is BinaryExpression -> evaluateBinary(expression)
        is FunctionExpression -> throw KestrelException(
            "aggregate function ${expression.name} is not allowed here"
        )
        is WindowExpression -> throw KestrelException(
            "window function ${expression.name} is not allowed here"
        )
        is CallExpression -> evaluateCall(expression)
        is IsNullExpression -> evaluateIsNull(expression)
        is InExpression -> evaluateIn(expression)
        is BetweenExpression -> evaluateBetween(expression)
        is LikeExpression -> evaluateLike(expression)
        is CaseExpression -> evaluateCase(expression)
        is ScalarSubqueryExpression -> evaluateScalarSubquery(expression)
        is ExistsExpression -> evaluateExists(expression)
        is InSubqueryExpression -> evaluateInSubquery(expression)
        is CastExpression -> evaluateCast(expression)
        is NextValueExpression -> evaluateNextValue(expression)
        is CurrentValueExpression -> evaluateCurrentValue(expression)
        is MergeActionExpression -> Value.TextValue(mergeAction ?: throw KestrelException("MERGE_ACTION() is not allowed here"))
        is ParameterExpression -> throw KestrelException(
            "parameter reference \$${expression.index} is only valid inside a PREPARE-d statement, resolved by EXECUTE"
        )
    }

    private fun evaluateNextValue(expression: NextValueExpression): Value = Value.IntegerValue(
        (sequences ?: throw KestrelException("NEXTVAL is not allowed here")).nextValue(expression.sequenceName)
    )

    private fun evaluateCurrentValue(expression: CurrentValueExpression): Value = Value.IntegerValue(
        (sequences ?: throw KestrelException("CURRVAL is not allowed here")).currentValue(expression.sequenceName)
    )

    private fun runSubquery(select: SelectStatement): SelectResult =
        (subqueries ?: throw KestrelException("subqueries are not allowed here")).run(select, resolver)

    private fun evaluateScalarSubquery(expression: ScalarSubqueryExpression): Value {
        val result = runSubquery(expression.select)
        if (result.columns.size != 1) {
            throw KestrelException("scalar subquery must return exactly one column but returned ${result.columns.size}")
        }
        return when {
            result.rows.isEmpty() -> Value.NullValue
            result.rows.size > 1 -> throw KestrelException("scalar subquery returned more than one row")
            else -> result.rows.single().single()
        }
    }

    private fun evaluateExists(expression: ExistsExpression): Value =
        Value.BooleanValue(runSubquery(expression.select).rows.isNotEmpty())

    private fun evaluateInSubquery(expression: InSubqueryExpression): Value {
        val operand = evaluate(expression.operand)
        if (operand is Value.NullValue) return Value.NullValue
        val collation = collationOf(expression.operand) ?: Collation.Binary
        val result = runSubquery(expression.select)
        if (result.columns.size != 1) {
            throw KestrelException("IN subquery must return exactly one column but returned ${result.columns.size}")
        }
        var sawNull = false
        for (row in result.rows) {
            val candidate = row.single()
            if (candidate is Value.NullValue) {
                sawNull = true
                continue
            }
            if (compareOperands(operand, candidate, collation) == 0) return Value.BooleanValue(!expression.negated)
        }
        return if (sawNull) Value.NullValue else Value.BooleanValue(expression.negated)
    }

    private fun evaluateCast(expression: CastExpression): Value {
        val operand = evaluate(expression.operand)
        if (operand is Value.NullValue) return Value.NullValue
        return when (expression.targetType) {
            ColumnType.Integer -> castToInteger(operand)
            ColumnType.Real -> castToReal(operand)
            ColumnType.Text -> Value.TextValue(castToText(operand))
            ColumnType.Boolean -> castToBoolean(operand)
            ColumnType.Decimal -> castToDecimal(operand, expression.precision, expression.scale)
            ColumnType.Date -> castToDate(operand)
        }
    }

    private fun castToInteger(value: Value): Value = Value.IntegerValue(
        when (value) {
            is Value.IntegerValue -> value.value
            is Value.RealValue -> value.value.toLong()
            is Value.DecimalValue -> value.value.toLong()
            is Value.DateValue -> value.value.toEpochDay()
            is Value.TextValue -> value.value.trim().toLongOrNull()
                ?: throw KestrelException("cannot cast '${value.value}' to INTEGER")
            else -> throw KestrelException("cannot cast ${value.typeName} to INTEGER")
        }
    )

    private fun castToReal(value: Value): Value = Value.RealValue(
        when (value) {
            is Value.IntegerValue -> value.value.toDouble()
            is Value.RealValue -> value.value
            is Value.DecimalValue -> value.value.toDouble()
            is Value.TextValue -> value.value.trim().toDoubleOrNull()
                ?: throw KestrelException("cannot cast '${value.value}' to REAL")
            else -> throw KestrelException("cannot cast ${value.typeName} to REAL")
        }
    )

    private fun castToText(value: Value): String = when (value) {
        is Value.IntegerValue -> value.value.toString()
        is Value.RealValue -> value.value.toString()
        is Value.DecimalValue -> value.value.toPlainString()
        is Value.BooleanValue -> value.value.toString()
        is Value.DateValue -> value.value.toString()
        is Value.TextValue -> value.value
        else -> throw KestrelException("cannot cast ${value.typeName} to TEXT")
    }

    private fun castToBoolean(value: Value): Value = Value.BooleanValue(
        when (value) {
            is Value.BooleanValue -> value.value
            is Value.IntegerValue -> value.value != 0L
            is Value.TextValue -> when (value.value.trim().lowercase()) {
                "true" -> true
                "false" -> false
                else -> throw KestrelException("cannot cast '${value.value}' to BOOLEAN")
            }
            else -> throw KestrelException("cannot cast ${value.typeName} to BOOLEAN")
        }
    )

    private fun castToDecimal(value: Value, precision: Int?, scale: Int?): Value {
        val raw = when (value) {
            is Value.DecimalValue -> value.value
            is Value.IntegerValue -> BigDecimal(value.value)
            is Value.RealValue -> BigDecimal.valueOf(value.value)
            is Value.TextValue -> value.value.trim().toBigDecimalOrNull()
                ?: throw KestrelException("cannot cast '${value.value}' to DECIMAL")
            else -> throw KestrelException("cannot cast ${value.typeName} to DECIMAL")
        }
        return Value.DecimalValue(DecimalColumns.normalize(raw, precision, scale, "CAST"))
    }

    private fun castToDate(value: Value): Value = Value.DateValue(
        when (value) {
            is Value.DateValue -> value.value
            is Value.IntegerValue -> LocalDate.ofEpochDay(value.value)
            is Value.TextValue -> runCatching { LocalDate.parse(value.value.trim()) }.getOrElse {
                throw KestrelException("cannot cast '${value.value}' to DATE")
            }
            else -> throw KestrelException("cannot cast ${value.typeName} to DATE")
        }
    )

    private fun evaluateCall(expression: CallExpression): Value {
        if (expression.name.equals("GROUPING", ignoreCase = true)) {
            throw KestrelException("GROUPING() can only be used in the SELECT list, HAVING, or ORDER BY of a query with GROUP BY")
        }
        val function = ScalarFunctions.lookup(expression.name)
            ?: throw KestrelException("unknown function: ${expression.name}")
        return function.apply(expression.arguments.map { evaluate(it) })
    }

    private fun evaluateIsNull(expression: IsNullExpression): Value {
        val isNull = evaluate(expression.operand).isNull
        return Value.BooleanValue(if (expression.negated) !isNull else isNull)
    }

    private fun evaluateIn(expression: InExpression): Value {
        val operand = evaluate(expression.operand)
        if (operand is Value.NullValue) return Value.NullValue
        val collation = collationOf(expression.operand) ?: Collation.Binary
        var sawNull = false
        for (candidateExpression in expression.values) {
            val candidate = evaluate(candidateExpression)
            if (candidate is Value.NullValue) {
                sawNull = true
                continue
            }
            if (compareOperands(operand, candidate, collation) == 0) return Value.BooleanValue(!expression.negated)
        }
        return if (sawNull) Value.NullValue else Value.BooleanValue(expression.negated)
    }

    private fun evaluateBetween(expression: BetweenExpression): Value {
        val operand = evaluate(expression.operand)
        val low = evaluate(expression.low)
        val high = evaluate(expression.high)
        if (operand is Value.NullValue || low is Value.NullValue || high is Value.NullValue) return Value.NullValue
        val withinRange = compareOperands(operand, low, pairCollation(expression.operand, expression.low)) >= 0 &&
            compareOperands(operand, high, pairCollation(expression.operand, expression.high)) <= 0
        return Value.BooleanValue(if (expression.negated) !withinRange else withinRange)
    }

    private fun evaluateLike(expression: LikeExpression): Value {
        val operand = evaluate(expression.operand)
        val pattern = evaluate(expression.pattern)
        if (operand is Value.NullValue || pattern is Value.NullValue) return Value.NullValue
        if (operand !is Value.TextValue || pattern !is Value.TextValue) {
            throw KestrelException("LIKE requires text operands but got ${operand.typeName} and ${pattern.typeName}")
        }
        val matches = likeToRegex(pattern.value).matches(operand.value)
        return Value.BooleanValue(if (expression.negated) !matches else matches)
    }

    private fun likeToRegex(pattern: String): Regex {
        val builder = StringBuilder("(?s)")
        for (character in pattern) {
            when (character) {
                '%' -> builder.append(".*")
                '_' -> builder.append(".")
                else -> builder.append(Regex.escape(character.toString()))
            }
        }
        return Regex(builder.toString())
    }

    private fun evaluateCase(expression: CaseExpression): Value {
        val operand = expression.operand?.let { evaluate(it) }
        for (branch in expression.branches) {
            val matched = if (operand != null) {
                if (operand is Value.NullValue) {
                    false
                } else {
                    val candidate = evaluate(branch.condition)
                    candidate !is Value.NullValue &&
                        compareOperands(operand, candidate, pairCollation(expression.operand!!, branch.condition)) == 0
                }
            } else {
                asBooleanOrNull(evaluate(branch.condition)) == true
            }
            if (matched) return evaluate(branch.result)
        }
        return expression.elseExpression?.let { evaluate(it) } ?: Value.NullValue
    }

    private fun evaluateColumn(expression: ColumnExpression): Value {
        val active = resolver ?: throw KestrelException("column '${expression.name}' is not allowed here")
        return active.resolve(expression)
    }

    private fun evaluateUnary(expression: UnaryExpression): Value {
        val operand = evaluate(expression.operand)
        if (operand is Value.NullValue) return Value.NullValue
        return when (expression.operator) {
            UnaryOperator.Negate -> when (operand) {
                is Value.IntegerValue -> Value.IntegerValue(-operand.value)
                is Value.RealValue -> Value.RealValue(-operand.value)
                is Value.DecimalValue -> Value.DecimalValue(-operand.value)
                else -> throw KestrelException("unary minus requires a numeric operand but got ${operand.typeName}")
            }
            UnaryOperator.Not -> when (operand) {
                is Value.BooleanValue -> Value.BooleanValue(!operand.value)
                else -> throw KestrelException("NOT requires a boolean operand but got ${operand.typeName}")
            }
        }
    }

    private fun evaluateBinary(expression: BinaryExpression): Value {
        val operator = expression.operator
        if (operator == BinaryOperator.And || operator == BinaryOperator.Or) {
            return evaluateLogical(operator, expression.left, expression.right)
        }
        val left = evaluate(expression.left)
        val right = evaluate(expression.right)
        return when (operator) {
            BinaryOperator.Add, BinaryOperator.Subtract,
            BinaryOperator.Multiply, BinaryOperator.Divide -> evaluateArithmetic(operator, left, right)
            else -> evaluateComparison(operator, left, right, pairCollation(expression.left, expression.right))
        }
    }

    private fun evaluateLogical(operator: BinaryOperator, leftExpression: Expression, rightExpression: Expression): Value {
        val left = asBooleanOrNull(evaluate(leftExpression))
        val right = asBooleanOrNull(evaluate(rightExpression))
        return when (operator) {
            BinaryOperator.And -> when {
                left == false || right == false -> Value.BooleanValue(false)
                left == true && right == true -> Value.BooleanValue(true)
                else -> Value.NullValue
            }
            BinaryOperator.Or -> when {
                left == true || right == true -> Value.BooleanValue(true)
                left == false && right == false -> Value.BooleanValue(false)
                else -> Value.NullValue
            }
            else -> error("unreachable logical operator")
        }
    }

    private fun asBooleanOrNull(value: Value): Boolean? = when (value) {
        is Value.NullValue -> null
        is Value.BooleanValue -> value.value
        else -> throw KestrelException("logical operator requires a boolean operand but got ${value.typeName}")
    }

    private fun evaluateArithmetic(operator: BinaryOperator, left: Value, right: Value): Value {
        if (left is Value.NullValue || right is Value.NullValue) return Value.NullValue
        if (left is Value.DateValue || right is Value.DateValue) {
            return evaluateDateArithmetic(operator, left, right)
        }
        if (left is Value.DecimalValue || right is Value.DecimalValue) {
            return evaluateDecimalArithmetic(operator, left, right)
        }
        if (!isNumeric(left) || !isNumeric(right)) {
            throw KestrelException("arithmetic requires numeric operands but got ${left.typeName} and ${right.typeName}")
        }
        if (left is Value.IntegerValue && right is Value.IntegerValue) {
            val a = left.value
            val b = right.value
            return Value.IntegerValue(
                when (operator) {
                    BinaryOperator.Add -> a + b
                    BinaryOperator.Subtract -> a - b
                    BinaryOperator.Multiply -> a * b
                    BinaryOperator.Divide -> {
                        if (b == 0L) throw KestrelException("division by zero")
                        a / b
                    }
                    else -> error("unreachable arithmetic operator")
                }
            )
        }
        val a = asDouble(left)
        val b = asDouble(right)
        return Value.RealValue(
            when (operator) {
                BinaryOperator.Add -> a + b
                BinaryOperator.Subtract -> a - b
                BinaryOperator.Multiply -> a * b
                BinaryOperator.Divide -> {
                    if (b == 0.0) throw KestrelException("division by zero")
                    a / b
                }
                else -> error("unreachable arithmetic operator")
            }
        )
    }

    private fun evaluateComparison(operator: BinaryOperator, left: Value, right: Value, collation: Collation): Value {
        if (left is Value.NullValue || right is Value.NullValue) return Value.NullValue
        val ordering = compareOperands(left, right, collation)
        val result = when (operator) {
            BinaryOperator.Equal -> ordering == 0
            BinaryOperator.NotEqual -> ordering != 0
            BinaryOperator.Less -> ordering < 0
            BinaryOperator.LessEqual -> ordering <= 0
            BinaryOperator.Greater -> ordering > 0
            BinaryOperator.GreaterEqual -> ordering >= 0
            else -> error("unreachable comparison operator")
        }
        return Value.BooleanValue(result)
    }

    /**
     * [collation] only ever changes the outcome for two [Value.TextValue]s - every other pairing
     * ignores it, matching SQLite (COLLATE is meaningless outside of text comparison). Defaults to
     * [Collation.Binary] (this engine's behavior before COLLATE existed) at every call site that
     * has no [CollateExpression] to resolve one from.
     */
    private fun compareOperands(left: Value, right: Value, collation: Collation = Collation.Binary): Int = when {
        left is Value.IntegerValue && right is Value.IntegerValue -> left.value.compareTo(right.value)
        left is Value.DecimalValue || right is Value.DecimalValue -> {
            val a = decimalOf(left) ?: throw KestrelException("cannot compare ${left.typeName} with ${right.typeName}")
            val b = decimalOf(right) ?: throw KestrelException("cannot compare ${left.typeName} with ${right.typeName}")
            a.compareTo(b)
        }
        isNumeric(left) && isNumeric(right) -> asDouble(left).compareTo(asDouble(right))
        left is Value.TextValue && right is Value.TextValue -> collation.compare(left.value, right.value)
        left is Value.BooleanValue && right is Value.BooleanValue -> left.value.compareTo(right.value)
        left is Value.DateValue && right is Value.DateValue -> left.value.compareTo(right.value)
        else -> throw KestrelException("cannot compare ${left.typeName} with ${right.typeName}")
    }

    /** The [Collation] an explicit `COLLATE` on [expression] itself names, or `null` when it is not a [CollateExpression] at all. */
    private fun explicitCollationOf(expression: Expression): Collation? = (expression as? CollateExpression)?.collation

    /**
     * [expression]'s own declared `COLLATE` (see [kestrel.catalog.Column.collation]) when it is a
     * bare [ColumnExpression] whose [resolver] reports one - `null` for anything else, including a
     * column that never declared one at all ([ColumnResolver.declaredCollation] reporting
     * [Collation.Binary], its class default, is treated the exact same as reporting nothing: a
     * column that never opted into a non-default sequence should not out-rank an operand on the
     * *other* side of a comparison that did, purely by virtue of appearing on the left - see
     * [pairCollation]).
     */
    private fun columnDeclaredCollationOf(expression: Expression): Collation? =
        (expression as? ColumnExpression)?.let { resolver?.declaredCollation(it) }?.takeIf { it != Collation.Binary }

    /**
     * The [Collation] [expression] compares with on its own, combining both of [pairCollation]'s
     * tiers into one - used where there is only a single operand to consult
     * ([evaluateIn]/[evaluateInSubquery]'s list/subquery operand), which can still tell "no COLLATE
     * here at all" apart from "explicitly COLLATE BINARY" (both [explicitCollationOf] and
     * [columnDeclaredCollationOf] return `null` only for the former).
     */
    private fun collationOf(expression: Expression): Collation? = explicitCollationOf(expression) ?: columnDeclaredCollationOf(expression)

    /**
     * Delegates to the shared [kestrel.engine.pairCollation] (SQLite's two-tier binary-comparison
     * rule) bound to this evaluator's own [resolver] - see that function's doc for the precedence
     * rule itself and why it must stay two separate passes rather than one combined
     * [collationOf]-per-side check.
     */
    private fun pairCollation(left: Expression, right: Expression): Collation =
        kestrel.engine.pairCollation(left, right) { resolver?.declaredCollation(it) }

    private fun isNumeric(value: Value): Boolean = value is Value.IntegerValue || value is Value.RealValue

    private fun asDouble(value: Value): Double = when (value) {
        is Value.IntegerValue -> value.value.toDouble()
        is Value.RealValue -> value.value
        else -> error("not a numeric value")
    }

    private fun decimalOf(value: Value): BigDecimal? = when (value) {
        is Value.DecimalValue -> value.value
        is Value.IntegerValue -> BigDecimal(value.value)
        is Value.RealValue -> BigDecimal.valueOf(value.value)
        else -> null
    }

    private fun evaluateDateArithmetic(operator: BinaryOperator, left: Value, right: Value): Value = when {
        left is Value.DateValue && right is Value.DateValue -> when (operator) {
            BinaryOperator.Subtract -> Value.IntegerValue(left.value.toEpochDay() - right.value.toEpochDay())
            else -> throw KestrelException("DATE ${operator.symbol} DATE is not supported; only subtraction (day difference) is")
        }
        left is Value.DateValue && right is Value.IntegerValue -> when (operator) {
            BinaryOperator.Add -> Value.DateValue(left.value.plusDays(right.value))
            BinaryOperator.Subtract -> Value.DateValue(left.value.minusDays(right.value))
            else -> throw KestrelException("DATE only supports + and - with an INTEGER day offset")
        }
        left is Value.IntegerValue && right is Value.DateValue && operator == BinaryOperator.Add ->
            Value.DateValue(right.value.plusDays(left.value))
        else -> throw KestrelException("arithmetic requires numeric operands but got ${left.typeName} and ${right.typeName}")
    }

    private fun evaluateDecimalArithmetic(operator: BinaryOperator, left: Value, right: Value): Value {
        val a = decimalOf(left) ?: throw KestrelException("arithmetic requires numeric operands but got ${left.typeName} and ${right.typeName}")
        val b = decimalOf(right) ?: throw KestrelException("arithmetic requires numeric operands but got ${left.typeName} and ${right.typeName}")
        return Value.DecimalValue(
            when (operator) {
                BinaryOperator.Add -> a + b
                BinaryOperator.Subtract -> a - b
                BinaryOperator.Multiply -> a * b
                BinaryOperator.Divide -> {
                    if (b.signum() == 0) throw KestrelException("division by zero")
                    a.divide(b, maxOf(a.scale(), b.scale(), MIN_DIVISION_SCALE), RoundingMode.HALF_UP)
                }
                else -> error("unreachable arithmetic operator")
            }
        )
    }

    private companion object {
        const val MIN_DIVISION_SCALE = 10
    }
}
