package io.github.eyupmiduck.changelogvalidator.linter.sql;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;

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
            familyOf(TokenWords.within(tokens, statement.startOffset(), statement.endOffset()), pgVersion)
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
        if (TokenWords.startsWith(words, "VACUUM")) {
            return Optional.of(Family.VACUUM);
        }
        if (TokenWords.startsWith(words, "CREATE", "DATABASE")) {
            return Optional.of(Family.CREATE_DATABASE);
        }
        if (TokenWords.startsWith(words, "DROP", "DATABASE")) {
            return Optional.of(Family.DROP_DATABASE);
        }
        if (TokenWords.startsWith(words, "ALTER", "SYSTEM")) {
            return Optional.of(Family.ALTER_SYSTEM);
        }
        if (isIndexConcurrently(words, "CREATE")) {
            return Optional.of(Family.CREATE_INDEX_CONCURRENTLY);
        }
        if (isIndexConcurrently(words, "DROP")) {
            return Optional.of(Family.DROP_INDEX_CONCURRENTLY);
        }
        if (TokenWords.startsWith(words, "REINDEX") && TokenWords.contains(words, "CONCURRENTLY")) {
            return Optional.of(Family.REINDEX_CONCURRENTLY);
        }
        if (TokenWords.startsWith(words, "ALTER", "TABLE") && TokenWords.adjacent(words, "DETACH", "PARTITION")
                && TokenWords.contains(words, "CONCURRENTLY")) {
            return Optional.of(Family.DETACH_PARTITION_CONCURRENTLY);
        }
        if (isAlterTypeAddValue(words) && pgVersion != null && pgVersion < ALTER_TYPE_ADD_VALUE_ALLOWED_FROM) {
            return Optional.of(Family.ALTER_TYPE_ADD_VALUE);
        }
        return Optional.empty();
    }

    private static boolean isIndexConcurrently(List<Token> words, String command) {
        if (!TokenWords.startsWith(words, command)) {
            return false;
        }
        // CREATE [UNIQUE] INDEX CONCURRENTLY / DROP INDEX CONCURRENTLY: CONCURRENTLY
        // must immediately follow INDEX, so an identifier of the same name
        // elsewhere in the statement does not match.
        int index = 1;
        if ("CREATE".equalsIgnoreCase(command) && index < words.size() && words.get(index).matchesKeyword("UNIQUE")) {
            index++;
        }
        return index + 1 < words.size()
                && words.get(index).matchesKeyword("INDEX")
                && words.get(index + 1).matchesKeyword("CONCURRENTLY");
    }

    private static boolean isAlterTypeAddValue(List<Token> words) {
        return TokenWords.startsWith(words, "ALTER", "TYPE") && TokenWords.adjacent(words, "ADD", "VALUE");
    }


    /**
     * A statement family that cannot run inside a transaction block.
     */
    public enum Family {

        /**
         * {@code CREATE [UNIQUE] INDEX CONCURRENTLY}.
         */
        CREATE_INDEX_CONCURRENTLY,

        /**
         * {@code DROP INDEX CONCURRENTLY}.
         */
        DROP_INDEX_CONCURRENTLY,

        /**
         * {@code REINDEX ... CONCURRENTLY}.
         */
        REINDEX_CONCURRENTLY,

        /**
         * {@code ALTER TABLE ... DETACH PARTITION ... CONCURRENTLY}.
         */
        DETACH_PARTITION_CONCURRENTLY,

        /**
         * {@code CREATE DATABASE}.
         */
        CREATE_DATABASE,

        /**
         * {@code DROP DATABASE}.
         */
        DROP_DATABASE,

        /**
         * {@code ALTER SYSTEM}.
         */
        ALTER_SYSTEM,

        /**
         * {@code VACUUM}.
         */
        VACUUM,

        /**
         * {@code ALTER TYPE ... ADD VALUE}, forbidden before PostgreSQL 12.
         */
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
}
