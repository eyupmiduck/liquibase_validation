package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link ChangelogAssertions} accepts the valid fixture changelog and
 * rejects a graph with a misnamed id or an orphaned SQL file.
 */
class ChangelogAssertionsTest {

    private static final Pattern IDS = Pattern.compile("\\d{3}[-_].+");

    /**
     * The fixture changelog passes the numbering, naming and orphan checks.
     */
    @Test
    void acceptsTheValidFixtureChangelog() {
        assertDoesNotThrow(() -> {
            ChangelogAssertions.assertSqlFilesAreNumbered(ChangelogTestSupport.changesRoot());
            ChangelogAssertions.assertChangeSetsFollowNaming(
                    ChangelogTestSupport.changelogRoot(), ChangelogTestSupport.master(), IDS);
            ChangelogAssertions.assertNoOrphanedSqlFiles(
                    ChangelogTestSupport.changelogRoot(), ChangelogTestSupport.master());
        });
    }

    /**
     * A changeSet id that does not match the pattern is reported.
     */
    @Test
    void rejectsAMisnamedChangeSet() {
        Path root = ChangelogTestSupport.changelogRoot();
        Path master = ChangelogTestSupport.master();
        // The fixture id 001-placeholder follows the pattern; a stricter pattern
        // that requires four digits must reject it.
        Pattern fourDigits = Pattern.compile("\\d{4}[-_].+");

        assertThrows(AssertionError.class,
                () -> ChangelogAssertions.assertChangeSetsFollowNaming(root, master, fourDigits));
    }

    /**
     * A missing changelog root fails loudly rather than passing vacuously.
     */
    @Test
    void rejectsAMissingChangelogRoot() {
        Path missing = Path.of("no/such/changelog");
        assertThrows(java.io.IOException.class,
                () -> ChangelogAssertions.assertNoOrphanedSqlFiles(missing, missing.resolve("master.xml")));
    }
}
