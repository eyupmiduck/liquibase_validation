package io.github.eyupmiduck.changelogvalidator.testing;

import io.github.eyupmiduck.changelogvalidator.AuditColumnsCheck;
import io.github.eyupmiduck.changelogvalidator.PlpgsqlCheck;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable assertions over the stored routines and audit columns of a migrated
 * database, so consumers share one implementation instead of copying the
 * plpgsql_check and audit-column fault checks.
 */
public final class RoutineAssertions {

    private RoutineAssertions() {
    }

    /**
     * Runs {@code plpgsql_check} over {@code schemas} and asserts there are no
     * unexpected findings and no stale allow-list entries.
     *
     * @param connection        an open owner connection to the migrated database
     * @param schemas           the schemas whose routines are checked
     * @param whitelistResource the classpath resource holding the allow-list
     * @throws IOException  if the allow-list cannot be read
     * @throws SQLException if the check cannot be run
     */
    public static void assertRoutinesPassPlpgsqlCheck(Connection connection, Collection<String> schemas,
                                                      String whitelistResource) throws IOException, SQLException {
        List<PlpgsqlCheck.AllowedFinding> allowed;
        try (InputStream whitelist = classpath(whitelistResource)) {
            allowed = PlpgsqlCheck.loadWhitelist(whitelist);
        }
        assertRoutinesPassPlpgsqlCheck(connection, schemas, allowed);
    }

    /**
     * Runs {@code plpgsql_check} over {@code schemas} and asserts there are no
     * unexpected findings and no stale allow-list entries.
     *
     * @param connection an open owner connection to the migrated database
     * @param schemas    the schemas whose routines are checked
     * @param allowed    the allow-list, for example from
     *                   {@link PlpgsqlCheck#loadWhitelist(InputStream)}
     * @throws SQLException if the check cannot be run
     */
    public static void assertRoutinesPassPlpgsqlCheck(Connection connection, Collection<String> schemas,
                                                      List<PlpgsqlCheck.AllowedFinding> allowed)
            throws SQLException {
        PlpgsqlCheck.Report report = PlpgsqlCheck.check(connection, schemas, allowed);
        assertEquals(List.of(), report.unexpected(),
                () -> "unexpected plpgsql_check findings:\n" + report.unexpected().stream()
                        .map(PlpgsqlCheck.Finding::describe)
                        .collect(Collectors.joining("\n")));
        assertEquals(List.of(), report.stale(),
                () -> "stale whitelist entries:\n" + report.stale().stream()
                        .map(PlpgsqlCheck.AllowedFinding::describe)
                        .collect(Collectors.joining("\n")));
    }

    /**
     * Asserts that every base table in {@code schemas} follows the audit-column
     * convention, so {@link AuditColumnsCheck#findViolations} reports nothing.
     *
     * @param connection an open owner connection to the migrated database
     * @param schemas    the schemas whose tables are checked
     * @throws SQLException if the catalog cannot be read
     */
    public static void assertAuditColumnsFollowConvention(Connection connection, Collection<String> schemas)
            throws SQLException {
        List<AuditColumnsCheck.Violation> violations = AuditColumnsCheck.findViolations(connection, schemas);
        assertEquals(List.of(), violations.stream().map(AuditColumnsCheck.Violation::describe).toList(),
                () -> "schemas " + schemas + " must follow the audit-column convention");
    }

    /**
     * Probes each named table's audit columns on a real row and asserts the
     * shared trigger refreshed {@code updated_at} and preserved
     * {@code created_at}. The probe rolls its update back.
     *
     * @param connection an open owner connection to the migrated database
     * @param schema     the table schema
     * @param tables     the tables to probe
     * @throws SQLException if the probe cannot run
     */
    public static void assertUpdateTriggerRefreshesAuditColumns(Connection connection, String schema,
                                                                Collection<String> tables) throws SQLException {
        for (String table : tables) {
            AuditColumnsCheck.Probe probe = AuditColumnsCheck.probeUpdate(connection, schema, table)
                    .orElseThrow(() -> new AssertionError("no row to probe in " + schema + "." + table));
            assertTrue(probe.passed(), probe::describe);
        }
    }

    private static InputStream classpath(String resource) {
        InputStream stream = RoutineAssertions.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream, resource + " not found on the test classpath");
        return stream;
    }
}
