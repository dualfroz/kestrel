package kestrel.store.sql

import kestrel.catalog.Column
import kestrel.catalog.TableSchema
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.LiteralExpression
import kestrel.storage.ColumnType
import kestrel.storage.Value
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pure unit tests for [DiskIndexAccessPlanner]'s decision logic, independent
 * of any real [DiskTable]/disk I/O - hand-built [TableSchema]/[Expression]
 * ASTs (the same shapes [Parser][kestrel.sql.parser.Parser] would produce)
 * are enough to exercise every branch. End-to-end proof that the chosen plan
 * is actually *used* (not just correctly chosen) lives in [DiskExecutorTest],
 * which asserts on [DiskTable]'s access counters.
 */
class DiskIndexAccessPlannerTest {
    private val planner = DiskIndexAccessPlanner()

    private val schema = TableSchema(
        "users",
        listOf(
            Column("id", ColumnType.Integer, notNull = true, primaryKey = true),
            Column("age", ColumnType.Integer, notNull = false, primaryKey = false),
            Column("name", ColumnType.Text, notNull = false, primaryKey = false)
        )
    )

    private fun col(name: String, qualifier: String? = null) = ColumnExpression(name, qualifier)
    private fun int(value: Long) = LiteralExpression(Value.IntegerValue(value))
    private fun text(value: String) = LiteralExpression(Value.TextValue(value))
    private fun cmp(left: Expression, operator: BinaryOperator, right: Expression) = BinaryExpression(operator, left, right)
    private fun and(left: Expression, right: Expression) = BinaryExpression(BinaryOperator.And, left, right)

    @Test
    fun aNullPredicateIsAFullScan() {
        assertEquals(DiskAccessPlan.FullScan, planner.plan(schema, emptyList(), null))
    }

    @Test
    fun equalityOnThePrimaryKeyIsAPointLookup() {
        val plan = planner.plan(schema, emptyList(), cmp(col("id"), BinaryOperator.Equal, int(5)))
        val point = assertIs<DiskAccessPlan.PrimaryKeyPoint>(plan)
        assertEquals(Value.IntegerValue(5), point.key)
    }

    @Test
    fun equalityWithTheLiteralOnTheLeftIsStillAPointLookup() {
        val plan = planner.plan(schema, emptyList(), cmp(int(5), BinaryOperator.Equal, col("id")))
        val point = assertIs<DiskAccessPlan.PrimaryKeyPoint>(plan)
        assertEquals(Value.IntegerValue(5), point.key)
    }

    @Test
    fun aSingleSidedRangeOnThePrimaryKeyIsAPrimaryKeyRange() {
        val plan = planner.plan(schema, emptyList(), cmp(col("id"), BinaryOperator.Greater, int(5)))
        val range = assertIs<DiskAccessPlan.PrimaryKeyRange>(plan)
        assertEquals(Value.IntegerValue(5), range.lower)
        assertEquals(false, range.lowerInclusive)
        assertNull(range.upper)
    }

    @Test
    fun aTwoSidedRangeOnThePrimaryKeyCombinesBothBounds() {
        val predicate = and(
            cmp(col("id"), BinaryOperator.GreaterEqual, int(2)),
            cmp(col("id"), BinaryOperator.LessEqual, int(8))
        )
        val range = assertIs<DiskAccessPlan.PrimaryKeyRange>(planner.plan(schema, emptyList(), predicate))
        assertEquals(Value.IntegerValue(2), range.lower)
        assertEquals(true, range.lowerInclusive)
        assertEquals(Value.IntegerValue(8), range.upper)
        assertEquals(true, range.upperInclusive)
    }

    @Test
    fun aRangeSideWithTheLiteralOnTheLeftIsFlippedCorrectly() {
        // "5 < id" means id > 5, not id < 5.
        val plan = planner.plan(schema, emptyList(), cmp(int(5), BinaryOperator.Less, col("id")))
        val range = assertIs<DiskAccessPlan.PrimaryKeyRange>(plan)
        assertEquals(Value.IntegerValue(5), range.lower)
        assertEquals(false, range.lowerInclusive)
        assertNull(range.upper)
    }

    @Test
    fun equalityOnAnUnindexedColumnFallsBackToAFullScan() {
        assertEquals(DiskAccessPlan.FullScan, planner.plan(schema, emptyList(), cmp(col("name"), BinaryOperator.Equal, LiteralExpression(Value.TextValue("bob")))))
    }

    @Test
    fun equalityOnAnIndexedNonPrimaryColumnUsesTheSecondaryIndex() {
        // A live TransactionalIndex is not needed to prove this branch of the
        // planner - only the (name, columnName) pairing it dispatches by.
        val plan = planner.plan(schema, listOf(secondaryHandleStub("idx_age", "age")), cmp(col("age"), BinaryOperator.Equal, int(30)))
        val secondary = assertIs<DiskAccessPlan.Secondary>(plan)
        assertEquals("idx_age", secondary.indexName)
        assertEquals(listOf(Value.IntegerValue(30)), secondary.keys)
    }

    @Test
    fun aPrimaryKeyEqualityIsPreferredOverASecondaryIndexEqualityInTheSamePredicate() {
        val predicate = and(cmp(col("id"), BinaryOperator.Equal, int(2)), cmp(col("age"), BinaryOperator.Equal, int(999)))
        val plan = planner.plan(schema, listOf(secondaryHandleStub("idx_age", "age")), predicate)
        val point = assertIs<DiskAccessPlan.PrimaryKeyPoint>(plan)
        assertEquals(Value.IntegerValue(2), point.key)
    }

    @Test
    fun anOrPredicateIsNotPushedDownAndFallsBackToAFullScan() {
        // conjuncts() only ever splits on AND; an OR clause is one opaque conjunct that
        // asColumnPredicate cannot turn into a single column/operator/literal triple.
        val predicate = BinaryExpression(BinaryOperator.Or, cmp(col("id"), BinaryOperator.Equal, int(1)), cmp(col("id"), BinaryOperator.Equal, int(2)))
        assertEquals(DiskAccessPlan.FullScan, planner.plan(schema, emptyList(), predicate))
    }

    @Test
    fun aQualifierMismatchAgainstTheGivenAliasIsRejected() {
        val plan = planner.plan(schema, emptyList(), cmp(col("id", qualifier = "other"), BinaryOperator.Equal, int(5)), alias = "users")
        assertEquals(DiskAccessPlan.FullScan, plan)
    }

    @Test
    fun aQualifiedColumnMatchesWhenTheAliasAgrees() {
        val plan = planner.plan(schema, emptyList(), cmp(col("id", qualifier = "u"), BinaryOperator.Equal, int(5)), alias = "u")
        assertIs<DiskAccessPlan.PrimaryKeyPoint>(plan)
    }

    @Test
    fun aQualifiedColumnMatchesWhenNoAliasWasGiven() {
        // UPDATE/DELETE statements have no FROM alias to compare against, so a caller
        // passing alias = null (the default) intentionally ignores qualifiers entirely.
        val plan = planner.plan(schema, emptyList(), cmp(col("id", qualifier = "whatever"), BinaryOperator.Equal, int(5)))
        assertIs<DiskAccessPlan.PrimaryKeyPoint>(plan)
    }

    private val orderLineSchema = TableSchema(
        "order_lines",
        listOf(
            Column("order_id", ColumnType.Integer, notNull = true, primaryKey = false),
            Column("line_no", ColumnType.Integer, notNull = true, primaryKey = false),
            Column("sku", ColumnType.Text, notNull = false, primaryKey = false)
        ),
        compositePrimaryKey = listOf("order_id", "line_no")
    )

