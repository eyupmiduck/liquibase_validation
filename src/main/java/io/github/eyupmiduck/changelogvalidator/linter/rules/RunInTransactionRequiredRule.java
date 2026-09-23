package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.sql.TransactionForbiddenClassifier;
import io.github.eyupmiduck.changelogvalidator.linter.sql.TransactionForbiddenClassifier.Classification;

import java.util.ArrayList;
import java.util.List;

/**
 * Requires a changeset that contains a statement PostgreSQL forbids inside a
 * transaction block to set {@code runInTransaction="false"}.
 *
 * <p>Applies to forward and rollback SQL, because {@code runInTransaction}
 * governs the whole changeset. A changeset that already sets it to false is left
 * to the single-statement rule (bead ddl-w8y.12).
 */
public final class RunInTransactionRequiredRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "changeset-run-in-transaction-required";

    private final Integer pgVersion;

    /**
     * Creates the rule with an unknown PostgreSQL version, so version-gated
     * statements are not classified.
     */
    public RunInTransactionRequiredRule() {
        this(null);
    }

    /**
     * Creates the rule.
     *
     * @param pgVersion the PostgreSQL major version for version-gated statements,
     *                  or null when unknown
     */
    public RunInTransactionRequiredRule(Integer pgVersion) {
        this.pgVersion = pgVersion;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public List<Violation> check(RuleContext context) {
        if (!context.changeSet().runInTransaction()) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        collect(context.forward(), violations);
        collect(context.rollback(), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, List<Violation> violations) {
        for (SqlUnit unit : units) {
            for (Classification classification : TransactionForbiddenClassifier.classify(
                    unit.tokens(), unit.statements(), pgVersion)) {
                Token token = RuleSupport.firstToken(unit, classification.statement());
                String statement = RuleSupport.display(classification.family());
                violations.add(new Violation(
                        statement,
                        statement + " cannot run inside a transaction; set runInTransaction=\"false\" on the changeset",
                        "Set runInTransaction=\"false\" and make this statement the changeset's only statement.",
                        unit.file(), token.line(), token.column()));
            }
        }
    }
}
