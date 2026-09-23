package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;
import io.github.eyupmiduck.changelogvalidator.linter.sql.TransactionForbiddenClassifier.Family;

import java.util.List;
import java.util.Locale;

/**
 * Shared helpers for the rules: token selection within a statement, qualified
 * identifiers, and the display name of a transaction-forbidden family.
 *
 * <p>The family display string is the public {@code statement} key a whitelist
 * entry matches on, so it is defined once here rather than copied into each rule
 * that reports a family.
 */
final class RuleSupport {

    private RuleSupport() {
    }

    /**
     * Returns the normalized text of an identifier token: an unquoted word folds
     * to lower case, a quoted identifier keeps its case (doubled quotes are
     * unescaped). Returns {@code null} for a token that is not an identifier.
     *
     * @param token the token
     * @return the normalized identifier, or null
     */
    static String identifier(Token token) {
        return switch (token.type()) {
            case WORD -> token.text().toLowerCase(Locale.ROOT);
            case QUOTED_IDENTIFIER -> token.text().substring(1, token.text().length() - 1).replace("\"\"", "\"");
            default -> null;
        };
    }

    /**
     * Returns whether the token is a {@code .} punctuation.
     *
     * @param token the token
     * @return {@code true} for a dot
     */
    static boolean isDot(Token token) {
        return token.type() == TokenType.PUNCTUATION && token.text().equals(".");
    }

    /**
     * Returns the dot-separated qualified name starting at {@code start}, or
     * {@code null} when that token is not an identifier. Only identifiers joined
     * by dots are consumed, so following keywords are not appended to the name.
     *
     * @param tokens the tokens
     * @param start  the index of the first identifier
     * @return the normalized qualified name, or null
     */
    static String qualifiedName(List<Token> tokens, int start) {
        if (start >= tokens.size()) {
            return null;
        }
        String first = identifier(tokens.get(start));
        if (first == null) {
            return null;
        }
        StringBuilder name = new StringBuilder(first);
        int i = start + 1;
        while (i + 1 < tokens.size() && isDot(tokens.get(i)) && identifier(tokens.get(i + 1)) != null) {
            name.append('.').append(identifier(tokens.get(i + 1)));
            i += 2;
        }
        return name.toString();
    }

    /**
     * Returns the non-trivia tokens within a statement's span.
     *
     * @param tokens    the SQL's tokens
     * @param statement the statement
     * @return the non-trivia tokens in the span, in order
     */
    static List<Token> nonTriviaWithin(List<Token> tokens, SqlStatement statement) {
        return TokenWords.within(tokens, statement.startOffset(), statement.endOffset());
    }

    /**
     * Returns the first token within a statement's span, for a finding location.
     * The search is bounded by {@code statement.endOffset()}; a statement with no
     * token in its span falls back to the unit's first token rather than reading
     * a later statement's token or throwing.
     *
     * @param unit      the SQL unit
     * @param statement the statement
     * @return the first token in the span, or the unit's first token as a fallback
     */
    static Token firstToken(SqlUnit unit, SqlStatement statement) {
        for (Token token : unit.tokens()) {
            if (token.startOffset() >= statement.startOffset() && token.endOffset() <= statement.endOffset()) {
                return token;
            }
        }
        return unit.tokens().get(0);
    }

    /**
     * Returns the first non-empty unit, for a finding location.
     *
     * @param units the SQL units
     * @return the first unit with a statement
     * @throws IllegalStateException when no unit has a statement
     */
    static SqlUnit firstUnit(List<SqlUnit> units) {
        return units.stream().filter(unit -> !unit.statements().isEmpty()).findFirst()
                .orElseThrow(() -> new IllegalStateException("no statements to report"));
    }

    /**
     * Returns the transaction-forbidden family's display name, the public
     * {@code statement} key a whitelist entry matches on.
     *
     * @param family the family
     * @return the display name
     */
    static String display(Family family) {
        return switch (family) {
            case CREATE_INDEX_CONCURRENTLY -> "CREATE INDEX CONCURRENTLY";
            case DROP_INDEX_CONCURRENTLY -> "DROP INDEX CONCURRENTLY";
            case REINDEX_CONCURRENTLY -> "REINDEX CONCURRENTLY";
            case DETACH_PARTITION_CONCURRENTLY -> "ALTER TABLE ... DETACH PARTITION CONCURRENTLY";
            case CREATE_DATABASE -> "CREATE DATABASE";
            case DROP_DATABASE -> "DROP DATABASE";
            case ALTER_SYSTEM -> "ALTER SYSTEM";
            case VACUUM -> "VACUUM";
            case ALTER_TYPE_ADD_VALUE -> "ALTER TYPE ... ADD VALUE";
        };
    }
}
