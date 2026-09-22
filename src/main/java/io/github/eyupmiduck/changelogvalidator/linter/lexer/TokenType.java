package io.github.eyupmiduck.changelogvalidator.linter.lexer;

/**
 * The lexical category of a {@link Token}.
 *
 * <p>Whitespace, comments and Liquibase formatted-SQL directives are trivia;
 * rules should normally skip them. The string literal forms are opaque text and
 * must not be searched for SQL keywords.
 */
public enum TokenType {

    /**
     * Spaces, tabs and line breaks.
     */
    WHITESPACE,

    /**
     * A {@code --} line comment that is not a Liquibase directive.
     */
    LINE_COMMENT,

    /**
     * A block comment, including nested block comments.
     */
    BLOCK_COMMENT,

    /**
     * A Liquibase formatted-SQL directive line, for example {@code --changeset}.
     */
    DIRECTIVE,

    /**
     * A standard string literal, including the {@code B'...'} and {@code X'...'} forms.
     */
    STRING,

    /**
     * An {@code E'...'} string literal with backslash escapes.
     */
    E_STRING,

    /**
     * A dollar-quoted string, for example {@code $$...$$} or {@code $tag$...$tag$}.
     */
    DOLLAR_STRING,

    /**
     * A double-quoted identifier.
     */
    QUOTED_IDENTIFIER,

    /**
     * A positional parameter, for example {@code $1}.
     */
    PARAMETER,

    /**
     * A numeric literal.
     */
    NUMBER,

    /**
     * A bare word: an identifier or a keyword.
     */
    WORD,

    /**
     * An operator such as {@code =}, {@code ||} or {@code ::}.
     */
    OPERATOR,

    /**
     * Structural punctuation: {@code ( ) [ ] , ; .}.
     */
    PUNCTUATION,

    /**
     * Input the lexer could not classify, for example an unterminated string.
     */
    ERROR;

    /**
     * Returns whether this token is trivia: whitespace, a comment or a
     * Liquibase directive.
     *
     * @return {@code true} when the token carries no SQL meaning
     */
    public boolean isTrivia() {
        return this == WHITESPACE || this == LINE_COMMENT || this == BLOCK_COMMENT || this == DIRECTIVE;
    }

    /**
     * Returns whether this token is a string literal.
     *
     * @return {@code true} for the standard, {@code E} and dollar-quoted forms
     */
    public boolean isStringLiteral() {
        return this == STRING || this == E_STRING || this == DOLLAR_STRING;
    }
}
