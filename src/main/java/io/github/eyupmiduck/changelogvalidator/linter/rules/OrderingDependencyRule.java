package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;

import java.nio.file.Path;
import java.util.*;

/**
 * Reports a table referenced in a changeset that creates it later in the graph.
 *
 * <p>The rule tracks the first {@code CREATE TABLE} order and checks the table
 * of an {@code ALTER TABLE}, a {@code CREATE INDEX ... ON}, an
 * {@code INSERT}/{@code UPDATE}/{@code DELETE}/{@code TRUNCATE}, and a foreign
 * key's {@code REFERENCES}. A table that is never created in the graph is left
 * to {@code project-foreign-key-target}; a table installed outside the project
 * is declared through the {@code knownTables} option. Column-level ordering,
 * {@code SELECT} references, and a rename are not modelled. It is an opt-in
 * warning.
 */
public final class OrderingDependencyRule implements ProjectAnalysisRule {

    /**
     * The rule id.
     */
    public static final String ID = "project-ordering";

    private final Set<String> knownTables;

    /**
     * Creates a rule with no known external tables.
     */
    public OrderingDependencyRule() {
        this(Set.of());
    }

    /**
     * Creates a rule with an allow-list of tables installed outside the project.
     *
     * @param knownTables the known {@code schema.name} or {@code name} tables
     */
    public OrderingDependencyRule(Set<String> knownTables) {
        Objects.requireNonNull(knownTables, "knownTables");
        this.knownTables = Set.copyOf(knownTables);
    }

    private static Map<String, Token> references(List<Token> tokens) {
        Map<String, Token> references = new LinkedHashMap<>();
        if (TokenWords.startsWith(tokens, "ALTER", "TABLE")) {
            int cursor = 2;
            if (TokenWords.at(tokens, cursor, "IF") && TokenWords.at(tokens, cursor + 1, "EXISTS")) {
                cursor += 2;
            }
            if (TokenWords.at(tokens, cursor, "ONLY")) {
                cursor++;
            }
            add(references, RuleSupport.qualifiedName(tokens, cursor), tokens, cursor);
        } else if (TokenWords.startsWith(tokens, "CREATE", "INDEX")
                || TokenWords.startsWith(tokens, "CREATE", "UNIQUE", "INDEX")) {
            int on = TokenWords.indexOf(tokens, "ON", 2);
            if (on >= 0) {
                add(references, RuleSupport.qualifiedName(tokens, on + 1), tokens, on + 1);
            }
        } else if (TokenWords.startsWith(tokens, "INSERT", "INTO")) {
            add(references, RuleSupport.qualifiedName(tokens, 2), tokens, 2);
        } else if (TokenWords.at(tokens, 0, "UPDATE")) {
            int cursor = TokenWords.at(tokens, 1, "ONLY") ? 2 : 1;
            add(references, RuleSupport.qualifiedName(tokens, cursor), tokens, cursor);
        } else if (TokenWords.startsWith(tokens, "DELETE", "FROM")) {
            int cursor = 2;
            if (TokenWords.at(tokens, cursor, "ONLY")) {
                cursor++;
            }
            add(references, RuleSupport.qualifiedName(tokens, cursor), tokens, cursor);
        } else if (TokenWords.at(tokens, 0, "TRUNCATE")) {
            int cursor = 1;
            if (TokenWords.at(tokens, cursor, "TABLE")) {
                cursor++;
            }
            if (TokenWords.at(tokens, cursor, "ONLY")) {
                cursor++;
            }
            addTableList(references, tokens, cursor);
        }
        // A REFERENCES token in a SELECT is not a foreign-key clause (it can be
        // an attribute name), and an identifier preceded by a dot is not the
        // keyword.
        boolean canDeclareForeignKey = TokenWords.startsWith(tokens, "ALTER", "TABLE")
                || (TokenWords.at(tokens, 0, "CREATE") && RuleSupport.createdTableAt(tokens, 0) != null);
        if (canDeclareForeignKey) {
            for (int i = 0; i + 1 < tokens.size(); i++) {
                if (tokens.get(i).matchesKeyword("REFERENCES")
                        && !(i > 0 && RuleSupport.isDot(tokens.get(i - 1)))) {
                    add(references, RuleSupport.qualifiedName(tokens, i + 1), tokens, i + 1);
                }
            }
        }
        return references;
    }

