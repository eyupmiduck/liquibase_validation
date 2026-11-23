package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Contract for a consuming project's changelog-naming test: SQL file names must
 * start with a three-digit prefix, and changeSet ids must match the shared
 * {@link ChangelogAssertions#CHANGESET_ID_PATTERN} ({@code NNN-name} for
 * schema/table changesets, or {@code function-}/{@code procedure-}/
 * {@code trigger-<schema>.<name>} for a stored object).
 *
 * <p>A formatted SQL changelog (first non-blank line
 * {@code --liquibase formatted sql}) is a changelog rather than a numbered SQL
 * change, so its file name is exempt from the {@code NNN-} rule; its changeset
 * ids are still checked.
 *
 * <p>A consumer extends this class and returns its one {@link ProjectProfile}
 * from {@link #projectProfile()}; the changelog root, changes root and master
 * come from that profile. Override {@link #changelogRoot()},
 * {@link #changesRoot()} or {@link #master()} for a one-off layout, and
 * {@link #changeSetIdPattern()} to accept project-specific ids.
 */
public abstract class ChangelogNamingContract {

    /**
     * The project profile, used for the changelog locations.
     *
     * @return the project profile
     */
    protected ProjectProfile projectProfile() {
        return ProjectProfile.DEFAULT;
    }

    /**
     * The changelog root directory.
     *
     * @return the changelog root
     */
    protected Path changelogRoot() {
        return projectProfile().changelogRoot();
    }

    /**
     * The changes directory holding the SQL files.
     *
     * @return the changes directory
     */
    protected Path changesRoot() {
        return projectProfile().changesRoot();
    }

    /**
     * The master changelog file.
     *
     * @return the master changelog
     */
    protected Path master() {
        return projectProfile().master();
    }

    /**
     * The accepted changeSet id pattern.
     *
     * @return the changeSet id pattern
     */
    protected Pattern changeSetIdPattern() {
        return ChangelogAssertions.CHANGESET_ID_PATTERN;
    }

    /**
     * No SQL file name in the changes directory violates the
     * {@code NNN-name.sql} pattern.
     *
     * @throws IOException if the changes directory cannot be read
     */
    @Test
    void sqlFilesHaveThreeDigitPrefix() throws IOException {
        ChangelogAssertions.assertSqlFilesAreNumbered(changesRoot());
    }

    /**
     * No changeSet id reachable from the master changelog violates the shared
     * naming pattern.
     *
     * @throws IOException if the changelog graph cannot be read
     */
    @Test
    void changeSetsFollowTheNamingConvention() throws IOException {
        ChangelogAssertions.assertChangeSetsFollowNaming(changelogRoot(), master(), changeSetIdPattern());
    }
}
