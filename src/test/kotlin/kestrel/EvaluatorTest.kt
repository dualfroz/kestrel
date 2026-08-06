package kestrel

import java.math.BigDecimal
import java.time.LocalDate
import kestrel.catalog.Column
import kestrel.catalog.TableSchema
import kestrel.engine.Evaluator
import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseBranch
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.LikeExpression
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

    @Test
    fun stringFunctionsTransformText() {
        assertEquals(Value.TextValue("ADA"), evalConstant(CallExpression("UPPER", listOf(textLit("ada")))))
        assertEquals(Value.TextValue("ada"), evalConstant(CallExpression("LOWER", listOf(textLit("ADA")))))
        assertEquals(Value.IntegerValue(3), evalConstant(CallExpression("LENGTH", listOf(textLit("ada")))))
        assertEquals(Value.TextValue("ada"), evalConstant(CallExpression("TRIM", listOf(textLit("  ada  ")))))
    }

    @Test
    fun substrHandlesTwoAndThreeArgumentForms() {
        assertEquals(
            Value.TextValue("ell"),
            evalConstant(CallExpression("SUBSTR", listOf(textLit("hello"), intLit(2), intLit(3))))
        )
        assertEquals(Value.TextValue("llo"), evalConstant(CallExpression("SUBSTR", listOf(textLit("hello"), intLit(3)))))
        assertEquals(Value.TextValue(""), evalConstant(CallExpression("SUBSTR", listOf(textLit("hello"), intLit(99)))))
    }

    @Test
    fun replaceAndConcat() {
        assertEquals(
            Value.TextValue("hexxo"),
            evalConstant(CallExpression("REPLACE", listOf(textLit("hello"), textLit("l"), textLit("x"))))
        )
        assertEquals(
            Value.TextValue("ab-cd"),
            evalConstant(CallExpression("CONCAT", listOf(textLit("ab"), textLit("-"), textLit("cd"))))
        )
    }

    @Test
    fun numericFunctionsComputeCorrectly() {
        assertEquals(Value.IntegerValue(4), evalConstant(CallExpression("ABS", listOf(intLit(-4)))))
        assertEquals(Value.RealValue(4.0), evalConstant(CallExpression("ABS", listOf(realLit(-4.0)))))
        assertEquals(Value.IntegerValue(3), evalConstant(CallExpression("CEIL", listOf(realLit(2.1)))))
        assertEquals(Value.IntegerValue(2), evalConstant(CallExpression("FLOOR", listOf(realLit(2.9)))))
        assertEquals(Value.RealValue(2.35), evalConstant(CallExpression("ROUND", listOf(realLit(2.346), intLit(2)))))
        assertEquals(Value.IntegerValue(5), evalConstant(CallExpression("ROUND", listOf(intLit(5)))))
    }

    @Test
    fun nullHandlingFunctionsSeeRawNulls() {
        assertEquals(Value.IntegerValue(7), evalConstant(CallExpression("COALESCE", listOf(nullLit, nullLit, intLit(7)))))
        assertEquals(Value.NullValue, evalConstant(CallExpression("COALESCE", listOf(nullLit, nullLit))))
        assertEquals(Value.IntegerValue(1), evalConstant(CallExpression("IFNULL", listOf(intLit(1), intLit(2)))))
        assertEquals(Value.IntegerValue(2), evalConstant(CallExpression("IFNULL", listOf(nullLit, intLit(2)))))
        assertEquals(Value.NullValue, evalConstant(CallExpression("NULLIF", listOf(intLit(5), intLit(5)))))
        assertEquals(Value.IntegerValue(5), evalConstant(CallExpression("NULLIF", listOf(intLit(5), intLit(6)))))
        assertEquals(Value.TextValue("NULL"), evalConstant(CallExpression("TYPEOF", listOf(nullLit))))
        assertEquals(Value.TextValue("INTEGER"), evalConstant(CallExpression("TYPEOF", listOf(intLit(1)))))
    }

    @Test
    fun mostFunctionsPropagateNullArguments() {
        assertEquals(Value.NullValue, evalConstant(CallExpression("UPPER", listOf(nullLit))))
        assertEquals(Value.NullValue, evalConstant(CallExpression("ABS", listOf(nullLit))))
    }

    @Test
    fun functionArityIsEnforced() {
        assertFailsWith<KestrelException> { evalConstant(CallExpression("UPPER", listOf(textLit("a"), textLit("b")))) }
        assertFailsWith<KestrelException> { evalConstant(CallExpression("SUBSTR", listOf(textLit("a")))) }
    }

    @Test
    fun unknownFunctionFails() {
        assertFailsWith<KestrelException> { evalConstant(CallExpression("NOPE", listOf(intLit(1)))) }
    }

    @Test
    fun currentDateReturnsTodaysWallClockDate() {
        assertEquals(Value.DateValue(LocalDate.now()), evalConstant(CallExpression("CURRENT_DATE", emptyList())))
    }

    @Test
    fun dateComponentFunctionsExtractYearMonthDay() {
        val date = dateLit(LocalDate.of(2024, 3, 15))
        assertEquals(Value.IntegerValue(2024), evalConstant(CallExpression("YEAR", listOf(date))))
        assertEquals(Value.IntegerValue(3), evalConstant(CallExpression("MONTH", listOf(date))))
        assertEquals(Value.IntegerValue(15), evalConstant(CallExpression("DAY", listOf(date))))
        assertEquals(Value.IntegerValue(5), evalConstant(CallExpression("DAYOFWEEK", listOf(date))))
    }

    @Test
    fun dateComponentFunctionsPropagateNullAndRejectNonDateArguments() {
        assertEquals(Value.NullValue, evalConstant(CallExpression("YEAR", listOf(nullLit))))
        assertFailsWith<KestrelException> { evalConstant(CallExpression("MONTH", listOf(intLit(1)))) }
    }

    @Test
    fun dateAddAndDateSubShiftByWholeDaysInEitherDirection() {
        val date = dateLit(LocalDate.of(2024, 3, 15))
        assertEquals(
            Value.DateValue(LocalDate.of(2024, 3, 25)),
            evalConstant(CallExpression("DATE_ADD", listOf(date, intLit(10))))
        )
        assertEquals(
            Value.DateValue(LocalDate.of(2024, 3, 5)),
            evalConstant(CallExpression("DATE_SUB", listOf(date, intLit(10))))
        )
        // Crossing a month boundary exercises java.time's own carry logic rather than hand-rolled arithmetic.
        assertEquals(
            Value.DateValue(LocalDate.of(2024, 4, 2)),
            evalConstant(CallExpression("DATE_ADD", listOf(date, intLit(18))))
        )
        assertEquals(
            Value.DateValue(LocalDate.of(2024, 3, 9)),
            evalConstant(CallExpression("DATE_ADD", listOf(date, intLit(-6))))
        )
    }

    @Test
    fun datediffReturnsTheSignedDayCountBetweenTwoDates() {
        val later = dateLit(LocalDate.of(2024, 3, 20))
        val earlier = dateLit(LocalDate.of(2024, 3, 15))
        assertEquals(Value.IntegerValue(5), evalConstant(CallExpression("DATEDIFF", listOf(later, earlier))))
        assertEquals(Value.IntegerValue(-5), evalConstant(CallExpression("DATEDIFF", listOf(earlier, later))))
        assertEquals(Value.IntegerValue(0), evalConstant(CallExpression("DATEDIFF", listOf(later, later))))
    }

    @Test
    fun repeatBuildsTheStringNTimesAndTreatsNonPositiveCountsAsEmpty() {
        assertEquals(Value.TextValue("ababab"), evalConstant(CallExpression("REPEAT", listOf(textLit("ab"), intLit(3)))))
        assertEquals(Value.TextValue(""), evalConstant(CallExpression("REPEAT", listOf(textLit("ab"), intLit(0)))))
        assertEquals(Value.TextValue(""), evalConstant(CallExpression("REPEAT", listOf(textLit("ab"), intLit(-1)))))
    }

    @Test
    fun lpadAndRpadPadToTheTargetLengthOrTruncateWhenAlreadyLongEnough() {
        assertEquals(Value.TextValue("--ab"), evalConstant(CallExpression("LPAD", listOf(textLit("ab"), intLit(4), textLit("-")))))
        assertEquals(Value.TextValue("ab--"), evalConstant(CallExpression("RPAD", listOf(textLit("ab"), intLit(4), textLit("-")))))
        assertEquals(Value.TextValue("  ab"), evalConstant(CallExpression("LPAD", listOf(textLit("ab"), intLit(4)))))
        assertEquals(Value.TextValue("he"), evalConstant(CallExpression("LPAD", listOf(textLit("hello"), intLit(2), textLit("-")))))
        assertEquals(Value.TextValue("he"), evalConstant(CallExpression("RPAD", listOf(textLit("hello"), intLit(2), textLit("-")))))
        assertEquals(Value.TextValue(""), evalConstant(CallExpression("LPAD", listOf(textLit("hello"), intLit(0), textLit("-")))))
    }

    @Test
    fun lpadCyclesAMultiCharacterPadStringAndTrimsItToExactlyFitTheTargetLength() {
        assertEquals(Value.TextValue("xyxab"), evalConstant(CallExpression("LPAD", listOf(textLit("ab"), intLit(5), textLit("xy")))))
    }

    @Test
    fun padFunctionsRejectAnEmptyPadStringWhenPaddingIsActuallyNeeded() {
        assertFailsWith<KestrelException> {
            evalConstant(CallExpression("LPAD", listOf(textLit("ab"), intLit(4), textLit(""))))
        }
    }

    @Test
    fun instrReturnsAOneBasedPositionOrZeroWhenNotFound() {
        assertEquals(Value.IntegerValue(3), evalConstant(CallExpression("INSTR", listOf(textLit("hello"), textLit("llo")))))
        assertEquals(Value.IntegerValue(1), evalConstant(CallExpression("INSTR", listOf(textLit("hello"), textLit("h")))))
        assertEquals(Value.IntegerValue(0), evalConstant(CallExpression("INSTR", listOf(textLit("hello"), textLit("z")))))
    }

    @Test
    fun powerStaysExactForTwoIntegersButFallsBackToRealOtherwise() {
        assertEquals(Value.IntegerValue(1024), evalConstant(CallExpression("POWER", listOf(intLit(2), intLit(10)))))
        assertEquals(Value.IntegerValue(1), evalConstant(CallExpression("POWER", listOf(intLit(5), intLit(0)))))
        assertEquals(Value.RealValue(0.25), evalConstant(CallExpression("POWER", listOf(intLit(2), intLit(-2)))))
        assertEquals(Value.RealValue(6.25), evalConstant(CallExpression("POWER", listOf(realLit(2.5), intLit(2)))))
    }

    @Test
    fun modComputesRemainderAndRejectsDivisionByZero() {
        assertEquals(Value.IntegerValue(1), evalConstant(CallExpression("MOD", listOf(intLit(7), intLit(3)))))
        assertEquals(Value.IntegerValue(-1), evalConstant(CallExpression("MOD", listOf(intLit(-7), intLit(3)))))
        assertEquals(Value.RealValue(0.5), evalConstant(CallExpression("MOD", listOf(realLit(2.5), intLit(1)))))
        assertFailsWith<KestrelException> { evalConstant(CallExpression("MOD", listOf(intLit(1), intLit(0)))) }
    }

    @Test
    fun reverseFlipsTheCharactersOfAString() {
        assertEquals(Value.TextValue("cba"), evalConstant(CallExpression("REVERSE", listOf(textLit("abc")))))
        assertEquals(Value.TextValue(""), evalConstant(CallExpression("REVERSE", listOf(textLit("")))))
        assertFailsWith<KestrelException> { evalConstant(CallExpression("REVERSE", listOf(intLit(1)))) }
    }

    @Test
    fun leftAndRightTakeCharactersFromEachEndAndClampToTheStringsLength() {
        assertEquals(Value.TextValue("hel"), evalConstant(CallExpression("LEFT", listOf(textLit("hello"), intLit(3)))))
        assertEquals(Value.TextValue("llo"), evalConstant(CallExpression("RIGHT", listOf(textLit("hello"), intLit(3)))))
        assertEquals(Value.TextValue("hello"), evalConstant(CallExpression("LEFT", listOf(textLit("hello"), intLit(100)))))
        assertEquals(Value.TextValue("hello"), evalConstant(CallExpression("RIGHT", listOf(textLit("hello"), intLit(100)))))
        assertEquals(Value.TextValue(""), evalConstant(CallExpression("LEFT", listOf(textLit("hello"), intLit(0)))))
        assertEquals(Value.TextValue(""), evalConstant(CallExpression("RIGHT", listOf(textLit("hello"), intLit(-1)))))
    }

    @Test
    fun signReturnsMinusOneZeroOrOneAcrossEveryNumericType() {
        assertEquals(Value.IntegerValue(1), evalConstant(CallExpression("SIGN", listOf(intLit(5)))))
        assertEquals(Value.IntegerValue(-1), evalConstant(CallExpression("SIGN", listOf(intLit(-5)))))
        assertEquals(Value.IntegerValue(0), evalConstant(CallExpression("SIGN", listOf(intLit(0)))))
        assertEquals(Value.IntegerValue(1), evalConstant(CallExpression("SIGN", listOf(realLit(0.1)))))
        assertEquals(Value.IntegerValue(-1), evalConstant(CallExpression("SIGN", listOf(realLit(-0.1)))))
        val negativeDecimal = LiteralExpression(Value.DecimalValue(BigDecimal("-3.50")))
        assertEquals(Value.IntegerValue(-1), evalConstant(CallExpression("SIGN", listOf(negativeDecimal))))
        assertFailsWith<KestrelException> { evalConstant(CallExpression("SIGN", listOf(textLit("x")))) }
    }

    @Test
    fun truncCutsDigitsTowardZeroWithoutRoundingUnlikeRound() {
        assertEquals(Value.IntegerValue(4), evalConstant(CallExpression("TRUNC", listOf(intLit(4)))))
        assertEquals(Value.RealValue(1.9), evalConstant(CallExpression("TRUNC", listOf(realLit(1.99), intLit(1)))))
        assertEquals(Value.RealValue(-1.9), evalConstant(CallExpression("TRUNC", listOf(realLit(-1.99), intLit(1)))))
        val decimal = LiteralExpression(Value.DecimalValue(BigDecimal("1.999")))
        assertEquals(
            Value.DecimalValue(BigDecimal("1.99")),
            evalConstant(CallExpression("TRUNC", listOf(decimal, intLit(2))))
        )
        // TRUNC never rounds up, exactly the case where it must differ from ROUND's HALF_UP default.
        assertEquals(Value.RealValue(1.0), evalConstant(CallExpression("TRUNC", listOf(realLit(1.9)))))
        assertEquals(Value.RealValue(2.0), evalConstant(CallExpression("ROUND", listOf(realLit(1.9)))))
    }

    @Test
    fun greatestAndLeastPickTheOrderingExtremeAcrossMixedNumericTypes() {
        assertEquals(
            Value.IntegerValue(7),
            evalConstant(CallExpression("GREATEST", listOf(intLit(3), intLit(7), intLit(1))))
        )
        assertEquals(
            Value.IntegerValue(1),
            evalConstant(CallExpression("LEAST", listOf(intLit(3), intLit(7), intLit(1))))
        )
        assertEquals(
            Value.RealValue(7.5),
            evalConstant(CallExpression("GREATEST", listOf(intLit(3), realLit(7.5), intLit(1))))
        )
        assertEquals(Value.TextValue("banana"), evalConstant(CallExpression("GREATEST", listOf(textLit("apple"), textLit("banana")))))
    }

    @Test
    fun greatestAndLeastPropagateNullAndRequireAtLeastTwoArguments() {
        assertEquals(Value.NullValue, evalConstant(CallExpression("GREATEST", listOf(intLit(1), nullLit))))
        assertEquals(Value.NullValue, evalConstant(CallExpression("LEAST", listOf(nullLit, intLit(1)))))
        assertFailsWith<KestrelException> { evalConstant(CallExpression("GREATEST", listOf(intLit(1)))) }
    }

    @Test
    fun functionRequiringTextRejectsWrongType() {
        assertFailsWith<KestrelException> { evalConstant(CallExpression("UPPER", listOf(intLit(1)))) }
    }

    @Test
    fun isNullDetectsNullness() {
        assertEquals(Value.BooleanValue(true), evalConstant(IsNullExpression(nullLit, negated = false)))
        assertEquals(Value.BooleanValue(false), evalConstant(IsNullExpression(intLit(1), negated = false)))
        assertEquals(Value.BooleanValue(true), evalConstant(IsNullExpression(intLit(1), negated = true)))
        assertEquals(Value.BooleanValue(false), evalConstant(IsNullExpression(nullLit, negated = true)))
    }

    @Test
    fun inMatchesAnyListMember() {
        assertEquals(
            Value.BooleanValue(true),
            evalConstant(InExpression(intLit(2), listOf(intLit(1), intLit(2), intLit(3)), negated = false))
        )
        assertEquals(
            Value.BooleanValue(false),
            evalConstant(InExpression(intLit(9), listOf(intLit(1), intLit(2)), negated = false))
        )
        assertEquals(
            Value.BooleanValue(false),
            evalConstant(InExpression(intLit(2), listOf(intLit(1), intLit(2)), negated = true))
        )
    }

    @Test
    fun inWithNullOperandIsNull() {
        assertEquals(Value.NullValue, evalConstant(InExpression(nullLit, listOf(intLit(1)), negated = false)))
    }

    @Test
    fun inWithUnmatchedNullInListIsNullNotFalse() {
        assertEquals(
            Value.NullValue,
            evalConstant(InExpression(intLit(9), listOf(intLit(1), nullLit), negated = false))
        )
    }

    @Test
    fun betweenIsInclusiveOnBothEnds() {
        assertEquals(Value.BooleanValue(true), evalConstant(BetweenExpression(intLit(18), intLit(18), intLit(65), negated = false)))
        assertEquals(Value.BooleanValue(true), evalConstant(BetweenExpression(intLit(65), intLit(18), intLit(65), negated = false)))
        assertEquals(Value.BooleanValue(false), evalConstant(BetweenExpression(intLit(17), intLit(18), intLit(65), negated = false)))
        assertEquals(Value.BooleanValue(true), evalConstant(BetweenExpression(intLit(17), intLit(18), intLit(65), negated = true)))
    }

    @Test
    fun likeSupportsPercentAndUnderscoreWildcards() {
        assertEquals(Value.BooleanValue(true), evalConstant(LikeExpression(textLit("hello"), textLit("h%"), negated = false)))
        assertEquals(Value.BooleanValue(true), evalConstant(LikeExpression(textLit("hello"), textLit("h_llo"), negated = false)))
        assertEquals(Value.BooleanValue(false), evalConstant(LikeExpression(textLit("hello"), textLit("world"), negated = false)))
        assertEquals(Value.BooleanValue(false), evalConstant(LikeExpression(textLit("hello"), textLit("h%"), negated = true)))
    }

    @Test
    fun likeEscapesRegexMetacharactersInPattern() {
        assertEquals(Value.BooleanValue(true), evalConstant(LikeExpression(textLit("a.b"), textLit("a.b"), negated = false)))
        assertEquals(Value.BooleanValue(false), evalConstant(LikeExpression(textLit("axb"), textLit("a.b"), negated = false)))
    }

    @Test
    fun searchedCaseReturnsFirstMatchingBranch() {
        val expression = CaseExpression(
            operand = null,
            branches = listOf(
                CaseBranch(bin(BinaryOperator.Less, intLit(10), intLit(5)), textLit("small")),
                CaseBranch(bin(BinaryOperator.GreaterEqual, intLit(10), intLit(5)), textLit("big"))
            ),
            elseExpression = textLit("none")
        )
        assertEquals(Value.TextValue("big"), evalConstant(expression))
    }

    @Test
    fun simpleCaseComparesOperandAgainstBranches() {
        val expression = CaseExpression(
            operand = intLit(2),
            branches = listOf(CaseBranch(intLit(1), textLit("one")), CaseBranch(intLit(2), textLit("two"))),
            elseExpression = textLit("other")
        )
        assertEquals(Value.TextValue("two"), evalConstant(expression))
    }

    @Test
    fun caseFallsThroughToElseWhenNoBranchMatches() {
        val expression = CaseExpression(
            operand = null,
            branches = listOf(CaseBranch(falseLit, textLit("nope"))),
            elseExpression = textLit("fallback")
        )
        assertEquals(Value.TextValue("fallback"), evalConstant(expression))
    }

    @Test
    fun caseWithoutElseYieldsNullWhenNoBranchMatches() {
        val expression = CaseExpression(operand = null, branches = listOf(CaseBranch(falseLit, textLit("nope"))), elseExpression = null)
        assertEquals(Value.NullValue, evalConstant(expression))
    }

    private fun cast(operand: Expression, type: ColumnType, precision: Int? = null, scale: Int? = null): Expression =
        CastExpression(operand, type, precision, scale)

    @Test
    fun castTextToIntegerRealAndBoolean() {
        assertEquals(Value.IntegerValue(42), evalConstant(cast(textLit("42"), ColumnType.Integer)))
        assertEquals(Value.RealValue(3.5), evalConstant(cast(textLit("3.5"), ColumnType.Real)))
        assertEquals(Value.BooleanValue(true), evalConstant(cast(textLit("true"), ColumnType.Boolean)))
        assertEquals(Value.BooleanValue(false), evalConstant(cast(textLit("FALSE"), ColumnType.Boolean)))
    }

    @Test
    fun castTextToDecimalAppliesScale() {
        val result = evalConstant(cast(textLit("19.999"), ColumnType.Decimal, 10, 2)) as Value.DecimalValue
        assertEquals("20.00", result.value.toPlainString())
    }

    @Test
    fun castTextToDateParsesIsoFormat() {
        val result = evalConstant(cast(textLit("2024-01-15"), ColumnType.Date))
        assertEquals(Value.DateValue(LocalDate.of(2024, 1, 15)), result)
    }

    @Test
    fun castNumbersAndDatesToText() {
        assertEquals(Value.TextValue("42"), evalConstant(cast(intLit(42), ColumnType.Text)))
        assertEquals(
            Value.TextValue("2024-01-15"),
            evalConstant(cast(LiteralExpression(Value.DateValue(LocalDate.of(2024, 1, 15))), ColumnType.Text))
        )
    }

    @Test
    fun castOfNullIsAlwaysNull() {
        assertEquals(Value.NullValue, evalConstant(cast(nullLit, ColumnType.Integer)))
        assertEquals(Value.NullValue, evalConstant(cast(nullLit, ColumnType.Decimal)))
    }

    @Test
    fun castRejectsUnparseableText() {
        assertFailsWith<KestrelException> { evalConstant(cast(textLit("not a number"), ColumnType.Integer)) }
        assertFailsWith<KestrelException> { evalConstant(cast(textLit("not a date"), ColumnType.Date)) }
        assertFailsWith<KestrelException> { evalConstant(cast(textLit("nope"), ColumnType.Boolean)) }
    }

    @Test
    fun decimalOverflowBeyondDeclaredPrecisionFails() {
        assertFailsWith<KestrelException> { evalConstant(cast(textLit("123456"), ColumnType.Decimal, 5, 0)) }
    }

    @Test
    fun decimalArithmeticIsExactUnlikeDoubles() {
        val a = cast(textLit("0.1"), ColumnType.Decimal, 10, 2)
        val b = cast(textLit("0.2"), ColumnType.Decimal, 10, 2)
        val sum = evalConstant(bin(BinaryOperator.Add, a, b)) as Value.DecimalValue
        assertEquals("0.30", sum.value.toPlainString())
    }

    @Test
    fun decimalEqualityIgnoresTrailingZeroScale() {
        val fifteenTenths = LiteralExpression(Value.DecimalValue(BigDecimal("1.50")))
        val fifteen = LiteralExpression(Value.DecimalValue(BigDecimal("1.5")))
        assertEquals(Value.BooleanValue(true), evalConstant(bin(BinaryOperator.Equal, fifteenTenths, fifteen)))
    }

    @Test
    fun decimalDivisionByZeroFails() {
        val a = cast(textLit("1"), ColumnType.Decimal, 10, 2)
        val zero = cast(textLit("0"), ColumnType.Decimal, 10, 2)
        assertFailsWith<KestrelException> { evalConstant(bin(BinaryOperator.Divide, a, zero)) }
    }

    @Test
    fun decimalComparesCorrectlyAgainstIntegerAndReal() {
        val decimal = LiteralExpression(Value.DecimalValue(BigDecimal("19.99")))
        assertEquals(Value.BooleanValue(true), evalConstant(bin(BinaryOperator.Less, decimal, intLit(20))))
        assertEquals(Value.BooleanValue(true), evalConstant(bin(BinaryOperator.Greater, decimal, realLit(19.98))))
    }

    private fun dateLit(value: LocalDate): Expression = LiteralExpression(Value.DateValue(value))

    @Test
    fun addingAndSubtractingIntegerDaysFromADate() {
        val start = dateLit(LocalDate.of(2024, 1, 1))
        assertEquals(Value.DateValue(LocalDate.of(2024, 1, 6)), evalConstant(bin(BinaryOperator.Add, start, intLit(5))))
        assertEquals(Value.DateValue(LocalDate.of(2023, 12, 27)), evalConstant(bin(BinaryOperator.Subtract, start, intLit(5))))
    }

    @Test
    fun subtractingTwoDatesYieldsIntegerDayDifference() {
        val later = dateLit(LocalDate.of(2024, 1, 10))
        val earlier = dateLit(LocalDate.of(2024, 1, 1))
        assertEquals(Value.IntegerValue(9), evalConstant(bin(BinaryOperator.Subtract, later, earlier)))
    }

    @Test
    fun multiplyingADateFails() {
        val start = dateLit(LocalDate.of(2024, 1, 1))
        assertFailsWith<KestrelException> { evalConstant(bin(BinaryOperator.Multiply, start, intLit(2))) }
    }
}
