package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;

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
 * in the same direction of the same changeset, because a fresh schema
 * legitimately builds its indexes non-concurrently. The exemption is not shared
 * between forward and rollback: the two directions never run together, so a
 * table created in one direction is not fresh when the other direction indexes
 * it. The structured {@code <createIndex>} change type is rendered into a
 * synthetic inline source, so both it and literal {@code CREATE INDEX} SQL are
 * checked, in forward and rollback SQL alike. Schema-qualified and unqualified
 * references to the same table name are reconciled by comparing the final
 * name segment, since the token-level heuristic cannot resolve the default
 * schema.
 */
public final class RequireConcurrentIndexCreationRule implements Rule {

    /**
     * The rule id (Squawk's).
     */
    public static final String ID = "require-concurrent-index-creation";

    /**
     * Returns whether {@code table} is one of the created tables, comparing the
     * final name segment so a qualified and an unqualified reference to the same
     * table match.
     */
    private static boolean isCreated(Set<String> created, String table) {
        if (created.contains(table)) {
            return true;
        }
        String simple = simpleName(table);
        for (String candidate : created) {
            if (simpleName(candidate).equals(simple)) {
                return true;
            }
        }
        return false;
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
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
        // Each direction is checked against its own CREATE TABLE statements: the
        // two directions never run together, so a table created in one is not
        // fresh when the other indexes it.
        List<SqlUnit> forward = RuleSupport.applicableToPostgres(context.forward());
        List<SqlUnit> rollback = RuleSupport.applicableToPostgres(context.rollback());
        List<Violation> violations = new ArrayList<>();
        collect(forward, ConcurrentIndexes.tablesCreatedIn(forward), violations);
        collect(rollback, ConcurrentIndexes.tablesCreatedIn(rollback), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, Set<String> created, List<Violation> violations) {
        for (ConcurrentIndexes.Candidate candidate : ConcurrentIndexes.find(units)) {
            if (candidate.kind() != ConcurrentIndexes.Kind.CREATE || isCreated(created, candidate.table())) {
                continue;
            }
            violations.add(new Violation(
                    "CREATE INDEX",
                    "CREATE INDEX without CONCURRENTLY blocks writes on " + candidate.table()
                            + " while the index builds",
                    "Build the index with CREATE INDEX CONCURRENTLY in a runInTransaction=\"false\" changeset.",
                    candidate.unit().file(), candidate.token().line(), candidate.token().column()));
        }
    }
}
