package kestrel

import kestrel.sql.lexer.Lexer
import kestrel.sql.lexer.TokenType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LexerTest {
    private fun lex(sql: String) = Lexer(sql).tokenize()

    private fun types(sql: String) = lex(sql).map { it.type }

    @Test
    fun keywordsAreCaseInsensitive() {
        assertEquals(
            listOf(TokenType.Select, TokenType.Select, TokenType.Select, TokenType.EndOfInput),
            types("SELECT select SeLeCt")
        )
    }

    @Test
    fun identifiersAreDistinctFromKeywords() {
        val tokens = lex("users user_name")
        assertEquals(TokenType.Identifier, tokens[0].type)
        assertEquals("users", tokens[0].lexeme)
        assertEquals(TokenType.Identifier, tokens[1].type)
        assertEquals("user_name", tokens[1].lexeme)
    }

    @Test
    fun integerLiteralCarriesValue() {
        val token = lex("42").first()
        assertEquals(TokenType.IntLiteral, token.type)
        assertEquals(42L, token.intValue)
    }

    @Test
    fun realLiteralCarriesValue() {
        val token = lex("3.14").first()
        assertEquals(TokenType.RealLiteral, token.type)
        assertEquals(3.14, token.realValue)
    }

    @Test
    fun trailingDotIsNotPartOfNumber() {
        assertEquals(
            listOf(TokenType.IntLiteral, TokenType.EndOfInput),
            types("7")
        )
    }

    @Test
    fun stringLiteralHandlesDoubledQuoteEscape() {
        val token = lex("'it''s ada'").first()
        assertEquals(TokenType.TextLiteral, token.type)
        assertEquals("it's ada", token.textValue)
    }

    @Test
    fun comparisonOperatorsAreTwoCharacterAware() {
        assertEquals(
            listOf(
                TokenType.LessEqual, TokenType.NotEqual, TokenType.GreaterEqual,
                TokenType.NotEqual, TokenType.Less, TokenType.Greater, TokenType.Equal,
                TokenType.EndOfInput
            ),
            types("<= <> >= != < > =")
        )
    }

    @Test
    fun lineCommentsAreIgnored() {
        assertEquals(
            listOf(TokenType.Select, TokenType.Star, TokenType.EndOfInput),
            types("SELECT -- pick everything\n *")
        )
    }

    @Test
    fun blockCommentsAreIgnored() {
        assertEquals(
            listOf(TokenType.Select, TokenType.Star, TokenType.EndOfInput),
            types("SELECT /* inline\n comment */ *")
        )
    }

    @Test
    fun positionsTrackLineAndColumn() {
        val tokens = lex("SELECT\n  age")
        val ageToken = tokens[1]
        assertEquals("age", ageToken.lexeme)
        assertEquals(2, ageToken.line)
        assertEquals(3, ageToken.column)
    }

    @Test
    fun unterminatedStringReportsIncomplete() {
        val exception = assertFailsWith<LexException> { lex("'open") }
        assertTrue(exception.incomplete)
    }

    @Test
    fun unexpectedCharacterFails() {
        assertFailsWith<LexException> { lex("SELECT @") }
    }
}
