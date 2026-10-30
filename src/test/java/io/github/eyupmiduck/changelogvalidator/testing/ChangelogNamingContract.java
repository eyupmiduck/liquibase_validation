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
 * <p>A consumer extends this class, normally without overriding anything, only
 * for the conventional {@code db/changelog} layout. Override
 * {@link #changelogRoot()}, {@link #changesRoot()} or {@link #master()} for a
 * different layout, and {@link #changeSetIdPattern()} to accept project-specific
 * ids.
 */
public abstract class ChangelogNamingContract {

    /**
     * The changelog root directory.
     *
     * @return the changelog root
     */
    protected Path changelogRoot() {
        return ChangelogTestSupport.changelogRoot();
    }

    /**
     * The changes directory holding the SQL files.
     *
     * @return the changes directory
     */
    protected Path changesRoot() {
        return ChangelogTestSupport.changesRoot();
    }

    /**
     * The master changelog file.
     *
     * @return the master changelog
     */
    protected Path master() {
        return ChangelogTestSupport.master();
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
