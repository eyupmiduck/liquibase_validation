package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Contract for a consuming project's orphaned-SQL test: every {@code .sql} file
 * in the changelog tree is reached by the changelog graph.
 *
 * <p>A consumer extends this class, normally without overriding anything, only
 * for the conventional {@code db/changelog} layout. Override
 * {@link #changelogRoot()} or {@link #master()} for a different layout.
 */
public abstract class ChangelogSqlFilesContract {

    /**
     * The changelog root directory.
     *
     * @return the changelog root
     */
    protected Path changelogRoot() {
        return ChangelogTestSupport.changelogRoot();
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
     * Every {@code .sql} file found by an independent filesystem walk is reached
     * by the changelog graph, and the validator reports no orphaned files.
     *
     * @throws IOException if the changelog tree cannot be read
     */
    @Test
    void noOrphanedSqlFiles() throws IOException {
        ChangelogAssertions.assertNoOrphanedSqlFiles(changelogRoot(), master());
    }
}
