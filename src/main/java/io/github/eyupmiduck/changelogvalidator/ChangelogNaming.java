package io.github.eyupmiduck.changelogvalidator;

import java.util.regex.Pattern;

/**
 * The shared {@code NNN-name} changelog convention: the id shape and the file
 * paths a numbered changeset promises.
 *
 * <p>The validator, the in-place layout check and the linter rule all enforce
 * the same convention, so the pattern and path builders live here rather than
 * being copied into each check.
 */
public final class ChangelogNaming {

    /**
     * The {@code NNN-name} shape: a three-digit, zero-padded prefix followed by
     * alphanumeric segments separated by {@code -} or {@code _}.
     */
    public static final String NUMBERED_ID = "\\d{3}[-_][A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*";

    private static final Pattern NUMBERED = Pattern.compile(NUMBERED_ID);

    private ChangelogNaming() {
    }

    /**
     * Returns whether {@code id} has the {@code NNN-name} shape.
     *
     * @param id the changeset id
     * @return {@code true} for a numbered id
     */
    public static boolean isNumbered(String id) {
        return NUMBERED.matcher(id).matches();
    }

    /**
     * The forward SQL path a numbered changeset promises.
     *
     * @param id the numbered changeset id
     * @return the path relative to the changes directory
     */
    public static String numberedForwardPath(String id) {
        return "sql_changes/" + id + ".sql";
    }

    /**
     * The rollback SQL path a numbered changeset promises.
     *
     * @param id the numbered changeset id
     * @return the path relative to the changes directory
     */
    public static String numberedRollbackPath(String id) {
        return "rollback/" + id + "-rollback.sql";
    }
}
