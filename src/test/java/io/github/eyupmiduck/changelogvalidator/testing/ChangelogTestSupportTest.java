package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ChangelogTestSupport} resolves the conventional changelog
 * layout from the test classpath.
 */
class ChangelogTestSupportTest {

    /**
     * The master path is derived from the changes root's parent and its file
     * name matches the resource constant.
     */
    @Test
    void resolvesTheMasterChangelog() {
        assertEquals("db.changelog-master.xml", Path.of(ChangelogTestSupport.MASTER_RESOURCE).getFileName().toString());
        assertEquals(ChangelogTestSupport.changelogRoot().resolve("db.changelog-master.xml"),
                ChangelogTestSupport.master());
    }

    /**
     * The changelog root contains the changes directory and the master file on
     * the exploded test classpath.
     */
    @Test
    void resolvesTheChangelogTree() {
        Path root = ChangelogTestSupport.changelogRoot();
        assertTrue(Files.isDirectory(root), () -> root + " should be a directory");
        assertTrue(Files.isDirectory(ChangelogTestSupport.changesRoot()),
                () -> ChangelogTestSupport.changesRoot() + " should be a directory");
        assertTrue(Files.isRegularFile(ChangelogTestSupport.master()),
                () -> ChangelogTestSupport.master() + " should be a file");
        assertEquals(root, ChangelogTestSupport.changesRoot().getParent());
    }

    /**
     * A resource that is not on the classpath fails fast with the resource name
     * instead of returning a bogus path.
     */
    @Test
    void rejectsAMissingResource() {
        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> ChangelogTestSupport.changelogRoot("no/such/changelog/dir"));
        assertTrue(failure.getMessage().contains("no/such/changelog/dir"));
    }
}
