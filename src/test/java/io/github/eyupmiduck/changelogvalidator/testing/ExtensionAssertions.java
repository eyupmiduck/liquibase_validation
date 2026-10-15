package io.github.eyupmiduck.changelogvalidator.testing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable assertions over the PostgreSQL extensions a project's image provides,
 * so consumers share one implementation instead of copying the
 * {@code pg_available_extensions} / {@code pg_extension} lookups.
 */
public final class ExtensionAssertions {

    private ExtensionAssertions() {
    }

    /**
     * Asserts that each named extension is available to install in the database.
     *
     * @param connection an open connection to the migrated database
     * @param extensions the extension names
     * @throws SQLException if the catalog cannot be read
     */
    public static void assertExtensionsAvailable(Connection connection, Collection<String> extensions)
            throws SQLException {
        Set<String> present = select(connection, extensions,
                "SELECT name FROM pg_catalog.pg_available_extensions WHERE name IN (%s)");
        Set<String> missing = new LinkedHashSet<>(extensions);
        missing.removeAll(present);
        assertTrue(missing.isEmpty(), () -> "extensions not available: " + missing);
    }

    /**
     * Asserts that each named extension is installed in the database.
     *
     * @param connection an open connection to the migrated database
     * @param extensions the extension names
     * @throws SQLException if the catalog cannot be read
     */
    public static void assertExtensionsInstalled(Connection connection, Collection<String> extensions)
            throws SQLException {
        Set<String> present = select(connection, extensions,
                "SELECT extname FROM pg_catalog.pg_extension WHERE extname IN (%s)");
        Set<String> missing = new LinkedHashSet<>(extensions);
        missing.removeAll(present);
        assertTrue(missing.isEmpty(), () -> "extensions not installed: " + missing);
    }

    private static Set<String> select(Connection connection, Collection<String> extensions, String sqlTemplate)
            throws SQLException {
        String placeholders = extensions.stream().map(name -> "?").collect(Collectors.joining(", "));
        Set<String> present = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sqlTemplate.formatted(placeholders))) {
            int index = 1;
            for (String extension : extensions) {
                statement.setString(index++, extension);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    present.add(rows.getString(1));
                }
            }
        }
        return present;
    }
}
