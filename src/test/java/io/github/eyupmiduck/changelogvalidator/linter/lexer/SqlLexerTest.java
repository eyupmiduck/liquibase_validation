package io.github.eyupmiduck.changelogvalidator.linter.lexer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link SqlLexer}: it classifies each supported lexical form, keeps
 * the token stream lossless, tracks positions, recognises Liquibase
 * formatted-SQL directives, and reports unclassifiable input as
 * {@link TokenType#ERROR} instead of skipping it.
 */
class SqlLexerTest {

    /**
     * Each supported lexical form is produced as a single token of the expected
     * type.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("singleTokenForms")
    void classifiesSingleTokenForms(String sql, TokenType expected) {
        List<Token> tokens = SqlLexer.tokenize(sql);

        assertEquals(1, tokens.size());
        assertEquals(expected, tokens.get(0).type());
    }

    private static Stream<Arguments> singleTokenForms() {
        return Stream.of(
                Arguments.of(" ", TokenType.WHITESPACE),
                Arguments.of("-- plain", TokenType.LINE_COMMENT),
                Arguments.of("/* plain */", TokenType.BLOCK_COMMENT),
                Arguments.of("'a''b'", TokenType.STRING),
                Arguments.of("B'1010'", TokenType.STRING),
                Arguments.of("X'1f'", TokenType.STRING),
                Arguments.of("E'a\\'b'", TokenType.E_STRING),
                Arguments.of("$$a;b$$", TokenType.DOLLAR_STRING),
                Arguments.of("$tag$a;b$tag$", TokenType.DOLLAR_STRING),
                Arguments.of("\"Col\"", TokenType.QUOTED_IDENTIFIER),
                Arguments.of("$1", TokenType.PARAMETER),
                Arguments.of("3.14", TokenType.NUMBER),
                Arguments.of(".5", TokenType.NUMBER),
                Arguments.of("5.", TokenType.NUMBER),
                Arguments.of("1e10", TokenType.NUMBER),
                Arguments.of("1E+3", TokenType.NUMBER),
                Arguments.of("0x1f", TokenType.NUMBER),
                Arguments.of("0o17", TokenType.NUMBER),
                Arguments.of("0b1010", TokenType.NUMBER),
                Arguments.of("1.5e-3", TokenType.NUMBER),
                Arguments.of("e'a'", TokenType.E_STRING),
                Arguments.of("b'1'", TokenType.STRING),
                Arguments.of("x'f'", TokenType.STRING),
                Arguments.of("$$$$", TokenType.DOLLAR_STRING),
                Arguments.of("\"a\"\"b\"", TokenType.QUOTED_IDENTIFIER),
                Arguments.of("select", TokenType.WORD),
                Arguments.of("::", TokenType.OPERATOR),
                Arguments.of("->>", TokenType.OPERATOR),
                Arguments.of("@>", TokenType.OPERATOR),
                Arguments.of("(", TokenType.PUNCTUATION),
                Arguments.of(")", TokenType.PUNCTUATION),
                Arguments.of("[", TokenType.PUNCTUATION),
                Arguments.of("]", TokenType.PUNCTUATION),
                Arguments.of(",", TokenType.PUNCTUATION),
                Arguments.of(".", TokenType.PUNCTUATION),
                Arguments.of(";", TokenType.PUNCTUATION));
    }

    /**
     * An empty input has no tokens.
     */
    @Test
    void handlesEmptyInput() {
        assertTrue(SqlLexer.tokenize("").isEmpty());
    }

    /**
     * Every whitespace character forms a single whitespace token.
     */
    @Test
    void classifiesAllWhitespaceKinds() {
        List<Token> tokens = SqlLexer.tokenize("\t\r\f\u000B");

        assertEquals(1, tokens.size());
        assertEquals(TokenType.WHITESPACE, tokens.get(0).type());
    }

    /**
     * A string prefix that is not followed by a quote is an ordinary word.
     */
    @Test
    void treatsPrefixWithoutQuoteAsWord() {
        List<Token> tokens = SqlLexer.tokenize("E x").stream().filter(t -> !t.isTrivia()).toList();

        assertEquals(TokenType.WORD, tokens.get(0).type());
        assertEquals("E", tokens.get(0).text());
        assertEquals(TokenType.WORD, tokens.get(1).type());
    }

    /**
     * An exponent marker without exponent digits ends the number before the
     * marker.
     */
    @Test
    void treatsTrailingExponentMarkerAsWord() {
        List<Token> tokens = SqlLexer.tokenize("1e").stream().filter(t -> !t.isTrivia()).toList();

        assertEquals(TokenType.NUMBER, tokens.get(0).type());
        assertEquals("1", tokens.get(0).text());
        assertEquals(TokenType.WORD, tokens.get(1).type());
        assertEquals("e", tokens.get(1).text());
    }

    /**
     * A lone dollar sign is an error, not a skipped character.
     */
    @Test
    void reportsLoneDollarAsError() {
        List<Token> tokens = SqlLexer.tokenize("$");

        assertEquals(1, tokens.size());
        assertEquals(TokenType.ERROR, tokens.get(0).type());
    }

    /**
     * Every formatted-SQL directive keyword is recognised, but the same word
     * after a space is an ordinary comment.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("directiveLines")
    void recognisesEveryDirectiveKeyword(String sql, TokenType expected) {
        List<Token> tokens = SqlLexer.tokenize(sql);

        assertEquals(1, tokens.size());
        assertEquals(expected, tokens.get(0).type());
    }

    private static Stream<Arguments> directiveLines() {
        return Stream.of(
                Arguments.of("--liquibase formatted sql", TokenType.DIRECTIVE),
                Arguments.of("--changeset me:1", TokenType.DIRECTIVE),
                Arguments.of("--rollback DROP TABLE t", TokenType.DIRECTIVE),
                Arguments.of("--preconditions onFail:HALT", TokenType.DIRECTIVE),
                Arguments.of("--property name:value", TokenType.DIRECTIVE),
                Arguments.of("--comment note", TokenType.DIRECTIVE),
                Arguments.of("-- spaced", TokenType.LINE_COMMENT));
    }

    /**
     * A line comment runs to the end of the input when there is no newline.
     */
    @Test
    void handlesLineCommentAtEndOfInput() {
        List<Token> tokens = SqlLexer.tokenize("--x");

        assertEquals(1, tokens.size());
        assertEquals(TokenType.LINE_COMMENT, tokens.get(0).type());
        assertEquals("--x", tokens.get(0).text());
    }

    /**
     * Concatenating the token texts reproduces the input exactly.
     */
    @Test
    void isLossless() {
        String sql = """
                CREATE OR REPLACE FUNCTION ddl_utils.f() RETURNS void AS $$
                BEGIN
                    RAISE NOTICE 'a;b';
                END;
                $$ LANGUAGE plpgsql;
                """;

        List<Token> tokens = SqlLexer.tokenize(sql);

        assertEquals(sql, tokens.stream().map(Token::text).reduce("", String::concat));
    }

    /**
     * The first character of each token is located by one-based line and column.
     */
    @Test
    void tracksLineAndColumn() {
        List<Token> tokens = SqlLexer.tokenize("select\n  x");

        assertEquals(3, tokens.size());
        assertEquals(1, tokens.get(0).line());
        assertEquals(1, tokens.get(0).column());
        assertEquals(1, tokens.get(1).line());
        assertEquals(7, tokens.get(1).column());
        assertEquals(2, tokens.get(2).line());
        assertEquals(3, tokens.get(2).column());
    }

    /**
     * Block comments nest, so the whole construct is a single comment token.
     */
    @Test
    void recognisesNestedBlockComments() {
        List<Token> tokens = SqlLexer.tokenize("/* a /* b */ c */");

        assertEquals(1, tokens.size());
        assertEquals(TokenType.BLOCK_COMMENT, tokens.get(0).type());
        assertEquals("/* a /* b */ c */", tokens.get(0).text());
    }

    /**
     * Liquibase formatted-SQL directives immediately after the marker are
     * directives, while an ordinary comment is not.
     */
    @Test
    void recognisesLiquibaseDirectives() {
        String sql = """
                --liquibase formatted sql
                --changeset me:1 runInTransaction:false
                CREATE INDEX CONCURRENTLY idx ON t (c);
                --rollback DROP INDEX CONCURRENTLY idx;
                -- note
                """;

        List<Token> tokens = SqlLexer.tokenize(sql);
        List<Token> directives = tokens.stream().filter(t -> t.type() == TokenType.DIRECTIVE).toList();
        List<Token> comments = tokens.stream().filter(t -> t.type() == TokenType.LINE_COMMENT).toList();

        assertEquals(3, directives.size());
        assertEquals("--changeset me:1 runInTransaction:false", directives.get(1).text());
        assertEquals(1, comments.size());
        assertEquals("-- note", comments.get(0).text());
    }

    /**
     * A dollar-quoted routine body is one token, so the semicolons and quotes
     * inside it are not statement boundaries.
     */
    @Test
    void keepsRoutineBodyAsOneDollarString() {
        String sql = """
                CREATE FUNCTION f() RETURNS void AS $$
                BEGIN
                    PERFORM 1; PERFORM 'x';
                END;
                $$ LANGUAGE plpgsql;
                """;

        List<Token> tokens = SqlLexer.tokenize(sql);
        List<Token> bodies = tokens.stream().filter(t -> t.type() == TokenType.DOLLAR_STRING).toList();

        assertEquals(1, bodies.size());
        assertTrue(bodies.get(0).text().contains("PERFORM 1;"));
        assertTrue(bodies.get(0).text().startsWith("$$"));
    }

    /**
     * A parameter is not a dollar quote, and a string prefix is only recognised
     * at the start of a token.
     */
    @Test
    void distinguishesParametersAndPrefixes() {
        List<Token> tokens = SqlLexer.tokenize("$1 numb'x' B'101'").stream()
                .filter(token -> !token.isTrivia())
                .toList();

        assertEquals(4, tokens.size());
        assertEquals(TokenType.PARAMETER, tokens.get(0).type());
        assertEquals(TokenType.WORD, tokens.get(1).type());
        assertEquals("numb", tokens.get(1).text());
        assertEquals(TokenType.STRING, tokens.get(2).type());
        assertEquals("'x'", tokens.get(2).text());
        assertEquals(TokenType.STRING, tokens.get(3).type());
        assertEquals("B'101'", tokens.get(3).text());
    }

    /**
     * Unterminated strings, dollar quotes, comments and identifiers are reported
     * as a single error token spanning to the end of the input.
     */
    @ParameterizedTest(name = "unterminated: {0}")
    @MethodSource("unterminatedInputs")
    void reportsUnterminatedConstructsAsError(String sql) {
        List<Token> tokens = SqlLexer.tokenize(sql);

        assertEquals(1, tokens.size());
        assertEquals(TokenType.ERROR, tokens.get(0).type());
        assertEquals(0, tokens.get(0).startOffset());
        assertEquals(sql.length(), tokens.get(0).endOffset());
    }

    private static Stream<Arguments> unterminatedInputs() {
        return Stream.of(
                Arguments.of("'abc"),
                Arguments.of("E'abc\\'"),
                Arguments.of("$$abc"),
                Arguments.of("$tag$abc"),
                Arguments.of("/* abc"),
                Arguments.of("\"abc"));
    }

    /**
     * A character that cannot start any token is an error token, not a skip.
     */
    @Test
    void reportsUnknownCharacterAsError() {
        List<Token> tokens = SqlLexer.tokenize("a { b");

        assertEquals(5, tokens.size());
        assertEquals(TokenType.ERROR, tokens.get(2).type());
        assertEquals("{", tokens.get(2).text());
    }

    /**
     * Keyword matching is case-insensitive and only applies to bare words.
     */
    @Test
    void matchesKeywordsIgnoringCase() {
        Token keyword = SqlLexer.tokenize("CREATE").get(0);
        Token identifier = SqlLexer.tokenize("\"CREATE\"").get(0);

        assertTrue(keyword.matchesKeyword("create"));
        assertFalse(identifier.matchesKeyword("create"));
    }
}
