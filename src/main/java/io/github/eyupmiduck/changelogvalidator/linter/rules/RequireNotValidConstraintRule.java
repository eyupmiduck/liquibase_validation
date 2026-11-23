package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Advises adding a {@code CHECK} or {@code FOREIGN KEY} constraint as
 * {@code NOT VALID}, unless the table is created in the same changeset.
 *
 * <p>A validating {@code ADD CONSTRAINT} scans the whole table while holding an
 * {@code ACCESS EXCLUSIVE} (or {@code SHARE ROW EXCLUSIVE} for a foreign key)
 * lock. Adding it {@code NOT VALID} is metadata-only; a later {@code VALIDATE
 * CONSTRAINT} takes only {@code SHARE UPDATE EXCLUSIVE} and can run separately.
 * A fresh table has no existing rows to scan, so it is exempt.
 *
 * <p>Only {@code CHECK} and {@code FOREIGN KEY} are checked, because PostgreSQL
 * cannot add {@code UNIQUE}, {@code PRIMARY KEY} or {@code EXCLUDE} as
 * {@code NOT VALID}; those need a different strategy. The rule is a warning and
 * opt-in. It checks forward and rollback SQL; each direction uses its own
 * created-table set.
 */
public final class RequireNotValidConstraintRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "require-not-valid-constraint";

    /**
     * Returns the next top-level {@code ADD} at or after {@code from}, or -1.
     * Skipping a nested one keeps an {@code add} column inside a CHECK expression
     * from being mistaken for a new clause.
     */
    private static int nextAdd(List<Token> tokens, int from) {
        int depth = 0;
        for (int i = from; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (isPunctuation(token, "(")) {
                depth++;
            } else if (isPunctuation(token, ")")) {
                depth--;
            } else if (depth == 0 && token.matchesKeyword("ADD")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns whether the current {@code ADD} clause has a top-level {@code NOT
     * VALID}. The scan stops at the next top-level {@code ADD} and ignores a
     * {@code NOT valid} pair inside a CHECK expression.
     */
    private static boolean notValid(List<Token> tokens, int add) {
        int next = nextAdd(tokens, add + 1);
        int end = next < 0 ? tokens.size() : next;
        int depth = 0;
        for (int i = add; i < end; i++) {
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
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public List<Violation> check(RuleContext context) {
        if (!RuleSupport.appliesToPostgres(context.changeSet())) {
            return List.of();
        }
        List<SqlUnit> forward = RuleSupport.applicableToPostgres(context.forward());
        List<SqlUnit> rollback = RuleSupport.applicableToPostgres(context.rollback());
        List<Violation> violations = new ArrayList<>();
        collect(forward, ConcurrentIndexes.tablesCreatedIn(forward), violations);
        collect(rollback, ConcurrentIndexes.tablesCreatedIn(rollback), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, Set<String> createdTables, List<Violation> violations) {
        for (SqlUnit unit : units) {
            for (SqlStatement statement : unit.statements()) {
                List<Token> tokens = RuleSupport.nonTriviaWithin(unit.tokens(), statement);
                for (int i = 0; i < tokens.size(); i++) {
                    if (!tokens.get(i).matchesKeyword("ALTER") || !TokenWords.at(tokens, i + 1, "TABLE")) {
                        continue;
                    }
                    int cursor = i + 2;
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
                    for (int add = nextAdd(tokens, cursor); add >= 0; add = nextAdd(tokens, add + 1)) {
                        int constraint = add + 1;
                        if (TokenWords.at(tokens, constraint, "CONSTRAINT")) {
                            constraint++;
                            if (constraint < tokens.size()
                                    && RuleSupport.identifier(tokens.get(constraint)) != null) {
                                constraint++;
                            }
                        }
                        boolean check = TokenWords.at(tokens, constraint, "CHECK");
                        boolean foreign = TokenWords.at(tokens, constraint, "FOREIGN");
                        if ((!check && !foreign) || notValid(tokens, add)
                                || RuleSupport.containsTable(createdTables, table)) {
                            continue;
                        }
                        Token token = tokens.get(add);
                        violations.add(new Violation(
                                "ALTER TABLE ... ADD CONSTRAINT",
                                "ADD CONSTRAINT on existing table " + table
                                        + " scans the table while holding a blocking lock",
                                "Add it NOT VALID, then VALIDATE CONSTRAINT separately"
                                        + " (ddl_utils.add_check_constraint/add_foreign_key do this).",
                                unit.file(), token.line(), token.column()));
                    }
                }
            }
        }
    }
}
