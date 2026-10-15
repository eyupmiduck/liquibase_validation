package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link ExtensionAssertions} against a real PostgreSQL: a built-in
 * extension is available and installed, and an unknown one is reported.
 */
class ExtensionAssertionsTest extends PostgresTestBase {

    /**
     * {@code plpgsql} is always available and installed.
     */
    @Test
    void plpgsqlIsAvailableAndInstalled() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            ExtensionAssertions.assertExtensionsAvailable(owner, List.of("plpgsql"));
            ExtensionAssertions.assertExtensionsInstalled(owner, List.of("plpgsql"));
        }
    }

    /**
     * An unknown extension is reported as neither available nor installed.
     */
    @Test
    void missingExtensionIsReported() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            assertThrows(AssertionError.class,
                    () -> ExtensionAssertions.assertExtensionsAvailable(owner, List.of("no_such_extension")));
            assertThrows(AssertionError.class,
                    () -> ExtensionAssertions.assertExtensionsInstalled(owner, List.of("no_such_extension")));
        }
    }
}
