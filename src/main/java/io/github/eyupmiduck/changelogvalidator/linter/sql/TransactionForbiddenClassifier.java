package io.github.eyupmiduck.changelogvalidator.linter.sql;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Classifies statements PostgreSQL forbids inside a transaction block.
 *
 * <p>The classifier works on a statement's non-trivia tokens, so a keyword in a
 * string or comment cannot match. It is the basis of the rules that require such
 * a statement to live in a {@code runInTransaction="false"} changeset and to be
 * that changeset's only statement.
 *
 * <p>{@code ALTER TYPE ... ADD VALUE} is only forbidden before PostgreSQL 12, so
 * it is classified only when a {@code pgVersion} below 12 is supplied; a
 * {@code null} version does not classify it.
 */
public final class TransactionForbiddenClassifier {

    private static final int ALTER_TYPE_ADD_VALUE_ALLOWED_FROM = 12;

    private TransactionForbiddenClassifier() {
    }

    /**
     * A statement family that cannot run inside a transaction block.
     */
    public enum Family {

        /** {@code CREATE [UNIQUE] INDEX CONCURRENTLY}. */
        CREATE_INDEX_CONCURRENTLY,

        /** {@code DROP INDEX CONCURRENTLY}. */
        DROP_INDEX_CONCURRENTLY,

        /** {@code REINDEX ... CONCURRENTLY}. */
        REINDEX_CONCURRENTLY,

        /** {@code ALTER TABLE ... DETACH PARTITION ... CONCURRENTLY}. */
        DETACH_PARTITION_CONCURRENTLY,

        /** {@code CREATE DATABASE}. */
        CREATE_DATABASE,

        /** {@code DROP DATABASE}. */
        DROP_DATABASE,

        /** {@code ALTER SYSTEM}. */
        ALTER_SYSTEM,

        /** {@code VACUUM}. */
        VACUUM,

        /** {@code ALTER TYPE ... ADD VALUE}, forbidden before PostgreSQL 12. */
        ALTER_TYPE_ADD_VALUE
    }

    /**
     * A classified statement.
     *
     * @param family    the transaction-forbidden family
     * @param statement the statement, with its source span
     */
    public record Classification(Family family, SqlStatement statement) {
    }

    /**
     * Classifies every statement in {@code statements} by its tokens.
     *
     * @param tokens     the tokens of the SQL the statements come from
     * @param statements the statements
     * @param pgVersion  the PostgreSQL major version, or null when unknown
     * @return the classifications, in statement order
     */
    public static List<Classification> classify(List<Token> tokens, List<SqlStatement> statements, Integer pgVersion) {
        List<Classification> classifications = new ArrayList<>();
        for (SqlStatement statement : statements) {
            familyOf(tokensWithin(tokens, statement), pgVersion)
                    .ifPresent(family -> classifications.add(new Classification(family, statement)));
        }
        return List.copyOf(classifications);
    }

    /**
     * Classifies a single statement's tokens.
     *
     * @param statementTokens the statement's tokens
     * @param pgVersion       the PostgreSQL major version, or null when unknown
     * @return the family, or empty when the statement is allowed in a transaction
     */
    public static Optional<Family> familyOf(List<Token> statementTokens, Integer pgVersion) {
        List<Token> words = statementTokens.stream()
                .filter(token -> token.type() == TokenType.WORD)
                .toList();
        if (words.isEmpty()) {
            return Optional.empty();
        }
        if (startsWith(words, "VACUUM")) {
            return Optional.of(Family.VACUUM);
        }
        if (startsWith(words, "CREATE", "DATABASE")) {
            return Optional.of(Family.CREATE_DATABASE);
        }
        if (startsWith(words, "DROP", "DATABASE")) {
            return Optional.of(Family.DROP_DATABASE);
        }
        if (startsWith(words, "ALTER", "SYSTEM")) {
            return Optional.of(Family.ALTER_SYSTEM);
        }
        if (isIndexConcurrently(words, "CREATE")) {
            return Optional.of(Family.CREATE_INDEX_CONCURRENTLY);
        }
        if (isIndexConcurrently(words, "DROP")) {
            return Optional.of(Family.DROP_INDEX_CONCURRENTLY);
        }
        if (startsWith(words, "REINDEX") && contains(words, "CONCURRENTLY")) {
            return Optional.of(Family.REINDEX_CONCURRENTLY);
        }
        if (startsWith(words, "ALTER", "TABLE") && contains(words, "DETACH")
                && contains(words, "PARTITION") && contains(words, "CONCURRENTLY")) {
            return Optional.of(Family.DETACH_PARTITION_CONCURRENTLY);
        }
        if (isAlterTypeAddValue(words) && pgVersion != null && pgVersion < ALTER_TYPE_ADD_VALUE_ALLOWED_FROM) {
            return Optional.of(Family.ALTER_TYPE_ADD_VALUE);
        }
        return Optional.empty();
    }

    private static boolean isIndexConcurrently(List<Token> words, String command) {
        if (!startsWith(words, command)) {
            return false;
        }
        int index = indexOf(words, "INDEX", 1);
        return index >= 0 && indexOf(words, "CONCURRENTLY", index + 1) >= 0;
    }

    private static boolean isAlterTypeAddValue(List<Token> words) {
        return startsWith(words, "ALTER", "TYPE") && contains(words, "ADD") && contains(words, "VALUE");
    }

    private static boolean startsWith(List<Token> words, String... keywords) {
        if (words.size() < keywords.length) {
            return false;
        }
        for (int i = 0; i < keywords.length; i++) {
            if (!words.get(i).matchesKeyword(keywords[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(List<Token> words, String keyword) {
        return indexOf(words, keyword, 0) >= 0;
    }

    private static int indexOf(List<Token> words, String keyword, int from) {
        for (int i = from; i < words.size(); i++) {
            if (words.get(i).matchesKeyword(keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static List<Token> tokensWithin(List<Token> tokens, SqlStatement statement) {
        return tokens.stream()
                .filter(token -> token.startOffset() >= statement.startOffset()
                        && token.endOffset() <= statement.endOffset())
                .toList();
    }
}
