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
     * @param statementTokens the statement's tokens; trivia is ignored, so raw
     *                        lexer output may be passed
     * @param pgVersion       the PostgreSQL major version, or null when unknown
     * @return the family, or empty when the statement is allowed in a transaction
     */
    public static Optional<Family> familyOf(List<Token> statementTokens, Integer pgVersion) {
        List<Token> tokens = statementTokens.stream().filter(token -> !token.isTrivia()).toList();
        List<Token> words = tokens.stream()
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
        if (TokenWords.startsWith(words, "CREATE", "TABLESPACE")) {
            return Optional.of(Family.CREATE_TABLESPACE);
        }
        if (TokenWords.startsWith(words, "DROP", "TABLESPACE")) {
            return Optional.of(Family.DROP_TABLESPACE);
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
        if (isRefreshMaterializedViewConcurrently(tokens)) {
            return Optional.of(Family.REFRESH_MATERIALIZED_VIEW_CONCURRENTLY);
        }
        Optional<Family> reindex = reindexFamily(tokens);
        if (reindex.isPresent()) {
            return reindex;
        }
        Optional<Family> detach = detachPartitionFamily(tokens);
        if (detach.isPresent()) {
            return detach;
        }
        if (isAlterTypeAddValue(tokens) && pgVersion != null && pgVersion < ALTER_TYPE_ADD_VALUE_ALLOWED_FROM) {
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
     * Classifies {@code REFRESH MATERIALIZED VIEW CONCURRENTLY name}. The option
     * is positional and the required name follows it, so a materialized view
     * merely named {@code concurrently} is not the option.
     */
    private static boolean isRefreshMaterializedViewConcurrently(List<Token> tokens) {
        return TokenWords.startsWith(tokens, "REFRESH", "MATERIALIZED", "VIEW")
                && tokens.size() > 4
                && tokens.get(3).matchesKeyword("CONCURRENTLY")
                && isName(tokens.get(4));
    }

    /**
     * Classifies {@code REINDEX} by the object kind after an optional option list
     * (for example {@code REINDEX (CONCURRENTLY) TABLE t}). A bare object named
     * {@code concurrently} is not the option: the option always precedes the
     * required object name.
     */
    private static Optional<Family> reindexFamily(List<Token> tokens) {
        if (!TokenWords.startsWith(tokens, "REINDEX")) {
            return Optional.empty();
        }
        int kindStart = 1;
        if (kindStart < tokens.size() && isPunctuation(tokens.get(kindStart), "(")) {
            int close = matchingParen(tokens, kindStart);
            if (close < 0) {
                return Optional.empty();
            }
            for (int i = kindStart + 1; i < close; i++) {
                if (!tokens.get(i).matchesKeyword("CONCURRENTLY")) {
                    continue;
                }
                // In TABLESPACE <name>, a name spelled concurrently is not the
                // option.
                if (i > kindStart + 1 && tokens.get(i - 1).matchesKeyword("TABLESPACE")) {
                    continue;
                }
                // The option grammar is CONCURRENTLY [boolean]: an explicit
                // false/off/0 disables it, so the statement is transaction-safe.
                if (i + 1 < close && isFalseLiteral(tokens.get(i + 1))) {
                    continue;
                }
                return Optional.of(Family.REINDEX_CONCURRENTLY);
            }
            kindStart = close + 1;
        }
        int kind = -1;
        for (int i = kindStart; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.matchesKeyword("INDEX")
                    || token.matchesKeyword("TABLE")
                    || token.matchesKeyword("SCHEMA")
                    || token.matchesKeyword("DATABASE")
                    || token.matchesKeyword("SYSTEM")) {
                kind = i;
                break;
            }
        }
        if (kind < 0) {
            return Optional.empty();
        }
        // The option precedes the required object name, so a trailing token named
        // concurrently is the object, not the option.
        if (kind + 2 < tokens.size()
                && tokens.get(kind + 1).matchesKeyword("CONCURRENTLY")
                && isName(tokens.get(kind + 2))) {
            return Optional.of(Family.REINDEX_CONCURRENTLY);
        }
        return switch (tokens.get(kind).text().toUpperCase(java.util.Locale.ROOT)) {
            case "SCHEMA", "DATABASE", "SYSTEM" -> Optional.of(Family.REINDEX);
            default -> Optional.empty();
        };
    }

    /**
     * Classifies {@code ALTER TABLE ... DETACH PARTITION name CONCURRENTLY},
     * ignoring an object merely named {@code concurrently}. The name is consumed
     * as a qualified name (one token, or {@code schema.name}) so a quoted name is
     * not mistaken for the option. FINALIZE is a transactional catalog step and
     * is not transaction-forbidden.
     */
    private static Optional<Family> detachPartitionFamily(List<Token> tokens) {
        if (!TokenWords.startsWith(tokens, "ALTER", "TABLE")) {
            return Optional.empty();
        }
        for (int i = 2; i + 2 < tokens.size(); i++) {
            if (!tokens.get(i).matchesKeyword("DETACH") || !tokens.get(i + 1).matchesKeyword("PARTITION")) {
                continue;
            }
            // Skip the partition name: a bare, quoted or schema-qualified name is
            // one qualified_name, so its tokens must not be read as the option.
            int next = i + 3;
            if (next < tokens.size() && ".".equals(tokens.get(next).text())) {
                next += 2;
            }
            if (next >= tokens.size()) {
                // No room for a variant; a later DETACH action may still match.
                continue;
            }
            if (tokens.get(next).matchesKeyword("CONCURRENTLY")) {
                return Optional.of(Family.DETACH_PARTITION_CONCURRENTLY);
            }
            // Not this action's variant: keep scanning for a later DETACH.
        }
        return Optional.empty();
    }

    /**
     * Recognises {@code ALTER TYPE ... ADD VALUE [IF NOT EXISTS] 'label'}. The
     * new enum value must be a string literal, so an adjacent {@code add value}
     * inside a composite attribute definition or a function call is not the
     * grammar form.
     */
    private static boolean isAlterTypeAddValue(List<Token> tokens) {
        if (!TokenWords.startsWith(tokens, "ALTER", "TYPE")) {
            return false;
        }
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (!tokens.get(i).matchesKeyword("ADD") || !tokens.get(i + 1).matchesKeyword("VALUE")) {
                continue;
            }
            int next = i + 2;
            if (next < tokens.size() && tokens.get(next).matchesKeyword("IF")) {
                if (next + 2 >= tokens.size()
                        || !tokens.get(next + 1).matchesKeyword("NOT")
                        || !tokens.get(next + 2).matchesKeyword("EXISTS")) {
                    return false;
                }
                next += 3;
            }
            if (next < tokens.size()) {
                TokenType type = tokens.get(next).type();
                if (type == TokenType.STRING || type == TokenType.E_STRING || type == TokenType.DOLLAR_STRING) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPunctuation(Token token, String punctuation) {
        return token.type() == TokenType.PUNCTUATION && punctuation.equals(token.text());
    }

    private static boolean isFalseLiteral(Token token) {
        return token.matchesKeyword("FALSE")
                || token.matchesKeyword("OFF")
                || (token.type() == TokenType.NUMBER && token.text().equals("0"));
    }

    private static boolean isName(Token token) {
        return token.type() == TokenType.WORD || token.type() == TokenType.QUOTED_IDENTIFIER;
    }

    private static int matchingParen(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (isPunctuation(tokens.get(i), "(")) {
                depth++;
            } else if (isPunctuation(tokens.get(i), ")")) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
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
         * {@code REFRESH MATERIALIZED VIEW CONCURRENTLY}.
         */
        REFRESH_MATERIALIZED_VIEW_CONCURRENTLY,

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
         * {@code CREATE DATABASE}.
         */
        CREATE_DATABASE,

        /**
         * {@code DROP DATABASE}.
         */
        DROP_DATABASE,

        /**
         * {@code CREATE TABLESPACE}.
         */
        CREATE_TABLESPACE,

        /**
         * {@code DROP TABLESPACE}.
         */
        DROP_TABLESPACE,

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
