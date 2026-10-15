package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * The canonical {@link ChangelogAssertions#CHANGESET_ID_PATTERN} accepts the
     * documented schema/table and stored-object ids and rejects malformed ones,
     * and the no-pattern overload uses it.
     */
    @Test
    void canonicalChangeSetIdPatternAcceptsAndRejects() {
        for (String valid : List.of(
                "001-create-schema",
                "999-grant_privileges",
                "006-create-migration-tables",
                "function-dml_utils.run_migration_chunks",
                "procedure-ddl_utils.ensure_not_null",
                "trigger-dml_utils_data.set_updated_at")) {
            assertTrue(ChangelogAssertions.CHANGESET_ID_PATTERN.matcher(valid).matches(), valid);
        }
        for (String invalid : List.of(
                "1-short",
                "0012-too-long",
                "function-dml_utils",
                "function-dml_utils.bad-name",
                "function-.name")) {
            assertFalse(ChangelogAssertions.CHANGESET_ID_PATTERN.matcher(invalid).matches(), invalid);
        }

        assertDoesNotThrow(() -> ChangelogAssertions.assertChangeSetsFollowNaming(
                ChangelogTestSupport.changelogRoot(), ChangelogTestSupport.master()));
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
