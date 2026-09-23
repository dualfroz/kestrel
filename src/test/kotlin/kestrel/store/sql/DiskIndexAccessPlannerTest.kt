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

    @Test
    fun equalityOnEveryCompositePrimaryKeyColumnIsAPointLookupInSchemaOrder() {
        val predicate = and(
            cmp(col("line_no"), BinaryOperator.Equal, int(2)),
            cmp(col("order_id"), BinaryOperator.Equal, int(1))
        )
        val plan = planner.plan(orderLineSchema, emptyList(), predicate)
        val point = assertIs<DiskAccessPlan.CompositePrimaryKeyPoint>(plan)
        assertEquals(listOf(Value.IntegerValue(1), Value.IntegerValue(2)), point.keys)
    }

    @Test
    fun equalityOnOnlySomeCompositePrimaryKeyColumnsFallsBackToAFullScan() {
        val plan = planner.plan(orderLineSchema, emptyList(), cmp(col("order_id"), BinaryOperator.Equal, int(1)))
        assertEquals(DiskAccessPlan.FullScan, plan)
    }

    @Test
    fun aRangeOnTheLastColumnOfACompositePrimaryKeyAfterAnEqualityPrefixIsPushedDown() {
        val predicate = and(
            cmp(col("order_id"), BinaryOperator.Equal, int(1)),
            cmp(col("line_no"), BinaryOperator.Greater, int(2))
        )
        val plan = planner.plan(orderLineSchema, emptyList(), predicate)
        val range = assertIs<DiskAccessPlan.CompositePrimaryKeyRange>(plan)
        assertEquals(listOf(Value.IntegerValue(1)), range.prefix)
        assertEquals(Value.IntegerValue(2), range.lower)
        assertEquals(false, range.lowerInclusive)
        assertNull(range.upper)
    }

    @Test
    fun aTwoSidedRangeOnTheLastColumnOfACompositePrimaryKeyCombinesBothBounds() {
        val predicate = and(
            cmp(col("order_id"), BinaryOperator.Equal, int(1)),
            and(
                cmp(col("line_no"), BinaryOperator.GreaterEqual, int(2)),
                cmp(col("line_no"), BinaryOperator.LessEqual, int(8))
            )
        )
        val range = assertIs<DiskAccessPlan.CompositePrimaryKeyRange>(planner.plan(orderLineSchema, emptyList(), predicate))
        assertEquals(listOf(Value.IntegerValue(1)), range.prefix)
        assertEquals(Value.IntegerValue(2), range.lower)
        assertEquals(true, range.lowerInclusive)
        assertEquals(Value.IntegerValue(8), range.upper)
        assertEquals(true, range.upperInclusive)
    }

    @Test
    fun aRangeOnTheFirstColumnOfACompositePrimaryKeyWithNoEqualityPrefixIsNotPushedDown() {
        val plan = planner.plan(orderLineSchema, emptyList(), cmp(col("order_id"), BinaryOperator.Greater, int(1)))
        assertEquals(DiskAccessPlan.FullScan, plan)
    }

    @Test
    fun anEqualityPrefixOnACompositePrimaryKeyIsPreferredOverARangeOnTheSameLastColumn() {
        val predicate = and(
            cmp(col("order_id"), BinaryOperator.Equal, int(1)),
            and(
                cmp(col("line_no"), BinaryOperator.Equal, int(2)),
                cmp(col("line_no"), BinaryOperator.Greater, int(0))
            )
        )
        val point = assertIs<DiskAccessPlan.CompositePrimaryKeyPoint>(planner.plan(orderLineSchema, emptyList(), predicate))
        assertEquals(listOf(Value.IntegerValue(1), Value.IntegerValue(2)), point.keys)
    }

    private val orderLineWithWarehouseSchema = TableSchema(
        "order_lines3",
        listOf(
            Column("warehouse", ColumnType.Text, notNull = true, primaryKey = false),
            Column("order_id", ColumnType.Integer, notNull = true, primaryKey = false),
            Column("line_no", ColumnType.Integer, notNull = true, primaryKey = false)
        ),
        compositePrimaryKey = listOf("warehouse", "order_id", "line_no")
    )

    @Test
    fun aRangeOnTheLastColumnOfAThreeColumnCompositePrimaryKeyAfterATwoColumnEqualityPrefixIsPushedDown() {
        val predicate = and(
            cmp(col("warehouse"), BinaryOperator.Equal, text("west")),
            and(
                cmp(col("order_id"), BinaryOperator.Equal, int(1)),
                cmp(col("line_no"), BinaryOperator.Less, int(10))
            )
        )
        val range = assertIs<DiskAccessPlan.CompositePrimaryKeyRange>(planner.plan(orderLineWithWarehouseSchema, emptyList(), predicate))
        assertEquals(listOf(Value.TextValue("west"), Value.IntegerValue(1)), range.prefix)
        assertNull(range.lower)
        assertEquals(Value.IntegerValue(10), range.upper)
        assertEquals(false, range.upperInclusive)
    }

    @Test
    fun aRangeOnAMiddleColumnOfAThreeColumnCompositePrimaryKeyLeavingTheLastColumnUnconstrainedIsNotPushedDown() {
        val predicate = and(
            cmp(col("warehouse"), BinaryOperator.Equal, text("west")),
            cmp(col("order_id"), BinaryOperator.Greater, int(1))
        )
        val plan = planner.plan(orderLineWithWarehouseSchema, emptyList(), predicate)
        assertEquals(DiskAccessPlan.FullScan, plan)
    }

    @Test
    fun comparingToANullLiteralIsNeverPushedDown() {
        val plan = planner.plan(schema, emptyList(), cmp(col("id"), BinaryOperator.Equal, LiteralExpression(Value.NullValue)))
        assertEquals(DiskAccessPlan.FullScan, plan)
    }

    private fun secondaryHandleStub(name: String, columnName: String): SecondaryIndexHandle {
        val dataFile = Files.createTempFile("kestrel-planner-test-data", ".db").toFile().also { it.deleteOnExit() }
        val walFile = Files.createTempFile("kestrel-planner-test-wal", ".db").toFile().also { it.deleteOnExit() }
        val catalog = DiskCatalog.open(dataFile, walFile)
        catalog.createTable("stub", schema.columns)
        return catalog.createIndex("stub", name, columnName)
    }

    private fun compositeSecondaryHandleStub(name: String, columnNames: List<String>): SecondaryIndexHandle {
        val dataFile = Files.createTempFile("kestrel-planner-test-data", ".db").toFile().also { it.deleteOnExit() }
        val walFile = Files.createTempFile("kestrel-planner-test-wal", ".db").toFile().also { it.deleteOnExit() }
        val catalog = DiskCatalog.open(dataFile, walFile)
        catalog.createTable("stub", orderLineSchema.columns)
        return catalog.createIndex("stub", name, columnNames)
    }

    @Test
    fun equalityOnEveryColumnOfACompositeSecondaryIndexUsesIt() {
        val handle = compositeSecondaryHandleStub("idx_order_sku", listOf("order_id", "sku"))
        val predicate = and(
            cmp(col("sku"), BinaryOperator.Equal, text("widget")),
            cmp(col("order_id"), BinaryOperator.Equal, int(1))
        )
        val plan = planner.plan(orderLineSchema, listOf(handle), predicate)
        val secondary = assertIs<DiskAccessPlan.Secondary>(plan)
        assertEquals("idx_order_sku", secondary.indexName)
        assertEquals(listOf(Value.IntegerValue(1), Value.TextValue("widget")), secondary.keys)
    }

    @Test
    fun equalityOnOnlySomeColumnsOfACompositeSecondaryIndexFallsBackToAFullScan() {
        val handle = compositeSecondaryHandleStub("idx_order_sku", listOf("order_id", "sku"))
        val plan = planner.plan(orderLineSchema, listOf(handle), cmp(col("order_id"), BinaryOperator.Equal, int(1)))
        assertEquals(DiskAccessPlan.FullScan, plan)
    }

    @Test
    fun aSingleSidedRangeOnASingleColumnSecondaryIndexIsASecondaryRange() {
        val handle = secondaryHandleStub("idx_age", "age")
        val plan = planner.plan(schema, listOf(handle), cmp(col("age"), BinaryOperator.GreaterEqual, int(30)))
        val range = assertIs<DiskAccessPlan.SecondaryRange>(plan)
        assertEquals("idx_age", range.indexName)
        assertEquals(emptyList<Value>(), range.prefix)
        assertEquals(Value.IntegerValue(30), range.lower)
        assertEquals(true, range.lowerInclusive)
        assertNull(range.upper)
    }

    @Test
    fun aTwoSidedRangeOnASingleColumnSecondaryIndexCombinesBothBounds() {
        val handle = secondaryHandleStub("idx_age", "age")
        val predicate = and(
            cmp(col("age"), BinaryOperator.Greater, int(20)),
            cmp(col("age"), BinaryOperator.Less, int(40))
        )
        val plan = planner.plan(schema, listOf(handle), predicate)
        val range = assertIs<DiskAccessPlan.SecondaryRange>(plan)
        assertEquals("idx_age", range.indexName)
        assertEquals(emptyList<Value>(), range.prefix)
        assertEquals(Value.IntegerValue(20), range.lower)
        assertEquals(false, range.lowerInclusive)
        assertEquals(Value.IntegerValue(40), range.upper)
        assertEquals(false, range.upperInclusive)
    }

    @Test
    fun anEqualityOnASecondaryIndexIsPreferredOverARangeOnTheSameColumn() {
        val handle = secondaryHandleStub("idx_age", "age")
        val predicate = and(cmp(col("age"), BinaryOperator.Equal, int(30)), cmp(col("age"), BinaryOperator.Greater, int(20)))
        val plan = planner.plan(schema, listOf(handle), predicate)
        val equality = assertIs<DiskAccessPlan.Secondary>(plan)
        assertEquals(listOf(Value.IntegerValue(30)), equality.keys)
    }

    @Test
    fun aRangeOnTheFirstColumnOfACompositeSecondaryIndexWithNoPrefixIsPushedDown() {
        val handle = compositeSecondaryHandleStub("idx_order_sku", listOf("order_id", "sku"))
        val plan = planner.plan(orderLineSchema, listOf(handle), cmp(col("order_id"), BinaryOperator.Greater, int(1)))
        val range = assertIs<DiskAccessPlan.SecondaryRange>(plan)
        assertEquals("idx_order_sku", range.indexName)
        assertEquals(emptyList<Value>(), range.prefix)
        assertEquals(Value.IntegerValue(1), range.lower)
        assertEquals(false, range.lowerInclusive)
        assertNull(range.upper)
    }

    @Test
    fun aRangeOnTheSecondColumnOfACompositeSecondaryIndexAfterAnEqualityPrefixIsPushedDown() {
        val handle = compositeSecondaryHandleStub("idx_order_sku", listOf("order_id", "sku"))
        val predicate = and(cmp(col("order_id"), BinaryOperator.Equal, int(1)), cmp(col("sku"), BinaryOperator.GreaterEqual, text("m")))
        val plan = planner.plan(orderLineSchema, listOf(handle), predicate)
        val range = assertIs<DiskAccessPlan.SecondaryRange>(plan)
        assertEquals("idx_order_sku", range.indexName)
        assertEquals(listOf(Value.IntegerValue(1)), range.prefix)
        assertEquals(Value.TextValue("m"), range.lower)
        assertEquals(true, range.lowerInclusive)
        assertNull(range.upper)
    }

    @Test
    fun aRangeOnTheSecondColumnOfACompositeSecondaryIndexWithNoEqualityPrefixIsNotPushedDown() {
        val handle = compositeSecondaryHandleStub("idx_order_sku", listOf("order_id", "sku"))
        val plan = planner.plan(orderLineSchema, listOf(handle), cmp(col("sku"), BinaryOperator.Greater, text("m")))
        assertEquals(DiskAccessPlan.FullScan, plan)
    }

    @Test
    fun anEqualityPrefixOnACompositeSecondaryIndexIsPreferredOverARangeOnTheSameNextColumn() {
        val handle = compositeSecondaryHandleStub("idx_order_sku", listOf("order_id", "sku"))
        val predicate = and(
            cmp(col("order_id"), BinaryOperator.Equal, int(1)),
            and(cmp(col("sku"), BinaryOperator.Equal, text("widget")), cmp(col("sku"), BinaryOperator.Greater, text("a")))
        )
        val plan = planner.plan(orderLineSchema, listOf(handle), predicate)
        val equality = assertIs<DiskAccessPlan.Secondary>(plan)
        assertEquals(listOf(Value.IntegerValue(1), Value.TextValue("widget")), equality.keys)
    }

    @Test
    fun aTypeMismatchedComparisonOnTheKeyIsAFullScan() {
        assertEquals(DiskAccessPlan.FullScan, planner.plan(schema, emptyList(), cmp(col("id"), BinaryOperator.Equal, text("5"))))
        assertEquals(DiskAccessPlan.FullScan, planner.plan(schema, emptyList(), cmp(text("5"), BinaryOperator.Less, col("id"))))
    }

    @Test
    fun aTypeMismatchedConjunctNextToAKeyEqualityIsAFullScan() {
        val mismatchedEquality = and(cmp(col("id"), BinaryOperator.Equal, int(5)), cmp(col("name"), BinaryOperator.Equal, int(5)))
        val mismatchedInequality = and(cmp(col("name"), BinaryOperator.NotEqual, int(5)), cmp(col("id"), BinaryOperator.Equal, int(5)))
        assertEquals(DiskAccessPlan.FullScan, planner.plan(schema, emptyList(), mismatchedEquality))
        assertEquals(DiskAccessPlan.FullScan, planner.plan(schema, emptyList(), mismatchedInequality))
    }

    @Test
    fun aMatchingInequalityNextToAKeyEqualityKeepsThePointLookup() {
        val predicate = and(cmp(col("name"), BinaryOperator.NotEqual, text("x")), cmp(col("id"), BinaryOperator.Equal, int(5)))
        assertIs<DiskAccessPlan.PrimaryKeyPoint>(planner.plan(schema, emptyList(), predicate))
    }
}
