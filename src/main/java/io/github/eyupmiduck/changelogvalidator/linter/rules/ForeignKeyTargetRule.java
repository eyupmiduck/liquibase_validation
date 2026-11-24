package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;

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

    private static boolean containsCreateTable(List<Token> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            if (tokens.get(i).matchesKeyword("CREATE")
                    && RuleSupport.createdTableAt(tokens, i) != null) {
                return true;
            }
        }
        return false;
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
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i).matchesKeyword("CREATE")) {
                    String table = RuleSupport.createdTableAt(tokens, i);
                    if (table != null) {
                        created.add(table);
                    }
                }
            }
        }
        List<Violation> violations = new ArrayList<>();
        for (ProjectStatements.Statement statement : statements) {
            List<Token> tokens = statement.tokens();
            // Only a CREATE TABLE or ALTER TABLE can declare a foreign key; a
            // REFERENCES in a SELECT can be an attribute name, and an identifier
            // preceded by a dot is not the keyword.
            boolean canDeclareForeignKey = RuleSupport.containsAlterTablePhrase(tokens)
                    || containsCreateTable(tokens);
            if (!canDeclareForeignKey) {
                continue;
            }
            for (int i = 0; i + 1 < tokens.size(); i++) {
                if (!tokens.get(i).matchesKeyword("REFERENCES")
                        || (i > 0 && RuleSupport.isDot(tokens.get(i - 1)))) {
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

    @Override
    public String description() {
        return "a foreign key whose referenced table is never created";
    }

    @Override
    public String help() {
        return "Create the referenced table, or add it to `knownTables`.";
    }
}
