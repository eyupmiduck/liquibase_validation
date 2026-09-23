package io.github.eyupmiduck.changelogvalidator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link PlpgsqlCheck} against a real PostgreSQL with the
 * {@code plpgsql_check} extension installed (built into the image with the
 * Debian package).
 */
class PlpgsqlCheckTest {

    private static GenericContainer<?> postgres;
    private static Connection connection;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = new GenericContainer<>(new ImageFromDockerfile()
                .withDockerfileFromBuilder(builder -> builder
                        .from("postgres:17")
                        .run("apt-get update"
                                + " && apt-get install -y --no-install-recommends postgresql-17-plpgsql-check"
                                + " && rm -rf /var/lib/apt/lists/*")
                        .build()))
                .withEnv("POSTGRES_PASSWORD", "test")
                .withEnv("POSTGRES_DB", "test")
                .withExposedPorts(5432);
        postgres.start();

        connection = openConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION plpgsql_check");
            statement.execute("CREATE SCHEMA checked");
            statement.execute("""
                    CREATE FUNCTION checked.clean() RETURNS integer LANGUAGE plpgsql IMMUTABLE AS $$
                    BEGIN
                        RETURN 1;
                    END;
                    $$""");
            statement.execute("""
                    CREATE FUNCTION checked.warned() RETURNS integer LANGUAGE plpgsql AS $$
                    BEGIN
                        RETURN missing_column + 1;
                    END;
                    $$""");
            // A non-PL/pgSQL function must be skipped, not checked.
            statement.execute("CREATE FUNCTION checked.sql_language() RETURNS integer LANGUAGE sql AS $$ SELECT 1 $$");
            statement.execute("""
                    CREATE PROCEDURE checked.broken_procedure() LANGUAGE plpgsql AS $$
                    DECLARE l_x integer;
                    BEGIN
                        SELECT missing_column INTO l_x FROM pg_class;
                    END;
                    $$""");
            // A trigger function is analysed through the relation it is
            // attached to; an unattached one has no relation to analyse.
            statement.execute("CREATE TABLE checked.audited(id integer, updated_at timestamptz)");
            statement.execute("CREATE TABLE checked.audited_broken(id integer, updated_at timestamptz)");
            statement.execute("""
                    CREATE FUNCTION checked.trigger_clean() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        NEW.updated_at := now();
                        RETURN NEW;
                    END;
                    $$""");
            statement.execute("""
                    CREATE FUNCTION checked.trigger_warned() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        NEW.updated_at := missing_column;
                        RETURN NEW;
                    END;
                    $$""");
            statement.execute("""
                    CREATE FUNCTION checked.trigger_unattached() RETURNS trigger LANGUAGE plpgsql AS $$
                    BEGIN
                        NEW.updated_at := now();
                        RETURN NEW;
                    END;
                    $$""");
            statement.execute("""
                    CREATE TRIGGER audited_set_updated_at
                        BEFORE UPDATE ON checked.audited
                        FOR EACH ROW EXECUTE FUNCTION checked.trigger_clean()
                    """);
            statement.execute("""
                    CREATE TRIGGER audited_broken_set_updated_at
                        BEFORE UPDATE ON checked.audited_broken
                        FOR EACH ROW EXECUTE FUNCTION checked.trigger_warned()
                    """);
        }
    }

    /**
     * Connects to the container, retrying while PostgreSQL is still starting up
     * (the port is listening before it accepts connections).
     */
    private static Connection openConnection() throws Exception {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/test";
        SQLException failure = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                return DriverManager.getConnection(url, "postgres", "test");
            } catch (SQLException e) {
                failure = e;
                Thread.sleep(1000);
            }
        }
        throw failure;
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (connection != null) {
            connection.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static ByteArrayInputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * findFindings reports the warned function and not the clean or non-PL/pgSQL
     * functions.
     */
    @Test
    void findsWarnings() throws Exception {
        List<PlpgsqlCheck.Finding> findings = PlpgsqlCheck.findFindings(connection, List.of("checked"));

        assertTrue(findings.stream().anyMatch(finding -> finding.function().equals("warned")));
        assertTrue(findings.stream().noneMatch(finding -> finding.function().equals("clean")
                || finding.function().equals("sql_language")));
    }

    /**
     * findFindings reports a broken procedure, not only broken functions.
     */
    @Test
    void findsWarningsInProcedures() throws Exception {
        List<PlpgsqlCheck.Finding> findings = PlpgsqlCheck.findFindings(connection, List.of("checked"));

        assertTrue(findings.stream().anyMatch(finding -> finding.function().equals("broken_procedure")),
                () -> "expected a finding for broken_procedure; got: " + findings);
    }

    /**
     * A trigger function is analysed through its trigger relation, so its
     * findings are reported and a clean one produces none.
     */
    @Test
    void analysesAttachedTriggerFunctions() throws Exception {
        List<PlpgsqlCheck.Finding> findings = PlpgsqlCheck.findFindings(connection, List.of("checked"));

        assertTrue(findings.stream().anyMatch(finding -> finding.function().equals("trigger_warned")),
                () -> "expected a finding for trigger_warned; got: " + findings);
        assertTrue(findings.stream().noneMatch(finding -> finding.function().equals("trigger_clean")),
                () -> "trigger_clean should produce no finding; got: " + findings);
    }

    /**
     * A trigger function attached to no relation cannot be analysed (the
     * analyser needs a trigger relation to resolve NEW/OLD), so it is skipped
     * rather than failing the whole check.
     */
    @Test
    void skipsUnattachedTriggerFunctions() throws Exception {
        List<PlpgsqlCheck.Finding> findings = PlpgsqlCheck.findFindings(connection, List.of("checked"));

        assertTrue(findings.stream().noneMatch(finding -> finding.function().equals("trigger_unattached")),
                () -> "trigger_unattached should be skipped; got: " + findings);
    }

    /**
     * A finding is accepted when an allow-list entry matches it.
     */
    @Test
    void acceptsWhitelistedFindings() throws Exception {
        PlpgsqlCheck.Report report = PlpgsqlCheck.check(connection, List.of("checked"),
                List.of(new PlpgsqlCheck.AllowedFinding("checked", "warned", null, null, null),
                        new PlpgsqlCheck.AllowedFinding("checked", "broken_procedure", null, null, null),
                        new PlpgsqlCheck.AllowedFinding("checked", "trigger_warned", null, null, null)));

        assertTrue(report.isEmpty());
    }

    /**
     * With no allow-list entries, every finding is unexpected and there is
     * nothing stale.
     */
    @Test
    void reportsUnexpectedFindings() throws Exception {
        PlpgsqlCheck.Report report = PlpgsqlCheck.check(connection, List.of("checked"), List.of());

        assertFalse(report.isEmpty());
        assertFalse(report.unexpected().isEmpty());
        assertTrue(report.stale().isEmpty());
    }

    /**
     * An entry that matches no finding is reported as stale.
     */
    @Test
    void reportsStaleEntries() throws Exception {
        PlpgsqlCheck.AllowedFinding stale = new PlpgsqlCheck.AllowedFinding("checked", "absent", null, null, null);

        PlpgsqlCheck.Report report = PlpgsqlCheck.check(connection, List.of("checked"), List.of(stale));

        assertEquals(List.of(stale), report.stale());
        assertFalse(report.isEmpty());
    }

    /**
     * loadWhitelist reads the entries and leaves unset fields null; other keys
     * (such as a reason) are ignored.
     */
    @Test
    void loadsWhitelist() throws Exception {
        String yaml = """
                - schema: ddl_utils_lib
                  function: alter_table
                  level: security
                  statement: EXECUTE
                  message: text type variable is not sanitized
                  reason: accepted by design
                - function: only_function
                """;

        List<PlpgsqlCheck.AllowedFinding> allowed = PlpgsqlCheck.loadWhitelist(stream(yaml));

        assertEquals(2, allowed.size());
        assertEquals(new PlpgsqlCheck.AllowedFinding("ddl_utils_lib", "alter_table", "security",
                "EXECUTE", "text type variable is not sanitized"), allowed.get(0));
        assertEquals("only_function", allowed.get(1).function());
        assertNull(allowed.get(1).schema());
        assertNull(allowed.get(1).message());
    }

    /**
     * Overlapping entries are all marked as used, so a finding matched by more
     * than one entry does not leave the other entries stale.
     */
    @Test
    void overlappingEntriesAreNotStale() throws Exception {
        PlpgsqlCheck.Report report = PlpgsqlCheck.check(connection, List.of("checked"), List.of(
                new PlpgsqlCheck.AllowedFinding("checked", "warned", null, null, null),
                new PlpgsqlCheck.AllowedFinding("checked", null, null, null, null)));

        assertTrue(report.isEmpty());
    }

    /**
     * loadWhitelist rejects a document that is not a list of mappings, and an
     * entry that sets none of the recognised keys.
     */
    @Test
    void rejectsMalformedWhitelist() {
        assertThrows(IOException.class, () -> PlpgsqlCheck.loadWhitelist(stream("schema: x")));
        assertThrows(IOException.class, () -> PlpgsqlCheck.loadWhitelist(stream("- just-a-string")));
        assertThrows(IOException.class, () -> PlpgsqlCheck.loadWhitelist(stream("- schemas: misspelled")));
    }

    /**
     * An entry matches a finding when every non-null field is equal.
     */
    @Test
    void matchesFindingsFieldByField() {
        PlpgsqlCheck.Finding finding = new PlpgsqlCheck.Finding("s", "f", 7, "warning", "DECLARE", "msg");

        // An all-null entry would match every finding; it is rejected.
        assertThrows(IllegalArgumentException.class,
                () -> new PlpgsqlCheck.AllowedFinding(null, null, null, null, null));

        assertTrue(new PlpgsqlCheck.AllowedFinding("s", null, null, null, null).matches(finding));
        assertTrue(new PlpgsqlCheck.AllowedFinding("s", "f", "warning", "DECLARE", "msg").matches(finding));
        assertFalse(new PlpgsqlCheck.AllowedFinding("other", null, null, null, null).matches(finding));
        assertFalse(new PlpgsqlCheck.AllowedFinding(null, null, "security", null, null).matches(finding));

        assertEquals("s.f:7: warning: msg", finding.describe());
        assertEquals("s.f warning: msg", new PlpgsqlCheck.AllowedFinding("s", "f", "warning", null, "msg").describe());
    }

    /**
     * A null or empty schema collection is rejected up front, and a null
     * allow-list is rejected, rather than NPE-ing inside the query or reporting a
     * confusing stale-whitelist failure.
     */
    @Test
    void rejectsMissingSchemasAndAllowList() {
        assertThrows(IllegalArgumentException.class, () -> PlpgsqlCheck.findFindings(null, List.of()));
        assertThrows(NullPointerException.class, () -> PlpgsqlCheck.findFindings(null, null));
        assertThrows(NullPointerException.class, () -> PlpgsqlCheck.check(null, List.of("public"), null));
    }
}
