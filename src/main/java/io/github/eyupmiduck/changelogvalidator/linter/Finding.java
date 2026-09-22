package io.github.eyupmiduck.changelogvalidator.linter;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A rule violation with the context the engine adds.
 *
 * @param ruleId          the id of the rule that reported the finding
 * @param severity        the effective severity
 * @param changeSetId     the changeset id
 * @param changeSetAuthor the changeset author, or an empty string
 * @param file            the file the violation is in (a SQL file, or the
 *                        changelog file for inline SQL)
 * @param line            the one-based line of the violation
 * @param column          the one-based column of the violation
 * @param statement       the offending statement as a stable label (for example
 *                        {@code CREATE INDEX CONCURRENTLY}), or null when the
 *                        rule does not identify one; whitelist entries match on
 *                        it
 * @param message         what is wrong
 * @param help            how to fix it, or null
 */
public record Finding(String ruleId, Severity severity, String changeSetId, String changeSetAuthor, Path file,
                      int line, int column, String statement, String message, String help) {

    /**
     * Validates the finding's required components.
     */
    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(changeSetId, "changeSetId");
        Objects.requireNonNull(changeSetAuthor, "changeSetAuthor");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Creates a finding that does not name the offending statement.
     *
     * @param ruleId          the id of the rule that reported the finding
     * @param severity        the effective severity
     * @param changeSetId     the changeset id
     * @param changeSetAuthor the changeset author, or an empty string
     * @param file            the file the violation is in
     * @param line            the one-based line of the violation
     * @param column          the one-based column of the violation
     * @param message         what is wrong
     * @param help            how to fix it, or null
     */
    public Finding(String ruleId, Severity severity, String changeSetId, String changeSetAuthor, Path file,
                   int line, int column, String message, String help) {
        this(ruleId, severity, changeSetId, changeSetAuthor, file, line, column, null, message, help);
    }
}
