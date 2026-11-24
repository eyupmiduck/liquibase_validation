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
        int nameEnd = qualifiedNameEnd(tokens, name);
        if (TokenWords.at(tokens, nameEnd, "AS")) {
            // CREATE TABLE ... AS SELECT: a later paren belongs to the query, not
            // to a column list.
            return;
        }
        int open = indexOfPunctuation(tokens, "(", nameEnd);
        if (open < 0) {
            return;
        }
        for (List<Token> segment : segments(tokens, open)) {
            Token first = segment.getFirst();
            if (isTableConstraint(segment)) {
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
                                    Map<String, Located> indexes, Map<String, String> indexTables,
                                    List<Violation> violations) {
        int name = TokenWords.at(tokens, 1, "UNIQUE") ? 2 : 1;
        name++; // INDEX
        if (TokenWords.at(tokens, name, "CONCURRENTLY")) {
            name++;
        }
        if (TokenWords.at(tokens, name, "ON")) {
            // Unnamed index: PostgreSQL generates the name, so there is no
            // declared name to track for duplicates.
            return;
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
        int on = TokenWords.indexOf(tokens, "ON", name + 1);
        if (on >= 0) {
            int tableStart = on + 1;
            if (TokenWords.at(tokens, tableStart, "ONLY")) {
                tableStart++;
            }
            String table = RuleSupport.qualifiedName(tokens, tableStart);
            if (table != null) {
                indexTables.putIfAbsent(index, table);
            }
        }
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
        // PostgreSQL executes the actions left to right, so a DROP followed by an
        // ADD of the same name is not a duplicate. Visit the clauses in textual
        // order instead of grouping all ADDs before all DROPs.
        int depth = 0;
        for (int i = cursor + 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (isPunctuation(token, "(")) {
                depth++;
                continue;
            }
            if (isPunctuation(token, ")")) {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth != 0 || !isActionStart(tokens, i, cursor)) {
                continue;
            }
            if (token.matchesKeyword("ADD")) {
                addAction(tokens, i, table, statement, columns, constraints, violations);
            } else if (token.matchesKeyword("DROP")) {
                dropAction(tokens, i, table, columns, constraints);
            }
        }
    }

    private static void addAction(List<Token> tokens, int add, String table,
                                  ProjectStatements.Statement statement, Map<String, Located> columns,
                                  Map<String, Located> constraints, List<Violation> violations) {
        int item = add + 1;
        if (TokenWords.at(tokens, item, "COLUMN")) {
            item++;
            if (skipIfNotExists(tokens, item)) {
                return;
            }
            if (item >= tokens.size()) {
                return;
            }
            String column = RuleSupport.identifier(tokens.get(item));
            if (column == null) {
                return;
            }
            String key = table + "." + column;
            report(columns, key, "column", "ADD COLUMN", statement, tokens.get(item), violations);
            columns.putIfAbsent(key, located(statement, tokens.get(item)));
            return;
        }
        if (TokenWords.at(tokens, item, "CONSTRAINT")) {
            item++;
            if (item >= tokens.size()) {
                return;
            }
            String constraint = RuleSupport.identifier(tokens.get(item));
            if (constraint == null) {
                return;
            }
            String key = table + "." + constraint;
            report(constraints, key, "constraint", "ADD CONSTRAINT", statement, tokens.get(item), violations);
            constraints.putIfAbsent(key, located(statement, tokens.get(item)));
            return;
        }
        // ADD [COLUMN] is optional; a bare identifier names a column unless it
        // starts an unnamed constraint kind.
        if (skipIfNotExists(tokens, item) || item >= tokens.size() || startsUnnamedConstraint(tokens, item)) {
            return;
        }
        String column = RuleSupport.identifier(tokens.get(item));
        if (column == null) {
            return;
        }
        String key = table + "." + column;
        report(columns, key, "column", "ADD COLUMN", statement, tokens.get(item), violations);
        columns.putIfAbsent(key, located(statement, tokens.get(item)));
    }

    private static void dropAction(List<Token> tokens, int drop, String table,
                                   Map<String, Located> columns, Map<String, Located> constraints) {
        int item = drop + 1;
        if (TokenWords.at(tokens, item, "COLUMN")) {
            item++;
            if (skipIfExists(tokens, item)) {
                item += 2;
            }
            if (item >= tokens.size()) {
                return;
            }
            String column = RuleSupport.identifier(tokens.get(item));
            if (column != null) {
                columns.remove(table + "." + column);
            }
            return;
        }
        if (TokenWords.at(tokens, item, "CONSTRAINT")) {
            item++;
            if (skipIfExists(tokens, item)) {
                item += 2;
            }
            if (item >= tokens.size()) {
                return;
            }
            String constraint = RuleSupport.identifier(tokens.get(item));
            if (constraint != null) {
                constraints.remove(table + "." + constraint);
            }
            return;
        }
        // DROP [COLUMN] is optional; a bare identifier names a column.
        if (skipIfExists(tokens, item)) {
            item += 2;
        }
        if (item >= tokens.size()) {
            return;
        }
        String column = RuleSupport.identifier(tokens.get(item));
        if (column != null) {
            columns.remove(table + "." + column);
        }
    }

    private static boolean skipIfNotExists(List<Token> tokens, int index) {
        return TokenWords.at(tokens, index, "IF") && TokenWords.at(tokens, index + 1, "NOT")
                && TokenWords.at(tokens, index + 2, "EXISTS");
    }

    private static boolean skipIfExists(List<Token> tokens, int index) {
        return TokenWords.at(tokens, index, "IF") && TokenWords.at(tokens, index + 1, "EXISTS");
    }

    private static void dropTable(ProjectStatements.Statement statement, List<Token> tokens,
                                  Map<String, Located> tables, Map<String, Located> columns,
                                  Map<String, Located> constraints, Map<String, Located> indexes,
                                  Map<String, String> indexTables) {
        int name = 2;
        if (TokenWords.at(tokens, name, "IF") && TokenWords.at(tokens, name + 1, "EXISTS")) {
            name += 2;
        }
        while (name < tokens.size()) {
            String table = RuleSupport.qualifiedName(tokens, name);
            if (table == null) {
                return;
            }
            tables.remove(table);
            removeChildren(columns, table);
            removeChildren(constraints, table);
            removeIndexesFor(indexes, indexTables, table);
            name = qualifiedNameEnd(tokens, name);
            if (!isPunctuation(tokens, name, ",")) {
                return;
            }
            name++;
        }
    }

    private static void dropIndex(List<Token> tokens, Map<String, Located> indexes,
                                  Map<String, String> indexTables) {
        int name = 2;
        if (TokenWords.at(tokens, name, "CONCURRENTLY")) {
            name++;
        }
        if (TokenWords.at(tokens, name, "IF") && TokenWords.at(tokens, name + 1, "EXISTS")) {
            name += 2;
        }
        while (name < tokens.size()) {
            String index = RuleSupport.qualifiedName(tokens, name);
            if (index == null) {
                return;
            }
            indexes.remove(index);
            indexTables.remove(index);
            name = qualifiedNameEnd(tokens, name);
            if (!isPunctuation(tokens, name, ",")) {
                return;
            }
            name++;
        }
    }

    private static void removeIndexesFor(Map<String, Located> indexes, Map<String, String> indexTables,
                                         String table) {
        List<String> dropped = new ArrayList<>();
        for (Map.Entry<String, String> entry : indexTables.entrySet()) {
            if (RuleSupport.containsTable(java.util.Set.of(table), entry.getValue())) {
                dropped.add(entry.getKey());
            }
        }
        for (String index : dropped) {
            indexes.remove(index);
            indexTables.remove(index);
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

    /**
     * Returns whether the token at {@code index} starts an {@code ALTER TABLE}
     * action: the first action directly after the table name, or an action after
     * a separating comma. The check keeps a column named {@code add} or
     * {@code drop} from being mistaken for a clause keyword.
     */
    private static boolean isActionStart(List<Token> tokens, int index, int cursor) {
        if (index <= cursor) {
            return false;
        }
        Token previous = tokens.get(index - 1);
        if (isPunctuation(previous, ",")) {
            return true;
        }
        if (previous.type() == TokenType.OPERATOR && previous.text().equals("*")
                && (index - 2 == cursor || (index >= 3 && isDot(tokens.get(index - 3))))) {
            // ALTER TABLE [ONLY] name * action also covers descendants.
            return true;
        }
        return RuleSupport.identifier(previous) != null
                && (index - 1 == cursor || (index >= 2 && isDot(tokens.get(index - 2))));
    }

    private static boolean isDot(Token token) {
        return isPunctuation(token, ".");
    }

    /**
     * Returns whether the tokens at {@code index} start an unnamed constraint
     * kind, which must not be treated as a bare column name.
     */
    private static boolean startsUnnamedConstraint(List<Token> tokens, int index) {
        if (TokenWords.at(tokens, index, "PRIMARY") && TokenWords.at(tokens, index + 1, "KEY")) {
            return true;
        }
        if (TokenWords.at(tokens, index, "FOREIGN") && TokenWords.at(tokens, index + 1, "KEY")) {
            return true;
        }
        if (TokenWords.at(tokens, index, "CHECK")) {
            return isPunctuation(tokens, index + 1, "(");
        }
        if (TokenWords.at(tokens, index, "UNIQUE")) {
            // PostgreSQL 15+ allows UNIQUE NULLS [NOT] DISTINCT before the list.
            return isPunctuation(tokens, index + 1, "(")
                    || TokenWords.at(tokens, index + 1, "NULLS");
        }
        if (TokenWords.at(tokens, index, "EXCLUDE")) {
            return TokenWords.at(tokens, index + 1, "USING") || isPunctuation(tokens, index + 1, "(");
        }
        return false;
    }

    private static int qualifiedNameEnd(List<Token> tokens, int start) {
        int i = start + 1;
        while (i + 1 < tokens.size() && isDot(tokens.get(i)) && RuleSupport.identifier(tokens.get(i + 1)) != null) {
            i += 2;
        }
        return i;
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

    /**
     * Returns whether a {@code CREATE TABLE} column-list segment is a table
     * constraint rather than a column definition. {@code CONSTRAINT} and
     * {@code EXCLUDE} are non-reserved in PostgreSQL, so they are only a
     * constraint lead in their actual grammatical form.
     */
    private static boolean isTableConstraint(List<Token> segment) {
        Token first = segment.getFirst();
        if (first.matchesKeyword("CONSTRAINT")) {
            return segment.size() > 2 && isConstraintKind(segment.get(2));
        }
        if (first.matchesKeyword("EXCLUDE")) {
            return segment.size() > 1
                    && (TokenWords.at(segment, 1, "USING") || isPunctuation(segment.get(1), "("));
        }
        return isConstraintKind(first) || first.matchesKeyword("LIKE");
    }

    private static boolean isConstraintKind(Token token) {
        return token.matchesKeyword("PRIMARY")
                || token.matchesKeyword("UNIQUE")
                || token.matchesKeyword("CHECK")
                || token.matchesKeyword("FOREIGN")
                || token.matchesKeyword("EXCLUDE");
    }

    private static boolean isPunctuation(List<Token> tokens, int index, String text) {
        return index >= 0 && index < tokens.size() && isPunctuation(tokens.get(index), text);
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
        Map<String, String> indexTables = new LinkedHashMap<>();
        List<Violation> violations = new ArrayList<>();
        for (ProjectStatements.Statement statement : ProjectStatements.forward(context)) {
            List<Token> tokens = statement.tokens();
            if (TokenWords.startsWith(tokens, "CREATE", "TABLE")) {
                createTable(statement, tokens, tables, columns, violations);
            } else if (isCreateIndex(tokens)) {
                createIndex(statement, tokens, indexes, indexTables, violations);
            } else if (TokenWords.startsWith(tokens, "ALTER", "TABLE")) {
                alterTable(statement, tokens, columns, constraints, violations);
            } else if (TokenWords.startsWith(tokens, "DROP", "TABLE")) {
                dropTable(statement, tokens, tables, columns, constraints, indexes, indexTables);
            } else if (TokenWords.startsWith(tokens, "DROP", "INDEX")) {
                dropIndex(tokens, indexes, indexTables);
            }
        }
        return List.copyOf(violations);
    }

    @Override
    public String description() {
        return "a table, index, column or constraint name declared twice";
    }

    @Override
    public String help() {
        return "Remove the duplicate declaration or rename one object.";
    }

    private record Located(ChangeSet changeSet, Path file, int line, int column) {
    }
}
