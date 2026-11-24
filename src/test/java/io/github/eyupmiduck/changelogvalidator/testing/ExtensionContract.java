package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

/**
 * Contract for a consuming project's PostgreSQL extension test: every extension
 * the project's image provides is both available to install and installed in
 * the test database.
 *
 * <p>A consumer extends this class, returns its extension names from
 * {@link #extensions()}, and returns its one {@link ProjectProfile} from
 * {@link #projectProfile()} so the container and changelog come from the same
 * values as the other contracts. The shared
 * {@link ExtensionAssertions} performs the catalog checks.
 */
public abstract class ExtensionContract extends PostgresTestBase {

    /**
     * The extensions the project's image provides.
     *
     * @return the extension names
     */
    protected abstract List<String> extensions();

    /**
     * Every configured extension is available to install and installed in the
     * test database.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void extensionsAreAvailableAndInstalled() throws Exception {
        try (Connection connection = openTestConnection()) {
            ExtensionAssertions.assertExtensionsAvailable(connection, extensions());
            ExtensionAssertions.assertExtensionsInstalled(connection, extensions());
        }
    }
}
