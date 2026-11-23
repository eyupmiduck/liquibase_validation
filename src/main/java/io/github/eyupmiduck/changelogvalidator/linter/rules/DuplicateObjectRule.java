package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports a table, index, column or constraint name declared more than once in
 * the forward changelog graph.
 *
 * <p>The rule tracks {@code CREATE TABLE}, {@code CREATE [UNIQUE] INDEX},
 * {@code ALTER TABLE ... ADD [COLUMN]} and {@code ALTER TABLE ... ADD
 * [CONSTRAINT]} declarations, removes them on the matching {@code DROP} or
 * {@code ALTER TABLE ... DROP}, and skips {@code IF NOT EXISTS}. It is a
 * conservative token-level analysis: a rename is not modelled, and an
 * unqualified name is compared with the same written spelling, so it does not
 * resolve PostgreSQL's {@code search_path}. It is an opt-in warning.
 */
public final class DuplicateObjectRule implements ProjectAnalysisRule {

    /**
     * The rule id.
     */
    public static final String ID = "project-duplicate-object";

    private static boolean isCreateIndex(List<Token> tokens) {
        return TokenWords.startsWith(tokens, "CREATE", "INDEX")
                || TokenWords.startsWith(tokens, "CREATE", "UNIQUE", "INDEX");
    }

    private static void createTable(ProjectStatements.Statement statement, List<Token> tokens,
                                    Map<String, Located> tables, Map<String, Located> columns,
                                    List<Violation> violations) {
        int name = 2;
        if (TokenWords.at(tokens, name, "IF") && TokenWords.at(tokens, name + 1, "NOT")
                && TokenWords.at(tokens, name + 2, "EXISTS")) {
            return;
        }
        String table = RuleSupport.qualifiedName(tokens, name);
        if (table == null) {
            return;
        }
        boolean duplicate = tables.containsKey(table);
        report(tables, table, "table", "CREATE TABLE", statement, tokens.get(name), violations);
        tables.putIfAbsent(table, located(statement, tokens.get(name)));
        if (duplicate) {
            // The whole table definition is the duplicate; its columns are not
            // separate findings.
            return;
        }
        int open = indexOfPunctuation(tokens, "(", name + 1);
        if (open < 0) {
            return;
        }
        for (List<Token> segment : segments(tokens, open)) {
            Token first = segment.getFirst();
            if (isTableConstraint(first)) {
                continue;
            }
            String column = RuleSupport.identifier(first);
            if (column == null) {
                continue;
            }
            String key = table + "." + column;
            report(columns, key, "column", "CREATE TABLE", statement, first, violations);
            columns.putIfAbsent(key, located(statement, first));
        }
    }

    private static void createIndex(ProjectStatements.Statement statement, List<Token> tokens,
                                    Map<String, Located> indexes, List<Violation> violations) {
        int name = TokenWords.at(tokens, 1, "UNIQUE") ? 2 : 1;
        name++; // INDEX
        if (TokenWords.at(tokens, name, "CONCURRENTLY")) {
            name++;
        }
        if (TokenWords.at(tokens, name, "IF") && TokenWords.at(tokens, name + 1, "NOT")
                && TokenWords.at(tokens, name + 2, "EXISTS")) {
            return;
        }
        String index = RuleSupport.qualifiedName(tokens, name);
        if (index == null) {
            return;
        }
        report(indexes, index, "index", "CREATE INDEX", statement, tokens.get(name), violations);
        indexes.putIfAbsent(index, located(statement, tokens.get(name)));
    }

    private static void alterTable(ProjectStatements.Statement statement, List<Token> tokens,
                                   Map<String, Located> columns, Map<String, Located> constraints,
                                   List<Violation> violations) {
        int cursor = 2;
        if (TokenWords.at(tokens, cursor, "IF") && TokenWords.at(tokens, cursor + 1, "EXISTS")) {
            cursor += 2;
        }
        if (TokenWords.at(tokens, cursor, "ONLY")) {
            cursor++;
        }
        String table = RuleSupport.qualifiedName(tokens, cursor);
        if (table == null) {
            return;
        }
        for (int add = nextClause(tokens, "ADD", cursor + 1); add >= 0;
             add = nextClause(tokens, "ADD", add + 1)) {
            int item = add + 1;
            if (TokenWords.at(tokens, item, "COLUMN")) {
                item++;
                if (TokenWords.at(tokens, item, "IF") && TokenWords.at(tokens, item + 1, "NOT")
                        && TokenWords.at(tokens, item + 2, "EXISTS")) {
                    continue;
                }
                String column = RuleSupport.identifier(tokens.get(item));
                if (column == null) {
                    continue;
                }
                String key = table + "." + column;
                report(columns, key, "column", "ADD COLUMN", statement, tokens.get(item), violations);
                columns.putIfAbsent(key, located(statement, tokens.get(item)));
            } else if (TokenWords.at(tokens, item, "CONSTRAINT")) {
                item++;
                String constraint = RuleSupport.identifier(tokens.get(item));
                if (constraint == null) {
                    continue;
                }
                String key = table + "." + constraint;
                report(constraints, key, "constraint", "ADD CONSTRAINT", statement, tokens.get(item), violations);
                constraints.putIfAbsent(key, located(statement, tokens.get(item)));
            }
        }
        for (int drop = nextClause(tokens, "DROP", cursor + 1); drop >= 0;
             drop = nextClause(tokens, "DROP", drop + 1)) {
            int item = drop + 1;
            if (TokenWords.at(tokens, item, "COLUMN")) {
                item++;
                if (TokenWords.at(tokens, item, "IF") && TokenWords.at(tokens, item + 1, "EXISTS")) {
                    item += 2;
                }
                String column = RuleSupport.identifier(tokens.get(item));
                if (column != null) {
                    columns.remove(table + "." + column);
                }
            } else if (TokenWords.at(tokens, item, "CONSTRAINT")) {
                item++;
                if (TokenWords.at(tokens, item, "IF") && TokenWords.at(tokens, item + 1, "EXISTS")) {
                    item += 2;
                }
                String constraint = RuleSupport.identifier(tokens.get(item));
                if (constraint != null) {
                    constraints.remove(table + "." + constraint);
                }
            }
        }
    }

