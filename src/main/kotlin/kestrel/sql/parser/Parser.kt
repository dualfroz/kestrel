package kestrel.sql.parser

import kestrel.ParseException
import kestrel.catalog.CheckConstraint
import kestrel.catalog.Column
import kestrel.catalog.ForeignKeyAction
import kestrel.catalog.ForeignKeyConstraint
import kestrel.catalog.UniqueConstraint
import kestrel.sql.ast.AddColumn
import kestrel.sql.ast.AddConstraint
import kestrel.sql.ast.AddedCheck
import kestrel.sql.ast.AddedConstraint
import kestrel.sql.ast.AddedForeignKey
import kestrel.sql.ast.AddedUnique
import kestrel.sql.ast.AlterSequenceStatement
import kestrel.sql.ast.AlterTableAction
import kestrel.sql.ast.AlterTableStatement
import kestrel.sql.ast.Assignment
import kestrel.sql.ast.BeginStatement
import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseBranch
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.ChangeColumnType
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.CommitStatement
import kestrel.sql.ast.CommonTableExpression
import kestrel.sql.ast.ConflictAction
import kestrel.sql.ast.ConflictDoNothing
import kestrel.sql.ast.ConflictDoUpdate
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
import kestrel.sql.ast.DropConstraint
import kestrel.sql.ast.DropIndexStatement
import kestrel.sql.ast.DropMode
import kestrel.sql.ast.DropSequenceStatement
import kestrel.sql.ast.DropTableStatement
import kestrel.sql.ast.DropTriggerStatement
import kestrel.sql.ast.DropViewStatement
import kestrel.sql.ast.ExecuteStatement
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.ExistsExpression
import kestrel.sql.ast.ExplainStatement
import kestrel.sql.ast.FrameBound
import kestrel.sql.ast.FrameBoundType
import kestrel.sql.ast.FrameExclude
import kestrel.sql.ast.FrameUnit
import kestrel.sql.ast.FromItem
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.GroupingSetsSpec
import kestrel.sql.ast.GroupingSpec
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.InSubqueryExpression
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.JoinClause
import kestrel.sql.ast.JoinType
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.MergeMatchedDelete
import kestrel.sql.ast.MergeMatchedUpdate
import kestrel.sql.ast.MergeNotMatchedInsert
import kestrel.sql.ast.MergeStatement
import kestrel.sql.ast.MergeWhenClause
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.OnConflictClause
import kestrel.sql.ast.OrderBy
import kestrel.sql.ast.OrderKey
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.PrepareStatement
import kestrel.sql.ast.PreparableStatement
import kestrel.sql.ast.Projection
import kestrel.sql.ast.QueryStatement
import kestrel.sql.ast.ReleaseSavepointStatement
import kestrel.sql.ast.RenameColumn
import kestrel.sql.ast.RenameTable
import kestrel.sql.ast.RollbackStatement
import kestrel.sql.ast.RollbackToSavepointStatement
import kestrel.sql.ast.RollupSpec
import kestrel.sql.ast.SavepointStatement
import kestrel.sql.ast.ScalarSubqueryExpression
import kestrel.sql.ast.SelectItem
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.SetOperationStatement
import kestrel.sql.ast.SetOperator
import kestrel.sql.ast.StarProjection
import kestrel.sql.ast.Statement
import kestrel.sql.ast.SubquerySource
import kestrel.sql.ast.TableSource
import kestrel.sql.ast.TruncateTableStatement
import kestrel.sql.ast.TriggerEvent
import kestrel.sql.ast.TriggerTiming
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UnaryOperator
import kestrel.sql.ast.UpdateStatement
import kestrel.sql.ast.WindowExpression
import kestrel.sql.ast.WindowFrame
import kestrel.sql.ast.WindowFunctionKind
import kestrel.sql.ast.WithQuery
import kestrel.sql.lexer.Token
import kestrel.sql.lexer.TokenType
import kestrel.storage.Collation
import kestrel.storage.ColumnType
import kestrel.storage.Value

class Parser(private val tokens: List<Token>) {
    private var position = 0

    fun parseProgram(): List<Statement> {
        val statements = mutableListOf<Statement>()
        while (!check(TokenType.EndOfInput)) {
            if (match(TokenType.Semicolon)) continue
            statements.add(parseStatement())
            if (!check(TokenType.EndOfInput)) {
                expect(TokenType.Semicolon, "expected ';' after statement")
            }
        }
        return statements
    }

    private fun parseStatement(): Statement {
        val token = peek()
        return when (token.type) {
            TokenType.Create -> parseCreate()
            TokenType.Insert -> parseInsert()
            TokenType.Select -> parseQueryStatement()
            TokenType.With -> parseQueryStatement()
            TokenType.Delete -> parseDelete()
            TokenType.Update -> parseUpdate()
            TokenType.Drop -> parseDrop()
            TokenType.Truncate -> parseTruncate()
            TokenType.Merge -> parseMerge()
            TokenType.Alter -> parseAlter()
            TokenType.Explain -> parseExplain()
            TokenType.Begin -> parseBegin()
            TokenType.Commit -> parseCommit()
            TokenType.Rollback -> parseRollback()
            TokenType.Savepoint -> parseSavepoint()
            TokenType.Release -> parseReleaseSavepoint()
            TokenType.Prepare -> parsePrepare()
            TokenType.Execute -> parseExecute()
            TokenType.Deallocate -> parseDeallocate()
            else -> throw error("expected a statement but found '${describe(token)}'", token)
        }
    }

    private fun parseCreate(): Statement {
        expect(TokenType.Create, "expected CREATE")
        var orReplace = false
        if (match(TokenType.Or)) {
            expect(TokenType.Replace, "expected REPLACE after OR")
            orReplace = true
        }
        if (orReplace && !check(TokenType.View)) {
            throw error("OR REPLACE is only supported for VIEW but found '${describe(peek())}'", peek())
        }
        val temporary = match(TokenType.Temp) || match(TokenType.Temporary)
        if (temporary && !check(TokenType.Table)) {
            throw error("TEMP/TEMPORARY is only supported for TABLE but found '${describe(peek())}'", peek())
        }
        return when {
            check(TokenType.Table) -> parseCreateTable(temporary)
            check(TokenType.Index) -> parseCreateIndex(unique = false)
            check(TokenType.Unique) -> parseCreateUniqueIndex()
            check(TokenType.View) -> parseCreateView(orReplace)
            check(TokenType.Trigger) -> parseCreateTrigger()
            check(TokenType.Sequence) -> parseCreateSequence()
            else -> throw error(
                "expected TABLE, INDEX, UNIQUE INDEX, VIEW, TRIGGER, or SEQUENCE after CREATE but found '${describe(peek())}'",
                peek()
            )
        }
    }

    /**
     * Every numeric option (`INCREMENT [BY]`/`MINVALUE`/`MAXVALUE`/`START [WITH]`) may appear at
     * most once and in any order, matching Postgres; a repeated option silently keeps the last one
     * written rather than erroring, the same "last one wins" leniency [parseAlterTable] already
     * gives `ADD`/`DROP`/`RENAME` if written more than once in spirit (though that one can only ever
     * carry a single action). `NO MINVALUE`/`NO MAXVALUE` here are never distinguished from "not
     * written at all" - see [CreateSequenceStatement]'s own doc for why that's fine for CREATE
     * specifically (unlike [AlterSequenceStatement], there is no earlier bound to preserve).
     */
    private fun parseCreateSequence(): CreateSequenceStatement {
        expect(TokenType.Sequence, "expected SEQUENCE")
        val name = expectIdentifier("expected sequence name")
        var incrementBy: Long? = null
        var minValue: Long? = null
        var maxValue: Long? = null
        var startValue: Long? = null
        var cycle = false
        while (true) {
            when {
                match(TokenType.Increment) -> {
                    match(TokenType.By)
                    incrementBy = expectLongLiteral("expected a value after INCREMENT BY")
                }
                match(TokenType.Minvalue) -> minValue = expectLongLiteral("expected a value after MINVALUE")
                match(TokenType.Maxvalue) -> maxValue = expectLongLiteral("expected a value after MAXVALUE")
                match(TokenType.Start) -> {
                    match(TokenType.With)
                    startValue = expectLongLiteral("expected a value after START WITH")
                }
                match(TokenType.Cycle) -> cycle = true
                check(TokenType.No) && peekIsSequenceNoClause() -> {
                    advance()
                    when {
                        match(TokenType.Minvalue) -> minValue = null
                        match(TokenType.Maxvalue) -> maxValue = null
                        match(TokenType.Cycle) -> cycle = false
                        else -> error("unreachable: guarded by peekIsSequenceNoClause")
                    }
                }
                else -> break
            }
        }
        return CreateSequenceStatement(name, incrementBy, minValue, maxValue, startValue, cycle)
    }

    /** Distinguishes a sequence option's `NO MINVALUE|MAXVALUE|CYCLE` from `ON DELETE NO ACTION`'s unrelated `NO` - both start with the same token. */
    private fun peekIsSequenceNoClause(): Boolean =
        peekAt(1)?.type in setOf(TokenType.Minvalue, TokenType.Maxvalue, TokenType.Cycle)

    private fun peekAt(offset: Int): Token? = tokens.getOrNull(position + offset)

