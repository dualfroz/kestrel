package kestrel.engine

import kestrel.KestrelException
import kestrel.catalog.TableSchema
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UnaryOperator
import kestrel.storage.Row
import kestrel.storage.Value
import kestrel.storage.typeName

fun interface ColumnResolver {
    fun resolve(expression: ColumnExpression): Value
}

class Evaluator(private val resolver: ColumnResolver? = null) {
    constructor(schema: TableSchema?, row: Row?) : this(schema?.let { active ->
        ColumnResolver { expression ->
            val index = active.columnIndex(expression.name)
                ?: throw KestrelException("no such column: ${expression.name}")
            row!!.values[index]
        }
    })

    fun evaluate(expression: Expression): Value = when (expression) {
        is LiteralExpression -> expression.value
        is ColumnExpression -> evaluateColumn(expression)
        is UnaryExpression -> evaluateUnary(expression)
        is BinaryExpression -> evaluateBinary(expression)
        is FunctionExpression -> throw KestrelException(
            "aggregate function ${expression.name} is not allowed here"
        )
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
            else -> evaluateComparison(operator, left, right)
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

    private fun evaluateComparison(operator: BinaryOperator, left: Value, right: Value): Value {
        if (left is Value.NullValue || right is Value.NullValue) return Value.NullValue
        val ordering = compareOperands(left, right)
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

    private fun compareOperands(left: Value, right: Value): Int = when {
        left is Value.IntegerValue && right is Value.IntegerValue -> left.value.compareTo(right.value)
        isNumeric(left) && isNumeric(right) -> asDouble(left).compareTo(asDouble(right))
        left is Value.TextValue && right is Value.TextValue -> left.value.compareTo(right.value)
        left is Value.BooleanValue && right is Value.BooleanValue -> left.value.compareTo(right.value)
        else -> throw KestrelException("cannot compare ${left.typeName} with ${right.typeName}")
    }

    private fun isNumeric(value: Value): Boolean = value is Value.IntegerValue || value is Value.RealValue

    private fun asDouble(value: Value): Double = when (value) {
        is Value.IntegerValue -> value.value.toDouble()
        is Value.RealValue -> value.value
        else -> error("not a numeric value")
    }
}
