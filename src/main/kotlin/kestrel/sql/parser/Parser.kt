package kestrel.sql.parser

import kestrel.ParseException
import kestrel.catalog.Column
import kestrel.sql.ast.Assignment
import kestrel.sql.ast.BeginStatement
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.BinaryOperator
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.CommitStatement
import kestrel.sql.ast.CreateIndexStatement
import kestrel.sql.ast.CreateTableStatement
import kestrel.sql.ast.DeleteStatement
import kestrel.sql.ast.DropIndexStatement
import kestrel.sql.ast.DropTableStatement
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.ExplainStatement
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.JoinClause
import kestrel.sql.ast.JoinType
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.OrderBy
import kestrel.sql.ast.Projection
import kestrel.sql.ast.RollbackStatement
import kestrel.sql.ast.SelectItem
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.StarProjection
import kestrel.sql.ast.Statement
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UnaryOperator
import kestrel.sql.ast.UpdateStatement
import kestrel.sql.lexer.Token
import kestrel.sql.lexer.TokenType
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
            TokenType.Select -> parseSelect()
            TokenType.Delete -> parseDelete()
            TokenType.Update -> parseUpdate()
            TokenType.Drop -> parseDrop()
            TokenType.Explain -> parseExplain()
            TokenType.Begin -> parseBegin()
            TokenType.Commit -> parseCommit()
            TokenType.Rollback -> parseRollback()
            else -> throw error("expected a statement but found '${describe(token)}'", token)
        }
    }

    private fun parseCreate(): Statement {
        expect(TokenType.Create, "expected CREATE")
        return when {
            check(TokenType.Table) -> parseCreateTable()
            check(TokenType.Index) -> parseCreateIndex()
            else -> throw error("expected TABLE or INDEX after CREATE but found '${describe(peek())}'", peek())
        }
    }

    private fun parseCreateIndex(): CreateIndexStatement {
        expect(TokenType.Index, "expected INDEX")
        val indexName = expectIdentifier("expected index name")
        expect(TokenType.On, "expected ON after index name")
        val tableName = expectIdentifier("expected table name")
        expect(TokenType.LeftParen, "expected '(' before indexed column")
        val columnName = expectIdentifier("expected column name")
        expect(TokenType.RightParen, "expected ')' after indexed column")
        return CreateIndexStatement(indexName, tableName, columnName)
    }

    private fun parseExplain(): ExplainStatement {
        expect(TokenType.Explain, "expected EXPLAIN")
        return ExplainStatement(parseSelect())
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
        match(TokenType.Transaction)
        return RollbackStatement
    }

    private fun parseCreateTable(): CreateTableStatement {
        expect(TokenType.Table, "expected TABLE after CREATE")
        val tableName = expectIdentifier("expected table name")
        expect(TokenType.LeftParen, "expected '(' after table name")
        val columns = mutableListOf<Column>()
        do {
            columns.add(parseColumnDefinition())
        } while (match(TokenType.Comma))
        expect(TokenType.RightParen, "expected ')' after column definitions")
        return CreateTableStatement(tableName, columns)
    }

    private fun parseColumnDefinition(): Column {
        val columnName = expectIdentifier("expected column name")
        val typeToken = advance()
        val type = when (typeToken.type) {
            TokenType.Integer -> ColumnType.Integer
            TokenType.Text -> ColumnType.Text
            TokenType.Boolean -> ColumnType.Boolean
            TokenType.Real -> ColumnType.Real
            else -> throw error("expected a column type but found '${describe(typeToken)}'", typeToken)
        }
        var notNull = false
        var primaryKey = false
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
                else -> break
            }
        }
        return Column(columnName, type, notNull, primaryKey)
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
        val rows = mutableListOf<List<Expression>>()
        do {
            expect(TokenType.LeftParen, "expected '(' before value list")
            val values = mutableListOf<Expression>()
            do {
                values.add(parseExpression())
            } while (match(TokenType.Comma))
            expect(TokenType.RightParen, "expected ')' after value list")
            rows.add(values)
        } while (match(TokenType.Comma))
        return InsertStatement(tableName, columns, rows)
    }

    private fun parseSelect(): SelectStatement {
        expect(TokenType.Select, "expected SELECT")
        val projection = parseProjection()
        expect(TokenType.From, "expected FROM")
        val (tableName, tableAlias) = parseTableRef()
        val joins = parseJoins()
        val where = if (match(TokenType.Where)) parseExpression() else null
        val groupBy = parseGroupBy()
        val having = if (match(TokenType.Having)) parseExpression() else null
        val orderBy = parseOrderBy()
        val limit = parseLimit()
        return SelectStatement(projection, tableName, tableAlias, joins, where, groupBy, having, orderBy, limit)
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

    private fun parseTableRef(): Pair<String, String?> {
        val tableName = expectIdentifier("expected table name")
        val alias = when {
            match(TokenType.As) -> expectIdentifier("expected alias after AS")
            check(TokenType.Identifier) -> advance().lexeme
            else -> null
        }
        return tableName to alias
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
                match(TokenType.Join) -> JoinType.Inner
                else -> break
            }
            val (tableName, alias) = parseTableRef()
            expect(TokenType.On, "expected ON after joined table")
            joins.add(JoinClause(type, tableName, alias, parseExpression()))
        }
        return joins
    }

    private fun parseGroupBy(): List<Expression> {
        if (!match(TokenType.Group)) return emptyList()
        expect(TokenType.By, "expected BY after GROUP")
        val expressions = mutableListOf<Expression>()
        do {
            expressions.add(parseExpression())
        } while (match(TokenType.Comma))
        return expressions
    }

    private fun parseOrderBy(): OrderBy? {
        if (!match(TokenType.Order)) return null
        expect(TokenType.By, "expected BY after ORDER")
        val expression = parseExpression()
        val descending = when {
            match(TokenType.Desc) -> true
            match(TokenType.Asc) -> false
            else -> false
        }
        return OrderBy(expression, descending)
    }

    private fun parseLimit(): Int? {
        if (!match(TokenType.Limit)) return null
        return expect(TokenType.IntLiteral, "expected an integer after LIMIT").intValue!!.toInt()
    }

    private fun parseDelete(): DeleteStatement {
        expect(TokenType.Delete, "expected DELETE")
        expect(TokenType.From, "expected FROM after DELETE")
        val tableName = expectIdentifier("expected table name")
        val where = if (match(TokenType.Where)) parseExpression() else null
        return DeleteStatement(tableName, where)
    }

    private fun parseUpdate(): UpdateStatement {
        expect(TokenType.Update, "expected UPDATE")
        val tableName = expectIdentifier("expected table name")
        expect(TokenType.Set, "expected SET after table name")
        val assignments = mutableListOf<Assignment>()
        do {
            val columnName = expectIdentifier("expected column name")
            expect(TokenType.Equal, "expected '=' in assignment")
            assignments.add(Assignment(columnName, parseExpression()))
        } while (match(TokenType.Comma))
        val where = if (match(TokenType.Where)) parseExpression() else null
        return UpdateStatement(tableName, assignments, where)
    }

    private fun parseDrop(): Statement {
        expect(TokenType.Drop, "expected DROP")
        return when {
            match(TokenType.Table) -> DropTableStatement(expectIdentifier("expected table name"))
            match(TokenType.Index) -> DropIndexStatement(expectIdentifier("expected index name"))
            else -> throw error("expected TABLE or INDEX after DROP but found '${describe(peek())}'", peek())
        }
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
            val operator = when {
                check(TokenType.Equal) -> BinaryOperator.Equal
                check(TokenType.NotEqual) -> BinaryOperator.NotEqual
                check(TokenType.Less) -> BinaryOperator.Less
                check(TokenType.LessEqual) -> BinaryOperator.LessEqual
                check(TokenType.Greater) -> BinaryOperator.Greater
                check(TokenType.GreaterEqual) -> BinaryOperator.GreaterEqual
                else -> break
            }
            advance()
            left = BinaryExpression(operator, left, parseAdditive())
        }
        return left
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
        var left = parseUnary()
        while (true) {
            val operator = when {
                check(TokenType.Star) -> BinaryOperator.Multiply
                check(TokenType.Slash) -> BinaryOperator.Divide
                else -> break
            }
            advance()
            left = BinaryExpression(operator, left, parseUnary())
        }
        return left
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
                val expression = parseExpression()
                expect(TokenType.RightParen, "expected ')' after expression")
                expression
            }
            else -> throw error("expected an expression but found '${describe(token)}'", token)
        }
    }

    private fun parseFunctionCall(name: String): Expression {
        expect(TokenType.LeftParen, "expected '(' after function name")
        if (match(TokenType.Star)) {
            expect(TokenType.RightParen, "expected ')' after '*'")
            return FunctionExpression(name, argument = null, star = true)
        }
        val argument = parseExpression()
        expect(TokenType.RightParen, "expected ')' after function argument")
        return FunctionExpression(name, argument, star = false)
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
}