    /**
     * `body` is deliberately restricted to INSERT/UPDATE/DELETE ([parseTriggerBodyStatement]) -
     * the only statement kinds with a side effect worth firing, matching SQLite's own trigger-body
     * grammar. The closing `END` here is the trigger body's own terminator, distinct from the `;`
     * `parseProgram` still expects after the whole CREATE TRIGGER statement.
     */
    private fun parseCreateTrigger(): CreateTriggerStatement {
        expect(TokenType.Trigger, "expected TRIGGER")
        val triggerName = expectIdentifier("expected trigger name")
        val timing = when {
            match(TokenType.Before) -> TriggerTiming.Before
            match(TokenType.After) -> TriggerTiming.After
            else -> throw error("expected BEFORE or AFTER after trigger name but found '${describe(peek())}'", peek())
        }
        val event = when {
            match(TokenType.Insert) -> TriggerEvent.Insert
            match(TokenType.Update) -> TriggerEvent.Update
            match(TokenType.Delete) -> TriggerEvent.Delete
            else -> throw error("expected INSERT, UPDATE, or DELETE after ${timing.name.uppercase()} but found '${describe(peek())}'", peek())
        }
        expect(TokenType.On, "expected ON after trigger event")
        val tableName = expectIdentifier("expected table name")
        expect(TokenType.For, "expected FOR after table name")
        expect(TokenType.Each, "expected EACH after FOR")
        expect(TokenType.Row, "expected ROW after EACH")
        val whenClause = if (match(TokenType.When)) parseExpression() else null
        expect(TokenType.Begin, "expected BEGIN to start trigger body")
        val body = mutableListOf<Statement>()
        while (!check(TokenType.End)) {
            body.add(parseTriggerBodyStatement())
            expect(TokenType.Semicolon, "expected ';' after trigger body statement")
        }
        expect(TokenType.End, "expected END to close trigger body")
        return CreateTriggerStatement(triggerName, timing, event, tableName, whenClause, body)
    }

    private fun parseTriggerBodyStatement(): Statement = when (peek().type) {
        TokenType.Insert -> parseInsert()
        TokenType.Update -> parseUpdate()
        TokenType.Delete -> parseDelete()
        else -> throw error(
            "trigger body only supports INSERT, UPDATE, or DELETE statements but found '${describe(peek())}'",
            peek()
        )
    }

    private fun parseCreateView(orReplace: Boolean): CreateViewStatement {
        expect(TokenType.View, "expected VIEW")
        val viewName = expectIdentifier("expected view name")
        expect(TokenType.As, "expected AS after view name")
        val query = parseQueryStatement()
        return CreateViewStatement(viewName, query, orReplace)
    }

    private fun parseCreateUniqueIndex(): CreateIndexStatement {
        expect(TokenType.Unique, "expected UNIQUE")
        return parseCreateIndex(unique = true)
    }

    private fun parseCreateIndex(unique: Boolean): CreateIndexStatement {
        expect(TokenType.Index, "expected INDEX")
        val indexName = expectIdentifier("expected index name")
        expect(TokenType.On, "expected ON after index name")
        val tableName = expectIdentifier("expected table name")
        expect(TokenType.LeftParen, "expected '(' before indexed column")
        val columnNames = mutableListOf(expectIdentifier("expected column name"))
        while (match(TokenType.Comma)) {
            columnNames.add(expectIdentifier("expected column name"))
        }
        expect(TokenType.RightParen, "expected ')' after indexed column")
        return CreateIndexStatement(indexName, tableName, columnNames, unique)
    }

    private fun parseExplain(): ExplainStatement {
        expect(TokenType.Explain, "expected EXPLAIN")
        return ExplainStatement(parseQueryStatement())
    }

    private fun parseBegin(): Statement {
        expect(TokenType.Begin, "expected BEGIN")
        match(TokenType.Transaction)
        return BeginStatement
    }

    private fun parseCommit(): Statement {
        expect(TokenType.Commit, "expected COMMIT")
        match(TokenType.Transaction)
        return CommitStatement
    }

    private fun parseRollback(): Statement {
        expect(TokenType.Rollback, "expected ROLLBACK")
        if (match(TokenType.To)) {
            match(TokenType.Savepoint)
            val name = expectIdentifier("expected savepoint name after ROLLBACK TO")
            return RollbackToSavepointStatement(name)
        }
        match(TokenType.Transaction)
        return RollbackStatement
    }

    private fun parseSavepoint(): Statement {
        expect(TokenType.Savepoint, "expected SAVEPOINT")
        val name = expectIdentifier("expected savepoint name after SAVEPOINT")
        return SavepointStatement(name)
    }

    private fun parseReleaseSavepoint(): Statement {
        expect(TokenType.Release, "expected RELEASE")
        match(TokenType.Savepoint)
        val name = expectIdentifier("expected savepoint name after RELEASE")
        return ReleaseSavepointStatement(name)
    }

    private fun parsePrepare(): Statement {
        expect(TokenType.Prepare, "expected PREPARE")
        val name = expectIdentifier("expected prepared statement name")
        expect(TokenType.As, "expected AS after prepared statement name")
        return PrepareStatement(name, parsePreparableStatement())
    }

    private fun parsePreparableStatement(): PreparableStatement = when (peek().type) {
        TokenType.Select, TokenType.With -> parseQueryStatement()
        TokenType.Insert -> parseInsert()
        TokenType.Update -> parseUpdate()
        TokenType.Delete -> parseDelete()
        TokenType.Merge -> parseMerge()
        else -> throw error(
            "PREPARE only supports SELECT, INSERT, UPDATE, DELETE, or MERGE but found '${describe(peek())}'",
            peek()
        )
    }

    private fun parseExecute(): Statement {
        expect(TokenType.Execute, "expected EXECUTE")
        val name = expectIdentifier("expected prepared statement name")
        val arguments = mutableListOf<Expression>()
        if (match(TokenType.LeftParen)) {
            if (!check(TokenType.RightParen)) {
                arguments.add(parseExpression())
                while (match(TokenType.Comma)) arguments.add(parseExpression())
            }
            expect(TokenType.RightParen, "expected ')' after EXECUTE arguments")
        }
        return ExecuteStatement(name, arguments)
    }

    private fun parseDeallocate(): Statement {
        expect(TokenType.Deallocate, "expected DEALLOCATE")
        match(TokenType.Prepare)
        if (match(TokenType.All)) return DeallocateStatement(null, all = true)
        val name = expectIdentifier("expected prepared statement name or ALL after DEALLOCATE")
        return DeallocateStatement(name)
    }

    private fun parseCreateTable(temporary: Boolean = false): CreateTableStatement {
        expect(TokenType.Table, "expected TABLE after CREATE")
        val tableName = expectIdentifier("expected table name")
        expect(TokenType.LeftParen, "expected '(' after table name")
        val columns = mutableListOf<Column>()
        val foreignKeys = mutableListOf<ForeignKeyConstraint>()
        val checks = mutableListOf<CheckConstraint>()
        val uniqueConstraints = mutableListOf<UniqueConstraint>()
        var compositePrimaryKey: List<String> = emptyList()
        do {
            when {
                check(TokenType.Constraint) -> parseNamedTableConstraint(foreignKeys, checks, uniqueConstraints)
                check(TokenType.Foreign) -> foreignKeys.add(parseTableForeignKey())
                check(TokenType.Check) -> checks.add(parseTableCheck())
                check(TokenType.Unique) -> uniqueConstraints.add(parseTableUnique())
                check(TokenType.Primary) -> {
                    if (compositePrimaryKey.isNotEmpty()) {
                        throw error("table declares more than one PRIMARY KEY clause", peek())
                    }
                    compositePrimaryKey = parseTablePrimaryKey()
                }
                else -> columns.add(parseColumnDefinition(foreignKeys, checks, uniqueConstraints))
            }
        } while (match(TokenType.Comma))
        expect(TokenType.RightParen, "expected ')' after column definitions")
        return CreateTableStatement(tableName, columns, foreignKeys, checks, uniqueConstraints, compositePrimaryKey, temporary)
    }

    private fun parseTablePrimaryKey(): List<String> {
        expect(TokenType.Primary, "expected PRIMARY")
        expect(TokenType.Key, "expected KEY after PRIMARY")
        expect(TokenType.LeftParen, "expected '(' after PRIMARY KEY")
        val columns = mutableListOf<String>()
        do {
            columns.add(expectIdentifier("expected column name"))
        } while (match(TokenType.Comma))
        expect(TokenType.RightParen, "expected ')' after PRIMARY KEY column list")
        return columns
    }

    /**
     * `CONSTRAINT name FOREIGN KEY (...) | CHECK (...) | UNIQUE (...)` - a named table-level
     * constraint, standard SQL syntax this engine accepts both inline in `CREATE TABLE` (here) and
     * via `ALTER TABLE ... ADD` (see [parseAlterTable]'s identical dispatch). `PRIMARY KEY` is
     * deliberately not accepted after `CONSTRAINT name`: [kestrel.catalog.TableSchema] has no name
     * field for a table's PRIMARY KEY at all, so there would be nowhere to put it, and
     * `ALTER TABLE ... DROP CONSTRAINT` never needs to target a PRIMARY KEY (see
     * [kestrel.catalog.Catalog.dropColumn]'s own doc on why dropping a PRIMARY KEY is out of scope
     * entirely - redesign it or drop the table instead).
     */
    private fun parseNamedTableConstraint(
        foreignKeys: MutableList<ForeignKeyConstraint>,
        checks: MutableList<CheckConstraint>,
        uniqueConstraints: MutableList<UniqueConstraint>
    ) {
        advance()
        val name = expectIdentifier("expected constraint name after CONSTRAINT")
        when {
            check(TokenType.Foreign) -> foreignKeys.add(parseTableForeignKey().copy(name = name))
            check(TokenType.Check) -> checks.add(parseTableCheck().copy(name = name))
            check(TokenType.Unique) -> uniqueConstraints.add(parseTableUnique().copy(name = name))
            else -> throw error(
                "expected FOREIGN KEY, CHECK, or UNIQUE after CONSTRAINT $name but found '${describe(peek())}'",
                peek()
            )
        }
    }

