package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reports a constraint added {@code NOT VALID} in the forward changelog graph
 * and never validated.
 *
 * <p>The rule tracks named {@code ALTER TABLE ... ADD CONSTRAINT ... NOT VALID}
 * declarations and removes them on {@code VALIDATE CONSTRAINT}, {@code DROP
 * CONSTRAINT} or {@code DROP TABLE}. An unnamed constraint cannot be matched to
 * a later {@code VALIDATE}, so it is skipped. A constraint validated outside the
 * changelog is a false positive; the rule is an opt-in warning, so a project can
 * leave it off or whitelist the finding.
 */
public final class UnvalidatedConstraintRule implements ProjectAnalysisRule {

    /**
     * The rule id.
     */
    public static final String ID = "project-unvalidated-constraint";

    private static void dropTable(List<Token> tokens, Map<PendingKey, Located> pending) {
        int name = 2;
        if (TokenWords.at(tokens, name, "IF") && TokenWords.at(tokens, name + 1, "EXISTS")) {
            name += 2;
        }
        while (name < tokens.size()) {
            String table = RuleSupport.qualifiedName(tokens, name);
            if (table == null) {
                return;
            }
            pending.keySet().removeIf(key -> tablesMatch(key.table(), table));
            name = qualifiedNameEnd(tokens, name);
            if (!isPunctuation(tokens, name, ",")) {
                return;
            }
            name++;
        }
    }

    /**
     * Returns the pending-constraint key. The qualified table is kept so two
     * different schemas with the same final name do not share an entry.
     */
    private static PendingKey key(String table, String constraint) {
        return new PendingKey(table, constraint);
    }

    /**
     * Compares two table references with the shared policy: an exact qualified
     * match, or equal simple names when at least one side is unqualified.
     */
    private static boolean tablesMatch(String a, String b) {
        return RuleSupport.containsTable(Set.of(a), b);
    }

    private record PendingKey(String table, String constraint) {
    }

    private static int qualifiedNameEnd(List<Token> tokens, int start) {
        int i = start + 1;
        while (i + 1 < tokens.size() && RuleSupport.isDot(tokens.get(i))
                && RuleSupport.identifier(tokens.get(i + 1)) != null) {
            i += 2;
        }
        return i;
    }

    private static boolean isPunctuation(List<Token> tokens, int index, String text) {
        return index >= 0 && index < tokens.size() && isPunctuation(tokens.get(index), text);
    }

