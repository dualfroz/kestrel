package kestrel

import kestrel.catalog.Catalog
import kestrel.engine.Executor
import kestrel.engine.SelectResult
import kestrel.engine.StatementResult
import kestrel.sql.lexer.Lexer
import kestrel.sql.parser.Parser
import kestrel.storage.Value
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderByNullsTest {
    private val catalog = Catalog()
    private val executor = Executor(catalog)

    private fun run(sql: String): List<StatementResult> =
        Parser(Lexer(sql).tokenize()).parseProgram().map { executor.execute(it) }

    private fun select(sql: String): SelectResult = run(sql).single() as SelectResult

    private fun int(value: Long) = Value.IntegerValue(value)
    private fun text(value: String) = Value.TextValue(value)

    private fun seedScored() {
        run("CREATE TABLE scored (id INTEGER PRIMARY KEY, score INTEGER)")
        run("INSERT INTO scored VALUES (1, 10), (2, NULL), (3, 30), (4, NULL), (5, 20)")
    }

    @Test
    fun ascendingOrderByDefaultsToNullsFirstButNullsLastOverridesIt() {
        seedScored()
        assertEquals(
            listOf(2, 4, 1, 5, 3).map { listOf(int(it.toLong())) },
            select("SELECT id FROM scored ORDER BY score").rows
        )
        assertEquals(
            listOf(1, 5, 3, 2, 4).map { listOf(int(it.toLong())) },
            select("SELECT id FROM scored ORDER BY score NULLS LAST").rows
        )
    }

    @Test
    fun descendingOrderByDefaultsToNullsLastButNullsFirstOverridesIt() {
        seedScored()
        assertEquals(
            listOf(3, 5, 1, 2, 4).map { listOf(int(it.toLong())) },
            select("SELECT id FROM scored ORDER BY score DESC").rows
        )
        assertEquals(
            listOf(2, 4, 3, 5, 1).map { listOf(int(it.toLong())) },
            select("SELECT id FROM scored ORDER BY score DESC NULLS FIRST").rows
        )
    }

    @Test
    fun nullsLastOnASecondaryKeyOnlyAffectsThatKeyInMultiColumnOrderBy() {
        run("CREATE TABLE grouped2 (id INTEGER PRIMARY KEY, grp TEXT, score INTEGER)")
        run("INSERT INTO grouped2 VALUES (1, 'a', NULL), (2, 'a', 5), (3, 'b', NULL), (4, 'b', 1)")
        val result = select("SELECT id FROM grouped2 ORDER BY grp, score NULLS LAST")
        assertEquals(listOf(2, 1, 4, 3).map { listOf(int(it.toLong())) }, result.rows)
    }

    @Test
    fun explicitNullsLastControlsWhereAnAllNullAggregateGroupSortsRelativeToOthers() {
        run("CREATE TABLE g (id INTEGER PRIMARY KEY, dept TEXT, bonus INTEGER)")
        run("INSERT INTO g VALUES (1, 'x', 100), (2, 'x', NULL), (3, 'y', NULL)")
        assertEquals(
            listOf(listOf(text("y"), Value.NullValue), listOf(text("x"), int(100))),
            select("SELECT dept, MAX(bonus) FROM g GROUP BY dept ORDER BY MAX(bonus)").rows
        )
        assertEquals(
            listOf(listOf(text("x"), int(100)), listOf(text("y"), Value.NullValue)),
            select("SELECT dept, MAX(bonus) FROM g GROUP BY dept ORDER BY MAX(bonus) NULLS LAST").rows
        )
    }

    @Test
    fun windowFunctionOrderByHonorsAnExplicitNullsLastClause() {
        seedScored()
        val result = select(
            "SELECT id, ROW_NUMBER() OVER (ORDER BY score NULLS LAST) AS rn FROM scored ORDER BY id ASC"
        )
        assertEquals(
            listOf(
                listOf(int(1), int(1)), listOf(int(2), int(4)), listOf(int(3), int(3)),
                listOf(int(4), int(5)), listOf(int(5), int(2))
            ),
            result.rows
        )
    }

    @Test
    fun stringAggInternalOrderByHonorsAnExplicitNullsLastClause() {
        run("CREATE TABLE namedScored (id INTEGER PRIMARY KEY, name TEXT, score INTEGER)")
        run(
            "INSERT INTO namedScored VALUES " +
                "(1, 'a', 10), (2, 'b', NULL), (3, 'c', 30), (4, 'd', NULL), (5, 'e', 20)"
        )
        assertEquals(
            listOf(listOf(text("b,d,a,e,c"))),
            select("SELECT STRING_AGG(name, ',' ORDER BY score) FROM namedScored").rows
        )
        assertEquals(
            listOf(listOf(text("a,e,c,b,d"))),
            select("SELECT STRING_AGG(name, ',' ORDER BY score NULLS LAST) FROM namedScored").rows
        )
    }
}
