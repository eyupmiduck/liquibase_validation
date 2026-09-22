package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Advises building indexes with {@code CREATE INDEX CONCURRENTLY}, mirroring
 * Squawk's {@code require-concurrent-index-creation}.
 *
 * <p>A plain {@code CREATE INDEX} holds a lock that blocks writes for the whole
 * build; {@code CONCURRENTLY} avoids it (at the cost of a slower, failure-prone
 * build that must run in a {@code runInTransaction="false"} changeset).
 *
 * <p>The rule is opt-in and advisory. It exempts an index whose table is created
 * in the same changeset, because a fresh schema legitimately builds its indexes
 * non-concurrently. Two limits follow from the token-level heuristic: a
 * schema-qualified reference must match the {@code CREATE TABLE} reference
 * (unqualified and qualified forms are not reconciled), and the structured
 * {@code <createIndex>} change type is not seen by the linter yet (bead
 * ddl-w8y.6), so only {@code CREATE INDEX} SQL is checked.
 */
public final class RequireConcurrentIndexCreationRule implements Rule {

    /** The rule id (Squawk's). */
    public static final String ID = "require-concurrent-index-creation";

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
        List<ConcurrentIndexes.Candidate> candidates = ConcurrentIndexes.find(context.forward());
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> created = ConcurrentIndexes.tablesCreatedIn(context.forward());
        List<Violation> violations = new ArrayList<>();
        for (ConcurrentIndexes.Candidate candidate : candidates) {
            if (candidate.kind() != ConcurrentIndexes.Kind.CREATE || created.contains(candidate.table())) {
                continue;
            }
            violations.add(new Violation(
                    "CREATE INDEX",
                    "CREATE INDEX without CONCURRENTLY blocks writes on " + candidate.table()
                            + " while the index builds",
                    "Build the index with CREATE INDEX CONCURRENTLY in a runInTransaction=\"false\" changeset.",
                    candidate.unit().file(), candidate.token().line(), candidate.token().column()));
        }
        return List.copyOf(violations);
    }
}