    private static int nextClause(List<Token> tokens, String keyword, int from, int end) {
        int depth = 0;
        for (int i = from; i < end; i++) {
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

    /**
     * Returns the end of the current {@code ALTER TABLE} command: the next
     * top-level {@code ;} or {@code ALTER TABLE}, or the end of the tokens.
     */
    private static int commandEnd(List<Token> tokens, int start) {
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (isPunctuation(token, "(")) {
                depth++;
            } else if (isPunctuation(token, ")")) {
                if (depth > 0) {
                    depth--;
                }
            } else if (depth == 0 && isPunctuation(token, ";")) {
                return i;
            } else if (depth == 0 && token.matchesKeyword("ALTER")
                    && TokenWords.at(tokens, i + 1, "TABLE")) {
                return i;
            }
        }
        return tokens.size();
    }

    private static boolean notValid(List<Token> tokens, int add, int end) {
        int next = nextClause(tokens, "ADD", add + 1, end);
        int clauseEnd = next < 0 ? end : next;
        int depth = 0;
        for (int i = add; i < clauseEnd; i++) {
            Token token = tokens.get(i);
            if (isPunctuation(token, "(")) {
                depth++;
            } else if (isPunctuation(token, ")")) {
                depth--;
            } else if (depth == 0 && token.matchesKeyword("NOT")
                    && TokenWords.at(tokens, i + 1, "VALID")) {
                return true;
            }
        }
        return false;
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
        Map<PendingKey, Located> pending = new LinkedHashMap<>();
        for (ProjectStatements.Statement statement : ProjectStatements.forward(context)) {
            List<Token> tokens = statement.tokens();
            if (TokenWords.startsWith(tokens, "DROP", "TABLE")) {
                dropTable(tokens, pending);
                continue;
            }
            if (!TokenWords.startsWith(tokens, "ALTER", "TABLE")) {
                continue;
            }
            int cursor = 2;
            if (TokenWords.at(tokens, cursor, "IF") && TokenWords.at(tokens, cursor + 1, "EXISTS")) {
                cursor += 2;
            }
            if (TokenWords.at(tokens, cursor, "ONLY")) {
                cursor++;
            }
            String table = RuleSupport.qualifiedName(tokens, cursor);
            if (table == null) {
                continue;
            }
            int end = commandEnd(tokens, cursor);
            // PostgreSQL runs DROP CONSTRAINT before ADD CONSTRAINT in a single
            // ALTER TABLE, and VALIDATE afterwards. A drop-and-re-add therefore
            // leaves the new constraint pending, not removed by the drop.
            for (int drop = nextClause(tokens, "DROP", cursor + 1, end); drop >= 0;
                 drop = nextClause(tokens, "DROP", drop + 1, end)) {
                int name = drop + 1;
                if (!TokenWords.at(tokens, name, "CONSTRAINT")) {
                    continue;
                }
                name++;
                if (TokenWords.at(tokens, name, "IF") && TokenWords.at(tokens, name + 1, "EXISTS")) {
                    name += 2;
                }
                if (name >= end) {
                    continue;
                }
                String constraint = RuleSupport.identifier(tokens.get(name));
                if (constraint != null) {
                    pending.keySet().removeIf(key -> key.constraint().equals(constraint)
                            && tablesMatch(key.table(), table));
                }
            }
            for (int add = nextClause(tokens, "ADD", cursor + 1, end); add >= 0;
                 add = nextClause(tokens, "ADD", add + 1, end)) {
                int name = add + 1;
                if (TokenWords.at(tokens, name, "CONSTRAINT")) {
                    name++;
                } else if (name + 1 < end
                        && RuleSupport.identifier(tokens.get(name)) != null
                        && (TokenWords.at(tokens, name + 1, "CHECK")
                        || TokenWords.at(tokens, name + 1, "FOREIGN")
                        || TokenWords.at(tokens, name + 1, "UNIQUE")
                        || TokenWords.at(tokens, name + 1, "PRIMARY")
                        || TokenWords.at(tokens, name + 1, "EXCLUDE"))) {
                    // The CONSTRAINT keyword is optional: the name is already
                    // at `name`, followed by the constraint kind.
                } else {
                    continue;
                }
                if (name >= end) {
                    continue;
                }
                String constraint = RuleSupport.identifier(tokens.get(name));
                if (constraint == null || !notValid(tokens, add, end)) {
                    continue;
                }
                pending.putIfAbsent(key(table, constraint), new Located(statement.changeSet(),
                        statement.unit().file(), tokens.get(name).line(), tokens.get(name).column()));
            }
            for (int validate = nextClause(tokens, "VALIDATE", cursor + 1, end); validate >= 0;
                 validate = nextClause(tokens, "VALIDATE", validate + 1, end)) {
                int name = validate + 1;
                if (!TokenWords.at(tokens, name, "CONSTRAINT")) {
                    continue;
                }
                name++;
                if (name >= end) {
                    continue;
                }
                String constraint = RuleSupport.identifier(tokens.get(name));
                if (constraint != null) {
                    pending.keySet().removeIf(key -> key.constraint().equals(constraint)
                            && tablesMatch(key.table(), table));
                }
            }
        }
        List<Violation> violations = new ArrayList<>();
        for (Map.Entry<PendingKey, Located> entry : pending.entrySet()) {
            Located located = entry.getValue();
            violations.add(new Violation(located.changeSet(), located.file(), located.line(), located.column(),
                    "ADD CONSTRAINT ... NOT VALID",
                    "constraint '" + entry.getKey().constraint() + "' on " + entry.getKey().table()
                            + " is added NOT VALID but never validated",
                    "Run ALTER TABLE ... VALIDATE CONSTRAINT in a later changeset, or remove the NOT VALID."));
        }
        return List.copyOf(violations);
    }

    @Override
    public String description() {
        return "a `NOT VALID` constraint that is never validated";
    }

    @Override
    public String help() {
        return "Add a later `VALIDATE CONSTRAINT` changeset.";
    }

    private record Located(io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet changeSet,
                           Path file, int line, int column) {
    }
}