    private fun parseColumnDefinition(
        foreignKeys: MutableList<ForeignKeyConstraint>,
        checks: MutableList<CheckConstraint>,
        uniqueConstraints: MutableList<UniqueConstraint>
    ): Column {
        val columnName = expectIdentifier("expected column name")
        val spec = parseTypeSpec()
        var notNull = false
        var primaryKey = false
        var default: Expression? = null
        var generated: Expression? = null
        var collation = Collation.Binary
        while (true) {
            when {
                check(TokenType.Not) -> {
                    advance()
                    expect(TokenType.Null, "expected NULL after NOT")
                    notNull = true
                }
                check(TokenType.Primary) -> {
                    advance()
                    expect(TokenType.Key, "expected KEY after PRIMARY")
                    primaryKey = true
                    notNull = true
                }
                check(TokenType.Unique) -> {
                    advance()
                    uniqueConstraints.add(UniqueConstraint(null, listOf(columnName)))
                }
                check(TokenType.References) -> foreignKeys.add(parseInlineForeignKey(columnName))
                check(TokenType.Check) -> checks.add(parseColumnCheck())
                check(TokenType.Default) -> {
                    advance()
                    default = parseExpression()
                }
                isContextualKeyword("GENERATED") -> generated = parseGeneratedClause()
                check(TokenType.Collate) -> collation = parseColumnCollation()
                else -> break
            }
        }
        if (generated != null && default != null) {
            throw error("a column cannot declare both DEFAULT and GENERATED ALWAYS AS", peek())
        }
        if (generated != null && primaryKey) {
            throw error("a GENERATED column cannot be a PRIMARY KEY", peek())
        }
        return Column(columnName, spec.type, notNull, primaryKey, spec.precision, spec.scale, default, generated, collation)
    }

    /** `COLLATE name` on a column definition (`CREATE TABLE`/`ALTER TABLE ADD COLUMN`) - reuses the same name validation as expression-level `expr COLLATE name` (see [parseCollate]). */
    private fun parseColumnCollation(): Collation {
        advance()
        val nameToken = expect(TokenType.Identifier, "expected a collation name after COLLATE")
        return Collation.fromName(nameToken.lexeme) ?: throw error("no such collating sequence: ${nameToken.lexeme}", nameToken)
    }

    /**
     * `GENERATED ALWAYS AS ( expression ) STORED` - Postgres only ever supports `STORED` (unlike
     * MySQL/SQLite, which also allow `VIRTUAL`), so this engine matches Postgres exactly rather than
     * accepting a keyword it would then have to silently reinterpret. `GENERATED`/`ALWAYS`/`STORED`
     * are contextual (same mechanism as `ROLLUP`/`CUBE`/`FILTER`), not reserved keywords, so a table
     * can still name a column `generated` without quoting.
     */
    private fun parseGeneratedClause(): Expression {
        advance()
        if (!isContextualKeyword("ALWAYS")) throw error("expected ALWAYS after GENERATED", peek())
        advance()
        expect(TokenType.As, "expected AS after GENERATED ALWAYS")
        expect(TokenType.LeftParen, "expected '(' after GENERATED ALWAYS AS")
        val expression = parseExpression()
        expect(TokenType.RightParen, "expected ')' to close GENERATED ALWAYS AS expression")
        if (!isContextualKeyword("STORED")) throw error("expected STORED after GENERATED ALWAYS AS (...)", peek())
        advance()
        return expression
    }

    private fun parseTableUnique(): UniqueConstraint {
        expect(TokenType.Unique, "expected UNIQUE")
        expect(TokenType.LeftParen, "expected '(' after UNIQUE")
        val columns = mutableListOf<String>()
        do {
            columns.add(expectIdentifier("expected column name"))
        } while (match(TokenType.Comma))
        expect(TokenType.RightParen, "expected ')' after UNIQUE column list")
        return UniqueConstraint(null, columns)
    }

    private fun parseTableForeignKey(): ForeignKeyConstraint {
        expect(TokenType.Foreign, "expected FOREIGN")
        expect(TokenType.Key, "expected KEY after FOREIGN")
        expect(TokenType.LeftParen, "expected '(' after FOREIGN KEY")
        val columnNames = mutableListOf<String>()
        do {
            columnNames.add(expectIdentifier("expected column name"))
        } while (match(TokenType.Comma))
        expect(TokenType.RightParen, "expected ')' after foreign key column list")
        return parseInlineForeignKey(columnNames)
    }

    private fun parseInlineForeignKey(columnName: String): ForeignKeyConstraint = parseInlineForeignKey(listOf(columnName))

    private fun parseInlineForeignKey(columnNames: List<String>): ForeignKeyConstraint {
        expect(TokenType.References, "expected REFERENCES")
        val refTable = expectIdentifier("expected referenced table name")
        expect(TokenType.LeftParen, "expected '(' before referenced column list")
        val refColumns = mutableListOf<String>()
        do {
            refColumns.add(expectIdentifier("expected referenced column name"))
        } while (match(TokenType.Comma))
        expect(TokenType.RightParen, "expected ')' after referenced column list")
        if (refColumns.size != columnNames.size) {
            throw error(
                "FOREIGN KEY (${columnNames.joinToString(", ")}) has ${columnNames.size} column(s) but " +
                    "REFERENCES $refTable(${refColumns.joinToString(", ")}) has ${refColumns.size}",
                peek()
            )
        }
        var onDelete = ForeignKeyAction.NoAction
        var onUpdate = ForeignKeyAction.NoAction
        while (check(TokenType.On)) {
            advance()
            when {
                match(TokenType.Delete) -> onDelete = parseForeignKeyAction()
                match(TokenType.Update) -> onUpdate = parseForeignKeyAction()
                else -> throw error("expected DELETE or UPDATE after ON", peek())
            }
        }
        return ForeignKeyConstraint(columnNames, refTable, refColumns, onDelete, onUpdate)
    }

    private fun parseForeignKeyAction(): ForeignKeyAction = when {
        match(TokenType.Cascade) -> ForeignKeyAction.Cascade
        match(TokenType.Restrict) -> ForeignKeyAction.Restrict
        match(TokenType.Set) -> {
            expect(TokenType.Null, "expected NULL after SET")
            ForeignKeyAction.SetNull
        }
        match(TokenType.No) -> {
            expect(TokenType.Action, "expected ACTION after NO")
            ForeignKeyAction.NoAction
        }
        else -> throw error("expected CASCADE, RESTRICT, SET NULL, or NO ACTION but found '${describe(peek())}'", peek())
    }

    private fun parseTableCheck(): CheckConstraint = parseCheckConstraint()

    private fun parseColumnCheck(): CheckConstraint = parseCheckConstraint()

    private fun parseCheckConstraint(): CheckConstraint {
        expect(TokenType.Check, "expected CHECK")
        expect(TokenType.LeftParen, "expected '(' after CHECK")
        val expression = parseExpression()
        expect(TokenType.RightParen, "expected ')' after CHECK expression")
        return CheckConstraint(null, expression)
    }

    private data class TypeSpec(val type: ColumnType, val precision: Int?, val scale: Int?)

    /** Shared by column definitions (`CREATE TABLE`) and `CAST(... AS type)`. */
    private fun parseTypeSpec(): TypeSpec {
        val typeToken = advance()
        val type = when (typeToken.type) {
            TokenType.Integer -> ColumnType.Integer
            TokenType.Text -> ColumnType.Text
            TokenType.Boolean -> ColumnType.Boolean
            TokenType.Real -> ColumnType.Real
            TokenType.Decimal -> ColumnType.Decimal
            TokenType.Date -> ColumnType.Date
            else -> throw error("expected a type but found '${describe(typeToken)}'", typeToken)
        }
        var precision: Int? = null
        var scale: Int? = null
        if (type == ColumnType.Decimal && match(TokenType.LeftParen)) {
            precision = expectIntLiteral("expected precision in DECIMAL(...)")
            scale = if (match(TokenType.Comma)) expectIntLiteral("expected scale in DECIMAL(precision, scale)") else 0
            expect(TokenType.RightParen, "expected ')' after DECIMAL precision/scale")
        }
        return TypeSpec(type, precision, scale)
    }

    private fun expectIntLiteral(message: String): Int = expect(TokenType.IntLiteral, message).intValue!!.toInt()

    /** A sequence option's bound (MINVALUE/MAXVALUE/START WITH/RESTART WITH) needs the full 64-bit range, unlike [expectIntLiteral]'s callers - and, unlike those, may be negative for a descending sequence. */
    private fun expectLongLiteral(message: String): Long {
        val negative = match(TokenType.Minus)
        val value = expect(TokenType.IntLiteral, message).intValue!!
        return if (negative) -value else value
    }

    private fun parseInsert(): InsertStatement {
        expect(TokenType.Insert, "expected INSERT")
        expect(TokenType.Into, "expected INTO after INSERT")
        val tableName = expectIdentifier("expected table name")
        var columns: List<String>? = null
        if (match(TokenType.LeftParen)) {
            val names = mutableListOf<String>()
            do {
                names.add(expectIdentifier("expected column name"))
            } while (match(TokenType.Comma))
            expect(TokenType.RightParen, "expected ')' after column list")
            columns = names
        }
        expect(TokenType.Values, "expected VALUES")
        val rows = mutableListOf<List<Expression?>>()
        do {
            expect(TokenType.LeftParen, "expected '(' before value list")
            val values = mutableListOf<Expression?>()
            do {
                values.add(if (match(TokenType.Default)) null else parseExpression())
            } while (match(TokenType.Comma))
            expect(TokenType.RightParen, "expected ')' after value list")
            rows.add(values)
        } while (match(TokenType.Comma))
        val onConflict = if (check(TokenType.On)) parseOnConflict() else null
        val returning = parseReturning()
        return InsertStatement(tableName, columns, rows, onConflict, returning)
    }

    private fun parseReturning(): Projection? = if (match(TokenType.Returning)) parseProjection() else null

