package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;

import java.util.List;

/**
 * Reports a changeset that declares no rollback, so a deployment cannot be
 * reverted cleanly.
 *
 * <p>The rule is advisory (warning) and opt-in: a project lists it under
 * {@code include} when it wants the check. A changeset is accepted when it
 * declares a {@code <rollback>} block, including an empty one and one that
 * references another changeset ({@code <rollback changeSetId="..."/>}), or when
 * it is a {@code runOnChange} changeset whose body is a stored routine
 * (re-running the body replaces it, so a rollback is not required to reverse
 * it).
 *
 * <p>A rollback that only partially inverts the forward change is the concern of
 * the opt-in {@link RequireRollbackParityRule}.
 */
public final class RequireRollbackRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "changeset-rollback-required";

    private static boolean isRoutineBody(RuleContext context) {
        return !context.forward().isEmpty()
                && context.forward().stream().allMatch(unit -> unit.source().kind() == SqlSource.Kind.ROUTINE_BODY);
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
        if (context.changeSet().rollbackDefined()) {
            return List.of();
        }
        if (context.changeSet().runOnChange() && isRoutineBody(context)) {
            return List.of();
        }
        return List.of(new Violation(
                "changeset has no rollback",
                "Add a <rollback> block that reverses the change, or an empty <rollback/> when the change needs none.",
                context.changeSet().changelogFile(), 1, 1));
    }
}
