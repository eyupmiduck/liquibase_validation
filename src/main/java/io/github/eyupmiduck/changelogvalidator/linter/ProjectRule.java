package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;

import java.util.List;
import java.util.Objects;

/**
 * A linter rule that needs the whole changelog graph rather than one changeset,
 * for example to detect a duplicate changeset identity.
 *
 * <p>Project rules run once after the per-changeset {@link Rule}s. They use the
 * same id, severity, enablement and whitelist model as a per-changeset rule, and
 * their violations carry the changeset that should be fixed.
 */
public interface ProjectRule {

    /**
     * The stable rule id.
     *
     * @return the id
     */
    String id();

    /**
     * The severity applied when the configuration does not override it.
     *
     * @return the default severity
     */
    Severity defaultSeverity();

    /**
     * Whether the rule runs unless the configuration excludes it. Opt-in rules
     * return {@code false} and must be listed under {@code include}.
     *
     * @return {@code true} when the rule is enabled by default
     */
    default boolean enabledByDefault() {
        return true;
    }

    /**
     * Checks the whole changelog graph.
     *
     * @param changeSets the changesets, in changelog order
     * @return the violations, or an empty list
     */
    List<Violation> check(List<ChangeSet> changeSets);

    /**
     * A project-rule violation with the changeset that should be fixed.
     *
     * @param changeSet the changeset to report against
     * @param line      the one-based line of the violation
     * @param column    the one-based column of the violation
     * @param statement the offending statement as a stable label, or null
     * @param message   what is wrong
     * @param help      how to fix it, or null
     */
    record Violation(ChangeSet changeSet, int line, int column, String statement, String message, String help) {

        /**
         * Validates the required components.
         */
        public Violation {
            Objects.requireNonNull(changeSet, "changeSet");
            Objects.requireNonNull(message, "message");
            if (line < 1) {
                throw new IllegalArgumentException("line must be one-based, was " + line);
            }
            if (column < 1) {
                throw new IllegalArgumentException("column must be one-based, was " + column);
            }
        }

        /**
         * Creates a violation at the changeset's declaration line.
         *
         * @param changeSet the changeset to report against
         * @param statement the offending statement label, or null
         * @param message   what is wrong
         * @param help      how to fix it, or null
         */
        public Violation(ChangeSet changeSet, String statement, String message, String help) {
            this(changeSet, changeSet.line(), 1, statement, message, help);
        }
    }
}
