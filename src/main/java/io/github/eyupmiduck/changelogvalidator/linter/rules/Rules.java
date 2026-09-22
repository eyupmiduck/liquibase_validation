package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;

import java.util.List;

/**
 * The built-in rules, registered explicitly so the order findings are produced
 * in is deterministic.
 */
public final class Rules {

    private Rules() {
    }

    /**
     * Returns the built-in rules.
     *
     * @param pgVersion the PostgreSQL major version for version-gated statements,
     *                  or null when unknown
     * @return the rules
     */
    public static List<Rule> all(Integer pgVersion) {
        return List.of(
                new RunInTransactionRequiredRule(pgVersion),
                new SingleStatementRule(pgVersion),
                new PreferSingleStatementRule(pgVersion));
    }
}
