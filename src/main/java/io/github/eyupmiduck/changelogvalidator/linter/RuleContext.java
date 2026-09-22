package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;

import java.util.List;
import java.util.Objects;

/**
 * The data a {@link Rule} checks: the changeset and its forward and rollback SQL
 * units.
 *
 * @param changeSet the changeset
 * @param forward   the forward SQL units
 * @param rollback  the rollback SQL units
 */
public record RuleContext(ChangeSet changeSet, List<SqlUnit> forward, List<SqlUnit> rollback) {

    /**
     * Validates the context's required components and copies its lists.
     */
    public RuleContext {
        Objects.requireNonNull(changeSet, "changeSet");
        forward = List.copyOf(forward);
        rollback = List.copyOf(rollback);
    }
}