    private fun parseOnConflict(): OnConflictClause {
        expect(TokenType.On, "expected ON")
        expect(TokenType.Conflict, "expected CONFLICT after ON")
        var targetColumns: List<String> = emptyList()
        if (match(TokenType.LeftParen)) {
            val names = mutableListOf<String>()
            do {
                names.add(expectIdentifier("expected column name"))
            } while (match(TokenType.Comma))
            expect(TokenType.RightParen, "expected ')' after ON CONFLICT column list")
            targetColumns = names
        }
        expect(TokenType.Do, "expected DO after ON CONFLICT")
        val action: ConflictAction = when {
            match(TokenType.Nothing) -> ConflictDoNothing
            match(TokenType.Update) -> {
                expect(TokenType.Set, "expected SET after DO UPDATE")
                val assignments = parseAssignmentList()
                val where = if (match(TokenType.Where)) parseExpression() else null
                ConflictDoUpdate(assignments, where)
            }
            else -> throw error("expected NOTHING or UPDATE after DO but found '${describe(peek())}'", peek())
        }
        return OnConflictClause(targetColumns, action)
    }

    private fun parseSelect(): SelectStatement {
        val core = parseSelectCore()
        val orderBy = parseOrderBy()
        val (limit, offset) = parseLimitOffset()
        return core.copy(orderBy = orderBy, limit = limit, offset = offset)
    }

    private fun parseSelectCore(): SelectStatement {
        expect(TokenType.Select, "expected SELECT")
        val (distinct, distinctOn) = parseDistinctClause()
        val projection = parseProjection()
        expect(TokenType.From, "expected FROM")
        val from = parseFromItem()
        val joins = parseJoins()
        val where = if (match(TokenType.Where)) parseExpression() else null
        val groupBy = parseGroupBy()
        val having = if (match(TokenType.Having)) parseExpression() else null
        return SelectStatement(
            projection, from, joins, where, groupBy.groupBy, groupBy.spec, having,
            distinct = distinct, distinctOn = distinctOn
        )
    }

    /** `SELECT [ALL | DISTINCT | DISTINCT ON (expr, ...)] ...` - `ALL` (the default) is accepted and discarded, matching Postgres. */
    private fun parseDistinctClause(): Pair<Boolean, List<Expression>> {
        if (match(TokenType.All)) return false to emptyList()
        if (!match(TokenType.Distinct)) return false to emptyList()
        if (!match(TokenType.On)) return true to emptyList()
        expect(TokenType.LeftParen, "expected '(' after DISTINCT ON")
        val expressions = mutableListOf<Expression>()
        do {
            expressions.add(parseExpression())
        } while (match(TokenType.Comma))
        expect(TokenType.RightParen, "expected ')' after DISTINCT ON expressions")
        return true to expressions
    }

    private fun parseQueryStatement(): QueryStatement {
        if (check(TokenType.With)) return parseWithQuery()
        return parseCompoundQuery()
    }

    private fun parseWithQuery(): WithQuery {
        expect(TokenType.With, "expected WITH")
        val recursive = match(TokenType.Recursive)
        val ctes = mutableListOf<CommonTableExpression>()
        do {
            val name = expectIdentifier("expected common table expression name")
            var columns: List<String>? = null
            if (match(TokenType.LeftParen)) {
                val names = mutableListOf<String>()
                do {
                    names.add(expectIdentifier("expected column name"))
                } while (match(TokenType.Comma))
                expect(TokenType.RightParen, "expected ')' after column list")
                columns = names
            }
            expect(TokenType.As, "expected AS after common table expression name")
            expect(TokenType.LeftParen, "expected '(' after AS")
            val query = parseCompoundQuery()
            expect(TokenType.RightParen, "expected ')' after common table expression")
            ctes.add(CommonTableExpression(name, columns, query))
        } while (match(TokenType.Comma))
        val body = parseCompoundQuery()
        return WithQuery(recursive, ctes, body)
    }

    private fun parseCompoundQuery(): QueryStatement {
        var left: QueryStatement = parseSelectCore()
        while (true) {
            val operator = when {
                match(TokenType.Union) -> if (match(TokenType.All)) SetOperator.UnionAll else SetOperator.Union
                match(TokenType.Intersect) -> if (match(TokenType.All)) SetOperator.IntersectAll else SetOperator.Intersect
                match(TokenType.Except) -> if (match(TokenType.All)) SetOperator.ExceptAll else SetOperator.Except
                else -> break
            }
            val right = parseSelectCore()
            left = SetOperationStatement(operator, left, right)
        }
        val orderBy = parseOrderBy()
        val (limit, offset) = parseLimitOffset()
        if (orderBy == null && limit == null && offset == null) return left
        return when (left) {
            is SelectStatement -> left.copy(orderBy = orderBy, limit = limit, offset = offset)
            is SetOperationStatement -> left.copy(orderBy = orderBy, limit = limit, offset = offset)
            is WithQuery -> error("unreachable: parseSelectCore never produces a WITH query")
        }
    }

    private fun parseProjection(): Projection {
        if (match(TokenType.Star)) return StarProjection
        val items = mutableListOf<SelectItem>()
        do {
            val expression = parseExpression()
            val alias = if (match(TokenType.As)) expectIdentifier("expected alias after AS") else null
            items.add(SelectItem(expression, alias))
        } while (match(TokenType.Comma))
        return ExpressionProjection(items)
    }

    private fun parseFromItem(): FromItem {
        if (match(TokenType.LeftParen)) {
            if (!check(TokenType.Select) && !check(TokenType.With)) {
                throw error("expected SELECT or WITH after '(' in FROM but found '${describe(peek())}'", peek())
            }
            val select = parseQueryStatement()
            expect(TokenType.RightParen, "expected ')' after derived table subquery")
            val alias = when {
                match(TokenType.As) -> expectIdentifier("expected alias after AS")
                check(TokenType.Identifier) -> advance().lexeme
                else -> throw error("a derived table in FROM requires an alias", peek())
            }
            return SubquerySource(select, alias)
        }
        val tableName = expectIdentifier("expected table name")
        val alias = when {
            match(TokenType.As) -> expectIdentifier("expected alias after AS")
            check(TokenType.Identifier) -> advance().lexeme
            else -> null
        }
        return TableSource(tableName, alias)
    }

    private fun parseJoins(): List<JoinClause> {
        val joins = mutableListOf<JoinClause>()
        while (true) {
            val type = when {
                match(TokenType.Inner) -> {
                    expect(TokenType.Join, "expected JOIN after INNER"); JoinType.Inner
                }
                match(TokenType.Left) -> {
                    match(TokenType.Outer); expect(TokenType.Join, "expected JOIN after LEFT"); JoinType.Left
                }
                match(TokenType.Right) -> {
                    match(TokenType.Outer); expect(TokenType.Join, "expected JOIN after RIGHT"); JoinType.Right
                }
                match(TokenType.Full) -> {
                    match(TokenType.Outer); expect(TokenType.Join, "expected JOIN after FULL"); JoinType.Full
                }
                match(TokenType.Cross) -> {
                    expect(TokenType.Join, "expected JOIN after CROSS"); JoinType.Cross
                }
                match(TokenType.Join) -> JoinType.Inner
                else -> break
            }
            val lateral = match(TokenType.Lateral)
            // A LATERAL right-hand side has no independent row set (it is re-evaluated per left
            // row), so there is nothing to pad with NULLs for an unmatched *right* row - RIGHT/FULL
            // JOIN LATERAL is therefore meaningless and rejected here rather than at execution time.
            if (lateral && (type == JoinType.Right || type == JoinType.Full)) {
                throw error("RIGHT/FULL JOIN LATERAL is not supported: LATERAL has no independent right-hand row set", peek())
            }
            val from = parseFromItem()
            if (lateral && from !is SubquerySource) {
                throw error("LATERAL is only allowed before a derived table subquery", peek())
            }
            val taggedFrom = if (lateral) (from as SubquerySource).copy(lateral = true) else from
            // CROSS JOIN has no ON clause at all - matches every left/right pairing unconditionally.
            val on = if (type == JoinType.Cross) {
                if (check(TokenType.On)) throw error("CROSS JOIN does not take an ON condition", peek())
                null
            } else {
                expect(TokenType.On, "expected ON after joined table")
                parseExpression()
            }
            joins.add(JoinClause(type, taggedFrom, on))
        }
        return joins
    }

    private data class GroupByClause(val groupBy: List<Expression>, val spec: GroupingSpec?)

    /**
     * `ROLLUP`/`CUBE`/`GROUPING SETS` are not reserved keywords (matching how most SQL engines treat
     * them - only meaningful right after `GROUP BY`), so they are recognized here by peeking at an
     * [TokenType.Identifier]'s text rather than via a dedicated token type; a bare column actually
     * named e.g. `rollup` is unaffected since the lookahead also requires the following token to open
     * the parenthesized argument list. Mixing plain columns with one of these in the same clause (SQL
     * allows it, producing a cross product of sets) is a scope cut - exactly one form fills the whole
     * clause.
     */
    private fun parseGroupBy(): GroupByClause {
        if (!match(TokenType.Group)) return GroupByClause(emptyList(), null)
        expect(TokenType.By, "expected BY after GROUP")
        if (isContextualKeyword("ROLLUP") && peekNext().type == TokenType.LeftParen) {
            advance()
            val expressions = parseParenthesizedExpressionList("ROLLUP")
            return GroupByClause(expressions, RollupSpec(expressions))
        }
        if (isContextualKeyword("CUBE") && peekNext().type == TokenType.LeftParen) {
            advance()
            val expressions = parseParenthesizedExpressionList("CUBE")
            return GroupByClause(expressions, CubeSpec(expressions))
        }
        if (isContextualKeyword("GROUPING") && peekNext().let { it.type == TokenType.Identifier && it.lexeme.equals("SETS", ignoreCase = true) }) {
            advance()
            advance()
            expect(TokenType.LeftParen, "expected '(' after GROUPING SETS")
            val sets = mutableListOf<List<Expression>>()
            do {
                sets.add(parseGroupingSetElement())
            } while (match(TokenType.Comma))
            expect(TokenType.RightParen, "expected ')' to close GROUPING SETS")
            val union = sets.flatten().distinct()
            return GroupByClause(union, GroupingSetsSpec(sets))
        }
        val expressions = mutableListOf<Expression>()
        do {
            expressions.add(parseExpression())
        } while (match(TokenType.Comma))
        return GroupByClause(expressions, null)
    }

