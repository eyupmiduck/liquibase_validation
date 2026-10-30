package io.github.eyupmiduck.changelogvalidator.testing;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable assertions over the caller grants of a migrated database and over the
 * parity between a grant changeset and its rollback, so consumers share one
 * implementation instead of copying the ACL queries and the rollback-parity
 * regexes.
 *
 * <p>The privilege helpers take an open connection to the migrated database; the
 * schema, role and type names are supplied by the consumer.
 */
public final class GrantAssertions {

    private GrantAssertions() {
    }

    /**
     * Asserts that no routine (function or procedure) in {@code schemas} keeps
     * PostgreSQL's default {@code PUBLIC} execute privilege: every routine must
     * have an explicit ACL without an entry granting {@code EXECUTE} to
     * {@code PUBLIC}.
     *
     * @param connection an open connection to the migrated database
     * @param schemas    the schemas whose routines are checked
     * @throws SQLException if the catalog cannot be read
     */
    public static void assertNoRoutineExecutableByPublic(Connection connection, Collection<String> schemas)
            throws SQLException {
        String sql = """
                SELECT n.nspname || '.' || p.proname AS routine
                FROM pg_catalog.pg_proc AS p
                JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
                WHERE n.nspname IN (%s)
                  AND p.prokind IN ('f', 'p')
                  AND (
                      p.proacl IS NULL
                      OR EXISTS (
                          SELECT 1
                          FROM pg_catalog.aclexplode(p.proacl) AS a
                          WHERE a.privilege_type = 'EXECUTE' AND a.grantee = 0
                      )
                  )
                ORDER BY 1
                """.formatted(placeholders(schemas.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, schemas);
            Set<String> offenders = new LinkedHashSet<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    offenders.add(rows.getString(1));
                }
            }
            assertTrue(offenders.isEmpty(),
                    () -> "routines executable by PUBLIC: " + offenders);
        }
    }

    /**
     * Asserts that {@code role} can execute every callable routine (every routine
     * except a trigger function) in {@code schemas}.
     *
     * @param connection an open connection to the migrated database
     * @param schemas    the schemas whose routines are checked
     * @param role       the role that should be able to execute them
     * @throws SQLException if the catalog cannot be read
     */
    public static void assertRoleCanExecuteEveryCallableRoutine(Connection connection, Collection<String> schemas,
                                                                String role) throws SQLException {
        String sql = """
                SELECT count(*)::int AS not_executable
                FROM pg_catalog.pg_proc AS p
                JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
                WHERE n.nspname IN (%s)
                  AND p.prokind IN ('f', 'p')
                  AND (p.prokind = 'p' OR p.prorettype <> 'trigger'::pg_catalog.regtype)
                  AND NOT pg_catalog.has_function_privilege(?, p.oid, 'EXECUTE')
                """.formatted(placeholders(schemas.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            // The schema placeholders come first in the statement; the role is the
            // last parameter.
            bind(statement, schemas, 1);
            statement.setString(schemas.size() + 1, role);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                int notExecutable = rows.getInt(1);
                assertTrue(notExecutable == 0,
                        () -> notExecutable + " callable routine(s) " + role + " cannot execute");
            }
        }
    }

    /**
     * Asserts that a type or domain does not retain PostgreSQL's default
     * {@code PUBLIC} usage privilege.
     *
     * @param connection an open connection to the migrated database
     * @param schema     the type's schema
     * @param type       the type name
     * @throws SQLException if the catalog cannot be read
     */
    public static void assertTypeIsNotUsableByPublic(Connection connection, String schema, String type)
            throws SQLException {
        String sql = """
                SELECT count(*)::int AS usable_by_public
                FROM pg_catalog.pg_type AS t
                JOIN pg_catalog.pg_namespace AS n ON n.oid = t.typnamespace
                WHERE n.nspname = ? AND t.typname = ?
                  AND (
                      t.typacl IS NULL
                      OR EXISTS (
                          SELECT 1
                          FROM pg_catalog.aclexplode(t.typacl) AS a
                          WHERE a.privilege_type = 'USAGE' AND a.grantee = 0
                      )
                  )
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, type);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                int usable = rows.getInt(1);
                assertTrue(usable == 0,
                        () -> schema + "." + type + " is usable by PUBLIC by default");
            }
        }
    }

    /**
     * Asserts that {@code role} has an explicit {@code USAGE} grant on a type or
     * domain, checked on the ACL (not via {@code has_type_privilege}, which would
     * also accept the default {@code PUBLIC} grant).
     *
     * @param connection an open connection to the migrated database
     * @param schema     the type's schema
     * @param type       the type name
     * @param role       the role that should hold the explicit grant
     * @throws SQLException if the catalog cannot be read
     */
    public static void assertRoleHasExplicitUsageOnType(Connection connection, String schema, String type, String role)
            throws SQLException {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_type AS t
                    JOIN pg_catalog.pg_namespace AS n ON n.oid = t.typnamespace
                    CROSS JOIN LATERAL pg_catalog.aclexplode(coalesce(t.typacl, '{}'::pg_catalog.aclitem[])) AS a
                    WHERE n.nspname = ? AND t.typname = ?
                      AND a.privilege_type = 'USAGE'
                      AND a.grantee = (SELECT r.oid FROM pg_catalog.pg_roles AS r WHERE r.rolname = ?)
                )
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, type);
            statement.setString(3, role);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                assertTrue(rows.getBoolean(1),
                        () -> role + " should have an explicit USAGE grant on " + schema + "." + type);
            }
        }
    }

    /**
     * Asserts that the rollback of a grant changeset mirrors the forward grants:
     * every function the forward grants to {@code callerRole} is revoked from the
     * caller in the rollback, and every function whose {@code PUBLIC} execute the
     * forward revokes has its {@code PUBLIC} grant restored. Both files are read
     * as classpath resources, normalised to single spaces, so a signature change
     * cannot leave the rollback referencing a stale function.
     *
     * @param forwardResource  the forward grant script classpath resource
     * @param callerRole       the role the forward grants to
     * @param rollbackResource the rollback script classpath resource
     * @throws IOException if a resource cannot be read
     */
    public static void assertGrantRollbackMirrors(String forwardResource, String callerRole,
                                                  String rollbackResource) throws IOException {
        assertMirrors(forwardResource,
                "GRANT EXECUTE ON FUNCTION\\s+(.*?)\\s+TO " + callerRole + ";",
                rollbackResource,
                "REVOKE EXECUTE ON FUNCTION\\s+(.*?)\\s+FROM " + callerRole + ";",
                "every function granted to " + callerRole + " should be revoked in the rollback");
        assertMirrors(forwardResource,
                "REVOKE EXECUTE ON FUNCTION\\s+(.*?)\\s+FROM public;",
                rollbackResource,
                "GRANT EXECUTE ON FUNCTION\\s+(.*?)\\s+TO public;",
                "every function revoked from PUBLIC should have its PUBLIC grant restored");
    }

    private static void assertMirrors(String forwardResource, String forwardRegex,
                                      String rollbackResource, String rollbackRegex, String message)
            throws IOException {
        Set<String> forward = extract(forwardResource, forwardRegex);
        Set<String> rollback = extract(rollbackResource, rollbackRegex);

        Set<String> missing = new LinkedHashSet<>(forward);
        missing.removeAll(rollback);
        assertTrue(missing.isEmpty(), () -> message + "; missing: " + missing);
        assertFalse(forward.isEmpty(), () -> "no matches for " + forwardRegex + " in " + forwardResource);
    }

    private static Set<String> extract(String resource, String regex) throws IOException {
        Set<String> signatures = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(read(resource));
        while (matcher.find()) {
            signatures.add(matcher.group(1).replaceAll("\\s+", " ").trim());
        }
        return signatures;
    }

    private static String read(String resource) throws IOException {
        return TestResources.read(resource);
    }

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "?")
                .reduce((a, b) -> a + ", " + b)
                .orElseThrow(() -> new IllegalArgumentException("no schemas"));
    }

    private static void bind(PreparedStatement statement, Collection<String> values) throws SQLException {
        bind(statement, values, 1);
    }

    private static void bind(PreparedStatement statement, Collection<String> values, int firstIndex)
            throws SQLException {
        int index = firstIndex;
        for (String value : values) {
            statement.setString(index++, value);
        }
    }
}
