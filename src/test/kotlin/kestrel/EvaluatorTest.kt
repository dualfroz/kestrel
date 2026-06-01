package kestrel

import kestrel.catalog.Column
import kestrel.catalog.TableSchema
import kestrel.engine.Evaluator
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UnaryOperator
import kestrel.storage.ColumnType
import kestrel.storage.Row
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EvaluatorTest {
    private fun intLit(value: Long): Expression = LiteralExpression(Value.IntegerValue(value))
    private fun realLit(value: Double): Expression = LiteralExpression(Value.RealValue(value))
    private fun textLit(value: String): Expression = LiteralExpression(Value.TextValue(value))
    private val nullLit: Expression = LiteralExpression(Value.NullValue)
    private val trueLit: Expression = LiteralExpression(Value.BooleanValue(true))
    private val falseLit: Expression = LiteralExpression(Value.BooleanValue(false))

    private fun bin(operator: BinaryOperator, left: Expression, right: Expression) =
        BinaryExpression(operator, left, right)

    private fun evalConstant(expression: Expression): Value = Evaluator().evaluate(expression)

    @Test
    fun integerArithmetic() {
        assertEquals(Value.IntegerValue(10), evalConstant(bin(BinaryOperator.Add, intLit(7), intLit(3))))
        assertEquals(Value.IntegerValue(21), evalConstant(bin(BinaryOperator.Multiply, intLit(7), intLit(3))))
    }

    @Test
    fun integerDivisionTruncates() {
        assertEquals(Value.IntegerValue(3), evalConstant(bin(BinaryOperator.Divide, intLit(7), intLit(2))))
    }

    @Test
    fun mixedArithmeticPromotesToReal() {
        assertEquals(Value.RealValue(3.5), evalConstant(bin(BinaryOperator.Add, intLit(1), realLit(2.5))))
    }

    @Test
    fun divisionByZeroFails() {
        assertFailsWith<KestrelException> { evalConstant(bin(BinaryOperator.Divide, intLit(1), intLit(0))) }
    }

    @Test
    fun numericComparison() {
        assertEquals(Value.BooleanValue(true), evalConstant(bin(BinaryOperator.Greater, intLit(5), intLit(3))))
        assertEquals(Value.BooleanValue(false), evalConstant(bin(BinaryOperator.Less, intLit(5), intLit(3))))
    }

    @Test
    fun textComparison() {
        assertEquals(Value.BooleanValue(true), evalConstant(bin(BinaryOperator.Less, textLit("abc"), textLit("abd"))))
    }

    @Test
    fun comparisonWithNullYieldsNull() {
        assertEquals(Value.NullValue, evalConstant(bin(BinaryOperator.Equal, intLit(1), nullLit)))
    }

    @Test
    fun threeValuedAnd() {
        assertEquals(Value.BooleanValue(false), evalConstant(bin(BinaryOperator.And, falseLit, nullLit)))
        assertEquals(Value.NullValue, evalConstant(bin(BinaryOperator.And, trueLit, nullLit)))
    }

    @Test
    fun threeValuedOr() {
        assertEquals(Value.BooleanValue(true), evalConstant(bin(BinaryOperator.Or, trueLit, nullLit)))
        assertEquals(Value.NullValue, evalConstant(bin(BinaryOperator.Or, falseLit, nullLit)))
    }

    @Test
    fun notHandlesNull() {
        assertEquals(Value.NullValue, evalConstant(UnaryExpression(UnaryOperator.Not, nullLit)))
        assertEquals(Value.BooleanValue(false), evalConstant(UnaryExpression(UnaryOperator.Not, trueLit)))
    }

    @Test
    fun unaryNegation() {
        assertEquals(Value.IntegerValue(-4), evalConstant(UnaryExpression(UnaryOperator.Negate, intLit(4))))
    }

    @Test
    fun columnReferenceReadsRowValue() {
        val schema = TableSchema("t", listOf(Column("age", ColumnType.Integer, notNull = false, primaryKey = false)))
        val row = Row(listOf(Value.IntegerValue(36)))
        assertEquals(Value.IntegerValue(36), Evaluator(schema, row).evaluate(ColumnExpression("age")))
    }

    @Test
    fun arithmeticOnTextFails() {
        assertFailsWith<KestrelException> { evalConstant(bin(BinaryOperator.Add, textLit("a"), intLit(1))) }
    }

    @Test
    fun logicalOperatorOnNumberFails() {
        assertFailsWith<KestrelException> { evalConstant(bin(BinaryOperator.And, intLit(1), trueLit)) }
    }
}