    private fun isContextualKeyword(name: String): Boolean =
        check(TokenType.Identifier) && peek().lexeme.equals(name, ignoreCase = true)

    private fun peekNext(): Token = tokens.getOrElse(position + 1) { tokens.last() }

    private fun parseParenthesizedExpressionList(clauseName: String): List<Expression> {
        expect(TokenType.LeftParen, "expected '(' after $clauseName")
        val expressions = mutableListOf<Expression>()
        if (!check(TokenType.RightParen)) {
            do {
                expressions.add(parseExpression())
            } while (match(TokenType.Comma))
        }
        expect(TokenType.RightParen, "expected ')' to close $clauseName argument list")
        return expressions
    }

    /** One element of `GROUPING SETS (...)`: `()` (the grand-total set) or a parenthesized expression list. */
    private fun parseGroupingSetElement(): List<Expression> {
        expect(TokenType.LeftParen, "expected '(' to open a GROUPING SETS element")
        if (match(TokenType.RightParen)) return emptyList()
        val expressions = mutableListOf<Expression>()
        do {
            expressions.add(parseExpression())
        } while (match(TokenType.Comma))
        expect(TokenType.RightParen, "expected ')' to close a GROUPING SETS element")
        return expressions
    }

    private fun parseOrderBy(): OrderBy? {
        val keys = parseOrderKeys()
        return if (keys.isEmpty()) null else OrderBy(keys)
    }

    /** Shared by top-level ORDER BY and a window function's `OVER (... ORDER BY ...)` clause. */
    private fun parseOrderKeys(): List<OrderKey> {
        if (!match(TokenType.Order)) return emptyList()
        expect(TokenType.By, "expected BY after ORDER")
        val keys = mutableListOf<OrderKey>()
        do {
            val expression = parseExpression()
            val descending = when {
                match(TokenType.Desc) -> true
                match(TokenType.Asc) -> false
                else -> false
            }
            val nullsFirst = parseOptionalNullsClause()
            keys.add(OrderKey(expression, descending, nullsFirst))
        } while (match(TokenType.Comma))
        return keys
    }

    /**
     * `NULLS FIRST` / `NULLS LAST`, optional after each ORDER BY key's own `ASC`/`DESC`. Like
     * `ROLLUP`/`CUBE`/`FILTER`/`GENERATED` elsewhere in this parser, `NULLS`/`FIRST`/`LAST` are not
     * reserved keywords - a column legitimately named any of the three is unaffected outside this
     * exact position, since [isContextualKeyword] only fires here, right after an order key.
     */
    private fun parseOptionalNullsClause(): Boolean? {
        if (!isContextualKeyword("NULLS")) return null
        advance()
        return when {
            isContextualKeyword("FIRST") -> { advance(); true }
            isContextualKeyword("LAST") -> { advance(); false }
            else -> throw error("expected FIRST or LAST after NULLS but found '${describe(peek())}'", peek())
        }
    }

    /**
     * `LIMIT`/`OFFSET` may appear in either order (matching Postgres, unlike the standard's
     * `OFFSET ... FETCH ...`), but not repeated - a second `LIMIT` or `OFFSET` stops the loop and is
     * left for whatever follows (typically a parse error from the caller, e.g. a stray token before
     * `;`) rather than silently overwriting the first.
     */
    private fun parseLimitOffset(): Pair<Int?, Int?> {
        var limit: Int? = null
        var offset: Int? = null
        while (true) {
            if (limit == null && match(TokenType.Limit)) {
                limit = expect(TokenType.IntLiteral, "expected an integer after LIMIT").intValue!!.toInt()
            } else if (offset == null && match(TokenType.Offset)) {
                offset = expect(TokenType.IntLiteral, "expected an integer after OFFSET").intValue!!.toInt()
            } else {
                break
            }
        }
        return limit to offset
    }

    private fun parseDelete(): DeleteStatement {
        expect(TokenType.Delete, "expected DELETE")
        expect(TokenType.From, "expected FROM after DELETE")
        val tableName = expectIdentifier("expected table name")
        val where = if (match(TokenType.Where)) parseExpression() else null
        val returning = parseReturning()
        return DeleteStatement(tableName, where, returning)
    }

    private fun parseUpdate(): UpdateStatement {
        expect(TokenType.Update, "expected UPDATE")
        val tableName = expectIdentifier("expected table name")
        expect(TokenType.Set, "expected SET after table name")
        val assignments = parseAssignmentList()
        val where = if (match(TokenType.Where)) parseExpression() else null
        val returning = parseReturning()
        return UpdateStatement(tableName, assignments, where, returning)
    }

    private fun parseAssignmentList(): List<Assignment> {
        val assignments = mutableListOf<Assignment>()
        do {
            val columnName = expectIdentifier("expected column name")
            expect(TokenType.Equal, "expected '=' in assignment")
            val value = if (match(TokenType.Default)) null else parseExpression()
            assignments.add(Assignment(columnName, value))
        } while (match(TokenType.Comma))
        return assignments
    }

    /**
     * `MERGE INTO target [[AS] alias] USING source ON condition (WHEN ...)+`. [parseFromItem] is
     * reused verbatim for the `USING` source: it is exactly a `FROM`-shaped item (bare table name
     * or a parenthesized, aliased derived-table subquery), not a MERGE-specific grammar production.
     */
    private fun parseMerge(): MergeStatement {
        expect(TokenType.Merge, "expected MERGE")
        expect(TokenType.Into, "expected INTO after MERGE")
        val targetTable = expectIdentifier("expected target table name")
        val targetAlias = parseOptionalAlias()
        expect(TokenType.Using, "expected USING after MERGE target")
        val source = parseFromItem()
        expect(TokenType.On, "expected ON after MERGE USING source")
        val onCondition = parseExpression()
        val whenClauses = mutableListOf<MergeWhenClause>()
        while (match(TokenType.When)) {
            whenClauses.add(parseMergeWhenClause())
        }
        if (whenClauses.isEmpty()) {
            throw error("MERGE requires at least one WHEN clause", peek())
        }
        val returning = parseReturning()
        return MergeStatement(targetTable, targetAlias, source, onCondition, whenClauses, returning)
    }

    private fun parseOptionalAlias(): String? = when {
        match(TokenType.As) -> expectIdentifier("expected alias after AS")
        check(TokenType.Identifier) -> advance().lexeme
        else -> null
    }

    /**
     * `WHEN [NOT] MATCHED [AND condition] THEN action` - the `AND condition` sits between the
     * MATCHED/NOT MATCHED keyword and THEN in both categories, matching Postgres. A NOT MATCHED
     * action is always INSERT (there is no target row for NOT MATCHED to UPDATE or DELETE);
     * [MergeNotMatchedInsert.values] uses the same "bare DEFAULT keyword or expression" per-slot
     * grammar as plain INSERT's own VALUES list ([parseInsert]).
     */
    private fun parseMergeWhenClause(): MergeWhenClause {
        val matched = when {
            match(TokenType.Matched) -> true
            match(TokenType.Not) -> {
                expect(TokenType.Matched, "expected MATCHED after NOT")
                false
            }
            else -> throw error("expected MATCHED or NOT MATCHED after WHEN but found '${describe(peek())}'", peek())
        }
        val condition = if (match(TokenType.And)) parseExpression() else null
        expect(TokenType.Then, "expected THEN after WHEN ${if (matched) "MATCHED" else "NOT MATCHED"}")
        if (matched) {
            return when {
                match(TokenType.Update) -> {
                    expect(TokenType.Set, "expected SET after WHEN MATCHED THEN UPDATE")
                    MergeMatchedUpdate(condition, parseAssignmentList())
                }
                match(TokenType.Delete) -> MergeMatchedDelete(condition)
                else -> throw error("expected UPDATE or DELETE after WHEN MATCHED THEN but found '${describe(peek())}'", peek())
            }
        }
        expect(TokenType.Insert, "expected INSERT after WHEN NOT MATCHED THEN")
        var columns: List<String>? = null
        if (match(TokenType.LeftParen)) {
            val names = mutableListOf<String>()
            do {
                names.add(expectIdentifier("expected column name"))
            } while (match(TokenType.Comma))
            expect(TokenType.RightParen, "expected ')' after column list")
            columns = names
        }
        expect(TokenType.Values, "expected VALUES after WHEN NOT MATCHED THEN INSERT")
        expect(TokenType.LeftParen, "expected '(' before value list")
        val values = mutableListOf<Expression?>()
        do {
            values.add(if (match(TokenType.Default)) null else parseExpression())
        } while (match(TokenType.Comma))
        expect(TokenType.RightParen, "expected ')' after value list")
        return MergeNotMatchedInsert(condition, columns, values)
    }

    private fun parseDrop(): Statement {
        expect(TokenType.Drop, "expected DROP")
        return when {
            match(TokenType.Table) -> DropTableStatement(expectIdentifier("expected table name"), parseDropMode())
            match(TokenType.Index) -> DropIndexStatement(expectIdentifier("expected index name"))
            match(TokenType.View) -> DropViewStatement(expectIdentifier("expected view name"), parseDropMode())
            match(TokenType.Trigger) -> DropTriggerStatement(expectIdentifier("expected trigger name"))
            match(TokenType.Sequence) -> DropSequenceStatement(expectIdentifier("expected sequence name"))
            else -> throw error("expected TABLE, INDEX, VIEW, TRIGGER, or SEQUENCE after DROP but found '${describe(peek())}'", peek())
        }
    }

