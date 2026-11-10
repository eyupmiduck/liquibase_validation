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
        List<Token> words = tokens.stream().filter(token -> !token.isTrivia()).toList();
        List<Classification> classifications = new ArrayList<>();
        int from = 0;
        for (SqlStatement statement : statements) {
            while (from < words.size() && words.get(from).endOffset() <= statement.startOffset()) {
                from++;
            }
            int to = from;
            while (to < words.size() && words.get(to).endOffset() <= statement.endOffset()) {
                to++;
            }
            familyOf(words.subList(from, to), pgVersion)
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
        Optional<Family> reindex = reindexFamily(words);
        if (reindex.isPresent()) {
            return reindex;
        }
        Optional<Family> detach = detachPartitionFamily(words);
        if (detach.isPresent()) {
            return detach;
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

    /**
     * Classifies {@code REINDEX} by the object kind immediately before the
     * optional {@code CONCURRENTLY}. A bare object named {@code concurrently} is
     * not the option.
     */
    private static Optional<Family> reindexFamily(List<Token> words) {
        if (!TokenWords.startsWith(words, "REINDEX")) {
            return Optional.empty();
        }
        int kind = -1;
        for (int i = 1; i < words.size(); i++) {
            Token word = words.get(i);
            if (word.matchesKeyword("INDEX")
                    || word.matchesKeyword("TABLE")
                    || word.matchesKeyword("SCHEMA")
                    || word.matchesKeyword("DATABASE")
                    || word.matchesKeyword("SYSTEM")) {
                kind = i;
                break;
            }
        }
        if (kind < 0) {
            return Optional.empty();
        }
        if (kind + 1 < words.size() && words.get(kind + 1).matchesKeyword("CONCURRENTLY")) {
            return Optional.of(Family.REINDEX_CONCURRENTLY);
        }
        return switch (words.get(kind).text().toUpperCase(java.util.Locale.ROOT)) {
            case "SCHEMA", "DATABASE", "SYSTEM" -> Optional.of(Family.REINDEX);
            default -> Optional.empty();
        };
    }

    /**
     * Classifies {@code ALTER TABLE ... DETACH PARTITION name CONCURRENTLY} and
     * its {@code FINALIZE} variant, ignoring an object merely named
     * {@code concurrently}.
     */
    private static Optional<Family> detachPartitionFamily(List<Token> words) {
        if (!TokenWords.startsWith(words, "ALTER", "TABLE")) {
            return Optional.empty();
        }
        for (int i = 2; i + 2 < words.size(); i++) {
            if (words.get(i).matchesKeyword("DETACH") && words.get(i + 1).matchesKeyword("PARTITION")) {
                if (i + 3 >= words.size()) {
                    return Optional.empty();
                }
                if (words.get(i + 3).matchesKeyword("CONCURRENTLY")) {
                    return Optional.of(Family.DETACH_PARTITION_CONCURRENTLY);
                }
                if (words.get(i + 3).matchesKeyword("FINALIZE")) {
                    return Optional.of(Family.DETACH_PARTITION_FINALIZE);
                }
                return Optional.empty();
            }
        }
        return Optional.empty();
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
         * {@code REINDEX DATABASE/SYSTEM/SCHEMA} without {@code CONCURRENTLY}.
         */
        REINDEX,

        /**
         * {@code ALTER TABLE ... DETACH PARTITION ... CONCURRENTLY}.
         */
        DETACH_PARTITION_CONCURRENTLY,

        /**
         * {@code ALTER TABLE ... DETACH PARTITION ... FINALIZE}.
         */
        DETACH_PARTITION_FINALIZE,

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
