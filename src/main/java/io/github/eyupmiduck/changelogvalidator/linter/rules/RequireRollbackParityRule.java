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
 * <p>The rule is opt-in. It only runs when a rollback is defined and is not an
 * empty {@code <rollback/>} block; a missing rollback is
 * {@link RequireRollbackRule}'s concern. A defined rollback that contributes no
 * executable statement (blank or comment-only SQL) is reported, because it
 * reverses nothing. A rollback that references another changeset
 * ({@code <rollback changeSetId="..."/>}), contains a routine body, or has no
 * source applicable to PostgreSQL is opaque and is not counted.
 */
public final class RequireRollbackParityRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "changeset-rollback-parity";

    private static int statements(List<SqlUnit> units) {
        return units.stream()
                .filter(unit -> unit.source().kind() != SqlSource.Kind.ROUTINE_BODY)
                .filter(RuleSupport::appliesToPostgres)
                .mapToInt(unit -> unit.commands().size())
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
        if (!RuleSupport.appliesToPostgres(context.changeSet())) {
            return List.of();
        }
        if (!context.changeSet().rollbackDefined() || context.rollback().isEmpty()) {
            return List.of();
        }
        int forward = statements(context.forward());
        if (forward == 0) {
            return List.of();
        }
        int rollback = statements(context.rollback());
        if (rollback == 0) {
            boolean opaque = context.rollback().stream()
                    .anyMatch(unit -> unit.source().kind() == SqlSource.Kind.ROUTINE_BODY);
            if (opaque || RuleSupport.applicableToPostgres(context.rollback()).isEmpty()) {
                return List.of();
            }
            return List.of(new Violation(
                    "rollback has no executable statement for " + forward + " forward statement"
                            + (forward == 1 ? "" : "s"),
                    "Make the rollback invert the whole forward change, not only part of it.",
                    context.changeSet().changelogFile(), context.changeSet().line(), 1));
        }
        if (rollback >= (forward + 1) / 2) {
            // At least half, rounded up, so an odd forward count needs one more
            // than half.
            return List.of();
        }
        return List.of(new Violation(
                "rollback has " + rollback + " statement" + (rollback == 1 ? "" : "s")
                        + " for " + forward + " forward statement" + (forward == 1 ? "" : "s"),
                "Make the rollback invert the whole forward change, not only part of it.",
                context.changeSet().changelogFile(), context.changeSet().line(), 1));
    }
}