    /**
     * `TRUNCATE [TABLE] t1 [, t2, ...] [RESTART IDENTITY | CONTINUE IDENTITY] [CASCADE | RESTRICT]`.
     * `TABLE` and the identity clause are both optional, matching Postgres (which also defaults to
     * CONTINUE IDENTITY when neither RESTART nor CONTINUE is written).
     */
    private fun parseTruncate(): TruncateTableStatement {
        expect(TokenType.Truncate, "expected TRUNCATE")
        match(TokenType.Table)
        val tableNames = mutableListOf(expectIdentifier("expected table name"))
        while (match(TokenType.Comma)) {
            tableNames.add(expectIdentifier("expected table name"))
        }
        val restartIdentity = when {
            match(TokenType.Restart) -> {
                expect(TokenType.Identity, "expected IDENTITY after RESTART")
                true
            }
            match(TokenType.Continue) -> {
                expect(TokenType.Identity, "expected IDENTITY after CONTINUE")
                false
            }
            else -> false
        }
        return TruncateTableStatement(tableNames, restartIdentity, parseDropMode())
    }

    private fun parseDropMode(): DropMode = when {
        match(TokenType.Cascade) -> DropMode.Cascade
        match(TokenType.Restrict) -> DropMode.Restrict
        else -> DropMode.Restrict
    }

    private fun parseAlter(): Statement {
        expect(TokenType.Alter, "expected ALTER")
        return when {
            check(TokenType.Table) -> parseAlterTable()
            check(TokenType.Sequence) -> parseAlterSequence()
            else -> throw error("expected TABLE or SEQUENCE after ALTER but found '${describe(peek())}'", peek())
        }
    }

    /**
     * Every option may appear at most once and in any order, same leniency as
     * [parseCreateSequence]. `NO MINVALUE`/`NO MAXVALUE` here are NOT equivalent to leaving the
     * option out entirely - see [AlterSequenceStatement]'s own doc.
     */
    private fun parseAlterSequence(): AlterSequenceStatement {
        expect(TokenType.Sequence, "expected SEQUENCE")
        val name = expectIdentifier("expected sequence name")
        var incrementBy: Long? = null
        var minValue: Long? = null
        var clearMinValue = false
        var maxValue: Long? = null
        var clearMaxValue = false
        var restart = false
        var restartWith: Long? = null
        var cycle: Boolean? = null
        while (true) {
            when {
                match(TokenType.Increment) -> {
                    match(TokenType.By)
                    incrementBy = expectLongLiteral("expected a value after INCREMENT BY")
                }
                match(TokenType.Minvalue) -> minValue = expectLongLiteral("expected a value after MINVALUE")
                match(TokenType.Maxvalue) -> maxValue = expectLongLiteral("expected a value after MAXVALUE")
                match(TokenType.Cycle) -> cycle = true
                match(TokenType.Restart) -> {
                    restart = true
                    if (match(TokenType.With)) restartWith = expectLongLiteral("expected a value after RESTART WITH")
                }
                check(TokenType.No) && peekIsSequenceNoClause() -> {
                    advance()
                    when {
                        match(TokenType.Minvalue) -> clearMinValue = true
                        match(TokenType.Maxvalue) -> clearMaxValue = true
                        match(TokenType.Cycle) -> cycle = false
                        else -> error("unreachable: guarded by peekIsSequenceNoClause")
                    }
                }
                else -> break
            }
        }
        return AlterSequenceStatement(name, incrementBy, minValue, clearMinValue, maxValue, clearMaxValue, restart, restartWith, cycle)
    }

    private fun parseAlterTable(): AlterTableStatement {
        expect(TokenType.Table, "expected TABLE after ALTER")
        val tableName = expectIdentifier("expected table name")
        val action: AlterTableAction = when {
            match(TokenType.Add) -> {
                if (match(TokenType.Column)) {
                    AddColumn(parseAlterAddColumnDefinition())
                } else {
                    expect(TokenType.Constraint, "expected COLUMN or CONSTRAINT after ADD")
                    val name = expectIdentifier("expected constraint name after CONSTRAINT")
                    val constraint: AddedConstraint = when {
                        check(TokenType.Foreign) -> AddedForeignKey(parseTableForeignKey().copy(name = name))
                        check(TokenType.Check) -> AddedCheck(parseTableCheck().copy(name = name))
                        check(TokenType.Unique) -> AddedUnique(parseTableUnique().copy(name = name))
                        else -> throw error(
                            "expected FOREIGN KEY, CHECK, or UNIQUE after CONSTRAINT $name but found '${describe(peek())}'",
                            peek()
                        )
                    }
                    AddConstraint(constraint)
                }
            }
            match(TokenType.Drop) -> {
                if (match(TokenType.Column)) {
                    val columnName = expectIdentifier("expected column name")
                    DropColumn(columnName, parseDropMode())
                } else {
                    expect(TokenType.Constraint, "expected COLUMN or CONSTRAINT after DROP")
                    val name = expectIdentifier("expected constraint name")
                    DropConstraint(name, parseDropMode())
                }
            }
            match(TokenType.Rename) -> {
                if (match(TokenType.To)) {
                    val newName = expectIdentifier("expected new table name")
                    RenameTable(newName)
                } else {
                    expect(TokenType.Column, "expected COLUMN or TO after RENAME")
                    val oldName = expectIdentifier("expected column name")
                    expect(TokenType.To, "expected TO after column name")
                    val newName = expectIdentifier("expected new column name")
                    RenameColumn(oldName, newName)
                }
            }
            match(TokenType.Alter) -> {
                match(TokenType.Column)
                val columnName = expectIdentifier("expected column name")
                if (!isContextualKeyword("TYPE")) {
                    throw error("expected TYPE after column name in ALTER TABLE ... ALTER COLUMN", peek())
                }
                advance()
                val spec = parseTypeSpec()
                val using = if (match(TokenType.Using)) parseExpression() else null
                ChangeColumnType(columnName, spec.type, spec.precision, spec.scale, using)
            }
            else -> throw error(
                "expected ADD, DROP, RENAME, or ALTER after table name in ALTER TABLE but found '${describe(peek())}'",
                peek()
            )
        }
        return AlterTableStatement(tableName, action)
    }

    /**
     * A deliberately restricted column definition: no PRIMARY KEY/REFERENCES/UNIQUE/CHECK, since
     * enforcing those against a table that may already hold rows (composite-key interactions,
     * FK target validation, CASCADE bookkeeping) is out of scope for a single ADD COLUMN - CREATE
     * UNIQUE INDEX already covers UNIQUE on an existing table, and CREATE TABLE covers the rest.
     * GENERATED ALWAYS AS is allowed, unlike those - see `Executor.executeAddColumn`/
     * `DiskExecutor.executeAddColumn` for why it does not share that "out of scope" reasoning
     * (it backfills by evaluating the expression per existing row, not by writing one constant).
     */
    private fun parseAlterAddColumnDefinition(): Column {
        val columnName = expectIdentifier("expected column name")
        val spec = parseTypeSpec()
        var notNull = false
        var default: Expression? = null
        var generated: Expression? = null
        var collation = Collation.Binary
        while (true) {
            when {
                check(TokenType.Not) -> {
                    advance()
                    expect(TokenType.Null, "expected NULL after NOT")
                    notNull = true
                }
                check(TokenType.Default) -> {
                    advance()
                    default = parseExpression()
                }
                isContextualKeyword("GENERATED") -> generated = parseGeneratedClause()
                check(TokenType.Collate) -> collation = parseColumnCollation()
                check(TokenType.Primary) -> throw error("ALTER TABLE ADD COLUMN cannot declare PRIMARY KEY", peek())
                check(TokenType.References) -> throw error("ALTER TABLE ADD COLUMN cannot declare REFERENCES", peek())
                check(TokenType.Unique) ->
                    throw error("ALTER TABLE ADD COLUMN cannot declare UNIQUE; use CREATE UNIQUE INDEX instead", peek())
                check(TokenType.Check) -> throw error("ALTER TABLE ADD COLUMN cannot declare CHECK", peek())
                else -> break
            }
        }
        if (generated != null && default != null) {
            throw error("a column cannot declare both DEFAULT and GENERATED ALWAYS AS", peek())
        }
        return Column(columnName, spec.type, notNull, primaryKey = false, spec.precision, spec.scale, default, generated, collation)
    }

    private fun parseExpression(): Expression = parseOr()

    private fun parseOr(): Expression {
        var left = parseAnd()
        while (match(TokenType.Or)) {
            left = BinaryExpression(BinaryOperator.Or, left, parseAnd())
        }
        return left
    }

    private fun parseAnd(): Expression {
        var left = parseNot()
        while (match(TokenType.And)) {
            left = BinaryExpression(BinaryOperator.And, left, parseNot())
        }
        return left
    }

    private fun parseNot(): Expression {
        if (match(TokenType.Not)) {
            return UnaryExpression(UnaryOperator.Not, parseNot())
        }
        return parseComparison()
    }

    private fun parseComparison(): Expression {
        var left = parseAdditive()
        while (true) {
            val negated = match(TokenType.Not)
            left = when {
                check(TokenType.In) -> { advance(); parseInSuffix(left, negated) }
                check(TokenType.Between) -> { advance(); parseBetweenSuffix(left, negated) }
                check(TokenType.Like) -> { advance(); parseLikeSuffix(left, negated) }
                negated -> throw error("expected IN, BETWEEN, or LIKE after NOT", peek())
                check(TokenType.Is) -> { advance(); parseIsSuffix(left) }
                else -> {
                    val operator = when {
                        check(TokenType.Equal) -> BinaryOperator.Equal
                        check(TokenType.NotEqual) -> BinaryOperator.NotEqual
                        check(TokenType.Less) -> BinaryOperator.Less
                        check(TokenType.LessEqual) -> BinaryOperator.LessEqual
                        check(TokenType.Greater) -> BinaryOperator.Greater
                        check(TokenType.GreaterEqual) -> BinaryOperator.GreaterEqual
                        else -> return left
                    }
                    advance()
                    BinaryExpression(operator, left, parseAdditive())
                }
            }
        }
    }

