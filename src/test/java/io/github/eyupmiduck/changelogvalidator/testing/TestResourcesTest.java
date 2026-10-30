package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link TestResources}: reading one classpath resource and reading
 * every {@code .sql} file under a classpath directory in a deterministic order.
 */
class TestResourcesTest {

    /**
     * A single classpath resource is read as text.
     */
    @Test
    void readsAClasspathResource() throws IOException {
        assertTrue(TestResources.read("db/changelog/changes/sql_changes/001-placeholder.sql")
                .contains("SELECT 1;"));
    }

    /**
     * Every {@code .sql} file under a directory is returned in file-name order.
     */
    @Test
    void readsSqlFilesInNameOrder() throws IOException {
        List<String> contents = TestResources.readSqlFiles("testing");

        assertEquals(List.of(
                TestResources.read("testing/grants-forward-extra.sql"),
                TestResources.read("testing/grants-forward.sql"),
                TestResources.read("testing/grants-rollback-one.sql"),
                TestResources.read("testing/grants-rollback.sql")), contents);
    }

    /**
     * A missing resource or directory fails loudly rather than yielding empty
     * content.
     */
    @Test
    void rejectsAMissingResourceOrDirectory() {
        assertThrows(AssertionError.class, () -> TestResources.read("no/such/resource.sql"));
        assertThrows(IOException.class, () -> TestResources.readSqlFiles("no/such/directory"));
    }

    /**
     * A directory with no SQL files yields an empty list rather than failing.
     */
    @Test
    void acceptsADirectoryWithNoSqlFiles() throws IOException {
        assertEquals(List.of(), TestResources.readSqlFiles("sarif"));
    }
}
