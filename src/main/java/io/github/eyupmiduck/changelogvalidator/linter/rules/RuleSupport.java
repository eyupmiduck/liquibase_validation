package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;
import io.github.eyupmiduck.changelogvalidator.linter.sql.TransactionForbiddenClassifier.Family;

import java.util.List;

/**
 * Shared helpers for the rules: token selection within a statement and the
 * display name of a transaction-forbidden family.
 *
 * <p>The family display string is the public {@code statement} key a whitelist
 * entry matches on, so it is defined once here rather than copied into each rule
 * that reports a family.
 */
final class RuleSupport {

    private RuleSupport() {
    }

    /**
     * Returns the non-trivia tokens within a statement's span.
     *
     * @param tokens    the SQL's tokens
     * @param statement the statement
     * @return the non-trivia tokens in the span, in order
     */
    static List<Token> nonTriviaWithin(List<Token> tokens, SqlStatement statement) {
        return tokens.stream()
                .filter(token -> !token.isTrivia())
                .filter(token -> token.startOffset() >= statement.startOffset()
                        && token.endOffset() <= statement.endOffset())
                .toList();
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