    private fun parseIsSuffix(left: Expression): Expression {
        val negated = match(TokenType.Not)
        expect(TokenType.Null, "expected NULL after IS" + if (negated) " NOT" else "")
        return IsNullExpression(left, negated)
    }

    private fun parseInSuffix(left: Expression, negated: Boolean): Expression {
        expect(TokenType.LeftParen, "expected '(' after IN")
        if (check(TokenType.Select)) {
            val select = parseSelect()
            expect(TokenType.RightParen, "expected ')' after IN subquery")
            return InSubqueryExpression(left, select, negated)
        }
        val values = mutableListOf<Expression>()
        do {
            values.add(parseExpression())
        } while (match(TokenType.Comma))
        expect(TokenType.RightParen, "expected ')' after IN list")
        return InExpression(left, values, negated)
    }

    private fun parseBetweenSuffix(left: Expression, negated: Boolean): Expression {
        val low = parseAdditive()
        expect(TokenType.And, "expected AND in BETWEEN")
        val high = parseAdditive()
        return BetweenExpression(left, low, high, negated)
    }

    private fun parseLikeSuffix(left: Expression, negated: Boolean): Expression {
        val pattern = parseAdditive()
        return LikeExpression(left, pattern, negated)
    }

    private fun parseAdditive(): Expression {
        var left = parseMultiplicative()
        while (true) {
            val operator = when {
                check(TokenType.Plus) -> BinaryOperator.Add
                check(TokenType.Minus) -> BinaryOperator.Subtract
                else -> break
            }
            advance()
            left = BinaryExpression(operator, left, parseMultiplicative())
        }
        return left
    }

    private fun parseMultiplicative(): Expression {
        var left = parseCollate()
        while (true) {
            val operator = when {
                check(TokenType.Star) -> BinaryOperator.Multiply
                check(TokenType.Slash) -> BinaryOperator.Divide
                else -> break
            }
            advance()
            left = BinaryExpression(operator, left, parseCollate())
        }
        return left
    }

    /**
     * `expr COLLATE name` - sits between unary and multiplicative in precedence, matching
     * SQLite's own grammar (binds tighter than every arithmetic/comparison operator, but looser
     * than unary `-`/`NOT`, so `-x COLLATE NOCASE` is `(-x) COLLATE NOCASE` and
     * `a || b COLLATE NOCASE` would apply to `b` alone). Left-associative chaining
     * (`x COLLATE A COLLATE B`) is accepted like SQLite accepts it - only the last one written
     * has any effect, since each wraps the previous [CollateExpression] as its own operand.
     */
    private fun parseCollate(): Expression {
        var expression = parseUnary()
        while (match(TokenType.Collate)) {
            val nameToken = expect(TokenType.Identifier, "expected a collation name after COLLATE")
            val collation = Collation.fromName(nameToken.lexeme)
                ?: throw error("no such collating sequence: ${nameToken.lexeme}", nameToken)
            expression = CollateExpression(expression, collation)
        }
        return expression
    }

    private fun parseUnary(): Expression {
        if (match(TokenType.Minus)) {
            return UnaryExpression(UnaryOperator.Negate, parseUnary())
        }
        return parsePrimary()
    }

    private fun parsePrimary(): Expression {
        val token = peek()
        return when (token.type) {
            TokenType.IntLiteral -> {
                advance(); LiteralExpression(Value.IntegerValue(token.intValue!!))
            }
            TokenType.RealLiteral -> {
                advance(); LiteralExpression(Value.RealValue(token.realValue!!))
            }
            TokenType.TextLiteral -> {
                advance(); LiteralExpression(Value.TextValue(token.textValue!!))
            }
            TokenType.True -> {
                advance(); LiteralExpression(Value.BooleanValue(true))
            }
            TokenType.False -> {
                advance(); LiteralExpression(Value.BooleanValue(false))
            }
            TokenType.Null -> {
                advance(); LiteralExpression(Value.NullValue)
            }
            TokenType.Parameter -> {
                advance()
                val index = token.intValue!!
                if (index < 1) throw error("parameter index must be >= 1 but was $index", token)
                ParameterExpression(index.toInt())
            }
            TokenType.Case -> parseCase()
            TokenType.Exists -> parseExists()
            TokenType.Cast -> parseCast()
            // LEFT/RIGHT are otherwise reserved (LEFT/RIGHT JOIN), but LEFT(str, n)/RIGHT(str, n) are
            // real, commonly expected scalar functions - only valid here as a call, never as a bare
            // column reference, so a missing '(' is unconditionally an error rather than falling back
            // to ColumnExpression like the TokenType.Identifier branch below does.
            TokenType.Left -> {
                advance()
                if (check(TokenType.LeftParen)) parseFunctionCall(token.lexeme) else throw error("expected '(' after LEFT", peek())
            }
            TokenType.Right -> {
                advance()
                if (check(TokenType.LeftParen)) parseFunctionCall(token.lexeme) else throw error("expected '(' after RIGHT", peek())
            }
            TokenType.Identifier -> {
                advance()
                when {
                    check(TokenType.LeftParen) -> parseFunctionCall(token.lexeme)
                    match(TokenType.Dot) -> ColumnExpression(expectIdentifier("expected column name after '.'"), token.lexeme)
                    else -> ColumnExpression(token.lexeme)
                }
            }
            TokenType.LeftParen -> {
                advance()
                if (check(TokenType.Select)) {
                    val select = parseSelect()
                    expect(TokenType.RightParen, "expected ')' after subquery")
                    ScalarSubqueryExpression(select)
                } else {
                    val expression = parseExpression()
                    expect(TokenType.RightParen, "expected ')' after expression")
                    expression
                }
            }
            else -> throw error("expected an expression but found '${describe(token)}'", token)
        }
    }

    private fun parseCase(): Expression {
        expect(TokenType.Case, "expected CASE")
        val operand = if (!check(TokenType.When)) parseExpression() else null
        val branches = mutableListOf<CaseBranch>()
        while (match(TokenType.When)) {
            val condition = parseExpression()
            expect(TokenType.Then, "expected THEN after CASE condition")
            val result = parseExpression()
            branches.add(CaseBranch(condition, result))
        }
        if (branches.isEmpty()) throw error("expected at least one WHEN clause in CASE expression", peek())
        val elseExpression = if (match(TokenType.Else)) parseExpression() else null
        expect(TokenType.End, "expected END to close CASE expression")
        return CaseExpression(operand, branches, elseExpression)
    }

    private fun parseCast(): Expression {
        expect(TokenType.Cast, "expected CAST")
        expect(TokenType.LeftParen, "expected '(' after CAST")
        val operand = parseExpression()
        expect(TokenType.As, "expected AS in CAST")
        val spec = parseTypeSpec()
        expect(TokenType.RightParen, "expected ')' after CAST target type")
        return CastExpression(operand, spec.type, spec.precision, spec.scale)
    }

    private fun parseExists(): Expression {
        expect(TokenType.Exists, "expected EXISTS")
        expect(TokenType.LeftParen, "expected '(' after EXISTS")
        val select = parseSelect()
        expect(TokenType.RightParen, "expected ')' after EXISTS subquery")
        return ExistsExpression(select)
    }

    private fun parseFunctionCall(name: String): Expression {
        expect(TokenType.LeftParen, "expected '(' after function name")
        val upperName = name.uppercase()
        if (upperName == "STRING_AGG") return parseStringAggCall(name)
        val isAggregate = upperName in AGGREGATE_FUNCTIONS
        var star = false
        var distinct = false
        val arguments = mutableListOf<Expression>()
        if (isAggregate && match(TokenType.Distinct)) {
            distinct = true
            arguments.add(parseExpression())
        } else if (isAggregate && match(TokenType.Star)) {
            star = true
        } else if (!check(TokenType.RightParen)) {
            do {
                arguments.add(parseExpression())
            } while (match(TokenType.Comma))
        }
        expect(TokenType.RightParen, "expected ')' after function arguments")
        // `FILTER` is not a reserved keyword either (same reasoning as ROLLUP/CUBE/GROUPING above) -
        // only meaningful right after an aggregate call's closing paren when immediately followed by
        // `(`, so a column or table legitimately named `filter` is unaffected.
        var filter: Expression? = null
        if (isContextualKeyword("FILTER") && peekNext().type == TokenType.LeftParen) {
            if (!isAggregate) throw error("FILTER can only be used with an aggregate function", peek())
            advance()
            advance()
            expect(TokenType.Where, "expected WHERE after FILTER (")
            filter = parseExpression()
            expect(TokenType.RightParen, "expected ')' to close FILTER clause")
        }
        if (check(TokenType.Over)) {
            if (distinct) throw error("DISTINCT is not supported in window functions", peek())
            if (filter != null) throw error("FILTER is not supported in window functions", peek())
            return parseWindowExpression(name, upperName, isAggregate, arguments, star)
        }
        if (isAggregate) {
            if (star) return FunctionExpression(name, argument = null, star = true, filter = filter)
            if (arguments.size != 1) {
                throw error("aggregate function $name requires exactly one argument", peek())
            }
            return FunctionExpression(name, arguments.single(), star = false, distinct = distinct, filter = filter)
        }
        if (upperName == "NEXTVAL" || upperName == "CURRVAL") {
            return parseSequenceFunctionCall(name, upperName, arguments)
        }
        if (upperName == "MERGE_ACTION") {
            if (arguments.isNotEmpty()) throw error("MERGE_ACTION takes no arguments", peek())
            return MergeActionExpression
        }
        return CallExpression(name, arguments)
    }

