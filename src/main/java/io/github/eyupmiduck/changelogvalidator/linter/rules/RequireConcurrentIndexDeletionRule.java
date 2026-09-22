package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * Advises dropping indexes with {@code DROP INDEX CONCURRENTLY}, mirroring
 * Squawk's {@code require-concurrent-index-deletion}.
 *
 * <p>A plain {@code DROP INDEX} takes an exclusive lock on the index, blocking
 * queries that would use it; {@code CONCURRENTLY} avoids it.
 *
 * <p>The rule is opt-in and advisory. Like the creation rule, it is a token-level
 * heuristic and the structured {@code <dropIndex>} change type is not seen by the
 * linter yet (bead ddl-w8y.6), so only {@code DROP INDEX} SQL is checked.
 */
public final class RequireConcurrentIndexDeletionRule implements Rule {

    /** The rule id (Squawk's). */
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
        List<Violation> violations = new ArrayList<>();
        for (ConcurrentIndexes.Candidate candidate : ConcurrentIndexes.find(context.forward())) {
            if (candidate.kind() != ConcurrentIndexes.Kind.DROP) {
                continue;
            }
            violations.add(new Violation(
                    "DROP INDEX",
                    "DROP INDEX without CONCURRENTLY blocks queries that use the index while it is dropped",
                    "Drop the index with DROP INDEX CONCURRENTLY in a runInTransaction=\"false\" changeset.",
                    candidate.unit().file(), candidate.token().line(), candidate.token().column()));
        }
        return List.copyOf(violations);
    }
}
