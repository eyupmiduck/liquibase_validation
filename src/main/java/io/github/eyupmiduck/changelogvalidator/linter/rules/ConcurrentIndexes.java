package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;

import java.util.*;

/**
 * Finds {@code CREATE INDEX} and {@code DROP INDEX} statements that do not use
 * {@code CONCURRENTLY}, and the tables a set of SQL units creates.
 *
 * <p>This is a token-level heuristic, not a parser: it recognises the statement
 * phrases and the {@code ON <table>} / {@code CREATE TABLE <table>} names. The
 * names are normalised (unquoted identifiers fold to lower case, quoted
 * identifiers keep their case) so an index and the table it is created with can
 * be compared.
 *
 * <p>Each phrase is searched for anywhere in a statement's tokens, not only at
 * position zero: a unit whose SQL was not split into individual statements (for
 * example {@code splitStatements=false}) is one statement whose first token is
 * the first command, and a trailing {@code CREATE INDEX} must still be seen.
 */
final class ConcurrentIndexes {

    private ConcurrentIndexes() {
    }

    /**
     * Finds every {@code CREATE INDEX} / {@code DROP INDEX} statement without
     * {@code CONCURRENTLY} in {@code units}.
     *
     * @param units the SQL units to scan
     * @return the candidates, in statement order
     */
    static List<Candidate> find(List<SqlUnit> units) {
        List<Candidate> candidates = new ArrayList<>();
        for (SqlUnit unit : units) {
            for (SqlStatement statement : unit.statements()) {
                candidates.addAll(candidates(RuleSupport.nonTriviaWithin(unit.tokens(), statement), unit));
            }
        }
        return List.copyOf(candidates);
    }

    /**
     * Returns the normalised names of the tables created by {@code units}, so a
     * fresh-schema changeset can build its indexes without {@code CONCURRENTLY}.
     *
     * @param units the SQL units to scan
     * @return the table names
     */
    static Set<String> tablesCreatedIn(List<SqlUnit> units) {
        Set<String> tables = new HashSet<>();
        for (SqlUnit unit : units) {
            for (SqlStatement statement : unit.statements()) {
                List<Token> tokens = RuleSupport.nonTriviaWithin(unit.tokens(), statement);
                for (int i = 0; i < tokens.size(); i++) {
                    if (tokens.get(i).matchesKeyword("CREATE")) {
                        String table = createdTableAt(tokens, i);
                        if (table != null) {
                            tables.add(table);
                        }
                    }
                }
            }
        }
        return tables;
    }

    private static List<Candidate> candidates(List<Token> tokens, SqlUnit unit) {
        List<Candidate> result = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).matchesKeyword("CREATE")) {
                Candidate candidate = createIndex(tokens, i, unit);
                if (candidate != null) {
                    result.add(candidate);
                }
            } else if (tokens.get(i).matchesKeyword("DROP")) {
                Candidate candidate = dropIndex(tokens, i, unit);
                if (candidate != null) {
                    result.add(candidate);
                }
            }
        }
        return result;
    }

    private static Candidate createIndex(List<Token> tokens, int start, SqlUnit unit) {
        int index = start + 1;
        if (index < tokens.size() && tokens.get(index).matchesKeyword("UNIQUE")) {
            index++;
        }
        if (index >= tokens.size() || !tokens.get(index).matchesKeyword("INDEX")) {
            return null;
        }
        // CONCURRENTLY, when present, immediately follows INDEX.
        if (index + 1 < tokens.size() && tokens.get(index + 1).matchesKeyword("CONCURRENTLY")) {
            return null;
        }
        int on = TokenWords.indexOf(tokens, "ON", index + 1);
        if (on < 0) {
            return null;
        }
        int nameStart = on + 1;
        if (nameStart < tokens.size() && tokens.get(nameStart).matchesKeyword("ONLY")) {
            nameStart++;
        }
        String table = qualifiedName(tokens, nameStart);
        if (table == null) {
            return null;
        }
        return new Candidate(Kind.CREATE, table, unit, tokens.get(start));
    }

    private static Candidate dropIndex(List<Token> tokens, int start, SqlUnit unit) {
        if (start + 1 >= tokens.size() || !tokens.get(start + 1).matchesKeyword("INDEX")) {
            return null;
        }
        if (start + 2 < tokens.size() && tokens.get(start + 2).matchesKeyword("CONCURRENTLY")) {
            return null;
        }
        return new Candidate(Kind.DROP, null, unit, tokens.get(start));
    }

    private static String createdTableAt(List<Token> tokens, int start) {
        int table = start + 1;
        while (table < tokens.size() && isCreateModifier(tokens.get(table))) {
            table++;
        }
        if (table >= tokens.size() || !tokens.get(table).matchesKeyword("TABLE")) {
            return null;
        }
        int nameStart = table + 1;
        if (nameStart + 2 < tokens.size()
                && tokens.get(nameStart).matchesKeyword("IF")
                && tokens.get(nameStart + 1).matchesKeyword("NOT")
                && tokens.get(nameStart + 2).matchesKeyword("EXISTS")) {
            nameStart += 3;
        }
        return qualifiedName(tokens, nameStart);
    }

    private static boolean isCreateModifier(Token token) {
        return token.matchesKeyword("UNLOGGED")
                || token.matchesKeyword("TEMPORARY")
                || token.matchesKeyword("TEMP")
                || token.matchesKeyword("GLOBAL")
                || token.matchesKeyword("LOCAL");
    }

    private static String qualifiedName(List<Token> tokens, int start) {
        StringBuilder name = new StringBuilder();
        int i = start;
        while (i < tokens.size()) {
            String part = identifier(tokens.get(i));
            if (part == null) {
                return null;
            }
            name.append(part);
            i++;
            if (i < tokens.size() && isDot(tokens.get(i))) {
                name.append('.');
                i++;
            } else {
                break;
            }
        }
        return name.isEmpty() ? null : name.toString();
    }

    private static String identifier(Token token) {
        return switch (token.type()) {
            case WORD -> token.text().toLowerCase(Locale.ROOT);
            case QUOTED_IDENTIFIER -> token.text().substring(1, token.text().length() - 1).replace("\"\"", "\"");
            default -> null;
        };
    }

    private static boolean isDot(Token token) {
        return token.type() == TokenType.PUNCTUATION && token.text().equals(".");
    }


    /**
     * The index command.
     */
    enum Kind {

        /**
         * {@code CREATE [UNIQUE] INDEX}.
         */
        CREATE,

        /**
         * {@code DROP INDEX}.
         */
        DROP
    }

    /**
     * A non-concurrent index statement.
     *
     * @param kind  the command
     * @param table the normalised indexed table for {@link Kind#CREATE}, otherwise null
     * @param unit  the SQL unit the statement is in
     * @param token the phrase's first token, for the location
     */
    record Candidate(Kind kind, String table, SqlUnit unit, Token token) {
    }
}
