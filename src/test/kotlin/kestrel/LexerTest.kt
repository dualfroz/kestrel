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

    @Test
    fun predicateKeywordsAreRecognized() {
        assertEquals(
            listOf(
                TokenType.Is, TokenType.In, TokenType.Between, TokenType.Like,
                TokenType.Case, TokenType.When, TokenType.Then, TokenType.Else, TokenType.End,
                TokenType.EndOfInput
            ),
            types("IS in Between LIKE case WHEN then ELSE end")
        )
    }

    @Test
    fun existsKeywordIsRecognizedAndCaseInsensitive() {
        assertEquals(listOf(TokenType.Exists, TokenType.Exists, TokenType.EndOfInput), types("EXISTS exists"))
    }

    @Test
    fun viewAndReplaceKeywordsAreRecognizedAndCaseInsensitive() {
        assertEquals(
            listOf(TokenType.View, TokenType.Replace, TokenType.View, TokenType.Replace, TokenType.EndOfInput),
            types("VIEW REPLACE view replace")
        )
    }

    @Test
    fun compoundQueryKeywordsAreRecognizedAndCaseInsensitive() {
        assertEquals(
            listOf(
                TokenType.Union, TokenType.All, TokenType.Intersect, TokenType.Except,
                TokenType.Union, TokenType.All, TokenType.Intersect, TokenType.Except,
                TokenType.EndOfInput
            ),
            types("UNION ALL INTERSECT EXCEPT union all intersect except")
        )
    }

    @Test
    fun withAndRecursiveKeywordsAreRecognizedAndCaseInsensitive() {
        assertEquals(
            listOf(TokenType.With, TokenType.Recursive, TokenType.With, TokenType.Recursive, TokenType.EndOfInput),
            types("WITH RECURSIVE with recursive")
        )
    }

    @Test
    fun castCascadeRestrictDecimalAndDateKeywordsAreRecognizedAndCaseInsensitive() {
        assertEquals(
            listOf(
                TokenType.Cast, TokenType.Cascade, TokenType.Restrict, TokenType.Decimal, TokenType.Date,
                TokenType.Cast, TokenType.Cascade, TokenType.Restrict, TokenType.Decimal, TokenType.Date,
                TokenType.EndOfInput
            ),
            types("CAST CASCADE RESTRICT DECIMAL DATE cast cascade restrict decimal date")
        )
    }

    @Test
    fun foreignKeyKeywordsAreRecognizedAndCaseInsensitive() {
        assertEquals(
            listOf(
                TokenType.Foreign, TokenType.Key, TokenType.References, TokenType.Check,
                TokenType.No, TokenType.Action,
                TokenType.Foreign, TokenType.Key, TokenType.References, TokenType.Check,
                TokenType.No, TokenType.Action,
                TokenType.EndOfInput
            ),
            types("FOREIGN KEY REFERENCES CHECK NO ACTION foreign key references check no action")
        )
    }

    @Test
    fun uniqueKeywordIsRecognizedAndCaseInsensitive() {
        assertEquals(
            listOf(TokenType.Unique, TokenType.Unique, TokenType.EndOfInput),
            types("UNIQUE unique")
        )
    }

    @Test
    fun prepareExecuteAndDeallocateKeywordsAreRecognizedAndCaseInsensitive() {
        assertEquals(
            listOf(
                TokenType.Prepare, TokenType.Execute, TokenType.Deallocate,
                TokenType.Prepare, TokenType.Execute, TokenType.Deallocate,
                TokenType.EndOfInput
            ),
            types("PREPARE EXECUTE DEALLOCATE prepare execute deallocate")
        )
    }

    @Test
    fun tempAndTemporaryKeywordsAreRecognizedAndCaseInsensitive() {
        assertEquals(
            listOf(TokenType.Temp, TokenType.Temporary, TokenType.Temp, TokenType.Temporary, TokenType.EndOfInput),
            types("TEMP TEMPORARY temp temporary")
        )
    }

    @Test
    fun collateKeywordIsRecognizedAndCaseInsensitive() {
        assertEquals(
            listOf(TokenType.Collate, TokenType.Collate, TokenType.EndOfInput),
            types("COLLATE collate")
        )
    }

    @Test
    fun parameterTokenCarriesItsOneBasedIndex() {
        val token = lex("\$1").first()
        assertEquals(TokenType.Parameter, token.type)
        assertEquals(1L, token.intValue)
        assertEquals("\$1", token.lexeme)
    }

    @Test
    fun parameterTokenSupportsMultipleDigits() {
        val token = lex("\$42").first()
        assertEquals(TokenType.Parameter, token.type)
        assertEquals(42L, token.intValue)
    }

    @Test
    fun consecutiveParametersLexAsSeparateTokens() {
        assertEquals(
            listOf(TokenType.Parameter, TokenType.Comma, TokenType.Parameter, TokenType.EndOfInput),
            types("\$1,\$2")
        )
    }

    @Test
    fun dollarSignWithNoFollowingDigitIsALexError() {
        assertFailsWith<LexException> { lex("\$") }
        assertFailsWith<LexException> { lex("\$abc") }
    }
}
