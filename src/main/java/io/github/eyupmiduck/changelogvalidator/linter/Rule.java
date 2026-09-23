package io.github.eyupmiduck.changelogvalidator.linter;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A linter rule: a stable id, a default severity, and a check over a changeset's
 * SQL units.
 *
 * <p>Ids are lower-case kebab-case and are a public API; a rule that mirrors a
 * Squawk rule reuses Squawk's id, and Liquibase-semantic rules are prefixed
 * {@code changeset-}. Rules are registered explicitly by the caller, never
 * discovered by scanning the classpath.
 */
public interface Rule {

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
     * Checks one changeset.
     *
     * @param context the changeset and its SQL units
     * @return the violations, or an empty list
     */
    List<Violation> check(RuleContext context);

    /**
     * A rule violation before the engine adds the rule id, severity and
     * changeset context.
     *
     * @param statement the offending statement as a stable label (for example
     *                  {@code CREATE INDEX CONCURRENTLY}), or null when the rule
     *                  does not identify one; whitelist entries match on it
     * @param message   what is wrong
     * @param help      how to fix it, or null
     * @param file      the file the violation is in
     * @param line      the one-based line of the violation
     * @param column    the one-based column of the violation
     */
    record Violation(String statement, String message, String help, Path file, int line, int column) {

        /**
         * Validates the violation's required components.
         */
        public Violation {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(file, "file");
            if (line < 1) {
                throw new IllegalArgumentException("line must be one-based, was " + line);
            }
            if (column < 1) {
                throw new IllegalArgumentException("column must be one-based, was " + column);
            }
        }

        /**
         * Creates a violation that does not name the offending statement.
         *
         * @param message what is wrong
         * @param help    how to fix it, or null
         * @param file    the file the violation is in
         * @param line    the one-based line of the violation
         * @param column  the one-based column of the violation
         */
        public Violation(String message, String help, Path file, int line, int column) {
            this(null, message, help, file, line, column);
        }
    }
}
