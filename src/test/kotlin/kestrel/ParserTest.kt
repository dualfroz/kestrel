package kestrel

import kestrel.catalog.ForeignKeyAction
import kestrel.sql.ast.AddColumn
import kestrel.sql.ast.AlterSequenceStatement
import kestrel.sql.ast.AlterTableStatement
import kestrel.sql.ast.Assignment
import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.ChangeColumnType
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.CreateIndexStatement
import kestrel.sql.ast.CreateSequenceStatement
import kestrel.sql.ast.CreateTableStatement
import kestrel.sql.ast.CreateTriggerStatement
import kestrel.sql.ast.CreateViewStatement
import kestrel.sql.ast.CubeSpec
import kestrel.sql.ast.CurrentValueExpression
import kestrel.sql.ast.DeallocateStatement
import kestrel.sql.ast.DeleteStatement
import kestrel.sql.ast.DropColumn
import kestrel.sql.ast.DropMode
import kestrel.sql.ast.DropSequenceStatement
import kestrel.sql.ast.DropTableStatement
import kestrel.sql.ast.DropTriggerStatement
import kestrel.sql.ast.DropViewStatement
import kestrel.sql.ast.ExecuteStatement
import kestrel.sql.ast.ExistsExpression
import kestrel.sql.ast.ExplainStatement
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.FrameBoundType
import kestrel.sql.ast.FrameExclude
import kestrel.sql.ast.FrameUnit
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.GroupingSetsSpec
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.InSubqueryExpression
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.JoinType
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.MergeMatchedDelete
import kestrel.sql.ast.MergeMatchedUpdate
import kestrel.sql.ast.MergeNotMatchedInsert
import kestrel.sql.ast.MergeStatement
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.OrderKey
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.PrepareStatement
import kestrel.sql.ast.QueryStatement
import kestrel.sql.ast.RenameColumn
import kestrel.sql.ast.RenameTable
import kestrel.sql.ast.RollupSpec
import kestrel.sql.ast.ScalarSubqueryExpression
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.SetOperationStatement
import kestrel.sql.ast.SetOperator
import kestrel.sql.ast.StarProjection
import kestrel.sql.ast.Statement
import kestrel.sql.ast.SubquerySource
import kestrel.sql.ast.TableSource
import kestrel.sql.ast.TriggerEvent
import kestrel.sql.ast.TriggerTiming
import kestrel.sql.ast.TruncateTableStatement
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UnaryOperator
import kestrel.sql.ast.UpdateStatement
import kestrel.sql.ast.WindowExpression
import kestrel.sql.ast.WindowFunctionKind
import kestrel.sql.ast.WithQuery
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Collation
import kestrel.storage.ColumnType
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {
    private fun parse(sql: String): List<Statement> = Parser(Lexer(sql).tokenize()).parseProgram()

    private fun parseOne(sql: String): Statement = parse(sql).single()

    private fun intLit(value: Long) = LiteralExpression(Value.IntegerValue(value))

    private fun whereOf(sql: String): Expression = (parseOne(sql) as SelectStatement).where!!

    private fun tableName(select: QueryStatement): String = ((select as SelectStatement).from as TableSource).name

    private fun firstProjection(sql: String): Expression =
        ((parseOne(sql) as SelectStatement).projection as ExpressionProjection).items.first().expression

    @Test
    fun parsesCreateTableWithConstraints() {
        val statement = parseOne("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, age INTEGER)")
        assertTrue(statement is CreateTableStatement)
        assertEquals("users", statement.tableName)
        assertEquals(3, statement.columns.size)
        val id = statement.columns[0]
        assertEquals(ColumnType.Integer, id.type)
        assertTrue(id.primaryKey)
        assertTrue(id.notNull)
        val name = statement.columns[1]
        assertTrue(name.notNull)
        assertTrue(!name.primaryKey)
        assertTrue(!statement.columns[2].notNull)
    }

    @Test
    fun parsesCreateTableWithoutTempIsNotTemporary() {
        val statement = parseOne("CREATE TABLE users (id INTEGER PRIMARY KEY)") as CreateTableStatement
        assertTrue(!statement.temporary)
    }

    @Test
    fun parsesCreateTempTable() {
        val statement = parseOne("CREATE TEMP TABLE scratch (id INTEGER PRIMARY KEY, note TEXT)") as CreateTableStatement
        assertTrue(statement.temporary)
        assertEquals("scratch", statement.tableName)
        assertEquals(2, statement.columns.size)
    }

    @Test
    fun parsesCreateTemporaryTable() {
        val statement = parseOne("CREATE TEMPORARY TABLE scratch (id INTEGER PRIMARY KEY)") as CreateTableStatement
        assertTrue(statement.temporary)
    }

    @Test
    fun tempKeywordIsCaseInsensitive() {
        val statement = parseOne("create temp table scratch (id integer primary key)") as CreateTableStatement
        assertTrue(statement.temporary)
    }

    @Test
    fun tempOnAnythingOtherThanTableIsAParseError() {
        assertFailsWith<ParseException> { parse("CREATE TEMP VIEW v AS SELECT 1") }
        assertFailsWith<ParseException> { parse("CREATE TEMP INDEX idx ON t (a)") }
    }

    @Test
    fun parsesInsertWithoutColumnList() {
        val statement = parseOne("INSERT INTO users VALUES (1, 'ada', 36)")
        assertTrue(statement is InsertStatement)
        assertNull(statement.columns)
        assertEquals(1, statement.rows.size)
        assertEquals(3, statement.rows[0].size)
    }

    @Test
    fun parsesInsertWithColumnListAndMultipleRows() {
        val statement = parseOne("INSERT INTO t (a, b) VALUES (1, 2), (3, 4), (5, 6)") as InsertStatement
        assertEquals(listOf("a", "b"), statement.columns)
        assertEquals(3, statement.rows.size)
    }

    @Test
    fun parsesInsertValuesWithLiteralDefaultKeyword() {
        val statement = parseOne("INSERT INTO t (a, b, c) VALUES (1, DEFAULT, 3)") as InsertStatement
        assertEquals(3, statement.rows[0].size)
        assertNotNull(statement.rows[0][0])
        assertNull(statement.rows[0][1])
        assertNotNull(statement.rows[0][2])
    }

    @Test
    fun parsesSelectStar() {
        val statement = parseOne("SELECT * FROM users") as SelectStatement
        assertEquals(StarProjection, statement.projection)
        assertEquals("users", tableName(statement))
        assertNull(statement.where)
        assertNull(statement.orderBy)
        assertNull(statement.limit)
    }

    @Test
    fun parsesSelectWithAliasWhereOrderLimit() {
        val statement = parseOne(
            "SELECT name AS who, age FROM users WHERE age > 40 ORDER BY age DESC LIMIT 5"
        ) as SelectStatement
        val projection = statement.projection as ExpressionProjection
        assertEquals("who", projection.items[0].alias)
        assertEquals(true, statement.orderBy!!.keys.single().descending)
        assertEquals(5, statement.limit)
    }

    @Test
    fun parsesLimitFollowedByOffset() {
        val statement = parseOne("SELECT id FROM users LIMIT 10 OFFSET 20") as SelectStatement
        assertEquals(10, statement.limit)
        assertEquals(20, statement.offset)
    }

    @Test
    fun parsesOffsetFollowedByLimitInEitherOrder() {
        val statement = parseOne("SELECT id FROM users OFFSET 20 LIMIT 10") as SelectStatement
        assertEquals(10, statement.limit)
        assertEquals(20, statement.offset)
    }

    @Test
    fun parsesOffsetAloneWithoutLimit() {
        val statement = parseOne("SELECT id FROM users OFFSET 5") as SelectStatement
        assertNull(statement.limit)
        assertEquals(5, statement.offset)
    }

    @Test
    fun offsetDefaultsToNullWhenAbsent() {
        val statement = parseOne("SELECT id FROM users LIMIT 5") as SelectStatement
        assertNull(statement.offset)
    }

    @Test
    fun rejectsARepeatedLimitOrOffsetClause() {
        assertFailsWith<ParseException> { parse("SELECT id FROM users LIMIT 5 LIMIT 10") }
        assertFailsWith<ParseException> { parse("SELECT id FROM users OFFSET 5 OFFSET 10") }
    }

    @Test
    fun rejectsANonIntegerAfterOffset() {
        assertFailsWith<ParseException> { parse("SELECT id FROM users OFFSET 'five'") }
    }

    @Test
    fun parsesOrderByWithMultipleKeysAndIndependentDirections() {
        val statement = parseOne("SELECT name FROM users ORDER BY dept ASC, age DESC, id") as SelectStatement
        val keys = statement.orderBy!!.keys
        assertEquals(3, keys.size)
        assertEquals("dept", (keys[0].expression as ColumnExpression).name)
        assertEquals(false, keys[0].descending)
        assertEquals("age", (keys[1].expression as ColumnExpression).name)
        assertEquals(true, keys[1].descending)
        assertEquals("id", (keys[2].expression as ColumnExpression).name)
        assertEquals(false, keys[2].descending)
    }

    @Test
    fun parsesOrderByWithExplicitNullsFirstAndNullsLastPerKey() {
        val statement = parseOne(
            "SELECT name FROM users ORDER BY dept NULLS FIRST, age DESC NULLS LAST, id"
        ) as SelectStatement
        val keys = statement.orderBy!!.keys
        assertEquals(3, keys.size)
        assertEquals(false, keys[0].descending)
        assertEquals(true, keys[0].nullsFirst)
        assertEquals(true, keys[1].descending)
        assertEquals(false, keys[1].nullsFirst)
        assertEquals(false, keys[2].descending)
        assertNull(keys[2].nullsFirst)
    }

    @Test
    fun nullsAndFirstAndLastRemainUsableAsOrdinaryColumnNamesOutsideTheNullsClause() {
        val statement = parseOne("SELECT id FROM users ORDER BY nulls DESC") as SelectStatement
        val key = statement.orderBy!!.keys.single()
        assertEquals(ColumnExpression("nulls"), key.expression)
        assertEquals(true, key.descending)
        assertNull(key.nullsFirst)
    }

    @Test
    fun nullsClauseWithoutFirstOrLastFailsToParse() {
        assertFailsWith<ParseException> { parseOne("SELECT id FROM users ORDER BY age NULLS") }
    }

    @Test
    fun parsesDeleteAndDropAndUpdate() {
        assertTrue(parseOne("DELETE FROM t WHERE id = 1") is DeleteStatement)
        assertTrue(parseOne("DROP TABLE t") is DropTableStatement)
        val update = parseOne("UPDATE t SET a = 1, b = a + 2 WHERE id = 3") as UpdateStatement
        assertEquals(2, update.assignments.size)
        assertEquals(Assignment("a", intLit(1)), update.assignments[0])
    }

    @Test
    fun updateSetToDefaultParsesTheAssignmentValueAsNull() {
        val update = parseOne("UPDATE t SET a = DEFAULT, b = 2 WHERE id = 3") as UpdateStatement
        assertEquals(Assignment("a", null), update.assignments[0])
        assertEquals(Assignment("b", intLit(2)), update.assignments[1])
    }

    @Test
    fun insertWithoutReturningLeavesItNull() {
        val statement = parseOne("INSERT INTO t VALUES (1)") as InsertStatement
        assertNull(statement.returning)
    }

    @Test
    fun insertReturningStarParsesAsStarProjection() {
        val statement = parseOne("INSERT INTO t VALUES (1) RETURNING *") as InsertStatement
        assertEquals(StarProjection, statement.returning)
    }

    @Test
    fun insertReturningExpressionListWithAliasParsesLikeASelectProjection() {
        val statement = parseOne("INSERT INTO t (a, b) VALUES (1, 2) RETURNING a, b + 1 AS bplus") as InsertStatement
        val projection = statement.returning as ExpressionProjection
        assertEquals(2, projection.items.size)
        assertEquals("a", (projection.items[0].expression as ColumnExpression).name)
        assertNull(projection.items[0].alias)
        assertEquals("bplus", projection.items[1].alias)
    }

    @Test
    fun insertOnConflictReturningParsesReturningAfterTheConflictClause() {
        val statement = parseOne(
            "INSERT INTO t (id) VALUES (1) ON CONFLICT (id) DO NOTHING RETURNING id"
        ) as InsertStatement
        assertNotNull(statement.onConflict)
        val projection = statement.returning as ExpressionProjection
        assertEquals("id", (projection.items.single().expression as ColumnExpression).name)
    }

    @Test
    fun updateReturningParsesAfterWhere() {
        val statement = parseOne("UPDATE t SET a = 1 WHERE id = 3 RETURNING id, a") as UpdateStatement
        val projection = statement.returning as ExpressionProjection
        assertEquals(2, projection.items.size)
    }

    @Test
    fun updateReturningWithoutWhereStillParses() {
        val statement = parseOne("UPDATE t SET a = 1 RETURNING *") as UpdateStatement
        assertNull(statement.where)
        assertEquals(StarProjection, statement.returning)
    }

    @Test
    fun deleteReturningParsesAfterWhere() {
        val statement = parseOne("DELETE FROM t WHERE id = 3 RETURNING id") as DeleteStatement
        val projection = statement.returning as ExpressionProjection
        assertEquals("id", (projection.items.single().expression as ColumnExpression).name)
    }

    @Test
    fun deleteReturningWithoutWhereStillParses() {
        val statement = parseOne("DELETE FROM t RETURNING *") as DeleteStatement
        assertNull(statement.where)
        assertEquals(StarProjection, statement.returning)
    }

    @Test
    fun multipleStatementsAreSeparatedBySemicolons() {
        val statements = parse("CREATE TABLE t (a INTEGER); DROP TABLE t;")
        assertEquals(2, statements.size)
    }

    @Test
    fun arithmeticHonoursPrecedence() {
        val expression = firstProjection("SELECT 1 + 2 * 3 FROM t")
        val expected = BinaryExpression(
            BinaryOperator.Add,
            intLit(1),
            BinaryExpression(BinaryOperator.Multiply, intLit(2), intLit(3))
        )
        assertEquals(expected, expression)
    }

    @Test
    fun notBindsTighterThanAnd() {
        val expression = whereOf("SELECT 1 FROM t WHERE NOT a AND b")
        val expected = BinaryExpression(
            BinaryOperator.And,
            UnaryExpression(UnaryOperator.Not, ColumnExpression("a")),
            ColumnExpression("b")
        )
        assertEquals(expected, expression)
    }

    @Test
    fun andBindsTighterThanOr() {
        val expression = whereOf("SELECT 1 FROM t WHERE a OR b AND c")
        val expected = BinaryExpression(
            BinaryOperator.Or,
            ColumnExpression("a"),
            BinaryExpression(BinaryOperator.And, ColumnExpression("b"), ColumnExpression("c"))
        )
        assertEquals(expected, expression)
    }

    @Test
    fun parenthesesOverridePrecedence() {
        val expression = firstProjection("SELECT (1 + 2) * 3 FROM t")
        val expected = BinaryExpression(
            BinaryOperator.Multiply,
            BinaryExpression(BinaryOperator.Add, intLit(1), intLit(2)),
            intLit(3)
        )
        assertEquals(expected, expression)
    }

    @Test
    fun reportsMissingProjectionWithPosition() {
        val exception = assertFailsWith<ParseException> { parse("SELECT FROM t") }
        assertEquals(1, exception.line)
        assertEquals(8, exception.column)
    }

    @Test
    fun reportsMissingClosingParen() {
        assertFailsWith<ParseException> { parse("CREATE TABLE t (a INTEGER") }
    }

    @Test
    fun reportsUnknownStatement() {
        assertFailsWith<ParseException> { parse("EXPLAIN users") }
    }

    @Test
    fun parsesScalarFunctionCallWithMultipleArguments() {
        val expression = firstProjection("SELECT SUBSTR(name, 1, 3) FROM t")
        assertEquals(
            CallExpression("SUBSTR", listOf(ColumnExpression("name"), intLit(1), intLit(3))),
            expression
        )
    }

    @Test
    fun parsesScalarFunctionCallWithNoArguments() {
        assertEquals(CallExpression("NOW", emptyList()), firstProjection("SELECT NOW() FROM t"))
    }

    @Test
    fun aggregateFunctionWithWrongArityFails() {
        assertFailsWith<ParseException> { parse("SELECT SUM(a, b) FROM t") }
        assertFailsWith<ParseException> { parse("SELECT COUNT() FROM t") }
    }

    @Test
    fun stringAggParsesTheValueAndSeparatorWithNoOrderByLeavingItEmpty() {
        val expression = firstProjection("SELECT STRING_AGG(name, ', ') FROM t")
        assertEquals(
            FunctionExpression(
                "STRING_AGG",
                ColumnExpression("name"),
                star = false,
                separator = LiteralExpression(Value.TextValue(", ")),
                orderBy = emptyList()
            ),
            expression
        )
    }

    @Test
    fun stringAggParsesAnInternalOrderByWithMultipleKeysAndExplicitDirections() {
        val expression = firstProjection("SELECT STRING_AGG(name, ',' ORDER BY dept ASC, name DESC) FROM t")
        assertEquals(
            FunctionExpression(
                "STRING_AGG",
                ColumnExpression("name"),
                star = false,
                separator = LiteralExpression(Value.TextValue(",")),
                orderBy = listOf(
                    OrderKey(ColumnExpression("dept"), descending = false),
                    OrderKey(ColumnExpression("name"), descending = true)
                )
            ),
            expression
        )
    }

    @Test
    fun stringAggParsesAnExplicitNullsLastInsideItsInternalOrderBy() {
        val expression = firstProjection("SELECT STRING_AGG(name, ',' ORDER BY dept NULLS LAST) FROM t")
        assertEquals(
            FunctionExpression(
                "STRING_AGG",
                ColumnExpression("name"),
                star = false,
                separator = LiteralExpression(Value.TextValue(",")),
                orderBy = listOf(OrderKey(ColumnExpression("dept"), descending = false, nullsFirst = false))
            ),
            expression
        )
    }

    @Test
    fun stringAggRequiresBothAValueAndASeparatorArgument() {
        assertFailsWith<ParseException> { parse("SELECT STRING_AGG(name) FROM t") }
        assertFailsWith<ParseException> { parse("SELECT STRING_AGG() FROM t") }
    }

    @Test
    fun stringAggCannotBeUsedAsAWindowFunction() {
        assertFailsWith<ParseException> { parse("SELECT STRING_AGG(name, ',') OVER () FROM t") }
    }

    @Test
    fun countDistinctSetsTheDistinctFlagWithTheGivenArgumentAsTheSoleArgument() {
        val expression = firstProjection("SELECT COUNT(DISTINCT dept) FROM t")
        assertEquals(
            FunctionExpression("COUNT", ColumnExpression("dept"), star = false, distinct = true),
            expression
        )
    }

    @Test
    fun sumAvgMinMaxAcceptDistinctTooNotJustCount() {
        for (name in listOf("SUM", "AVG", "MIN", "MAX")) {
            val expression = firstProjection("SELECT $name(DISTINCT amount) FROM t")
            assertEquals(
                FunctionExpression(name, ColumnExpression("amount"), star = false, distinct = true),
                expression
            )
        }
    }

    @Test
    fun plainAggregateCallsDefaultDistinctToFalse() {
        val expression = firstProjection("SELECT COUNT(dept) FROM t") as FunctionExpression
        assertEquals(false, expression.distinct)
    }

    @Test
    fun countDistinctStarIsRejected() {
        assertFailsWith<ParseException> { parse("SELECT COUNT(DISTINCT *) FROM t") }
    }

    @Test
    fun countDistinctWithMultipleArgumentsIsRejected() {
        assertFailsWith<ParseException> { parse("SELECT COUNT(DISTINCT dept, region) FROM t") }
    }

    @Test
    fun distinctAggregateCannotBeUsedAsAWindowFunction() {
        assertFailsWith<ParseException> { parse("SELECT COUNT(DISTINCT dept) OVER () FROM t") }
        assertFailsWith<ParseException> { parse("SELECT SUM(DISTINCT amount) OVER () FROM t") }
    }

    @Test
    fun aggregateFilterSetsTheFilterFieldToTheWhereCondition() {
        val expression = firstProjection("SELECT COUNT(dept) FILTER (WHERE amount > 100) FROM t")
        assertEquals(
            FunctionExpression(
                "COUNT",
                ColumnExpression("dept"),
                star = false,
                filter = BinaryExpression(BinaryOperator.Greater, ColumnExpression("amount"), LiteralExpression(Value.IntegerValue(100)))
            ),
            expression
        )
    }

    @Test
    fun countStarFilterKeepsStarTrueAlongsideTheFilter() {
        val expression = firstProjection("SELECT COUNT(*) FILTER (WHERE amount > 0) FROM t") as FunctionExpression
        assertEquals(true, expression.star)
        assertEquals(true, expression.filter != null)
    }

    @Test
    fun plainAggregateCallsDefaultFilterToNull() {
        val expression = firstProjection("SELECT COUNT(dept) FROM t") as FunctionExpression
        assertEquals(null, expression.filter)
    }

    @Test
    fun filterCanBeCombinedWithDistinct() {
        val expression = firstProjection("SELECT COUNT(DISTINCT dept) FILTER (WHERE amount > 0) FROM t") as FunctionExpression
        assertEquals(true, expression.distinct)
        assertEquals(true, expression.filter != null)
    }

    @Test
    fun filterRequiresAWhereClauseInsideItsParentheses() {
        assertFailsWith<ParseException> { parse("SELECT COUNT(dept) FILTER (amount > 100) FROM t") }
    }

    @Test
    fun filterIsRejectedOnNonAggregateFunctions() {
        assertFailsWith<ParseException> { parse("SELECT UPPER(dept) FILTER (WHERE amount > 0) FROM t") }
    }

    @Test
    fun filterCannotBeUsedWithAWindowFunction() {
        assertFailsWith<ParseException> { parse("SELECT COUNT(dept) FILTER (WHERE amount > 0) OVER () FROM t") }
    }

    @Test
    fun filterColumnNamedFilterIsStillUsableAsAPlainIdentifier() {
        val expression = firstProjection("SELECT filter FROM t") as ColumnExpression
        assertEquals("filter", expression.name)
    }

    // LEFT/RIGHT are otherwise reserved (LEFT/RIGHT JOIN), so LEFT(str, n)/RIGHT(str, n) as scalar
    // functions each need their own disambiguation in the parser.
    @Test
    fun leftParsesAsAScalarFunctionCallDespiteAlsoBeingTheLeftJoinKeyword() {
        val expression = firstProjection("SELECT LEFT(name, 3) FROM t")
        assertEquals(CallExpression("LEFT", listOf(ColumnExpression("name"), intLit(3))), expression)
    }

    @Test
    fun rightParsesAsAScalarFunctionCallDespiteAlsoBeingTheRightJoinKeyword() {
        val expression = firstProjection("SELECT RIGHT(name, 3) FROM t")
        assertEquals(CallExpression("RIGHT", listOf(ColumnExpression("name"), intLit(3))), expression)
    }

    @Test
    fun leftJoinStillParsesAsAJoinNotAFunctionCall() {
        val statement = parseOne("SELECT * FROM t LEFT JOIN u ON t.id = u.id") as SelectStatement
        assertEquals(1, statement.joins.size)
        assertEquals(JoinType.Left, statement.joins[0].type)
    }

    @Test
    fun bareLeftKeywordWithoutAFollowingParenIsAParseError() {
        assertFailsWith<ParseException> { parse("SELECT LEFT FROM t") }
    }

    @Test
    fun bareRightKeywordWithoutAFollowingParenIsAParseError() {
        assertFailsWith<ParseException> { parse("SELECT RIGHT FROM t") }
    }

    @Test
    fun rightJoinAndFullJoinParseWithOptionalOuter() {
        val right = parseOne("SELECT * FROM t RIGHT JOIN u ON t.id = u.id") as SelectStatement
        assertEquals(JoinType.Right, right.joins[0].type)
        val rightOuter = parseOne("SELECT * FROM t RIGHT OUTER JOIN u ON t.id = u.id") as SelectStatement
        assertEquals(JoinType.Right, rightOuter.joins[0].type)
        val full = parseOne("SELECT * FROM t FULL JOIN u ON t.id = u.id") as SelectStatement
        assertEquals(JoinType.Full, full.joins[0].type)
        val fullOuter = parseOne("SELECT * FROM t FULL OUTER JOIN u ON t.id = u.id") as SelectStatement
        assertEquals(JoinType.Full, fullOuter.joins[0].type)
    }

    @Test
    fun rightAndFullJoinLateralAreRejected() {
        assertFailsWith<ParseException> {
            parse("SELECT * FROM t RIGHT JOIN LATERAL (SELECT * FROM u WHERE u.id = t.id) x ON true")
        }
        assertFailsWith<ParseException> {
            parse("SELECT * FROM t FULL JOIN LATERAL (SELECT * FROM u WHERE u.id = t.id) x ON true")
        }
    }

    @Test
    fun crossJoinParsesWithoutAnOnClauseAndOnIsNull() {
        val statement = parseOne("SELECT * FROM t CROSS JOIN u") as SelectStatement
        assertEquals(JoinType.Cross, statement.joins[0].type)
        assertEquals(null, statement.joins[0].on)
    }

    @Test
    fun crossJoinWithAnExplicitOnClauseIsAParseError() {
        assertFailsWith<ParseException> { parse("SELECT * FROM t CROSS JOIN u ON t.id = u.id") }
    }

    @Test
    fun crossJoinLateralIsAccepted() {
        val statement = parseOne(
            "SELECT * FROM t CROSS JOIN LATERAL (SELECT * FROM u WHERE u.id = t.id) x"
        ) as SelectStatement
        assertEquals(JoinType.Cross, statement.joins[0].type)
        assertEquals(null, statement.joins[0].on)
    }

    @Test
    fun parsesIsNullAndIsNotNull() {
        assertEquals(IsNullExpression(ColumnExpression("age"), negated = false), whereOf("SELECT 1 FROM t WHERE age IS NULL"))
        assertEquals(
            IsNullExpression(ColumnExpression("age"), negated = true),
            whereOf("SELECT 1 FROM t WHERE age IS NOT NULL")
        )
    }

    @Test
    fun parsesInAndNotIn() {
        assertEquals(
            InExpression(ColumnExpression("id"), listOf(intLit(1), intLit(2), intLit(3)), negated = false),
            whereOf("SELECT 1 FROM t WHERE id IN (1, 2, 3)")
        )
        assertEquals(
            InExpression(ColumnExpression("id"), listOf(intLit(1)), negated = true),
            whereOf("SELECT 1 FROM t WHERE id NOT IN (1)")
        )
    }

    @Test
    fun parsesBetweenAndNotBetween() {
        assertEquals(
            BetweenExpression(ColumnExpression("age"), intLit(18), intLit(65), negated = false),
            whereOf("SELECT 1 FROM t WHERE age BETWEEN 18 AND 65")
        )
        assertEquals(
            BetweenExpression(ColumnExpression("age"), intLit(18), intLit(65), negated = true),
            whereOf("SELECT 1 FROM t WHERE age NOT BETWEEN 18 AND 65")
        )
    }

    @Test
    fun parsesLikeAndNotLike() {
        assertEquals(
            LikeExpression(ColumnExpression("name"), LiteralExpression(Value.TextValue("a%")), negated = false),
            whereOf("SELECT 1 FROM t WHERE name LIKE 'a%'")
        )
        assertEquals(
            LikeExpression(ColumnExpression("name"), LiteralExpression(Value.TextValue("a%")), negated = true),
            whereOf("SELECT 1 FROM t WHERE name NOT LIKE 'a%'")
        )
    }

    @Test
    fun notInWithoutInOrBetweenOrLikeFails() {
        assertFailsWith<ParseException> { parse("SELECT 1 FROM t WHERE id NOT = 1") }
    }

    @Test
    fun parsesSearchedCaseExpression() {
        val expression = firstProjection(
            "SELECT CASE WHEN age < 18 THEN 'minor' WHEN age < 65 THEN 'adult' ELSE 'senior' END FROM t"
        ) as CaseExpression
        assertNull(expression.operand)
        assertEquals(2, expression.branches.size)
        assertEquals(LiteralExpression(Value.TextValue("senior")), expression.elseExpression)
    }

    @Test
    fun parsesSimpleCaseExpressionWithoutElse() {
        val expression = firstProjection("SELECT CASE grade WHEN 1 THEN 'a' END FROM t") as CaseExpression
        assertEquals(ColumnExpression("grade"), expression.operand)
        assertEquals(1, expression.branches.size)
        assertNull(expression.elseExpression)
    }

    @Test
    fun caseWithoutWhenClauseFails() {
        assertFailsWith<ParseException> { parse("SELECT CASE WHEN THEN 1 END FROM t") }
        assertFailsWith<ParseException> { parse("SELECT CASE ELSE 1 END FROM t") }
    }

    @Test
    fun caseMissingEndFails() {
        assertFailsWith<ParseException> { parse("SELECT CASE WHEN 1 THEN 2 FROM t") }
    }

    @Test
    fun parsesScalarSubqueryInWhereClause() {
        val expression = whereOf("SELECT 1 FROM t WHERE age > (SELECT AVG(age) FROM t)") as BinaryExpression
        val subquery = expression.right as ScalarSubqueryExpression
        assertEquals("t", tableName(subquery.select))
        assertEquals(BinaryOperator.Greater, expression.operator)
    }

    @Test
    fun parsesInSubquery() {
        val expression = whereOf("SELECT 1 FROM t WHERE id IN (SELECT id FROM other)") as InSubqueryExpression
        assertEquals(ColumnExpression("id"), expression.operand)
        assertEquals("other", tableName(expression.select))
        assertEquals(false, expression.negated)
    }

    @Test
    fun parsesNotInSubquery() {
        val expression = whereOf("SELECT 1 FROM t WHERE id NOT IN (SELECT id FROM other)") as InSubqueryExpression
        assertEquals(true, expression.negated)
    }

    @Test
    fun parsesExistsAndNotExists() {
        val exists = whereOf("SELECT 1 FROM t WHERE EXISTS (SELECT 1 FROM other)") as ExistsExpression
        assertEquals("other", tableName(exists.select))
        val notExists = whereOf("SELECT 1 FROM t WHERE NOT EXISTS (SELECT 1 FROM other)") as UnaryExpression
        assertEquals(UnaryOperator.Not, notExists.operator)
        assertEquals("other", tableName((notExists.operand as ExistsExpression).select))
    }

    @Test
    fun parenthesizedNonSelectExpressionIsStillJustGrouping() {
        assertEquals(intLit(1), firstProjection("SELECT (1) FROM t"))
    }

    @Test
    fun parsesCreateView() {
        val statement = parseOne("CREATE VIEW adults AS SELECT id, name FROM users WHERE age >= 18") as CreateViewStatement
        assertEquals("adults", statement.viewName)
        assertEquals(false, statement.orReplace)
        assertEquals("users", tableName(statement.query))
    }

    @Test
    fun parsesCreateOrReplaceView() {
        val statement = parseOne("CREATE OR REPLACE VIEW adults AS SELECT id FROM users") as CreateViewStatement
        assertEquals(true, statement.orReplace)
    }

    @Test
    fun orReplaceOnNonViewFails() {
        assertFailsWith<ParseException> { parseOne("CREATE OR REPLACE TABLE t (id INTEGER)") }
    }

    @Test
    fun parsesDropView() {
        val statement = parseOne("DROP VIEW adults") as DropViewStatement
        assertEquals("adults", statement.viewName)
        assertEquals(DropMode.Restrict, statement.mode)
    }

    @Test
    fun dropTableAndDropViewAcceptExplicitCascadeAndRestrict() {
        assertEquals(DropMode.Cascade, (parseOne("DROP TABLE t CASCADE") as DropTableStatement).mode)
        assertEquals(DropMode.Restrict, (parseOne("DROP TABLE t RESTRICT") as DropTableStatement).mode)
        assertEquals(DropMode.Cascade, (parseOne("DROP VIEW v CASCADE") as DropViewStatement).mode)
        assertEquals(DropMode.Restrict, (parseOne("DROP VIEW v RESTRICT") as DropViewStatement).mode)
    }

    @Test
    fun parsesTruncateWithAndWithoutTheTableKeyword() {
        val withKeyword = parseOne("TRUNCATE TABLE t") as TruncateTableStatement
        assertEquals(listOf("t"), withKeyword.tableNames)
        assertEquals(false, withKeyword.restartIdentity)
        assertEquals(DropMode.Restrict, withKeyword.mode)

        val withoutKeyword = parseOne("TRUNCATE t") as TruncateTableStatement
        assertEquals(listOf("t"), withoutKeyword.tableNames)
    }

    @Test
    fun parsesTruncateWithMultipleTableNames() {
        val statement = parseOne("TRUNCATE TABLE a, b, c") as TruncateTableStatement
        assertEquals(listOf("a", "b", "c"), statement.tableNames)
    }

    @Test
    fun parsesTruncateRestartIdentityAndContinueIdentity() {
        assertEquals(true, (parseOne("TRUNCATE TABLE t RESTART IDENTITY") as TruncateTableStatement).restartIdentity)
        assertEquals(false, (parseOne("TRUNCATE TABLE t CONTINUE IDENTITY") as TruncateTableStatement).restartIdentity)
        assertEquals(false, (parseOne("TRUNCATE TABLE t") as TruncateTableStatement).restartIdentity)
    }

    @Test
    fun parsesTruncateCascadeAndRestrictAfterAnIdentityClause() {
        val statement = parseOne("TRUNCATE TABLE t RESTART IDENTITY CASCADE") as TruncateTableStatement
        assertEquals(true, statement.restartIdentity)
        assertEquals(DropMode.Cascade, statement.mode)
    }

    @Test
    fun parsesMergeWithATableSourceAndAnUpdateClause() {
        val statement = parseOne(
            "MERGE INTO accounts USING staging ON accounts.id = staging.id " +
                "WHEN MATCHED THEN UPDATE SET balance = staging.balance"
        ) as MergeStatement
        assertEquals("accounts", statement.targetTable)
        assertNull(statement.targetAlias)
        val source = statement.source as TableSource
        assertEquals("staging", source.name)
        assertNull(source.alias)
        assertTrue(statement.onCondition is BinaryExpression)
        assertEquals(1, statement.whenClauses.size)
        val clause = statement.whenClauses.single() as MergeMatchedUpdate
        assertNull(clause.condition)
        assertEquals(listOf(Assignment("balance", ColumnExpression("balance", "staging"))), clause.assignments)
    }

    @Test
    fun parsesMergeTargetAndSourceAliasesWithAndWithoutAs() {
        val explicitAs = parseOne(
            "MERGE INTO accounts AS acc USING staging AS s ON acc.id = s.id WHEN MATCHED THEN DELETE"
        ) as MergeStatement
        assertEquals("acc", explicitAs.targetAlias)
        assertEquals("s", (explicitAs.source as TableSource).alias)

        val bareAlias = parseOne(
            "MERGE INTO accounts acc USING staging s ON acc.id = s.id WHEN MATCHED THEN DELETE"
        ) as MergeStatement
        assertEquals("acc", bareAlias.targetAlias)
        assertEquals("s", (bareAlias.source as TableSource).alias)
    }

    @Test
    fun parsesMergeUsingADerivedTableSubquerySource() {
        val statement = parseOne(
            "MERGE INTO accounts USING (SELECT id FROM staging) AS s ON accounts.id = s.id WHEN MATCHED THEN DELETE"
        ) as MergeStatement
        val source = statement.source as SubquerySource
        assertEquals("s", source.alias)
        assertTrue(source.select is SelectStatement)
    }

    @Test
    fun parsesMergeWhenMatchedDeleteWithAnAndCondition() {
        val statement = parseOne(
            "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED AND s.flag THEN DELETE"
        ) as MergeStatement
        val clause = statement.whenClauses.single() as MergeMatchedDelete
        assertEquals(ColumnExpression("flag", "s"), clause.condition)
    }

    @Test
    fun parsesMergeWhenNotMatchedInsertWithAndWithoutAColumnList() {
        val withColumns = parseOne(
            "MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN INSERT (id, label) VALUES (s.id, s.label)"
        ) as MergeStatement
        val withColumnsClause = withColumns.whenClauses.single() as MergeNotMatchedInsert
        assertEquals(listOf("id", "label"), withColumnsClause.columns)
        assertEquals(listOf(ColumnExpression("id", "s"), ColumnExpression("label", "s")), withColumnsClause.values)

        val withoutColumns = parseOne(
            "MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN INSERT VALUES (s.id, DEFAULT)"
        ) as MergeStatement
        val withoutColumnsClause = withoutColumns.whenClauses.single() as MergeNotMatchedInsert
        assertNull(withoutColumnsClause.columns)
        assertEquals(listOf<Expression?>(ColumnExpression("id", "s"), null), withoutColumnsClause.values)
    }

    @Test
    fun parsesMergeWhenNotMatchedWithAnAndCondition() {
        val statement = parseOne(
            "MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED AND s.active THEN INSERT (id) VALUES (s.id)"
        ) as MergeStatement
        val clause = statement.whenClauses.single() as MergeNotMatchedInsert
        assertEquals(ColumnExpression("active", "s"), clause.condition)
    }

    @Test
    fun parsesMergeWithMultipleWhenClausesInWrittenOrder() {
        val statement = parseOne(
            "MERGE INTO t USING s ON t.id = s.id " +
                "WHEN MATCHED AND s.gone THEN DELETE " +
                "WHEN MATCHED THEN UPDATE SET v = s.v " +
                "WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id)"
        ) as MergeStatement
        assertEquals(3, statement.whenClauses.size)
        assertTrue(statement.whenClauses[0] is MergeMatchedDelete)
        assertTrue(statement.whenClauses[1] is MergeMatchedUpdate)
        assertTrue(statement.whenClauses[2] is MergeNotMatchedInsert)
    }

    @Test
    fun mergeWithNoWhenClauseFailsToParse() {
        assertFailsWith<ParseException> { parseOne("MERGE INTO t USING s ON t.id = s.id") }
    }

    @Test
    fun mergeWhenMatchedRequiresUpdateOrDelete() {
        assertFailsWith<ParseException> { parseOne("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN INSERT (id) VALUES (s.id)") }
    }

    @Test
    fun mergeWhenNotMatchedOnlySupportsInsert() {
        assertFailsWith<ParseException> { parseOne("MERGE INTO t USING s ON t.id = s.id WHEN NOT MATCHED THEN DELETE") }
    }

    @Test
    fun mergeWithoutReturningLeavesItNull() {
        val statement = parseOne("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE") as MergeStatement
        assertNull(statement.returning)
    }

    @Test
    fun mergeReturningStarParsesAsStarProjectionAfterTheLastWhenClause() {
        val statement = parseOne(
            "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE RETURNING *"
        ) as MergeStatement
        assertEquals(StarProjection, statement.returning)
    }

    @Test
    fun mergeReturningExpressionListParsesLikeASelectProjection() {
        val statement = parseOne(
            "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN DELETE RETURNING id, MERGE_ACTION() AS kind"
        ) as MergeStatement
        val projection = statement.returning as ExpressionProjection
        assertEquals(2, projection.items.size)
        assertEquals("id", (projection.items[0].expression as ColumnExpression).name)
        assertNull(projection.items[0].alias)
        assertEquals(MergeActionExpression, projection.items[1].expression)
        assertEquals("kind", projection.items[1].alias)
    }

    @Test
    fun mergeActionParsesAsItsOwnExpressionNode() {
        val select = parseOne("SELECT MERGE_ACTION() FROM t") as SelectStatement
        val items = (select.projection as ExpressionProjection).items
        assertEquals(MergeActionExpression, items.single().expression)
    }

    @Test
    fun mergeActionTakesNoArguments() {
        assertFailsWith<ParseException> { parse("SELECT MERGE_ACTION(1)") }
        assertFailsWith<ParseException> { parse("SELECT MERGE_ACTION('x')") }
    }

    @Test
    fun parsesDecimalColumnWithAndWithoutPrecisionAndScale() {
        val statement = parseOne(
            "CREATE TABLE t (bare DECIMAL, scaled DECIMAL(10), precise DECIMAL(10, 2))"
        ) as CreateTableStatement
        assertEquals(ColumnType.Decimal, statement.columns[0].type)
        assertNull(statement.columns[0].precision)
        assertNull(statement.columns[0].scale)
        assertEquals(10, statement.columns[1].precision)
        assertEquals(0, statement.columns[1].scale)
        assertEquals(10, statement.columns[2].precision)
        assertEquals(2, statement.columns[2].scale)
    }

    @Test
    fun parsesDateColumn() {
        val statement = parseOne("CREATE TABLE t (born DATE)") as CreateTableStatement
        assertEquals(ColumnType.Date, statement.columns[0].type)
    }

    @Test
    fun parsesCastExpressionWithAndWithoutPrecisionAndScale() {
        val plain = firstProjection("SELECT CAST(a AS INTEGER) FROM t") as CastExpression
        assertEquals(ColumnType.Integer, plain.targetType)
        assertNull(plain.precision)

        val decimal = firstProjection("SELECT CAST(a AS DECIMAL(10, 2)) FROM t") as CastExpression
        assertEquals(ColumnType.Decimal, decimal.targetType)
        assertEquals(10, decimal.precision)
        assertEquals(2, decimal.scale)
    }

    @Test
    fun parsesCollateExpressionWithEachBuiltInSequenceCaseInsensitively() {
        val nocase = firstProjection("SELECT name COLLATE NOCASE FROM t") as CollateExpression
        assertEquals(Collation.NoCase, nocase.collation)
        assertEquals(ColumnExpression("name"), nocase.operand)

        val rtrim = firstProjection("SELECT name COLLATE rtrim FROM t") as CollateExpression
        assertEquals(Collation.Rtrim, rtrim.collation)

        val binary = firstProjection("SELECT name COLLATE BINARY FROM t") as CollateExpression
        assertEquals(Collation.Binary, binary.collation)
    }

    @Test
    fun collateWithAnUnknownSequenceNameFailsToParse() {
        assertFailsWith<ParseException> { parse("SELECT name COLLATE BOGUS FROM t") }
    }

    @Test
    fun collateWithoutAFollowingNameFailsToParse() {
        assertFailsWith<ParseException> { parse("SELECT name COLLATE FROM t") }
    }

    @Test
    fun collateBindsTighterThanComparisonSoItWrapsOnlyTheColumnItFollows() {
        val where = whereOf("SELECT 1 FROM t WHERE name COLLATE NOCASE = 'bob'")
        val comparison = where as BinaryExpression
        assertEquals(BinaryOperator.Equal, comparison.operator)
        val left = comparison.left as CollateExpression
        assertEquals(Collation.NoCase, left.collation)
        assertEquals(ColumnExpression("name"), left.operand)
        assertEquals(LiteralExpression(Value.TextValue("bob")), comparison.right)
    }

    @Test
    fun collateBindsTighterThanMultiplicationSoItWrapsOnlyItsOwnOperand() {
        val projection = firstProjection("SELECT a * b COLLATE NOCASE FROM t") as BinaryExpression
        assertEquals(BinaryOperator.Multiply, projection.operator)
        assertEquals(ColumnExpression("a"), projection.left)
        val right = projection.right as CollateExpression
        assertEquals(Collation.NoCase, right.collation)
        assertEquals(ColumnExpression("b"), right.operand)
    }

    @Test
    fun collateBindsLooserThanUnaryMinusSoItWrapsTheWholeNegation() {
        val projection = firstProjection("SELECT -a COLLATE NOCASE FROM t") as CollateExpression
        assertEquals(Collation.NoCase, projection.collation)
        val negated = projection.operand as UnaryExpression
        assertEquals(UnaryOperator.Negate, negated.operator)
        assertEquals(ColumnExpression("a"), negated.operand)
    }

    @Test
    fun chainedCollateWrapsEachPreviousCollateExpression() {
        val projection = firstProjection("SELECT a COLLATE BINARY COLLATE NOCASE FROM t") as CollateExpression
        assertEquals(Collation.NoCase, projection.collation)
        val inner = projection.operand as CollateExpression
        assertEquals(Collation.Binary, inner.collation)
        assertEquals(ColumnExpression("a"), inner.operand)
    }

    @Test
    fun parsesColumnLevelCollateInCreateTable() {
        val statement = parseOne("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT COLLATE NOCASE, other TEXT)") as CreateTableStatement
        assertEquals(Collation.NoCase, statement.columns[1].collation)
        assertEquals(Collation.Binary, statement.columns[0].collation)
        assertEquals(Collation.Binary, statement.columns[2].collation)
    }

    /**
     * COLLATE before DEFAULT is unambiguous: the column-definition loop consumes it as its own
     * clause before ever calling [Parser.parseExpression] for DEFAULT's value. The other order
     * (`DEFAULT 'x' COLLATE NOCASE`) is deliberately not covered here - `parseExpression` itself
     * accepts a trailing `COLLATE` (see `parseCollate`), so it greedily becomes part of the DEFAULT
     * expression instead (`DEFAULT ('x' COLLATE NOCASE)`), not a separate column-level clause; not a
     * bug, just the same expression-grammar greediness this column-level COLLATE reuses.
     */
    @Test
    fun columnLevelCollateBeforeDefaultAttachesToTheColumnNotTheDefaultExpression() {
        val statement = parseOne("CREATE TABLE t (name TEXT COLLATE NOCASE DEFAULT 'x')") as CreateTableStatement
        assertEquals(Collation.NoCase, statement.columns[0].collation)
        assertEquals(LiteralExpression(Value.TextValue("x")), statement.columns[0].default)
    }

    @Test
    fun columnLevelCollateWithAnUnknownSequenceNameFailsToParse() {
        assertFailsWith<ParseException> { parse("CREATE TABLE t (name TEXT COLLATE BOGUS)") }
    }

    @Test
    fun columnLevelCollateWithoutAFollowingNameFailsToParse() {
        assertFailsWith<ParseException> { parse("CREATE TABLE t (name TEXT COLLATE)") }
    }

    @Test
    fun parsesColumnLevelCollateInAlterTableAddColumn() {
        val statement = parseOne("ALTER TABLE t ADD COLUMN name TEXT COLLATE NOCASE") as AlterTableStatement
        val action = statement.action as AddColumn
        assertEquals(Collation.NoCase, action.column.collation)
    }

    @Test
    fun parsesDerivedTableInFromWithRequiredAlias() {
        val statement = parseOne("SELECT s.id FROM (SELECT id FROM users) AS s") as SelectStatement
        val from = statement.from as SubquerySource
        assertEquals("s", from.alias)
        assertEquals("users", tableName(from.select))
    }

    @Test
    fun parsesDerivedTableWithImplicitAlias() {
        val statement = parseOne("SELECT s.id FROM (SELECT id FROM users) s") as SelectStatement
        assertEquals("s", (statement.from as SubquerySource).alias)
    }

    @Test
    fun derivedTableWithoutAliasFails() {
        assertFailsWith<ParseException> { parseOne("SELECT id FROM (SELECT id FROM users)") }
    }

    @Test
    fun parsesDerivedTableAsJoinTarget() {
        val statement = parseOne(
            "SELECT * FROM users JOIN (SELECT dept, COUNT(*) AS n FROM users GROUP BY dept) AS d ON users.dept = d.dept"
        ) as SelectStatement
        val joinFrom = statement.joins.single().from as SubquerySource
        assertEquals("d", joinFrom.alias)
    }

    @Test
    fun parsesLateralJoinMarksTheSubquerySource() {
        val statement = parseOne(
            "SELECT * FROM users u JOIN LATERAL (SELECT id FROM orders WHERE orders.userId = u.id) AS o ON TRUE"
        ) as SelectStatement
        val joinFrom = statement.joins.single().from as SubquerySource
        assertEquals("o", joinFrom.alias)
        assertTrue(joinFrom.lateral)
    }

    @Test
    fun nonLateralDerivedTableJoinIsNotMarkedLateral() {
        val statement = parseOne("SELECT * FROM users JOIN (SELECT id FROM orders) AS o ON TRUE") as SelectStatement
        assertFalse((statement.joins.single().from as SubquerySource).lateral)
    }

    @Test
    fun lateralBeforeAPlainTableFails() {
        assertFailsWith<ParseException> { parseOne("SELECT * FROM users JOIN LATERAL orders ON TRUE") }
    }

    @Test
    fun parsesRankingWindowFunctionWithPartitionAndOrder() {
        val statement = parseOne(
            "SELECT ROW_NUMBER() OVER (PARTITION BY dept ORDER BY salary DESC) AS rn FROM users"
        ) as SelectStatement
        val item = (statement.projection as ExpressionProjection).items.single()
        val window = item.expression as WindowExpression
        assertEquals("ROW_NUMBER", window.name)
        assertEquals(WindowFunctionKind.RowNumber, window.kind)
        assertNull(window.argument)
        assertFalse(window.star)
        assertEquals(listOf(ColumnExpression("dept")), window.partitionBy)
        assertEquals(1, window.orderBy.size)
        assertEquals(ColumnExpression("salary"), window.orderBy.single().expression)
        assertTrue(window.orderBy.single().descending)
        assertEquals("rn", item.alias)
    }

    @Test
    fun parsesAggregateWindowFunctionWithStarAndNoPartition() {
        val statement = parseOne("SELECT COUNT(*) OVER () FROM users") as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        assertEquals(WindowFunctionKind.Aggregate, window.kind)
        assertTrue(window.star)
        assertNull(window.argument)
        assertTrue(window.partitionBy.isEmpty())
        assertTrue(window.orderBy.isEmpty())
    }

    @Test
    fun aggregateWindowFunctionWithSingleArgumentAndOrderBy() {
        val statement = parseOne("SELECT SUM(salary) OVER (ORDER BY id) FROM users") as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        assertEquals("SUM", window.name)
        assertEquals(ColumnExpression("salary"), window.argument)
        assertFalse(window.star)
        assertEquals(listOf(ColumnExpression("id")), window.orderBy.map { it.expression })
    }

    @Test
    fun windowOverOrderByParsesAnExplicitNullsFirstClause() {
        val statement = parseOne("SELECT SUM(salary) OVER (ORDER BY bonus NULLS FIRST) FROM users") as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        val key = window.orderBy.single()
        assertEquals(ColumnExpression("bonus"), key.expression)
        assertEquals(false, key.descending)
        assertEquals(true, key.nullsFirst)
    }

    @Test
    fun functionNameWithoutOverIsNotTreatedAsAWindowFunction() {
        val statement = parseOne("SELECT ROW_NUMBER() FROM users") as SelectStatement
        val expression = (statement.projection as ExpressionProjection).items.single().expression
        assertTrue(expression is CallExpression)
        assertEquals("ROW_NUMBER", (expression as CallExpression).name)
    }

    @Test
    fun rowNumberWithArgumentsFailsToParse() {
        assertFailsWith<ParseException> { parseOne("SELECT ROW_NUMBER(id) OVER () FROM users") }
    }

    @Test
    fun rankWithAnArgumentFailsToParse() {
        assertFailsWith<ParseException> { parseOne("SELECT RANK(id) OVER (ORDER BY id) FROM users") }
    }

    @Test
    fun nonWindowableFunctionBeforeOverFailsToParse() {
        assertFailsWith<ParseException> { parseOne("SELECT UPPER(name) OVER () FROM users") }
    }

    @Test
    fun lagWithOffsetAndDefaultParsesIntoTheirOwnFields() {
        val statement = parseOne(
            "SELECT LAG(salary, 2, 0) OVER (PARTITION BY dept ORDER BY id) AS prev FROM users"
        ) as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        assertEquals("LAG", window.name)
        assertEquals(WindowFunctionKind.Lag, window.kind)
        assertEquals(ColumnExpression("salary"), window.argument)
        assertEquals(LiteralExpression(Value.IntegerValue(2)), window.offset)
        assertEquals(LiteralExpression(Value.IntegerValue(0)), window.defaultValue)
        assertFalse(window.star)
    }

    @Test
    fun leadWithASingleArgumentLeavesOffsetAndDefaultNull() {
        val statement = parseOne("SELECT LEAD(salary) OVER (ORDER BY id) FROM users") as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        assertEquals(WindowFunctionKind.Lead, window.kind)
        assertEquals(ColumnExpression("salary"), window.argument)
        assertNull(window.offset)
        assertNull(window.defaultValue)
    }

    @Test
    fun parsesFirstValueLastValueAndNtile() {
        val statement = parseOne(
            "SELECT FIRST_VALUE(salary) OVER (ORDER BY id), LAST_VALUE(salary) OVER (ORDER BY id), " +
                "NTILE(4) OVER (ORDER BY id) FROM users"
        ) as SelectStatement
        val windows = (statement.projection as ExpressionProjection).items.map { it.expression as WindowExpression }
        assertEquals(WindowFunctionKind.FirstValue, windows[0].kind)
        assertEquals(WindowFunctionKind.LastValue, windows[1].kind)
        assertEquals(WindowFunctionKind.Ntile, windows[2].kind)
        assertEquals(LiteralExpression(Value.IntegerValue(4)), windows[2].argument)
    }

    @Test
    fun lagWithNoArgumentsFailsToParse() {
        assertFailsWith<ParseException> { parseOne("SELECT LAG() OVER (ORDER BY id) FROM users") }
    }

    @Test
    fun lagWithFourArgumentsFailsToParse() {
        assertFailsWith<ParseException> { parseOne("SELECT LAG(salary, 1, 0, 9) OVER (ORDER BY id) FROM users") }
    }

    @Test
    fun firstValueWithTwoArgumentsFailsToParse() {
        assertFailsWith<ParseException> { parseOne("SELECT FIRST_VALUE(salary, id) OVER (ORDER BY id) FROM users") }
    }

    @Test
    fun ntileWithNoArgumentsFailsToParse() {
        assertFailsWith<ParseException> { parseOne("SELECT NTILE() OVER (ORDER BY id) FROM users") }
    }

    @Test
    fun parsesExplicitRowsBetweenFrameWithLiteralOffsets() {
        val statement = parseOne(
            "SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND 2 FOLLOWING) FROM users"
        ) as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        val frame = window.frame!!
        assertEquals(FrameBoundType.Preceding, frame.start.type)
        assertEquals(LiteralExpression(Value.IntegerValue(1)), frame.start.offset)
        assertEquals(FrameBoundType.Following, frame.end.type)
        assertEquals(LiteralExpression(Value.IntegerValue(2)), frame.end.offset)
    }

    @Test
    fun parsesUnboundedAndCurrentRowFrameBounds() {
        val statement = parseOne(
            "SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM users"
        ) as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        val frame = window.frame!!
        assertEquals(FrameBoundType.UnboundedPreceding, frame.start.type)
        assertNull(frame.start.offset)
        assertEquals(FrameBoundType.CurrentRow, frame.end.type)
        assertNull(frame.end.offset)
    }

    @Test
    fun rowsShorthandWithoutBetweenEndsAtCurrentRow() {
        val statement = parseOne(
            "SELECT SUM(salary) OVER (ORDER BY id ROWS 2 PRECEDING) FROM users"
        ) as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        val frame = window.frame!!
        assertEquals(FrameBoundType.Preceding, frame.start.type)
        assertEquals(LiteralExpression(Value.IntegerValue(2)), frame.start.offset)
        assertEquals(FrameBoundType.CurrentRow, frame.end.type)
    }

    @Test
    fun rowsShorthandWithUnboundedFollowingFailsBecauseItWouldStartAfterCurrentRow() {
        assertFailsWith<ParseException> {
            parseOne("SELECT SUM(salary) OVER (ORDER BY id ROWS UNBOUNDED FOLLOWING) FROM users")
        }
    }

    @Test
    fun frameStartAfterFrameEndFailsToParse() {
        assertFailsWith<ParseException> {
            parseOne("SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN CURRENT ROW AND 1 PRECEDING) FROM users")
        }
    }

    @Test
    fun frameWithoutBetweenOrBoundFailsToParse() {
        assertFailsWith<ParseException> {
            parseOne("SELECT SUM(salary) OVER (ORDER BY id ROWS) FROM users")
        }
    }

    @Test
    fun windowWithNoFrameLeavesFrameNull() {
        val statement = parseOne("SELECT SUM(salary) OVER (ORDER BY id) FROM users") as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        assertNull(window.frame)
    }

    @Test
    fun parsesExplicitRangeBetweenFrameWithLiteralOffsets() {
        val statement = parseOne(
            "SELECT SUM(salary) OVER (ORDER BY amount RANGE BETWEEN 5 PRECEDING AND 10 FOLLOWING) FROM users"
        ) as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        val frame = window.frame!!
        assertEquals(FrameUnit.Range, frame.unit)
        assertEquals(FrameBoundType.Preceding, frame.start.type)
        assertEquals(LiteralExpression(Value.IntegerValue(5)), frame.start.offset)
        assertEquals(FrameBoundType.Following, frame.end.type)
        assertEquals(LiteralExpression(Value.IntegerValue(10)), frame.end.offset)
    }

    @Test
    fun rangeShorthandWithoutBetweenEndsAtCurrentRowAndDefaultsRowsUnitOtherwise() {
        val rangeStatement = parseOne(
            "SELECT SUM(salary) OVER (ORDER BY amount RANGE 3 PRECEDING) FROM users"
        ) as SelectStatement
        val rangeWindow = (rangeStatement.projection as ExpressionProjection).items.single().expression as WindowExpression
        assertEquals(FrameUnit.Range, rangeWindow.frame!!.unit)
        assertEquals(FrameBoundType.CurrentRow, rangeWindow.frame!!.end.type)

        val rowsStatement = parseOne(
            "SELECT SUM(salary) OVER (ORDER BY amount ROWS 3 PRECEDING) FROM users"
        ) as SelectStatement
        val rowsWindow = (rowsStatement.projection as ExpressionProjection).items.single().expression as WindowExpression
        assertEquals(FrameUnit.Rows, rowsWindow.frame!!.unit)
    }

    @Test
    fun parsesExplicitGroupsBetweenFrameWithLiteralOffsets() {
        val statement = parseOne(
            "SELECT SUM(salary) OVER (ORDER BY amount GROUPS BETWEEN 1 PRECEDING AND 2 FOLLOWING) FROM users"
        ) as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        val frame = window.frame!!
        assertEquals(FrameUnit.Groups, frame.unit)
        assertEquals(FrameBoundType.Preceding, frame.start.type)
        assertEquals(LiteralExpression(Value.IntegerValue(1)), frame.start.offset)
        assertEquals(FrameBoundType.Following, frame.end.type)
        assertEquals(LiteralExpression(Value.IntegerValue(2)), frame.end.offset)
    }

    @Test
    fun groupsShorthandWithoutBetweenEndsAtCurrentRow() {
        val statement = parseOne(
            "SELECT SUM(salary) OVER (ORDER BY amount GROUPS 3 PRECEDING) FROM users"
        ) as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        assertEquals(FrameUnit.Groups, window.frame!!.unit)
        assertEquals(FrameBoundType.CurrentRow, window.frame!!.end.type)
    }

    @Test
    fun groupsFrameStartAfterFrameEndFailsToParse() {
        assertFailsWith<ParseException> {
            parseOne("SELECT SUM(salary) OVER (ORDER BY amount GROUPS BETWEEN CURRENT ROW AND 1 PRECEDING) FROM users")
        }
    }

    @Test
    fun parsesRangeBetweenUnboundedAndCurrentRow() {
        val statement = parseOne(
            "SELECT SUM(salary) OVER (ORDER BY amount RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) FROM users"
        ) as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        val frame = window.frame!!
        assertEquals(FrameUnit.Range, frame.unit)
        assertEquals(FrameBoundType.UnboundedPreceding, frame.start.type)
        assertEquals(FrameBoundType.CurrentRow, frame.end.type)
    }

    @Test
    fun rangeFrameStartAfterFrameEndFailsToParse() {
        assertFailsWith<ParseException> {
            parseOne("SELECT SUM(salary) OVER (ORDER BY amount RANGE BETWEEN CURRENT ROW AND 1 PRECEDING) FROM users")
        }
    }

    @Test
    fun frameWithNoExcludeClauseDefaultsToNoOthers() {
        val statement = parseOne(
            "SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) FROM users"
        ) as SelectStatement
        val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
        assertEquals(FrameExclude.NoOthers, window.frame!!.exclude)
    }

    @Test
    fun parsesEveryExcludeMode() {
        fun excludeOf(clause: String): FrameExclude {
            val statement = parseOne(
                "SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING $clause) FROM users"
            ) as SelectStatement
            val window = (statement.projection as ExpressionProjection).items.single().expression as WindowExpression
            return window.frame!!.exclude
        }
        assertEquals(FrameExclude.CurrentRow, excludeOf("EXCLUDE CURRENT ROW"))
        assertEquals(FrameExclude.Group, excludeOf("EXCLUDE GROUP"))
        assertEquals(FrameExclude.Ties, excludeOf("EXCLUDE TIES"))
        assertEquals(FrameExclude.NoOthers, excludeOf("EXCLUDE NO OTHERS"))
    }

    @Test
    fun excludeWithoutAValidModeFailsToParse() {
        assertFailsWith<ParseException> {
            parseOne(
                "SELECT SUM(salary) OVER (ORDER BY id ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING EXCLUDE FOO) FROM users"
            )
        }
    }

    @Test
    fun parsesUnionAsASetOperationStatement() {
        val statement = parseOne("SELECT id FROM a UNION SELECT id FROM b") as SetOperationStatement
        assertEquals(SetOperator.Union, statement.operator)
        assertEquals("a", tableName(statement.left))
        assertEquals("b", tableName(statement.right))
    }

    @Test
    fun parsesUnionAllIntersectAndExcept() {
        assertEquals(SetOperator.UnionAll, (parseOne("SELECT id FROM a UNION ALL SELECT id FROM b") as SetOperationStatement).operator)
        assertEquals(SetOperator.Intersect, (parseOne("SELECT id FROM a INTERSECT SELECT id FROM b") as SetOperationStatement).operator)
        assertEquals(SetOperator.Except, (parseOne("SELECT id FROM a EXCEPT SELECT id FROM b") as SetOperationStatement).operator)
    }

    @Test
    fun parsesIntersectAllAndExceptAll() {
        assertEquals(SetOperator.IntersectAll, (parseOne("SELECT id FROM a INTERSECT ALL SELECT id FROM b") as SetOperationStatement).operator)
        assertEquals(SetOperator.ExceptAll, (parseOne("SELECT id FROM a EXCEPT ALL SELECT id FROM b") as SetOperationStatement).operator)
    }

    @Test
    fun parsesCreateIndexAndCreateUniqueIndex() {
        val plain = parseOne("CREATE INDEX idx ON t (a)") as CreateIndexStatement
        assertEquals(false, plain.unique)
        assertEquals("idx", plain.indexName)
        assertEquals("t", plain.tableName)
        assertEquals(listOf("a"), plain.columnNames)
        val unique = parseOne("CREATE UNIQUE INDEX idx ON t (a)") as CreateIndexStatement
        assertEquals(true, unique.unique)
    }

    @Test
    fun chainedCompoundOperatorsAreLeftAssociative() {
        val statement = parseOne("SELECT id FROM a UNION SELECT id FROM b INTERSECT SELECT id FROM c") as SetOperationStatement
        assertEquals(SetOperator.Intersect, statement.operator)
        val left = statement.left as SetOperationStatement
        assertEquals(SetOperator.Union, left.operator)
        assertEquals("a", tableName(left.left))
        assertEquals("b", tableName(left.right))
        assertEquals("c", tableName(statement.right))
    }

    @Test
    fun trailingOrderByAndLimitAttachToTheWholeCompoundQuery() {
        val statement = parseOne("SELECT id FROM a UNION SELECT id FROM b ORDER BY id DESC LIMIT 3") as SetOperationStatement
        val key = statement.orderBy!!.keys.single()
        assertEquals("id", (key.expression as ColumnExpression).name)
        assertEquals(true, key.descending)
        assertEquals(3, statement.limit)
    }

    @Test
    fun individualSelectCoresInACompoundQueryDoNotOwnOrderByOrLimit() {
        val statement = parseOne("SELECT id FROM a UNION SELECT id FROM b") as SetOperationStatement
        assertNull((statement.left as SelectStatement).orderBy)
        assertNull((statement.left as SelectStatement).limit)
    }

    @Test
    fun parsesWithClauseAsAWithQuery() {
        val statement = parseOne("WITH recent AS (SELECT id FROM a) SELECT id FROM recent") as WithQuery
        assertEquals(false, statement.recursive)
        val cte = statement.ctes.single()
        assertEquals("recent", cte.name)
        assertNull(cte.columns)
        assertEquals("a", tableName(cte.query))
        assertEquals("recent", tableName(statement.body))
    }

    @Test
    fun parsesWithRecursiveAndMultipleCtesWithColumnLists() {
        val statement = parseOne(
            "WITH RECURSIVE first_cte(x) AS (SELECT id FROM a), second_cte AS (SELECT id FROM b) SELECT x FROM first_cte"
        ) as WithQuery
        assertEquals(true, statement.recursive)
        assertEquals(2, statement.ctes.size)
        assertEquals(listOf("x"), statement.ctes[0].columns)
        assertNull(statement.ctes[1].columns)
    }

    @Test
    fun explainAcceptsAUnionQuery() {
        val statement = parseOne("EXPLAIN SELECT id FROM a UNION SELECT id FROM b") as ExplainStatement
        assertTrue(statement.select is SetOperationStatement)
    }

    @Test
    fun createViewAcceptsAUnionQuery() {
        val statement = parseOne("CREATE VIEW v AS SELECT id FROM a UNION SELECT id FROM b") as CreateViewStatement
        assertTrue(statement.query is SetOperationStatement)
    }

    @Test
    fun derivedTableAcceptsACompoundQuery() {
        val statement = parseOne("SELECT * FROM (SELECT id FROM a UNION SELECT id FROM b) AS u") as SelectStatement
        val from = statement.from as SubquerySource
        assertTrue(from.select is SetOperationStatement)
    }

    @Test
    fun derivedTableAcceptsAWithQuery() {
        val statement = parseOne("SELECT * FROM (WITH x AS (SELECT id FROM a) SELECT id FROM x) AS u") as SelectStatement
        val from = statement.from as SubquerySource
        assertTrue(from.select is WithQuery)
    }

    @Test
    fun parsesColumnLevelForeignKeyWithDefaultActions() {
        val statement = parseOne(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, dept_id INTEGER REFERENCES departments(id))"
        ) as CreateTableStatement
        val fk = statement.foreignKeys.single()
        assertEquals(listOf("dept_id"), fk.columns)
        assertEquals("departments", fk.referencedTable)
        assertEquals(listOf("id"), fk.referencedColumns)
        assertEquals(ForeignKeyAction.NoAction, fk.onDelete)
        assertEquals(ForeignKeyAction.NoAction, fk.onUpdate)
    }

    @Test
    fun parsesForeignKeyActionsInEitherOrder() {
        val cascade = parseOne(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER REFERENCES d(id) ON DELETE CASCADE ON UPDATE RESTRICT)"
        ) as CreateTableStatement
        val fk1 = cascade.foreignKeys.single()
        assertEquals(ForeignKeyAction.Cascade, fk1.onDelete)
        assertEquals(ForeignKeyAction.Restrict, fk1.onUpdate)

        val reordered = parseOne(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, x INTEGER REFERENCES d(id) ON UPDATE SET NULL ON DELETE NO ACTION)"
        ) as CreateTableStatement
        val fk2 = reordered.foreignKeys.single()
        assertEquals(ForeignKeyAction.NoAction, fk2.onDelete)
        assertEquals(ForeignKeyAction.SetNull, fk2.onUpdate)
    }

    @Test
    fun parsesTableLevelForeignKeyConstraint() {
        val statement = parseOne(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, dept_id INTEGER, FOREIGN KEY (dept_id) REFERENCES departments(id))"
        ) as CreateTableStatement
        assertEquals(2, statement.columns.size)
        val fk = statement.foreignKeys.single()
        assertEquals(listOf("dept_id"), fk.columns)
        assertEquals("departments", fk.referencedTable)
    }

    @Test
    fun parsesColumnLevelAndTableLevelCheckConstraints() {
        val statement = parseOne(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, age INTEGER CHECK (age > 0), CHECK (id <> 0))"
        ) as CreateTableStatement
        assertEquals(2, statement.checks.size)
        assertTrue(statement.checks[0].expression is BinaryExpression)
        assertTrue(statement.checks[1].expression is BinaryExpression)
    }

    @Test
    fun parsesColumnLevelUniqueConstraint() {
        val statement = parseOne(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE)"
        ) as CreateTableStatement
        val unique = statement.uniqueConstraints.single()
        assertEquals(listOf("email"), unique.columns)
    }

    @Test
    fun parsesTableLevelCompositeUniqueConstraint() {
        val statement = parseOne(
            "CREATE TABLE t (a INTEGER, b INTEGER, c INTEGER, UNIQUE (a, b, c))"
        ) as CreateTableStatement
        val unique = statement.uniqueConstraints.single()
        assertEquals(listOf("a", "b", "c"), unique.columns)
    }

    @Test
    fun parsesMultipleUniqueConstraintsMixingColumnAndTableLevel() {
        val statement = parseOne(
            "CREATE TABLE t (id INTEGER PRIMARY KEY, email TEXT UNIQUE, a INTEGER, b INTEGER, UNIQUE (a, b))"
        ) as CreateTableStatement
        assertEquals(2, statement.uniqueConstraints.size)
        assertEquals(listOf("email"), statement.uniqueConstraints[0].columns)
        assertEquals(listOf("a", "b"), statement.uniqueConstraints[1].columns)
    }

    @Test
    fun parsesAlterTableAddColumnWithNotNullAndDefault() {
        val statement = parseOne("ALTER TABLE t ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE") as AlterTableStatement
        assertEquals("t", statement.tableName)
        val action = statement.action as AddColumn
        assertEquals("active", action.column.name)
        assertEquals(ColumnType.Boolean, action.column.type)
        assertTrue(action.column.notNull)
        assertEquals(LiteralExpression(Value.BooleanValue(true)), action.column.default)
    }

    @Test
    fun parsesAlterTableAddColumnWithoutDefault() {
        val statement = parseOne("ALTER TABLE t ADD COLUMN note TEXT") as AlterTableStatement
        val action = statement.action as AddColumn
        assertEquals("note", action.column.name)
        assertFalse(action.column.notNull)
        assertNull(action.column.default)
    }

    @Test
    fun parsesAlterTableDropColumnWithAndWithoutCascade() {
        val plain = (parseOne("ALTER TABLE t DROP COLUMN note") as AlterTableStatement).action as DropColumn
        assertEquals("note", plain.columnName)
        assertEquals(DropMode.Restrict, plain.mode)
        val cascaded = (parseOne("ALTER TABLE t DROP COLUMN note CASCADE") as AlterTableStatement).action as DropColumn
        assertEquals(DropMode.Cascade, cascaded.mode)
    }

    @Test
    fun parsesAlterTableRenameColumn() {
        val action = (parseOne("ALTER TABLE t RENAME COLUMN a TO b") as AlterTableStatement).action as RenameColumn
        assertEquals("a", action.oldName)
        assertEquals("b", action.newName)
    }

    @Test
    fun parsesAlterTableRenameTo() {
        val statement = parseOne("ALTER TABLE t RENAME TO t2") as AlterTableStatement
        assertEquals("t", statement.tableName)
        val action = statement.action as RenameTable
        assertEquals("t2", action.newName)
    }

    @Test
    fun parsesAlterTableAlterColumnTypeWithColumnKeywordAndUsing() {
        val action = (parseOne("ALTER TABLE t ALTER COLUMN amount TYPE DECIMAL(10, 2) USING amount") as AlterTableStatement)
            .action as ChangeColumnType
        assertEquals("amount", action.columnName)
        assertEquals(ColumnType.Decimal, action.newType)
        assertEquals(10, action.precision)
        assertEquals(2, action.scale)
        assertEquals(ColumnExpression("amount"), action.using)
    }

    @Test
    fun parsesAlterTableAlterColumnTypeWithoutColumnKeywordOrUsing() {
        val action = (parseOne("ALTER TABLE t ALTER amount TYPE REAL") as AlterTableStatement).action as ChangeColumnType
        assertEquals("amount", action.columnName)
        assertEquals(ColumnType.Real, action.newType)
        assertNull(action.precision)
        assertNull(action.using)
    }

    @Test
    fun alterTableAlterColumnRejectsAMissingTypeKeyword() {
        assertFailsWith<ParseException> { parse("ALTER TABLE t ALTER COLUMN amount REAL") }
    }

    @Test
    fun alterTableAddColumnRejectsPrimaryKeyReferencesUniqueAndCheck() {
        assertFailsWith<ParseException> { parse("ALTER TABLE t ADD COLUMN id INTEGER PRIMARY KEY") }
        assertFailsWith<ParseException> { parse("ALTER TABLE t ADD COLUMN dept_id INTEGER REFERENCES departments(id)") }
        assertFailsWith<ParseException> { parse("ALTER TABLE t ADD COLUMN email TEXT UNIQUE") }
        assertFailsWith<ParseException> { parse("ALTER TABLE t ADD COLUMN age INTEGER CHECK (age > 0)") }
    }

    @Test
    fun parsesColumnLevelDefaultInCreateTable() {
        val statement = parseOne("CREATE TABLE t (id INTEGER PRIMARY KEY, status TEXT DEFAULT 'new')") as CreateTableStatement
        assertEquals(LiteralExpression(Value.TextValue("new")), statement.columns[1].default)
    }

    @Test
    fun parsesGeneratedAlwaysAsStoredInCreateTable() {
        val statement = parseOne("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a * 2) STORED)") as CreateTableStatement
        assertEquals(null, statement.columns[0].generated)
        assertEquals(
            BinaryExpression(BinaryOperator.Multiply, ColumnExpression("a"), LiteralExpression(Value.IntegerValue(2))),
            statement.columns[1].generated
        )
    }

    @Test
    fun generatedColumnRequiresAlwaysKeyword() {
        assertFailsWith<ParseException> { parse("CREATE TABLE t (a INTEGER, b INTEGER GENERATED AS (a) STORED)") }
    }

    @Test
    fun generatedColumnRequiresAsKeyword() {
        assertFailsWith<ParseException> { parse("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS (a) STORED)") }
    }

    @Test
    fun generatedColumnRequiresStoredKeyword() {
        assertFailsWith<ParseException> { parse("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a))") }
    }

    @Test
    fun generatedColumnRejectsCombiningWithDefault() {
        assertFailsWith<ParseException> { parse("CREATE TABLE t (a INTEGER, b INTEGER DEFAULT 1 GENERATED ALWAYS AS (a) STORED)") }
    }

    @Test
    fun generatedColumnRejectsCombiningWithPrimaryKey() {
        assertFailsWith<ParseException> {
            parse("CREATE TABLE t (a INTEGER, b INTEGER GENERATED ALWAYS AS (a) STORED PRIMARY KEY)")
        }
    }

    @Test
    fun generatedIsAContextualKeywordUsableAsAColumnName() {
        val statement = parseOne("CREATE TABLE t (generated INTEGER, always INTEGER, stored INTEGER)") as CreateTableStatement
        assertEquals(listOf("generated", "always", "stored"), statement.columns.map { it.name })
    }

    @Test
    fun parsesGeneratedAlwaysAsStoredInAlterTableAddColumn() {
        val statement = parseOne("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (a * 2) STORED") as AlterTableStatement
        val column = (statement.action as AddColumn).column
        assertEquals(
            BinaryExpression(BinaryOperator.Multiply, ColumnExpression("a"), LiteralExpression(Value.IntegerValue(2))),
            column.generated
        )
        assertEquals(null, column.default)
    }

    @Test
    fun alterTableAddColumnGeneratedRejectsCombiningWithDefault() {
        assertFailsWith<ParseException> {
            parse("ALTER TABLE t ADD COLUMN b INTEGER DEFAULT 1 GENERATED ALWAYS AS (a) STORED")
        }
    }

    @Test
    fun alterTableAddColumnGeneratedStillRejectsPrimaryKey() {
        assertFailsWith<ParseException> {
            parse("ALTER TABLE t ADD COLUMN b INTEGER GENERATED ALWAYS AS (a) STORED PRIMARY KEY")
        }
    }

    @Test
    fun parsesCreateTriggerWithTimingEventAndBody() {
        val statement = parseOne(
            "CREATE TRIGGER log AFTER INSERT ON orders FOR EACH ROW BEGIN INSERT INTO audit VALUES (NEW.id); END"
        ) as CreateTriggerStatement
        assertEquals("log", statement.triggerName)
        assertEquals(TriggerTiming.After, statement.timing)
        assertEquals(TriggerEvent.Insert, statement.event)
        assertEquals("orders", statement.tableName)
        assertEquals(null, statement.whenClause)
        assertEquals(1, statement.body.size)
        assertTrue(statement.body.single() is InsertStatement)
    }

    @Test
    fun parsesCreateTriggerWithWhenClauseAndMultipleBodyStatements() {
        val statement = parseOne(
            "CREATE TRIGGER guard BEFORE UPDATE ON accounts FOR EACH ROW WHEN NEW.balance < 0 " +
                "BEGIN UPDATE accounts SET balance = 0 WHERE id = NEW.id; DELETE FROM overdrafts WHERE account_id = NEW.id; END"
        ) as CreateTriggerStatement
        assertEquals(TriggerTiming.Before, statement.timing)
        assertEquals(TriggerEvent.Update, statement.event)
        assertTrue(statement.whenClause is BinaryExpression)
        assertEquals(2, statement.body.size)
        assertTrue(statement.body[0] is UpdateStatement)
        assertTrue(statement.body[1] is DeleteStatement)
    }

    @Test
    fun parsesDropTrigger() {
        val statement = parseOne("DROP TRIGGER log") as DropTriggerStatement
        assertEquals("log", statement.triggerName)
    }

    @Test
    fun createTriggerRejectsNonDmlBodyStatement() {
        assertFailsWith<ParseException> { parse("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN SELECT 1; END") }
    }

    @Test
    fun createTriggerRequiresForEachRow() {
        assertFailsWith<ParseException> { parse("CREATE TRIGGER log AFTER INSERT ON t BEGIN DELETE FROM t WHERE 1 = 0; END") }
    }

    @Test
    fun createTriggerRequiresMatchingEnd() {
        assertFailsWith<ParseException> { parse("CREATE TRIGGER log AFTER INSERT ON t FOR EACH ROW BEGIN DELETE FROM t WHERE 1 = 0;") }
    }

    @Test
    fun createTriggerRequiresTimingAndEventKeywords() {
        assertFailsWith<ParseException> { parse("CREATE TRIGGER log ON t FOR EACH ROW BEGIN DELETE FROM t WHERE 1 = 0; END") }
        assertFailsWith<ParseException> { parse("CREATE TRIGGER log AFTER ON t FOR EACH ROW BEGIN DELETE FROM t WHERE 1 = 0; END") }
    }

    @Test
    fun parsesCreateSequenceWithNoOptions() {
        val statement = parseOne("CREATE SEQUENCE seq") as CreateSequenceStatement
        assertEquals("seq", statement.sequenceName)
        assertEquals(null, statement.incrementBy)
        assertEquals(null, statement.minValue)
        assertEquals(null, statement.maxValue)
        assertEquals(null, statement.startValue)
        assertEquals(false, statement.cycle)
    }

    @Test
    fun parsesCreateSequenceWithEveryOptionInAnyOrder() {
        val statement = parseOne("CREATE SEQUENCE seq CYCLE START WITH 10 MAXVALUE 100 MINVALUE 1 INCREMENT BY 2") as CreateSequenceStatement
        assertEquals(2L, statement.incrementBy)
        assertEquals(1L, statement.minValue)
        assertEquals(100L, statement.maxValue)
        assertEquals(10L, statement.startValue)
        assertEquals(true, statement.cycle)
    }

    @Test
    fun parsesCreateSequenceWithNegativeBounds() {
        val statement = parseOne("CREATE SEQUENCE seq INCREMENT BY -1 MINVALUE -100 START WITH -1") as CreateSequenceStatement
        assertEquals(-1L, statement.incrementBy)
        assertEquals(-100L, statement.minValue)
        assertEquals(-1L, statement.startValue)
    }

    @Test
    fun parsesDropSequence() {
        val statement = parseOne("DROP SEQUENCE seq") as DropSequenceStatement
        assertEquals("seq", statement.sequenceName)
    }

    @Test
    fun parsesAlterSequenceRestartWithAndIncrementBy() {
        val statement = parseOne("ALTER SEQUENCE seq INCREMENT BY 5 RESTART WITH 1") as AlterSequenceStatement
        assertEquals("seq", statement.sequenceName)
        assertEquals(5L, statement.incrementBy)
        assertEquals(true, statement.restart)
        assertEquals(1L, statement.restartWith)
    }

    @Test
    fun parsesAlterSequenceBareRestart() {
        val statement = parseOne("ALTER SEQUENCE seq RESTART") as AlterSequenceStatement
        assertEquals(true, statement.restart)
        assertEquals(null, statement.restartWith)
    }

    @Test
    fun parsesAlterSequenceNoMinvalueNoMaxvalueNoCycle() {
        val statement = parseOne("ALTER SEQUENCE seq NO MINVALUE NO MAXVALUE NO CYCLE") as AlterSequenceStatement
        assertEquals(true, statement.clearMinValue)
        assertEquals(true, statement.clearMaxValue)
        assertEquals(false, statement.cycle)
    }

    @Test
    fun alterDispatchesToTableOrSequenceByFollowingKeyword() {
        assertTrue(parseOne("ALTER TABLE t ADD COLUMN x INTEGER") is AlterTableStatement)
        assertTrue(parseOne("ALTER SEQUENCE seq INCREMENT BY 1") is AlterSequenceStatement)
        assertFailsWith<ParseException> { parse("ALTER VIEW v RENAME TO v2") }
    }

    @Test
    fun parsesNextvalAndCurrvalAsDedicatedExpressions() {
        val select = parseOne("SELECT NEXTVAL('seq'), CURRVAL('seq') FROM t") as SelectStatement
        val items = (select.projection as ExpressionProjection).items
        assertEquals(NextValueExpression("seq"), items[0].expression)
        assertEquals(CurrentValueExpression("seq"), items[1].expression)
    }

    @Test
    fun nextvalRequiresASingleStringLiteralArgument() {
        assertFailsWith<ParseException> { parse("SELECT NEXTVAL(seq_column)") }
        assertFailsWith<ParseException> { parse("SELECT NEXTVAL('a', 'b')") }
        assertFailsWith<ParseException> { parse("SELECT NEXTVAL()") }
    }

    @Test
    fun parsesGroupByRollup() {
        val statement = parseOne("SELECT dept, region, SUM(amount) FROM sales GROUP BY ROLLUP (dept, region)") as SelectStatement
        val spec = statement.groupingSpec as RollupSpec
        assertEquals(listOf("dept", "region"), spec.expressions.map { (it as ColumnExpression).name })
        assertEquals(listOf("dept", "region"), statement.groupBy.map { (it as ColumnExpression).name })
    }

    @Test
    fun parsesGroupByCube() {
        val statement = parseOne("SELECT dept, region, SUM(amount) FROM sales GROUP BY CUBE (dept, region)") as SelectStatement
        val spec = statement.groupingSpec as CubeSpec
        assertEquals(listOf("dept", "region"), spec.expressions.map { (it as ColumnExpression).name })
    }

    @Test
    fun parsesGroupByGroupingSetsWithAnEmptySet() {
        val statement = parseOne(
            "SELECT dept, region, SUM(amount) FROM sales GROUP BY GROUPING SETS ((dept, region), (dept), ())"
        ) as SelectStatement
        val spec = statement.groupingSpec as GroupingSetsSpec
        assertEquals(3, spec.sets.size)
        assertEquals(listOf("dept", "region"), spec.sets[0].map { (it as ColumnExpression).name })
        assertEquals(listOf("dept"), spec.sets[1].map { (it as ColumnExpression).name })
        assertTrue(spec.sets[2].isEmpty())
        assertEquals(listOf("dept", "region"), statement.groupBy.map { (it as ColumnExpression).name })
    }

    @Test
    fun rollupCubeAndGroupingSetsAreNotReservedWordsAsPlainColumnNames() {
        val statement = parseOne("SELECT rollup FROM t GROUP BY rollup") as SelectStatement
        assertNull(statement.groupingSpec)
        assertEquals(listOf("rollup"), statement.groupBy.map { (it as ColumnExpression).name })
    }

    @Test
    fun groupByRollupRequiresAClosingParenthesis() {
        assertFailsWith<ParseException> { parse("SELECT 1 FROM t GROUP BY ROLLUP (dept") }
    }

    @Test
    fun groupByGroupingSetsRequiresParenthesizedElements() {
        assertFailsWith<ParseException> { parse("SELECT 1 FROM t GROUP BY GROUPING SETS (dept)") }
    }

    @Test
    fun parsesGroupingFunctionCallLikeAnyOtherFunction() {
        val statement = parseOne("SELECT dept, GROUPING(dept) FROM sales GROUP BY ROLLUP (dept)") as SelectStatement
        val projection = statement.projection as ExpressionProjection
        val call = projection.items[1].expression as CallExpression
        assertEquals("GROUPING", call.name)
        assertEquals(listOf("dept"), call.arguments.map { (it as ColumnExpression).name })
    }

    @Test
    fun plainSelectDefaultsToNotDistinct() {
        val statement = parseOne("SELECT dept FROM sales") as SelectStatement
        assertFalse(statement.distinct)
        assertTrue(statement.distinctOn.isEmpty())
    }

    @Test
    fun selectAllParsesTheSameAsNoModifierAtAll() {
        val statement = parseOne("SELECT ALL dept FROM sales") as SelectStatement
        assertFalse(statement.distinct)
        assertTrue(statement.distinctOn.isEmpty())
    }

    @Test
    fun selectDistinctSetsTheFlagWithNoDistinctOnExpressions() {
        val statement = parseOne("SELECT DISTINCT dept, region FROM sales") as SelectStatement
        assertTrue(statement.distinct)
        assertTrue(statement.distinctOn.isEmpty())
        val projection = statement.projection as ExpressionProjection
        assertEquals(listOf("dept", "region"), projection.items.map { (it.expression as ColumnExpression).name })
    }

    @Test
    fun selectDistinctOnParsesItsParenthesizedExpressionList() {
        val statement = parseOne("SELECT DISTINCT ON (dept, region) dept, region, amount FROM sales") as SelectStatement
        assertTrue(statement.distinct)
        assertEquals(listOf("dept", "region"), statement.distinctOn.map { (it as ColumnExpression).name })
    }

    @Test
    fun selectDistinctOnAcceptsAnArbitraryExpressionNotJustAColumn() {
        val statement = parseOne("SELECT DISTINCT ON (amount + 1) dept FROM sales") as SelectStatement
        assertTrue(statement.distinctOn.single() is BinaryExpression)
    }

    @Test
    fun selectDistinctOnRequiresParenthesesAfterOn() {
        assertFailsWith<ParseException> { parse("SELECT DISTINCT ON dept FROM sales") }
    }

    @Test
    fun selectDistinctOnRequiresAtLeastOneExpression() {
        assertFailsWith<ParseException> { parse("SELECT DISTINCT ON () dept FROM sales") }
    }

    @Test
    fun selectDistinctOnRequiresAClosingParenthesis() {
        assertFailsWith<ParseException> { parse("SELECT DISTINCT ON (dept dept FROM sales") }
    }

    @Test
    fun distinctWorksInsideADerivedTableSubquery() {
        val statement = parseOne("SELECT * FROM (SELECT DISTINCT dept FROM sales) AS d") as SelectStatement
        val source = statement.from as SubquerySource
        assertTrue((source.select as SelectStatement).distinct)
    }

    @Test
    fun distinctSurvivesOrderByAndLimitBeingAttachedAfterward() {
        val statement = parseOne("SELECT DISTINCT dept FROM sales ORDER BY dept LIMIT 5") as SelectStatement
        assertTrue(statement.distinct)
        assertNotNull(statement.orderBy)
        assertEquals(5, statement.limit)
    }

    @Test
    fun dollarDigitParsesAsAParameterExpression() {
        val statement = parseOne("SELECT \$1 FROM sales") as SelectStatement
        val projection = statement.projection as ExpressionProjection
        assertEquals(ParameterExpression(1), projection.items.single().expression)
    }

    @Test
    fun multiDigitParameterIndexParses() {
        val statement = parseOne("SELECT \$12 FROM sales") as SelectStatement
        val projection = statement.projection as ExpressionProjection
        assertEquals(ParameterExpression(12), projection.items.single().expression)
    }

    @Test
    fun parameterIndexZeroIsAParseError() {
        assertFailsWith<ParseException> { parse("SELECT \$0 FROM sales") }
    }

    @Test
    fun prepareParsesTheNameAndWrapsTheTargetStatement() {
        val statement = parseOne("PREPARE byId AS SELECT * FROM sales WHERE id = \$1") as PrepareStatement
        assertEquals("byId", statement.name)
        val target = statement.target as SelectStatement
        val where = target.where as BinaryExpression
        assertEquals(BinaryOperator.Equal, where.operator)
        assertEquals(ParameterExpression(1), where.right)
    }

    @Test
    fun prepareAcceptsInsertUpdateDeleteAndMergeAsItsTarget() {
        assertTrue((parseOne("PREPARE p AS INSERT INTO sales VALUES (\$1)") as PrepareStatement).target is InsertStatement)
        assertTrue((parseOne("PREPARE p AS UPDATE sales SET dept = \$1") as PrepareStatement).target is UpdateStatement)
        assertTrue((parseOne("PREPARE p AS DELETE FROM sales WHERE dept = \$1") as PrepareStatement).target is DeleteStatement)
        assertTrue(
            (parseOne("PREPARE p AS MERGE INTO sales USING sales2 ON sales.id = sales2.id WHEN MATCHED THEN DELETE") as PrepareStatement)
                .target is MergeStatement
        )
    }

    @Test
    fun prepareRejectsDdlAndTransactionControlTargets() {
        assertFailsWith<ParseException> { parse("PREPARE p AS CREATE TABLE t (id INTEGER)") }
        assertFailsWith<ParseException> { parse("PREPARE p AS BEGIN") }
        assertFailsWith<ParseException> { parse("PREPARE p AS EXPLAIN SELECT 1") }
    }

    @Test
    fun executeParsesNameAndPositionalArguments() {
        val statement = parseOne("EXECUTE byId(1, 'ada')") as ExecuteStatement
        assertEquals("byId", statement.name)
        assertEquals(listOf(intLit(1), LiteralExpression(Value.TextValue("ada"))), statement.arguments)
    }

    @Test
    fun executeWithNoParenthesesHasNoArguments() {
        val statement = parseOne("EXECUTE byId") as ExecuteStatement
        assertEquals(emptyList(), statement.arguments)
    }

    @Test
    fun executeWithEmptyParenthesesHasNoArguments() {
        val statement = parseOne("EXECUTE byId()") as ExecuteStatement
        assertEquals(emptyList(), statement.arguments)
    }

    @Test
    fun deallocateParsesAName() {
        assertEquals(DeallocateStatement("byId"), parseOne("DEALLOCATE byId"))
    }

    @Test
    fun deallocatePrepareKeywordIsOptional() {
        assertEquals(DeallocateStatement("byId"), parseOne("DEALLOCATE PREPARE byId"))
    }

    @Test
    fun deallocateAllHasNoName() {
        assertEquals(DeallocateStatement(null, all = true), parseOne("DEALLOCATE ALL"))
    }
}
