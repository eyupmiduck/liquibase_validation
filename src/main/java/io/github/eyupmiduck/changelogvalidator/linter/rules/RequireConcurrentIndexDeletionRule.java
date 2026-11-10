package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * Advises dropping indexes with {@code DROP INDEX CONCURRENTLY}, mirroring
 * Squawk's {@code require-concurrent-index-deletion}.
 *
 * <p>A plain {@code DROP INDEX} takes an {@code ACCESS EXCLUSIVE} lock on the
 * index's parent table, blocking all reads and writes on that table (not only
 * queries that would use the index); {@code CONCURRENTLY} avoids it.
 *
 * <p>The rule is opt-in and advisory. Like the creation rule, it is a token-level
 * heuristic. The structured {@code <dropIndex>} change type is rendered into a
 * synthetic inline source (forward and rollback), so both it and literal
 * {@code DROP INDEX} SQL are checked.
 */
public final class RequireConcurrentIndexDeletionRule implements Rule {

    /**
     * The rule id (Squawk's).
     */
    public static final String ID = "require-concurrent-index-deletion";

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
        List<Violation> violations = new ArrayList<>();
        collect(RuleSupport.applicableToPostgres(context.forward()), violations);
        collect(RuleSupport.applicableToPostgres(context.rollback()), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, List<Violation> violations) {
        for (ConcurrentIndexes.Candidate candidate : ConcurrentIndexes.find(units)) {
            if (candidate.kind() != ConcurrentIndexes.Kind.DROP) {
                continue;
            }
            violations.add(new Violation(
                    "DROP INDEX",
                    "DROP INDEX without CONCURRENTLY takes an ACCESS EXCLUSIVE lock on the table, "
                            + "blocking reads and writes",
                    "Drop the index with DROP INDEX CONCURRENTLY in a runInTransaction=\"false\" changeset.",
                    candidate.unit().file(), candidate.token().line(), candidate.token().column()));
        }
    }
}
