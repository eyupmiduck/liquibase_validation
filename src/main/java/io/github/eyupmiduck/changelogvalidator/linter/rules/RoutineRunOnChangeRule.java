package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;

import java.util.List;

/**
 * Reports a stored-routine changeset that does not set
 * {@code runOnChange="true"}.
 *
 * <p>A routine body is a living definition: when the file changes, Liquibase
 * only re-applies the changeset if it is {@code runOnChange}, otherwise the
 * database silently keeps the old body. A changeset is a routine changeset when
 * at least one PostgreSQL-applicable forward source is a
 * {@code createProcedure}/{@code createFunction} body.
 *
 * <p>The rule is an error and on by default.
 */
public final class RoutineRunOnChangeRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "routine-run-on-change-required";

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
        if (!RuleSupport.appliesToPostgres(context.changeSet())) {
            return List.of();
        }
        boolean routineBody = context.forward().stream()
                .filter(RuleSupport::appliesToPostgres)
                .anyMatch(unit -> unit.source().kind() == SqlSource.Kind.ROUTINE_BODY);
        if (!routineBody || context.changeSet().runOnChange()) {
            return List.of();
        }
        return List.of(new Violation(
                "routine changeset without runOnChange",
                "Set runOnChange=\"true\" so a changed routine body is re-applied instead of silently keeping the old definition.",
                context.changeSet().changelogFile(), context.changeSet().line(), 1));
    }

    @Override
    public String description() {
        return "a routine changeset without `runOnChange=\"true\"`";
    }

    @Override
    public String help() {
        return "Add `runOnChange=\"true\"` to the routine changeset.";
    }
}
