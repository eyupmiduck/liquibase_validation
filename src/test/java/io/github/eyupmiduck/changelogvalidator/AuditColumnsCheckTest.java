package io.github.eyupmiduck.changelogvalidator;

import io.github.eyupmiduck.changelogvalidator.testing.PostgresTestBase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link AuditColumnsCheck} against a real PostgreSQL: the catalog
 * check for the audit-column convention and the behavioral probe that an
 * {@code UPDATE} refreshes {@code updated_at} without touching
 * {@code created_at}.
 */
class AuditColumnsCheckTest {

    private static GenericContainer<?> postgres;
    private static Connection connection;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = new GenericContainer<>(DockerImageName.parse(
                System.getProperty(PostgresTestBase.POSTGRES_IMAGE_PROPERTY, PostgresTestBase.DEFAULT_POSTGRES_IMAGE)))
                .withEnv("POSTGRES_DB", "test")
                .withEnv("POSTGRES_USER", "test")
                .withEnv("POSTGRES_PASSWORD", "test")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
        postgres.start();

        connection = openConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA audited");
            statement.execute("CREATE SCHEMA unaudited");
        }
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

    private static Connection openConnection() throws Exception {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/test";
        SQLException failure = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try {
                return DriverManager.getConnection(url, "test", "test");
            } catch (SQLException e) {
                failure = e;
                Thread.sleep(1000);
            }
        }
        throw failure;
    }

    private static List<String> problems(List<AuditColumnsCheck.Violation> violations) {
        return violations.stream().map(AuditColumnsCheck.Violation::describe).toList();
    }

    /**
     * Drops the fixtures so each test starts from a clean schema.
     */
    @BeforeEach
    void resetSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA audited CASCADE");
            statement.execute("DROP SCHEMA unaudited CASCADE");
            statement.execute("CREATE SCHEMA audited");
            statement.execute("CREATE SCHEMA unaudited");
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Creates a table with correct audit columns and the shared trigger.
     */
    private void createAuditedTable(String table) throws SQLException {
        execute("""
                CREATE TABLE audited.%s (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """.formatted(table));
        execute("""
                CREATE OR REPLACE FUNCTION audited.set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    NEW.updated_at := now();
                    RETURN NEW;
                END;
                $$""");
        execute("CREATE TRIGGER %s_set_updated_at BEFORE UPDATE ON audited.%s"
                .formatted(table, table)
                + " FOR EACH ROW EXECUTE FUNCTION audited.set_updated_at()");
    }

    /**
     * An empty or misspelled schema list is rejected instead of producing a
     * vacuous pass.
     */
    @Test
    void rejectsEmptyAndMissingSchemas() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> AuditColumnsCheck.findViolations(connection, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> AuditColumnsCheck.findViolations(connection, List.of("missing_schema")));
    }

    /**
     * A compliant table produces no violations.
     */
    @Test
    void acceptsCompliantTable() throws Exception {
        createAuditedTable("orders");

        List<AuditColumnsCheck.Violation> violations =
                AuditColumnsCheck.findViolations(connection, List.of("audited"));

        assertEquals(List.of(), problems(violations));
    }

    /**
     * A table with no audit columns at all is reported for both columns.
     */
    @Test
    void reportsMissingColumns() throws Exception {
        execute("CREATE TABLE unaudited.plain (id integer PRIMARY KEY)");

        List<AuditColumnsCheck.Violation> violations =
                AuditColumnsCheck.findViolations(connection, List.of("unaudited"));

        assertTrue(problems(violations).stream().anyMatch(p -> p.contains("created_at is missing")),
                () -> "expected a missing created_at; got: " + problems(violations));
        assertTrue(problems(violations).stream().anyMatch(p -> p.contains("updated_at is missing")),
                () -> "expected a missing updated_at; got: " + problems(violations));
    }

    /**
     * Wrong type, nullable columns and a non-now() default are all reported.
     */
    @Test
    void reportsWrongTypeNullabilityAndDefault() throws Exception {
        execute("""
                CREATE TABLE unaudited.sloppy (
                    id integer PRIMARY KEY,
                    created_at timestamp NOT NULL DEFAULT now(),
                    updated_at timestamptz DEFAULT '2020-01-01'::timestamptz
                )
                """);

        List<String> problems = problems(AuditColumnsCheck.findViolations(connection, List.of("unaudited")));

        assertTrue(problems.stream().anyMatch(p -> p.contains("created_at must be timestamp with time zone")),
                () -> "expected a type violation; got: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("updated_at must be NOT NULL")),
                () -> "expected a nullability violation; got: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("updated_at must default to now()")),
                () -> "expected a default violation; got: " + problems);
    }

    /**
     * A table whose columns are correct but that has no trigger is reported.
     */
    @Test
    void reportsMissingTrigger() throws Exception {
        execute("""
                CREATE TABLE unaudited.untracked (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);

        List<String> problems = problems(AuditColumnsCheck.findViolations(connection, List.of("unaudited")));

        assertEquals(List.of("unaudited.untracked: no enabled BEFORE UPDATE FOR EACH ROW trigger"), problems);
    }

    /**
     * A statement-level trigger and a disabled trigger do not satisfy the
     * convention, because neither refreshes a row's updated_at.
     */
    @Test
    void rejectsStatementLevelAndDisabledTriggers() throws Exception {
        execute("CREATE OR REPLACE FUNCTION unaudited.noop() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$");
        execute("""
                CREATE TABLE unaudited.statement_level (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        execute("CREATE TRIGGER stmt BEFORE UPDATE ON unaudited.statement_level"
                + " FOR EACH STATEMENT EXECUTE FUNCTION unaudited.noop()");
        execute("""
                CREATE TABLE unaudited.disabled (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        execute("CREATE TRIGGER dis BEFORE UPDATE ON unaudited.disabled"
                + " FOR EACH ROW EXECUTE FUNCTION unaudited.noop()");
        execute("ALTER TABLE unaudited.disabled DISABLE TRIGGER dis");

        List<String> problems = problems(AuditColumnsCheck.findViolations(connection, List.of("unaudited")));

        assertTrue(problems.stream().anyMatch(p -> p.startsWith("unaudited.statement_level")),
                () -> "statement-level trigger must not count; got: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.startsWith("unaudited.disabled")),
                () -> "disabled trigger must not count; got: " + problems);
    }

    /**
     * Column-restricted and WHEN-clause triggers do not refresh every update and
     * must not satisfy the convention.
     */
    @Test
    void rejectsRestrictedTriggers() throws Exception {
        execute("CREATE OR REPLACE FUNCTION unaudited.noop() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$");
        for (String table : List.of("column_only", "when_only")) {
            execute("""
                    CREATE TABLE unaudited.%s (
                        id integer PRIMARY KEY,
                        created_at timestamptz NOT NULL DEFAULT now(),
                        updated_at timestamptz NOT NULL DEFAULT now()
                    )
                    """.formatted(table));
        }
        execute("CREATE TRIGGER column_only_trigger BEFORE UPDATE OF updated_at ON unaudited.column_only"
                + " FOR EACH ROW EXECUTE FUNCTION unaudited.noop()");
        execute("CREATE TRIGGER when_only_trigger BEFORE UPDATE ON unaudited.when_only"
                + " FOR EACH ROW WHEN (OLD.id IS DISTINCT FROM NEW.id) EXECUTE FUNCTION unaudited.noop()");

        List<String> problems = problems(AuditColumnsCheck.findViolations(connection, List.of("unaudited")));

        assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("unaudited.column_only")),
                () -> "column-restricted trigger must not count; got: " + problems);
        assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("unaudited.when_only")),
                () -> "WHEN-clause trigger must not count; got: " + problems);
    }

    /**
     * A trigger that returns NULL suppresses the probe update; the probe reports a
     * failure instead of pretending the table was empty.
     */
    @Test
    void probeReportsASuppressedUpdate() throws Exception {
        execute("CREATE OR REPLACE FUNCTION unaudited.suppress() RETURNS trigger LANGUAGE plpgsql AS $$"
                + " BEGIN RETURN NULL; END; $$");
        execute("""
                CREATE TABLE unaudited.suppressed (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        execute("CREATE TRIGGER suppress BEFORE UPDATE ON unaudited.suppressed"
                + " FOR EACH ROW EXECUTE FUNCTION unaudited.suppress()");
        execute("INSERT INTO unaudited.suppressed (id) VALUES (1)");

        Optional<AuditColumnsCheck.Probe> probe =
                AuditColumnsCheck.probeUpdate(connection, "unaudited", "suppressed");

        assertTrue(probe.isPresent());
        assertFalse(probe.get().passed(), () -> probe.get().describe());
    }

    /**
     * A partitioned table has no safe cross-partition ctid, so the probe skips it
     * rather than updating an arbitrary partition row.
     */
    @Test
    void probeSkipsPartitionedTables() throws Exception {
        execute("""
                CREATE TABLE unaudited.parted (
                    id integer,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                ) PARTITION BY RANGE (id)
                """);
        execute("CREATE TABLE unaudited.parted_low PARTITION OF unaudited.parted FOR VALUES FROM (0) TO (100)");
        execute("INSERT INTO unaudited.parted (id) VALUES (1)");

        assertTrue(AuditColumnsCheck.probeUpdate(connection, "unaudited", "parted").isEmpty());
    }

    /**
     * Equivalent spellings of now() (CURRENT_TIMESTAMP, transaction_timestamp,
     * a precision specifier and a redundant cast) are accepted as defaults.
     */
    @Test
    void acceptsEquivalentNowDefaults() throws Exception {
        execute("CREATE OR REPLACE FUNCTION audited.set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$"
                + " BEGIN NEW.updated_at := now(); RETURN NEW; END; $$");
        execute("""
                CREATE TABLE audited.equivalent (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp()
                )
                """);
        execute("CREATE TRIGGER equivalent_set_updated_at BEFORE UPDATE ON audited.equivalent"
                + " FOR EACH ROW EXECUTE FUNCTION audited.set_updated_at()");
        execute("""
                CREATE TABLE audited.precision (
                    id integer PRIMARY KEY,
                    created_at timestamptz(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at timestamptz NOT NULL DEFAULT now()::timestamptz(3)
                )
                """);
        execute("CREATE TRIGGER precision_set_updated_at BEFORE UPDATE ON audited.precision"
                + " FOR EACH ROW EXECUTE FUNCTION audited.set_updated_at()");

        List<AuditColumnsCheck.Violation> violations =
                AuditColumnsCheck.findViolations(connection, List.of("audited"));

        assertEquals(List.of(), problems(violations));
    }

    /**
     * An unrelated cast on a now-family default (for example now()::date) is
     * reported, so the default must resolve to a timestamp.
     */
    @Test
    void rejectsAnUnrelatedCastOnANowDefault() throws Exception {
        execute("""
                CREATE TABLE unaudited.related_cast (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT (now()::date)
                )
                """);

        List<String> problems = problems(AuditColumnsCheck.findViolations(connection, List.of("unaudited")));

        assertTrue(problems.stream().anyMatch(p -> p.contains("updated_at must default to now()")),
                () -> "now()::date must not be accepted; got: " + problems);
    }

    /**
     * A non-transaction timestamp default (statement_timestamp) is still
     * reported, so the relaxed default matching does not accept everything.
     */
    @Test
    void rejectsNonTransactionTimestampDefault() throws Exception {
        execute("""
                CREATE TABLE unaudited.untracked (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
                    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
                )
                """);

        List<String> problems = problems(AuditColumnsCheck.findViolations(connection, List.of("unaudited")));

        assertTrue(problems.stream().anyMatch(p -> p.contains("created_at must default to now()")),
                () -> "statement_timestamp must not be accepted; got: " + problems);
        assertTrue(problems.stream().anyMatch(p -> p.contains("updated_at must default to now()")),
                () -> "clock_timestamp must not be accepted; got: " + problems);
    }

    /**
     * The probe passes for a table whose trigger refreshes updated_at, and the
     * rolled-back update leaves the row unchanged.
     */
    @Test
    void probePassesAndLeavesRowUnchanged() throws Exception {
        createAuditedTable("orders");
        execute("INSERT INTO audited.orders (id) VALUES (1)");
        Object before = updatedAt("audited.orders", 1);

        Optional<AuditColumnsCheck.Probe> probe = AuditColumnsCheck.probeUpdate(connection, "audited", "orders");

        assertTrue(probe.isPresent());
        assertTrue(probe.get().passed(), () -> probe.get().describe());
        assertEquals(before, updatedAt("audited.orders", 1), "the probe must roll its update back");
    }

    /**
     * The probe fails when no trigger refreshes updated_at: the sentinel sticks.
     */
    @Test
    void probeFailsWithoutRefreshingTrigger() throws Exception {
        execute("""
                CREATE TABLE unaudited.untracked (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        execute("INSERT INTO unaudited.untracked (id) VALUES (1)");

        Optional<AuditColumnsCheck.Probe> probe =
                AuditColumnsCheck.probeUpdate(connection, "unaudited", "untracked");

        assertTrue(probe.isPresent());
        assertFalse(probe.get().updatedAtRefreshed(), () -> probe.get().describe());
        assertTrue(probe.get().createdAtPreserved(), () -> probe.get().describe());
        assertFalse(probe.get().passed());
    }

    /**
     * A trigger that writes a fixed timestamp after the sentinel is not a
     * refresh, so the probe must reject it.
     */
    @Test
    void probeRejectsAFixedTimestamp() throws Exception {
        execute("CREATE OR REPLACE FUNCTION unaudited.fixed_set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$"
                + " BEGIN NEW.updated_at := '2025-01-01T00:00:00Z'::timestamptz; RETURN NEW; END; $$");
        execute("""
                CREATE TABLE unaudited.fixed (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        execute("CREATE TRIGGER fixed_set_updated_at BEFORE UPDATE ON unaudited.fixed"
                + " FOR EACH ROW EXECUTE FUNCTION unaudited.fixed_set_updated_at()");
        execute("INSERT INTO unaudited.fixed (id) VALUES (1)");

        Optional<AuditColumnsCheck.Probe> probe = AuditColumnsCheck.probeUpdate(connection, "unaudited", "fixed");

        assertTrue(probe.isPresent());
        assertFalse(probe.get().updatedAtRefreshed(), () -> probe.get().describe());
    }

    /**
     * The probe reports nothing to check for an empty table.
     */
    @Test
    void probeReturnsEmptyForEmptyTable() throws Exception {
        createAuditedTable("empty");

        Optional<AuditColumnsCheck.Probe> probe = AuditColumnsCheck.probeUpdate(connection, "audited", "empty");

        assertTrue(probe.isEmpty());
    }

    /**
     * The probe preserves a caller's in-flight transaction: an uncommitted
     * insert survives the probe and can still be committed.
     */
    @Test
    void probePreservesCallerTransaction() throws Exception {
        createAuditedTable("orders");
        try (Connection caller = openConnection()) {
            caller.setAutoCommit(false);
            try (Statement statement = caller.createStatement()) {
                statement.execute("INSERT INTO audited.orders (id) VALUES (7)");
            }

            AuditColumnsCheck.probeUpdate(caller, "audited", "orders");

            caller.commit();
        }

        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count(*) FROM audited.orders WHERE id = 7")) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1), "the caller's insert must survive the probe");
        }
    }

    /**
     * The probe works on a connection left in autocommit mode, restoring it.
     */
    @Test
    void probeWorksInAutocommitAndRestoresIt() throws Exception {
        createAuditedTable("orders");
        execute("INSERT INTO audited.orders (id) VALUES (1)");

        try (Connection autocommit = openConnection()) {
            assertTrue(autocommit.getAutoCommit());

            Optional<AuditColumnsCheck.Probe> probe = AuditColumnsCheck.probeUpdate(autocommit, "audited", "orders");

            assertTrue(probe.isPresent());
            assertTrue(probe.get().passed(), () -> probe.get().describe());
            assertTrue(autocommit.getAutoCommit(), "autocommit must be restored");
        }
    }

    /**
     * A failed rollback leaves the connection in manual-commit mode instead of
     * switching to autocommit (which would commit the probe's update), and the
     * row remains unchanged when the connection closes.
     */
    @Test
    void doesNotCommitWhenRollbackFails() throws Exception {
        createAuditedTable("orders");
        execute("INSERT INTO audited.orders (id) VALUES (1)");
        Object before = updatedAt("audited.orders", 1);

        try (Connection delegate = openConnection()) {
            Connection failing = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("rollback") && (args == null || args.length == 0)) {
                            throw new SQLException("rollback failed");
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });

            assertThrows(SQLException.class,
                    () -> AuditColumnsCheck.probeUpdate(failing, "audited", "orders"));
            assertFalse(delegate.getAutoCommit(),
                    "autocommit must not be restored after a failed rollback");
        }

        assertEquals(before, updatedAt("audited.orders", 1),
                "the probe update must not be committed");
    }

    /**
     * A quoted, mixed-case table name is probed correctly.
     */
    @Test
    void probesQuotedIdentifier() throws Exception {
        execute("CREATE OR REPLACE FUNCTION audited.set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$"
                + " BEGIN NEW.updated_at := now(); RETURN NEW; END; $$");
        execute("""
                CREATE TABLE audited."Order Lines" (
                    id integer PRIMARY KEY,
                    created_at timestamptz NOT NULL DEFAULT now(),
                    updated_at timestamptz NOT NULL DEFAULT now()
                )
                """);
        execute("CREATE TRIGGER order_lines_set_updated_at BEFORE UPDATE ON audited.\"Order Lines\""
                + " FOR EACH ROW EXECUTE FUNCTION audited.set_updated_at()");
        execute("INSERT INTO audited.\"Order Lines\" (id) VALUES (1)");

        Optional<AuditColumnsCheck.Probe> probe =
                AuditColumnsCheck.probeUpdate(connection, "audited", "Order Lines");

        assertTrue(probe.isPresent());
        assertTrue(probe.get().passed(), () -> probe.get().describe());
    }

    private Object updatedAt(String table, int id) throws SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery(
                     "SELECT updated_at FROM " + table + " WHERE id = " + id)) {
            assertTrue(result.next());
            return result.getObject(1);
        }
    }
}
