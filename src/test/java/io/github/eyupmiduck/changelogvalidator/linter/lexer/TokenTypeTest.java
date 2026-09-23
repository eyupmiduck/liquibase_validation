package io.github.eyupmiduck.changelogvalidator.linter.lexer;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the classification helpers on {@link TokenType} for every token
 * type.
 */
class TokenTypeTest {

    /**
     * Only whitespace, comments and directives are trivia.
     *
     * @param type every token type
     */
    @ParameterizedTest
    @EnumSource(TokenType.class)
    void isTriviaOnlyForTriviaTypes(TokenType type) {
        boolean expected = type == TokenType.WHITESPACE
                || type == TokenType.LINE_COMMENT
                || type == TokenType.BLOCK_COMMENT
                || type == TokenType.DIRECTIVE;

        assertEquals(expected, type.isTrivia());
    }

    /**
     * Only the string literal forms count as string literals.
     *
     * @param type every token type
     */
    @ParameterizedTest
    @EnumSource(TokenType.class)
    void isStringLiteralOnlyForStringTypes(TokenType type) {
        boolean expected = type == TokenType.STRING
                || type == TokenType.E_STRING
                || type == TokenType.DOLLAR_STRING;

        assertEquals(expected, type.isStringLiteral());
    }

    /**
     * Only the ERROR type is an error.
     *
     * @param type every token type
     */
    @ParameterizedTest
    @EnumSource(TokenType.class)
    void isErrorOnlyForError(TokenType type) {
        assertEquals(type == TokenType.ERROR, type.isError());
    }
}