    private static void addTableList(Map<String, Token> references, List<Token> tokens, int start) {
        int index = start;
        while (index < tokens.size()) {
            String table = RuleSupport.qualifiedName(tokens, index);
            if (table == null) {
                return;
            }
            references.putIfAbsent(table, tokens.get(index));
            index = qualifiedNameEnd(tokens, index);
            if (index >= tokens.size() || !isPunctuation(tokens.get(index), ",")) {
                return;
            }
            index++;
        }
    }

    private static int qualifiedNameEnd(List<Token> tokens, int start) {
        int i = start + 1;
        while (i + 1 < tokens.size() && RuleSupport.isDot(tokens.get(i))
                && RuleSupport.identifier(tokens.get(i + 1)) != null) {
            i += 2;
        }
        return i;
    }

    private static boolean isPunctuation(Token token, String text) {
        return token.type() == io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType.PUNCTUATION
                && token.text().equals(text);
    }

    private static void add(Map<String, Token> references, String table, List<Token> tokens, int index) {
        if (table != null && index < tokens.size()) {
            references.putIfAbsent(table, tokens.get(index));
        }
    }

    private static Located creation(Map<String, Located> created, String table) {
        Located exact = created.get(table);
        if (exact != null) {
            return exact;
        }
        String simple = simpleName(table);
        Located earliest = null;
        for (Map.Entry<String, Located> entry : created.entrySet()) {
            if (simpleName(entry.getKey()).equals(simple)
                    && (earliest == null || entry.getValue().order() < earliest.order())) {
                earliest = entry.getValue();
            }
        }
        return earliest;
    }

    private static String simpleName(String table) {
        int dot = table.lastIndexOf('.');
        return dot < 0 ? table : table.substring(dot + 1);
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
        List<ProjectStatements.Statement> statements = ProjectStatements.forward(context);
        Map<String, Located> created = new LinkedHashMap<>();
        for (ProjectStatements.Statement statement : statements) {
            List<Token> tokens = statement.tokens();
            if (!TokenWords.at(tokens, 0, "CREATE")) {
                continue;
            }
            int name = RuleSupport.createdTableNameIndex(tokens, 0);
            if (name < 0) {
                continue;
            }
            String table = RuleSupport.qualifiedName(tokens, name);
            if (table != null) {
                created.putIfAbsent(table, new Located(statement.changeSet(), statement.unit().file(),
                        tokens.get(name).line(), tokens.get(name).column(), statement.order()));
            }
        }
        List<Violation> violations = new ArrayList<>();
        for (ProjectStatements.Statement statement : statements) {
            List<Token> tokens = statement.tokens();
            Map<String, Token> references = references(tokens);
            for (Map.Entry<String, Token> reference : references.entrySet()) {
                String table = reference.getKey();
                if (RuleSupport.containsTable(knownTables, table)) {
                    continue;
                }
                Located creation = creation(created, table);
                if (creation == null || creation.order() <= statement.order()) {
                    continue;
                }
                Token token = reference.getValue();
                violations.add(new Violation(statement.changeSet(), statement.unit().file(),
                        token.line(), token.column(), "table reference",
                        "table '" + table + "' is referenced before it is created; it is created later at "
                                + creation.file().getFileName() + ":" + creation.line(),
                        "Move the creating changeset before the changeset that uses the table."));
            }
        }
        return List.copyOf(violations);
    }

    @Override
    public String description() {
        return "a table used in a changeset that creates it later";
    }

    @Override
    public String help() {
        return "Move the creating changeset earlier, or add the table to `knownTables`.";
    }

    private record Located(ChangeSet changeSet, Path file, int line, int column, int order) {
    }
}
