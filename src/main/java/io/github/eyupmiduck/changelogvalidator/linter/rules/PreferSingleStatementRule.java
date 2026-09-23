package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;
import io.github.eyupmiduck.changelogvalidator.linter.sql.TransactionForbiddenClassifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Advises a {@code runInTransaction="false"} changeset to contain a single
 * statement.
 *
 * <p>Liquibase cannot roll a partial failure of a non-transactional changeset
 * back, so several statements in one changeset leave the database and
 * {@code DATABASECHANGELOG} inconsistent even when no statement is
 * transaction-forbidden. This is the advisory counterpart of the error rule
 * {@link SingleStatementRule} (bead ddl-w8y.12), which covers a
 * transaction-forbidden statement; this rule stays quiet for those changesets
 * and only reports the multi-statement smell.
 *
 * <p>It is opt-in: a non-transactional changeset with several statements is
 * often intentional (for example a partition maintenance batch), so a project
 * enables the rule explicitly.
 */
public final class PreferSingleStatementRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "changeset-prefer-single-statement";

    private final Integer pgVersion;

    /**
     * Creates the rule with an unknown PostgreSQL version, so version-gated
     * statements are not classified.
     */
    public PreferSingleStatementRule() {
        this(null);
    }

    /**
     * Creates the rule.
     *
     * @param pgVersion the PostgreSQL major version for version-gated statements,
     *                  or null when unknown
     */
    public PreferSingleStatementRule(Integer pgVersion) {
        this.pgVersion = pgVersion;
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
        if (context.changeSet().runInTransaction()) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        collect(context.forward(), "forward", violations);
        collect(context.rollback(), "rollback", violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, String direction, List<Violation> violations) {
        int statementCount = units.stream().mapToInt(unit -> unit.statements().size()).sum();
        if (statementCount <= 1) {
            return;
        }
        for (SqlUnit unit : units) {
            if (!TransactionForbiddenClassifier.classify(unit.tokens(), unit.statements(), pgVersion).isEmpty()) {
                // The single-statement error rule covers a transaction-forbidden
                // statement; do not also warn about it here.
                return;
            }
        }
        SqlUnit firstUnit = RuleSupport.firstUnit(units);
        SqlStatement first = firstUnit.statements().get(0);
        Token token = RuleSupport.firstToken(firstUnit, first);
        violations.add(new Violation(
                "runInTransaction=\"false\" " + direction + " SQL has " + statementCount
                        + " statements; a partial failure cannot be rolled back",
                "Move each statement into its own runInTransaction=\"false\" changeset.",
                firstUnit.file(), token.line(), token.column()));
    }
}
