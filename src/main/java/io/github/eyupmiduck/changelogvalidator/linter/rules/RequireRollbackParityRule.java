package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;

import java.util.List;

/**
 * Advises that a changeset's rollback should have about as many statements as
 * its forward SQL.
 *
 * <p>This is a deliberately shallow heuristic, not an inverse-operation checker:
 * it cannot know that {@code DROP TABLE t} reverses {@code CREATE TABLE t}. What
 * it catches is a rollback that clearly cannot undo the whole forward change:
 * no rollback statements at all, or far fewer than the forward side (for example
 * a multi-statement changeset whose rollback only reverses the last one).
 *
 * <p>The rule is opt-in. It only runs when a rollback with statements is defined;
 * a missing rollback is {@link RequireRollbackRule}'s concern, and a rollback
 * that references another changeset ({@code <rollback changeSetId="..."/>}) has
 * no statements to compare. Routine bodies are opaque and are not counted.
 */
public final class RequireRollbackParityRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "changeset-rollback-parity";

    private static int statements(List<SqlUnit> units) {
        return units.stream()
                .filter(unit -> unit.source().kind() != SqlSource.Kind.ROUTINE_BODY)
                .mapToInt(unit -> unit.statements().size())
                .sum();
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
        if (!context.changeSet().rollbackDefined() || context.rollback().isEmpty()) {
            return List.of();
        }
        int forward = statements(context.forward());
        int rollback = statements(context.rollback());
        if (forward == 0 || rollback == 0) {
            return List.of();
        }
        if (rollback >= (forward + 1) / 2) {
            // About half or more; tolerate a one-to-many inversion.
            return List.of();
        }
        return List.of(new Violation(
                "rollback has " + rollback + " statement" + (rollback == 1 ? "" : "s")
                        + " for " + forward + " forward statement" + (forward == 1 ? "" : "s"),
                "Make the rollback invert the whole forward change, not only part of it.",
                context.changeSet().changelogFile(), 1, 1));
    }
}
