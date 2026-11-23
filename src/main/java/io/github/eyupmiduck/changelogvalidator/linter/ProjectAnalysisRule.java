package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangelogProject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A linter rule that needs the whole {@link ChangelogProject} rather than only
 * the changeset list: the file graph, the effective properties, and each
 * changeset's loaded SQL.
 *
 * <p>Project analysis rules run once after the per-changeset rules and the
 * changeset-only {@link ProjectRule}s. They use the same id, severity,
 * enablement and whitelist model as every other rule. A violation may name the
 * changeset that should be fixed; a file-level finding (for example an
 * unregistered routine file) leaves it {@code null}.
 */
public interface ProjectAnalysisRule {

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
     * Whether the rule runs unless the configuration excludes it. Project
     * analysis is opt-in by default.
     *
     * @return {@code true} when the rule is enabled by default
     */
    default boolean enabledByDefault() {
        return false;
    }

    /**
     * Checks the project.
     *
     * @param context the project and its loaded changesets
     * @return the violations, or an empty list
     * @throws IOException if a project file cannot be read
     */
    List<Violation> check(ProjectContext context) throws IOException;

    /**
     * The project and each changeset's loaded SQL, in changelog order.
     *
     * @param project    the changelog graph
     * @param changesets the changesets with their loaded forward/rollback units
     */
    record ProjectContext(ChangelogProject project, List<AnalyzedChangeSet> changesets) {

        /**
         * Validates and copies the collections.
         */
        public ProjectContext {
            Objects.requireNonNull(project, "project");
            Objects.requireNonNull(changesets, "changesets");
            changesets = List.copyOf(changesets);
        }
    }

    /**
     * One changeset and its loaded SQL.
     *
     * @param changeSet the changeset
     * @param context   the loaded forward/rollback units
     */
    record AnalyzedChangeSet(ChangeSet changeSet, RuleContext context) {

        /**
         * Validates the required components.
         */
        public AnalyzedChangeSet {
            Objects.requireNonNull(changeSet, "changeSet");
            Objects.requireNonNull(context, "context");
        }
    }

    /**
     * A project-analysis violation.
     *
     * @param changeSet the changeset to report against, or {@code null} for a
     *                  file-level finding
     * @param file      the file the violation is in
     * @param line      the one-based line
     * @param column    the one-based column
     * @param statement the offending statement label, or null
     * @param message   what is wrong
     * @param help      how to fix it, or null
     */
    record Violation(ChangeSet changeSet, Path file, int line, int column, String statement,
                     String message, String help) {

        /**
         * Validates the required components.
         */
        public Violation {
            Objects.requireNonNull(file, "file");
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
            this(Objects.requireNonNull(changeSet, "changeSet"), changeSet.changelogFile(),
                    changeSet.line(), 1, statement, message, help);
        }

        /**
         * Creates a file-level violation.
         *
         * @param file    the file
         * @param message what is wrong
         * @param help    how to fix it, or null
         */
        public Violation(Path file, String message, String help) {
            this(null, file, 1, 1, null, message, help);
        }
    }
}
