package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;

import java.util.*;

/**
 * Reports a foreign key whose referenced table is never created in the forward
 * changelog graph.
 *
 * <p>The rule collects every {@code CREATE TABLE} in the graph and scans every
 * {@code REFERENCES} in forward SQL, so a table created later is still known
 * (the ordering rule reports use-before-create). A table installed outside this
 * changelog (for example a shared dependency) is declared through the
 * {@code knownTables} option; a table that is genuinely missing is reported at
 * the {@code REFERENCES} token. It is an opt-in warning.
 */
public final class ForeignKeyTargetRule implements ProjectAnalysisRule {

    /**
     * The rule id.
     */
    public static final String ID = "project-foreign-key-target";

    private final Set<String> knownTables;

    /**
     * Creates a rule with no known external tables.
     */
    public ForeignKeyTargetRule() {
        this(Set.of());
    }

    /**
     * Creates a rule with an allow-list of tables installed outside the project.
     *
     * @param knownTables the known {@code schema.name} or {@code name} tables
     */
    public ForeignKeyTargetRule(Set<String> knownTables) {
        Objects.requireNonNull(knownTables, "knownTables");
        this.knownTables = Set.copyOf(knownTables);
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
        Set<String> created = new LinkedHashSet<>();
        for (ProjectStatements.Statement statement : statements) {
            List<Token> tokens = statement.tokens();
            if (TokenWords.startsWith(tokens, "CREATE", "TABLE")) {
                int name = 2;
                if (TokenWords.at(tokens, name, "IF") && TokenWords.at(tokens, name + 1, "NOT")
                        && TokenWords.at(tokens, name + 2, "EXISTS")) {
                    name += 3;
                }
                String table = RuleSupport.qualifiedName(tokens, name);
                if (table != null) {
                    created.add(table);
                }
            }
        }
        List<Violation> violations = new ArrayList<>();
        for (ProjectStatements.Statement statement : statements) {
            List<Token> tokens = statement.tokens();
            for (int i = 0; i + 1 < tokens.size(); i++) {
                if (!tokens.get(i).matchesKeyword("REFERENCES")) {
                    continue;
                }
                String referenced = RuleSupport.qualifiedName(tokens, i + 1);
                if (referenced == null
                        || RuleSupport.containsTable(created, referenced)
                        || RuleSupport.containsTable(knownTables, referenced)) {
                    continue;
                }
                Token token = tokens.get(i);
                violations.add(new Violation(statement.changeSet(), statement.unit().file(),
                        token.line(), token.column(), "REFERENCES",
                        "foreign key references table '" + referenced
                                + "' but no changeset creates it",
                        "Create the table in this changelog, or list it under the rule's knownTables"
                                + " option if it is installed outside the project."));
            }
        }
        return List.copyOf(violations);
    }
}