    /**
     * `STRING_AGG(value, separator [ORDER BY key [ASC|DESC], ...])` - shaped nothing like the rest of
     * [AGGREGATE_FUNCTIONS] (two required arguments, no `*` form, an optional ORDER BY *inside* the
     * parens that sorts values within each group independently of the query's own ORDER BY), so it
     * gets its own parse path called before `(` is even consumed for the generic aggregate branch.
     * The left paren was already consumed by [parseFunctionCall]. Never valid with `OVER` - unlike
     * COUNT/SUM/AVG/MIN/MAX, [kestrel.engine.AggregateFunctions] has no per-row windowed form for it
     * (concatenation order inside a sliding frame is a separate, unimplemented feature), so `OVER`
     * right after the closing `)` is rejected here with a clear message instead of falling through to
     * a generic "expected ';'" parse error later.
     */
    private fun parseStringAggCall(name: String): Expression {
        val value = parseExpression()
        expect(TokenType.Comma, "expected ',' after STRING_AGG value argument")
        val separator = parseExpression()
        val orderBy = parseOrderKeys()
        expect(TokenType.RightParen, "expected ')' after STRING_AGG arguments")
        if (check(TokenType.Over)) throw error("STRING_AGG cannot be used as a window function", peek())
        return FunctionExpression(name, value, star = false, separator = separator, orderBy = orderBy)
    }

    /**
     * Unlike every other function call, [arguments] must be exactly one string literal - the
     * sequence's name is resolved once here, at parse time, rather than carried as a general
     * [Expression] evaluated per row (see [NextValueExpression]'s own doc for why). A bad argument
     * (wrong count, wrong shape - a column reference, another call, ...) is a parse-time error here
     * rather than something [kestrel.engine.Evaluator] discovers later, matching how this parser
     * already treats e.g. a non-identifier index/table name elsewhere.
     */
    private fun parseSequenceFunctionCall(name: String, upperName: String, arguments: List<Expression>): Expression {
        val sequenceNameArgument = arguments.singleOrNull() as? LiteralExpression
        val sequenceName = (sequenceNameArgument?.value as? Value.TextValue)?.value
            ?: throw error("$name requires a single string literal argument naming the sequence", peek())
        return if (upperName == "NEXTVAL") NextValueExpression(sequenceName) else CurrentValueExpression(sequenceName)
    }

    /**
     * `name(args) OVER (...)`, reached once `name(args)` is already parsed and `OVER` is next.
     * Ranking functions (ROW_NUMBER/RANK/DENSE_RANK) take no arguments at all; the aggregate
     * vocabulary (COUNT/SUM/AVG/MIN/MAX) is exactly the [AGGREGATE_FUNCTIONS] set, reused here with
     * per-row windowed semantics instead of collapsing a group. LAG/LEAD take 1-3 arguments (value,
     * offset, default); FIRST_VALUE/LAST_VALUE/NTILE take exactly one. Any other name before OVER is
     * a clear error rather than silently falling back to a plain (non-window) call.
     */
    private fun parseWindowExpression(
        name: String,
        upperName: String,
        isAggregate: Boolean,
        arguments: List<Expression>,
        star: Boolean
    ): Expression {
        val kind = WINDOW_RANKING_FUNCTIONS[upperName]
            ?: WINDOW_OTHER_FUNCTIONS[upperName]
            ?: (if (isAggregate) WindowFunctionKind.Aggregate else null)
            ?: throw error("function $name cannot be used as a window function", peek())
        when (kind) {
            WindowFunctionKind.RowNumber, WindowFunctionKind.Rank, WindowFunctionKind.DenseRank ->
                if (star || arguments.isNotEmpty()) throw error("$name does not take arguments", peek())
            WindowFunctionKind.Aggregate ->
                if (!star && arguments.size != 1) throw error("window function $name requires exactly one argument", peek())
            WindowFunctionKind.Lag, WindowFunctionKind.Lead ->
                if (star || arguments.isEmpty() || arguments.size > 3) {
                    throw error("$name requires between 1 and 3 arguments", peek())
                }
            WindowFunctionKind.FirstValue, WindowFunctionKind.LastValue, WindowFunctionKind.Ntile ->
                if (star || arguments.size != 1) throw error("$name requires exactly one argument", peek())
        }
        expect(TokenType.Over, "expected OVER")
        expect(TokenType.LeftParen, "expected '(' after OVER")
        val partitionBy = mutableListOf<Expression>()
        if (match(TokenType.Partition)) {
            expect(TokenType.By, "expected BY after PARTITION")
            do {
                partitionBy.add(parseExpression())
            } while (match(TokenType.Comma))
        }
        val orderBy = parseOrderKeys()
        val frame = parseWindowFrame()
        expect(TokenType.RightParen, "expected ')' to close OVER clause")
        val argument = if (kind == WindowFunctionKind.Aggregate) arguments.singleOrNull() else arguments.getOrNull(0)
        val offset = if (kind == WindowFunctionKind.Lag || kind == WindowFunctionKind.Lead) arguments.getOrNull(1) else null
        val defaultValue = if (kind == WindowFunctionKind.Lag || kind == WindowFunctionKind.Lead) arguments.getOrNull(2) else null
        return WindowExpression(name, kind, argument, star, partitionBy, orderBy, offset, defaultValue, frame)
    }

    /**
     * `ROWS|RANGE|GROUPS BETWEEN <bound> AND <bound>`, or the shorthand `ROWS|RANGE|GROUPS <bound>`
     * (equivalent to `... BETWEEN <bound> AND CURRENT ROW`, exactly like every other SQL engine's
     * shorthand).
     */
    private fun parseWindowFrame(): WindowFrame? {
        val unit = when {
            match(TokenType.Rows) -> FrameUnit.Rows
            match(TokenType.Range) -> FrameUnit.Range
            match(TokenType.Groups) -> FrameUnit.Groups
            else -> return null
        }
        val (start, end) = if (match(TokenType.Between)) {
            val start = parseFrameBound()
            expect(TokenType.And, "expected AND between the two bounds of a frame")
            val end = parseFrameBound()
            start to end
        } else {
            parseFrameBound() to FrameBound(FrameBoundType.CurrentRow)
        }
        if (frameBoundRank(start.type) > frameBoundRank(end.type)) {
            throw error("frame start cannot come after frame end", peek())
        }
        return WindowFrame(unit, start, end, parseFrameExclude())
    }

    /**
     * `EXCLUDE CURRENT ROW | EXCLUDE GROUP | EXCLUDE TIES | EXCLUDE NO OTHERS`, optional and always
     * following the frame's bounds. Absent entirely is [FrameExclude.NoOthers], identical to writing
     * `EXCLUDE NO OTHERS` out - SQL engines never require it and this parser accepts either.
     */
    private fun parseFrameExclude(): FrameExclude {
        if (!match(TokenType.Exclude)) return FrameExclude.NoOthers
        return when {
            match(TokenType.Current) -> {
                expect(TokenType.Row, "expected ROW after EXCLUDE CURRENT")
                FrameExclude.CurrentRow
            }
            match(TokenType.Group) -> FrameExclude.Group
            match(TokenType.Ties) -> FrameExclude.Ties
            match(TokenType.No) -> {
                expect(TokenType.Others, "expected OTHERS after EXCLUDE NO")
                FrameExclude.NoOthers
            }
            else -> throw error("expected CURRENT ROW, GROUP, TIES, or NO OTHERS after EXCLUDE", peek())
        }
    }

    private fun frameBoundRank(type: FrameBoundType): Int = when (type) {
        FrameBoundType.UnboundedPreceding -> 0
        FrameBoundType.Preceding -> 1
        FrameBoundType.CurrentRow -> 2
        FrameBoundType.Following -> 3
        FrameBoundType.UnboundedFollowing -> 4
    }

    private fun parseFrameBound(): FrameBound {
        if (match(TokenType.Unbounded)) {
            return when {
                match(TokenType.Preceding) -> FrameBound(FrameBoundType.UnboundedPreceding)
                match(TokenType.Following) -> FrameBound(FrameBoundType.UnboundedFollowing)
                else -> throw error("expected PRECEDING or FOLLOWING after UNBOUNDED", peek())
            }
        }
        if (match(TokenType.Current)) {
            expect(TokenType.Row, "expected ROW after CURRENT")
            return FrameBound(FrameBoundType.CurrentRow)
        }
        val offset = parseExpression()
        return when {
            match(TokenType.Preceding) -> FrameBound(FrameBoundType.Preceding, offset)
            match(TokenType.Following) -> FrameBound(FrameBoundType.Following, offset)
            else -> throw error("expected PRECEDING or FOLLOWING", peek())
        }
    }

    private fun peek(): Token = tokens[position]

    private fun check(type: TokenType): Boolean = peek().type == type

    private fun advance(): Token {
        val token = tokens[position]
        if (token.type != TokenType.EndOfInput) position++
        return token
    }

    private fun match(type: TokenType): Boolean {
        if (check(type)) {
            advance()
            return true
        }
        return false
    }

    private fun expect(type: TokenType, message: String): Token {
        if (check(type)) return advance()
        val token = peek()
        throw error("$message but found '${describe(token)}'", token)
    }

    private fun expectIdentifier(message: String): String =
        expect(TokenType.Identifier, message).lexeme

    private fun error(message: String, token: Token): ParseException =
        ParseException(message, token.line, token.column)

    private fun describe(token: Token): String =
        if (token.type == TokenType.EndOfInput) "end of input" else token.lexeme

    private companion object {
        val AGGREGATE_FUNCTIONS = setOf("COUNT", "SUM", "AVG", "MIN", "MAX")
        val WINDOW_RANKING_FUNCTIONS = mapOf(
            "ROW_NUMBER" to WindowFunctionKind.RowNumber,
            "RANK" to WindowFunctionKind.Rank,
            "DENSE_RANK" to WindowFunctionKind.DenseRank
        )
        val WINDOW_OTHER_FUNCTIONS = mapOf(
            "LAG" to WindowFunctionKind.Lag,
            "LEAD" to WindowFunctionKind.Lead,
            "FIRST_VALUE" to WindowFunctionKind.FirstValue,
            "LAST_VALUE" to WindowFunctionKind.LastValue,
            "NTILE" to WindowFunctionKind.Ntile
        )
    }
}