    private static void dropTable(ProjectStatements.Statement statement, List<Token> tokens,
                                  Map<String, Located> tables, Map<String, Located> columns,
                                  Map<String, Located> constraints) {
        int name = 2;
        if (TokenWords.at(tokens, name, "IF") && TokenWords.at(tokens, name + 1, "EXISTS")) {
            name += 2;
        }
        String table = RuleSupport.qualifiedName(tokens, name);
        if (table == null) {
            return;
        }
        tables.remove(table);
        removeChildren(columns, table);
        removeChildren(constraints, table);
    }

    private static void dropIndex(List<Token> tokens, Map<String, Located> indexes) {
        int name = 2;
        if (TokenWords.at(tokens, name, "CONCURRENTLY")) {
            name++;
        }
        if (TokenWords.at(tokens, name, "IF") && TokenWords.at(tokens, name + 1, "EXISTS")) {
            name += 2;
        }
        String index = RuleSupport.qualifiedName(tokens, name);
        if (index != null) {
            indexes.remove(index);
        }
    }

    private static void removeChildren(Map<String, Located> seen, String table) {
        seen.keySet().removeIf(key -> key.startsWith(table + "."));
    }

    private static void report(Map<String, Located> seen, String key, String kind, String statement,
                               ProjectStatements.Statement current, Token token, List<Violation> violations) {
        Located first = seen.get(key);
        if (first != null) {
            violations.add(new Violation(current.changeSet(), current.unit().file(), token.line(), token.column(),
                    statement,
                    kind + " '" + key + "' is declared again; first declared at "
                            + first.file().getFileName() + ":" + first.line(),
                    "Make the " + kind + " name unique or remove the duplicate declaration."));
        }
    }

    private static Located located(ProjectStatements.Statement statement, Token token) {
        return new Located(statement.changeSet(), statement.unit().file(), token.line(), token.column());
    }

    private static int nextClause(List<Token> tokens, String keyword, int from) {
        int depth = 0;
        for (int i = from; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (isPunctuation(token, "(")) {
                depth++;
            } else if (isPunctuation(token, ")")) {
                depth--;
            } else if (depth == 0 && token.matchesKeyword(keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfPunctuation(List<Token> tokens, String punctuation, int from) {
        for (int i = from; i < tokens.size(); i++) {
            if (isPunctuation(tokens.get(i), punctuation)) {
                return i;
            }
        }
        return -1;
    }

    private static List<List<Token>> segments(List<Token> tokens, int open) {
        int close = matchingParen(tokens, open);
        if (close < 0) {
            return List.of();
        }
        List<List<Token>> segments = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int depth = 0;
        for (int i = open + 1; i < close; i++) {
            Token token = tokens.get(i);
            if (isPunctuation(token, "(")) {
                depth++;
            } else if (isPunctuation(token, ")")) {
                depth--;
            }
            if (depth == 0 && isPunctuation(token, ",")) {
                if (!current.isEmpty()) {
                    segments.add(current);
                }
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        if (!current.isEmpty()) {
            segments.add(current);
        }
        return segments;
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

    private static boolean isTableConstraint(Token token) {
        return token.matchesKeyword("CONSTRAINT")
                || token.matchesKeyword("PRIMARY")
                || token.matchesKeyword("UNIQUE")
                || token.matchesKeyword("CHECK")
                || token.matchesKeyword("FOREIGN")
                || token.matchesKeyword("EXCLUDE")
                || token.matchesKeyword("LIKE");
    }

    private static boolean isPunctuation(Token token, String text) {
        return token.type() == TokenType.PUNCTUATION && token.text().equals(text);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARNING;
    }

    @Override
    public List<Violation> check(ProjectContext context) {
        Map<String, Located> tables = new LinkedHashMap<>();
        Map<String, Located> indexes = new LinkedHashMap<>();
        Map<String, Located> columns = new LinkedHashMap<>();
        Map<String, Located> constraints = new LinkedHashMap<>();
        List<Violation> violations = new ArrayList<>();
        for (ProjectStatements.Statement statement : ProjectStatements.forward(context)) {
            List<Token> tokens = statement.tokens();
            if (TokenWords.startsWith(tokens, "CREATE", "TABLE")) {
                createTable(statement, tokens, tables, columns, violations);
            } else if (isCreateIndex(tokens)) {
                createIndex(statement, tokens, indexes, violations);
            } else if (TokenWords.startsWith(tokens, "ALTER", "TABLE")) {
                alterTable(statement, tokens, columns, constraints, violations);
            } else if (TokenWords.startsWith(tokens, "DROP", "TABLE")) {
                dropTable(statement, tokens, tables, columns, constraints);
            } else if (TokenWords.startsWith(tokens, "DROP", "INDEX")) {
                dropIndex(tokens, indexes);
            }
        }
        return List.copyOf(violations);
    }

    private record Located(ChangeSet changeSet, Path file, int line, int column) {
    }

    @Override
    public String description() {
        return "a table, index, column or constraint name declared twice";
    }

    @Override
    public String help() {
        return "Remove the duplicate declaration or rename one object.";
    }
}
