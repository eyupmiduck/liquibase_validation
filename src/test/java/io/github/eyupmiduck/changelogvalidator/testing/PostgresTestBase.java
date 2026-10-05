package io.github.eyupmiduck.changelogvalidator.testing;

import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base class for tests that need a migrated PostgreSQL database.
 *
 * <p>A single PostgreSQL container is shared by all tests. On first use, the
 * roles must already exist (the custom image's init script creates them) and the
 * Liquibase changelog is applied once to a template database, connecting as the
 * owner role (like a real deployment). Each test class then gets its own private
 * database, created cheaply with {@code CREATE DATABASE ... TEMPLATE ...}, and
 * connects to it as the test role. The private database is dropped after the
 * class finishes.
 *
 * <p>A subclass supplies the project-specific configuration by overriding the
 * {@code protected} accessors: the role/database names, the changelog resource,
 * the optional fixture changelog, the PostgreSQL image default and any
 * extensions the template database needs. The shared helpers assume the test
 * role is a member of the caller role and has {@code CREATE} on the public
 * schema, which the init script must arrange.
 *
 * <p>The consumers must have JUnit, Testcontainers ({@code postgresql}), jOOQ,
 * {@code liquibase-core} and the PostgreSQL driver on the test classpath; a
 * {@code tests} classifier dependency is not transitive, so each consumer
 * declares them itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class PostgresTestBase {

    /**
     * The schema tests create their own tables in; the test role has
     * {@code CREATE} on it.
     */
    public static final String PUBLIC_SCHEMA = "public";

    /**
     * The schema Liquibase keeps its tracking tables in, so they stay out of the
     * application schemas. The custom image's init script creates it for real
     * databases; {@link #prepareTemplateDatabase} creates it for the template.
     */
    public static final String LIQUIBASE_SCHEMA = "liquibase";

    private static PostgreSQLContainer postgres;

    /**
     * jOOQ context connected to this test class's private database.
     */
    protected DSLContext dsl;
    private String databaseName;
    private Connection connection;

    /**
     * The PostgreSQL image to run, matching the one used for jOOQ codegen.
     * Defaults to the {@code postgres.image} system property (set by surefire)
     * or the subclass's {@link #defaultPostgresImage()}.
     */
    protected static String postgresImage() {
        return System.getProperty("postgres.image", "postgres:17-alpine");
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (connection; Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Returns the SQLSTATE of the first {@link SQLException} in a throwable's
     * cause chain, or {@code null} when there is none.
     *
     * @param throwable the throwable to inspect
     * @return the SQLSTATE, or {@code null}
     */
    public static String sqlState(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        return null;
    }

    /**
     * Asserts that a call fails with the given SQLSTATE.
     *
     * @param expectedSqlState the expected SQLSTATE
     * @param call             the call under test
     */
    public static void assertSqlState(String expectedSqlState, Executable call) {
        DataAccessException exception = assertThrows(DataAccessException.class, call);
        assertEquals(expectedSqlState, sqlState(exception),
                () -> "expected SQLSTATE " + expectedSqlState + " but was: " + exception.getMessage());
    }

    /**
     * Asserts that a call fails with SQLSTATE {@code 23514}
     * ({@code check_violation}), as a domain constraint violation does.
     *
     * @param call the call under test
     */
    public static void assertDomainViolation(Executable call) {
        assertSqlState("23514", call);
    }

    /**
     * The default PostgreSQL image when the {@code postgres.image} system
     * property is not set. Override to point at the project's custom image,
     * which must bake in the roles created by the init script.
     *
     * @return the default image reference
     */
    protected String defaultPostgresImage() {
        return "postgres:17-alpine";
    }

    /**
     * The database name the container is created with and tests connect to.
     *
     * @return the database name
     */
    protected String databaseName() {
        return "test";
    }

    /**
     * The owner role, which runs Liquibase and owns the schema objects.
     *
     * @return the owner role name
     */
    protected String ownerUser() {
        return "test";
    }

    /**
     * The owner role's password.
     *
     * @return the owner password
     */
    protected String ownerPassword() {
        return "test";
    }

    /**
     * The test role, which acts as an application caller.
     *
     * @return the test role name
     */
    protected String testUser() {
        return "test";
    }

    /**
     * The test role's password.
     *
     * @return the test password
     */
    protected String testPassword() {
        return "test";
    }

    /**
     * The master changelog resource path, relative to the classpath root.
     *
     * @return the master changelog resource
     */
    protected String masterResource() {
        return ChangelogTestSupport.MASTER_RESOURCE;
    }

    /**
     * The optional fixture changelog resource applied after the production
     * changelog, or {@code null} when there is none. Override to load test-only
     * fixture tables.
     *
     * @return the fixture changelog resource, or {@code null}
     */
    protected String fixturesResource() {
        return null;
    }

    /**
     * Returns the Liquibase tracking table name for this project.
     *
     * @return the change-log table name
     */
    protected String databaseChangeLogTableName() {
        return "databasechangelog";
    }

    /**
     * Returns the Liquibase tracking lock table name for this project.
     *
     * @return the change-log lock table name
     */
    protected String databaseChangeLogLockTableName() {
        return "databasechangeloglock";
    }

    /**
     * Hook to install extensions and grant roles in the template database
     * before the changelog runs (for example {@code plpgsql_check} or
     * {@code pg_background}). Runs with the container's superuser connection to
     * the template database. The default installs nothing.
     *
     * @param templateDatabase the template database name
     * @throws SQLException if the bootstrap fails
     */
    protected void installExtensions(String templateDatabase) throws SQLException {
    }

    private String templateDatabaseName() {
        return databaseName() + "_template";
    }

    /**
     * Starts the shared container and prepares the migrated template database
     * once per JVM.
     */
    @BeforeAll
    void startContainerAndCreateTestDatabase() throws Exception {
        synchronized (PostgresTestBase.class) {
            if (postgres == null) {
                postgres = new PostgreSQLContainer(DockerImageName.parse(postgresImage())
                        .asCompatibleSubstituteFor("postgres"))
                        .withDatabaseName(databaseName());
                try {
                    postgres.start();
                    prepareTemplateDatabase();
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to start the PostgreSQL test container", e);
                }
            }
        }
        createTestDatabase();
    }

    private void prepareTemplateDatabase() {
        String template = templateDatabaseName();
        try {
            // Tolerate a template left behind by an interrupted earlier run in
            // the same container, so setup is repeatable.
            execute(adminConnection(databaseName()), "DROP DATABASE IF EXISTS " + template + " WITH (FORCE)");
            execute(adminConnection(databaseName()), "CREATE DATABASE " + template);
            // The template is fresh, so grant the owner role the privileges it
            // needs to run Liquibase (as the init script does for the main
            // database): CREATE on the database and on its public schema. The
            // test role may create tables it owns in public too.
            execute(adminConnection(databaseName()), "GRANT CREATE ON DATABASE " + template + " TO " + ownerUser());
            try (Connection admin = adminConnection(template);
                 Statement statement = admin.createStatement()) {
                statement.execute("GRANT CREATE ON SCHEMA public TO " + ownerUser());
                statement.execute("GRANT CREATE ON SCHEMA public TO " + testUser());
            }
            installExtensions(template);
            try (Connection owner = connection(template, ownerUser(), ownerPassword())) {
                try (Statement statement = owner.createStatement()) {
                    statement.execute("CREATE SCHEMA IF NOT EXISTS " + LIQUIBASE_SCHEMA);
                }
                Database database = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(owner));
                database.setLiquibaseSchemaName(LIQUIBASE_SCHEMA);
                database.setDatabaseChangeLogTableName(databaseChangeLogTableName());
                database.setDatabaseChangeLogLockTableName(databaseChangeLogLockTableName());
                new Liquibase(masterResource(), new ClassLoaderResourceAccessor(), database).update();
                String fixtures = fixturesResource();
                if (fixtures != null) {
                    new Liquibase(fixtures, new ClassLoaderResourceAccessor(), database).update();
                }
            }
            // Mark as a real template and forbid connections, so a leaked session
            // cannot make CREATE DATABASE ... TEMPLATE fail.
            execute(adminConnection(databaseName()),
                    "ALTER DATABASE " + template + " WITH IS_TEMPLATE TRUE ALLOW_CONNECTIONS FALSE");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare template database", e);
        }
    }

    private void createTestDatabase() throws SQLException {
        String template = templateDatabaseName();
        // Bounded and collision-resistant: a hash suffix of the fully qualified
        // name keeps the identifier under PostgreSQL's 63-byte limit and wide
        // enough that two test classes do not collide. Quoted so an unusual
        // class name cannot produce an invalid identifier.
        String suffix = Integer.toUnsignedString(getClass().getName().hashCode(), 16);
        databaseName = "test_" + suffix;
        try (Connection admin = adminConnection(databaseName());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE \"" + databaseName + "\" TEMPLATE " + template);
        } catch (SQLException e) {
            dropDatabaseQuietly();
            throw e;
        }
        try {
            connection = connection(databaseName, testUser(), testPassword());
            dsl = DSL.using(connection, SQLDialect.POSTGRES);
        } catch (SQLException e) {
            if (connection != null) {
                connection.close();
            }
            dropDatabaseQuietly();
            throw e;
        }
    }

    private Connection adminConnection(String database) throws SQLException {
        return connection(database, postgres.getUsername(), postgres.getPassword());
    }

    /**
     * Opens a superuser connection to a database in the shared container, for
     * use from {@link #installExtensions(String)}. The caller closes it.
     *
     * @param database the database to connect to
     * @return a superuser connection
     * @throws SQLException if the connection cannot be opened
     */
    protected Connection openAdminConnection(String database) throws SQLException {
        return adminConnection(database);
    }

    private Connection connection(String database, String user, String password) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database,
                user, password);
    }

    /**
     * Opens an additional connection to this test class's private database as
     * the test role, for tests that need a second session. The caller is
     * responsible for closing it.
     *
     * @return a new connection to the private test database
     * @throws SQLException if the connection cannot be opened
     */
    protected Connection openTestConnection() throws SQLException {
        return connection(databaseName, testUser(), testPassword());
    }

    /**
     * Opens a connection to this test class's private database as the schema
     * owner, for tests that must change data the caller role cannot. The caller
     * is responsible for closing it.
     *
     * @return a new owner connection to the private test database
     * @throws SQLException if the connection cannot be opened
     */
    protected Connection openOwnerConnection() throws SQLException {
        return connection(databaseName, ownerUser(), ownerPassword());
    }

    /**
     * Returns whether a base table exists (ordinary or partitioned), reading the
     * catalog directly so the lookup works for schemas the test role has no
     * privileges on. A view, sequence or index with the same name does not count.
     *
     * @param schema   the schema name
     * @param relation the table name
     * @return {@code true} when the table exists
     */
    protected boolean relationExists(String schema, String relation) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_class c
                            JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname = ? AND c.relname = ?
                              AND c.relkind IN ('r', 'p')
                        )
                        """,
                schema, relation));
    }

    /**
     * Returns whether a table exists.
     *
     * @param schema the table schema
     * @param table  the table name
     * @return {@code true} when the table exists
     */
    protected boolean tableExists(String schema, String table) {
        return relationExists(schema, table);
    }

    /**
     * Returns whether a table exists in the public schema.
     *
     * @param table the table name
     * @return {@code true} when the table exists
     */
    protected boolean tableExists(String table) {
        return tableExists(PUBLIC_SCHEMA, table);
    }

    /**
     * Returns the {@code information_schema.columns} row for a column, or
     * {@code null} when the column does not exist.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     * @return the column's information_schema row, or {@code null}
     */
    protected Record column(String schema, String table, String column) {
        return dsl.fetchOne(
                """
                        SELECT *
                        FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = ? AND column_name = ?
                        """,
                schema, table, column);
    }

    /**
     * Returns whether a column exists.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     * @return {@code true} when the column exists
     */
    protected boolean hasColumn(String schema, String table, String column) {
        return column(schema, table, column) != null;
    }

    /**
     * Returns a single {@code information_schema.columns} attribute for a
     * column, failing when the column does not exist.
     *
     * @param schema    the table schema
     * @param table     the table name
     * @param column    the column name
     * @param attribute the information_schema column to read
     * @return the attribute value
     */
    protected String columnAttribute(String schema, String table, String column, String attribute) {
        Record record = column(schema, table, column);
        assertNotNull(record, () -> "column not found: " + schema + "." + table + "." + column);
        return record.get(attribute, String.class);
    }

    /**
     * Returns whether the named trigger exists on a table.
     *
     * @param schema  the table schema
     * @param table   the table name
     * @param trigger the trigger name
     * @return {@code true} when the trigger exists
     */
    protected boolean triggerExists(String schema, String table, String trigger) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                """
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_trigger t
                            JOIN pg_class c ON c.oid = t.tgrelid
                            JOIN pg_namespace n ON n.oid = c.relnamespace
                            WHERE n.nspname = ? AND c.relname = ? AND t.tgname = ? AND NOT t.tgisinternal
                        )
                        """,
                schema, table, trigger));
    }

    /**
     * Drops this class's private database, ignoring an already-gone database, so
     * a failed setup does not leak it.
     */
    private void dropDatabaseQuietly() {
        if (databaseName == null) {
            return;
        }
        try {
            execute(adminConnection(databaseName()),
                    "DROP DATABASE IF EXISTS \"" + databaseName + "\" WITH (FORCE)");
        } catch (SQLException ignored) {
            // Best effort; the @AfterAll cleanup is the backstop.
        }
    }

    /**
     * Closes the connection and drops this test class's private database.
     */
    @AfterAll
    void closeAndDropTestDatabase() throws Exception {
        try {
            if (connection != null) {
                connection.close();
            }
        } finally {
            // Drop even if closing the connection failed, so the per-class
            // database cannot be leaked by a broken connection.
            dropDatabaseQuietly();
        }
    }

    /**
     * Returns whether the test class's private database is still open and
     * usable; used by tests that need to assert setup ran.
     *
     * @return {@code true} when the database connection is open
     */
    protected boolean databaseReady() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            return false;
        }
    }
}
