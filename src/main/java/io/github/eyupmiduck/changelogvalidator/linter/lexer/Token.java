package io.github.eyupmiduck.changelogvalidator.linter.lexer;

import java.util.Objects;

/**
 * A single lexical token produced by {@link SqlLexer}.
 *
 * <p>Offsets are zero-based indices into the original input; {@code endOffset}
 * is exclusive. {@code line} and {@code column} are one-based and locate the
 * first character of the token.
 *
 * @param type        the lexical category
 * @param text        the exact input text of the token
 * @param startOffset the inclusive start offset
 * @param endOffset   the exclusive end offset
 * @param line        the one-based line of the first character
 * @param column      the one-based column of the first character
 */
public record Token(TokenType type, String text, int startOffset, int endOffset, int line, int column) {

    /**
     * Validates the token's required components.
     */
    public Token {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
    }

    /**
     * Returns whether this token is trivia.
     *
     * @return {@code true} when the token is whitespace, a comment or a directive
     */
    public boolean isTrivia() {
        return type.isTrivia();
    }

    /**
     * Returns whether this token is a string literal.
     *
     * @return {@code true} for the standard, {@code E} and dollar-quoted forms
     */
    public boolean isStringLiteral() {
        return type.isStringLiteral();
    }

    /**
     * Returns whether this token is a bare word equal to {@code keyword},
     * ignoring case.
     *
     * @param keyword the keyword to compare with
     * @return {@code true} when this is a {@link TokenType#WORD} with the given text
     */
    public boolean matchesKeyword(String keyword) {
        return type == TokenType.WORD && text.equalsIgnoreCase(keyword);
    }
}
