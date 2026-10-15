package io.github.eyupmiduck.changelogvalidator.testing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable assertion that Liquibase keeps its tracking tables out of the
 * application schemas: the (project-prefixed) tracking tables live in the
 * dedicated schema, and the generic {@code databasechangelog} /
 * {@code databasechangeloglock} names are absent from the application schemas.
 */
public final class TrackingTableAssertions {

    private static final String GENERIC_CHANGELOG = "databasechangelog";
    private static final String GENERIC_CHANGELOG_LOCK = "databasechangeloglock";

    private TrackingTableAssertions() {
    }

    /**
     * Asserts that the {@code changelogTable} and {@code changelogLockTable} exist
     * in {@code trackingSchema}, and that no relation named
     * {@code databasechangelog} / {@code databasechangeloglock} exists in any of
     * {@code applicationSchemas}. Any relation kind counts, so a misplaced view,
     * sequence or index cannot masquerade as an absent tracking table.
     *
     * @param connection         an open connection to the migrated database
     * @param trackingSchema     the schema that should hold the tracking tables
     * @param changelogTable     the project's change-log table name
     * @param changelogLockTable the project's change-log lock table name
     * @param applicationSchemas the schemas that must not hold the generic names
     * @throws SQLException if the catalog cannot be read
     */
    public static void assertTrackingTablesAreIsolated(Connection connection, String trackingSchema,
                                                       String changelogTable, String changelogLockTable,
                                                       Collection<String> applicationSchemas) throws SQLException {
        assertTrue(anyRelationExists(connection, trackingSchema, changelogTable),
                () -> "expected " + trackingSchema + "." + changelogTable);
        assertTrue(anyRelationExists(connection, trackingSchema, changelogLockTable),
                () -> "expected " + trackingSchema + "." + changelogLockTable);

        for (String schema : applicationSchemas) {
            assertFalse(anyRelationExists(connection, schema, GENERIC_CHANGELOG),
                    () -> "the generic " + schema + "." + GENERIC_CHANGELOG + " should not exist");
            assertFalse(anyRelationExists(connection, schema, GENERIC_CHANGELOG_LOCK),
                    () -> "the generic " + schema + "." + GENERIC_CHANGELOG_LOCK + " should not exist");
        }
    }

    private static boolean anyRelationExists(Connection connection, String schema, String relation)
            throws SQLException {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_class AS c
                    JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ?
                )
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, relation);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }
}
