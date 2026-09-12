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
        // A partial equality match with no range on the remaining column either cannot be
        // turned into a point lookup (line_no is left entirely unbound) - see
        // DiskAccessPlan.CompositePrimaryKeyRange's own documentation for the narrower case where
        // a range on the remaining last column IS pushed down instead.
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
        // order_id is not the LAST composite PRIMARY KEY column - see
        // DiskAccessPlan.CompositePrimaryKeyRange's own documentation on why leaving line_no
        // entirely unconstrained after a range on order_id is a deliberate scope cut.
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
        // line_no would be left entirely unconstrained after a range on order_id - the same
        // "must always fill every PRIMARY KEY column" restriction as the two-column schema's
        // aRangeOnTheFirstColumnOfACompositePrimaryKeyWithNoEqualityPrefixIsNotPushedDown, just one
        // column deeper.
        val predicate = and(
            cmp(col("warehouse"), BinaryOperator.Equal, text("west")),
            cmp(col("order_id"), BinaryOperator.Greater, int(1))
        )
        val plan = planner.plan(orderLineWithWarehouseSchema, emptyList(), predicate)
        assertEquals(DiskAccessPlan.FullScan, plan)
    }

    @Test
    fun comparingToANullLiteralIsNeverPushedDown() {
        // "id = NULL" is never true under three-valued SQL logic and must not be
        // treated as an equality lookup for NULL (which the encoding cannot represent).
        val plan = planner.plan(schema, emptyList(), cmp(col("id"), BinaryOperator.Equal, LiteralExpression(Value.NullValue)))
        assertEquals(DiskAccessPlan.FullScan, plan)
    }

    /**
     * A real [SecondaryIndexHandle] backed by a genuine, temp-file [DiskCatalog].
     * The planner only ever reads a handle's `name`/`columnName` - never its
     * live [kestrel.store.engine.TransactionalIndex] - but that index has no
     * lightweight fake (its constructor is private and wired to a real
     * [kestrel.store.page.PageStore]/[kestrel.store.txn.TransactionManager]),
     * so real disk infrastructure is the simplest way to obtain a valid one.
     */
    private fun secondaryHandleStub(name: String, columnName: String): SecondaryIndexHandle {
        val dataFile = Files.createTempFile("kestrel-planner-test-data", ".db").toFile().also { it.deleteOnExit() }
        val walFile = Files.createTempFile("kestrel-planner-test-wal", ".db").toFile().also { it.deleteOnExit() }
        val catalog = DiskCatalog.open(dataFile, walFile)
        catalog.createTable("stub", schema.columns)
        return catalog.createIndex("stub", name, columnName)
    }

    /** Composite-column counterpart of [secondaryHandleStub], over [orderLineSchema]'s columns. */
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
        // A range predicate alone would push down to SecondaryRange, but a stricter equality
        // conjunct on the very same column is always more selective and must win.
        val handle = secondaryHandleStub("idx_age", "age")
        val predicate = and(cmp(col("age"), BinaryOperator.Equal, int(30)), cmp(col("age"), BinaryOperator.Greater, int(20)))
        val plan = planner.plan(schema, listOf(handle), predicate)
        val equality = assertIs<DiskAccessPlan.Secondary>(plan)
        assertEquals(listOf(Value.IntegerValue(30)), equality.keys)
    }

    @Test
    fun aRangeOnTheFirstColumnOfACompositeSecondaryIndexWithNoPrefixIsPushedDown() {
        // The composite index is sorted by order_id first, so a bare range on it (no equality
        // on any column at all) is just as pushable as the single-column case above - an empty
        // prefix, mirroring kestrel.engine.IndexAccessPlanner's own "even an empty run" case.
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
        // The index is sorted by order_id first - a range on sku alone cannot narrow the scan
        // without first pinning order_id down, the same restriction as an equality-only prefix.
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
}
