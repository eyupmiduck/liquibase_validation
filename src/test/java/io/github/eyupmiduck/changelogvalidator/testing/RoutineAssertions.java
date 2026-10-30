package io.github.eyupmiduck.changelogvalidator.testing;

import io.github.eyupmiduck.changelogvalidator.AuditColumnsCheck;
import io.github.eyupmiduck.changelogvalidator.PlpgsqlCheck;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reusable assertions over the stored routines and audit columns of a migrated
 * database, so consumers share one implementation instead of copying the
 * plpgsql_check and audit-column fault checks.
 */
public final class RoutineAssertions {

    /**
     * Matches a rollback {@code DROP} and captures its signature (the part
     * between {@code IF EXISTS} and the semicolon).
     */
    private static final Pattern ROLLBACK_DROP = Pattern.compile(
            "DROP\\s+(?:FUNCTION|PROCEDURE)\\s+IF\\s+EXISTS\\s+(.+?);",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

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

    /**
     * Asserts that the rollback files under {@code rollbackDirectories} drop
     * exactly the routines in {@code routineSchemas}: every routine has a
     * matching rollback {@code DROP FUNCTION/PROCEDURE IF EXISTS <signature>},
     * every such signature resolves to an existing routine, and no signature is
     * dropped twice.
     *
     * <p>Because {@code IF EXISTS} makes a stale {@code DROP} a silent no-op, a
     * signature change that is not propagated to the rollback would otherwise go
     * unnoticed until a rollback left the routine behind. Comments are stripped
     * first, so a commented-out {@code DROP} is not counted.
     *
     * @param connection          an open connection to the migrated database
     * @param routineSchemas      the schemas whose routines are checked
     * @param rollbackDirectories the classpath directories holding the rollback
     *                            SQL files
     * @throws IOException  if a rollback directory cannot be read
     * @throws SQLException if the catalog cannot be read
     */
    public static void assertRollbackDropsMatchRoutines(Connection connection, Collection<String> routineSchemas,
                                                        Collection<String> rollbackDirectories)
            throws IOException, SQLException {
        List<String> signatures = rollbackSignatures(rollbackDirectories);

        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new TreeSet<>();
        for (String signature : signatures) {
            if (!seen.add(signature)) {
                duplicates.add(signature);
            }
        }
        assertTrue(duplicates.isEmpty(),
                () -> "a rollback DROP signature must appear exactly once: " + duplicates);

        Map<Long, String> routines = routineOids(connection, routineSchemas);
        Set<Long> dropped = new TreeSet<>();
        for (String signature : seen) {
            dropped.add(resolveSignature(connection, signature));
        }

        Set<Long> missing = new TreeSet<>(routines.keySet());
        missing.removeAll(dropped);
        Set<Long> unexpected = new TreeSet<>(dropped);
        unexpected.removeAll(routines.keySet());

        assertTrue(missing.isEmpty(),
                () -> "routines with no rollback DROP: " + describeOids(missing, routines));
        assertTrue(unexpected.isEmpty(),
                () -> "rollback DROPs that match no routine in " + routineSchemas
                        + ": " + describeOids(unexpected, routines));
    }

    private static List<String> describeOids(Set<Long> oids, Map<Long, String> routines) {
        return oids.stream()
                .map(oid -> routines.getOrDefault(oid, "oid " + oid))
                .toList();
    }

    /**
     * Collects the {@code DROP} signatures of every rollback SQL file under the
     * given classpath directories.
     */
    private static List<String> rollbackSignatures(Collection<String> rollbackDirectories) throws IOException {
        List<String> signatures = new ArrayList<>();
        for (String directory : rollbackDirectories) {
            for (String sql : TestResources.readSqlFiles(directory)) {
                Matcher matcher = ROLLBACK_DROP.matcher(stripComments(sql));
                while (matcher.find()) {
                    signatures.add(matcher.group(1).replaceAll("\\s+", " ").trim());
                }
            }
        }
        assertFalse(signatures.isEmpty(),
                () -> "expected rollback DROP statements in " + rollbackDirectories);
        return signatures;
    }

    /**
     * Removes SQL line and block comments so a {@code DROP} written inside a
     * comment is not treated as a rollback statement.
     */
    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--[^\\n]*", " ");
    }

    /**
     * Returns every routine in {@code schemas}, keyed by OID with its qualified
     * name, so a mismatch can name the routines rather than print OIDs.
     */
    private static Map<Long, String> routineOids(Connection connection, Collection<String> schemas)
            throws SQLException {
        String sql = """
                SELECT p.oid, n.nspname || '.' || p.proname
                FROM pg_catalog.pg_proc AS p
                JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
                WHERE n.nspname IN (%s)
                """.formatted(placeholders(schemas.size()));
        Map<Long, String> routines = new TreeMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, schemas);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    routines.put(rows.getLong(1), rows.getString(2));
                }
            }
        }
        return routines;
    }

    /**
     * Resolves a rollback {@code DROP} signature to a routine OID, failing when
     * no routine has that signature.
     */
    private static long resolveSignature(Connection connection, String signature) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.to_regprocedure(?)::oid")) {
            statement.setString(1, signature);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                long oid = rows.getLong(1);
                if (rows.wasNull()) {
                    fail("rollback DROP does not resolve: " + signature);
                }
                return oid;
            }
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    private static void bind(PreparedStatement statement, Collection<String> values) throws SQLException {
        int index = 1;
        for (String value : values) {
            statement.setString(index++, value);
        }
    }

    private static InputStream classpath(String resource) {
        InputStream stream = RoutineAssertions.class.getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream, resource + " not found on the test classpath");
        return stream;
    }
}
