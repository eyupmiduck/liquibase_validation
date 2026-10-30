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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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
 * <p>A subclass supplies the project-specific configuration by overriding
 * {@link #configuration()} (preferred, especially when the project also has an
 * {@link UpgradeTestBase} subclass) or the individual {@code protected}
 * accessors: the role/database names, the changelog resource, the optional
 * fixture changelog, the PostgreSQL image default and any extensions the
 * template database needs. The shared helpers assume the test role is a member
 * of the caller role and has {@code CREATE} on the public schema, which the init
 * script must arrange.
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

    /**
     * The system property naming the PostgreSQL image the tests run against,
     * set by surefire and by the CI workflow matrix.
     */
    public static final String POSTGRES_IMAGE_PROPERTY = "postgres.image";

    /**
     * The PostgreSQL image the tests run against when the property is unset.
     */
    public static final String DEFAULT_POSTGRES_IMAGE = "postgres:17-alpine";

    /**
     * The container and changelog configuration of a consumer: the database and
     * role names, the PostgreSQL image, the changelog resources and the
     * Liquibase tracking-table names.
     *
     * <p>A consumer overrides {@link #configuration()} once with a constant of
     * this type instead of overriding every accessor. Its regular test base and
     * its {@link UpgradeTestBase} subclass then share one value, so the two
     * cannot drift.
     *
     * @param databaseName               the database the container is created
     *                                   with and the tests connect to
     * @param ownerUser                  the owner role, which runs Liquibase and
     *                                   owns the schema objects
     * @param ownerPassword              the owner role's password
     * @param testUser                   the test role, which acts as an
     *                                   application caller
     * @param testPassword               the test role's password
     * @param postgresImage              the default PostgreSQL image, used when
     *                                   the {@code postgres.image} system
     *                                   property is unset
     * @param masterResource             the master changelog classpath resource
     * @param databaseChangeLogTable     the Liquibase tracking table name
     * @param databaseChangeLogLockTable the Liquibase tracking lock table name
     * @param fixturesResource           the optional fixture changelog resource,
     *                                   or {@code null} when there is none
     * @param extensionInstaller         installs the project's extensions and
     *                                   grants into a database, or {@code null}
     *                                   when the project needs none
     */
    public record Configuration(String databaseName, String ownerUser, String ownerPassword,
                                String testUser, String testPassword, String postgresImage,
                                String masterResource, String databaseChangeLogTable,
                                String databaseChangeLogLockTable, String fixturesResource,
                                ExtensionInstaller extensionInstaller) {

        /**
         * Installs the extensions and grants a project's image provides into a
         * database before the changelog runs. It receives a superuser connection
         * to the target database and must close nothing.
         */
        @FunctionalInterface
        public interface ExtensionInstaller {

            /**
             * Installs the extensions and grants.
             *
             * @param adminConnection a superuser connection to the target database
             * @throws SQLException if the installation fails
             */
            void install(Connection adminConnection) throws SQLException;
        }

        /**
         * The library's own defaults, used by its tests of the shared contracts:
         * a stock image, the conventional changelog fixture, the generic
         * Liquibase tracking-table names and no extensions.
         */
        public static final Configuration DEFAULT = new Configuration(
                "test", "test", "test", "test", "test", DEFAULT_POSTGRES_IMAGE,
                ChangelogTestSupport.MASTER_RESOURCE, "databasechangelog", "databasechangeloglock", null, null);

        /**
         * Validates the required values; only the fixture changelog may be absent.
         */
        public Configuration {
            databaseName = requireNonBlank(databaseName, "databaseName");
            ownerUser = requireNonBlank(ownerUser, "ownerUser");
            ownerPassword = requireNonBlank(ownerPassword, "ownerPassword");
            testUser = requireNonBlank(testUser, "testUser");
            testPassword = requireNonBlank(testPassword, "testPassword");
            postgresImage = requireNonBlank(postgresImage, "postgresImage");
            masterResource = requireNonBlank(masterResource, "masterResource");
            databaseChangeLogTable = requireNonBlank(databaseChangeLogTable, "databaseChangeLogTable");
            databaseChangeLogLockTable =
                    requireNonBlank(databaseChangeLogLockTable, "databaseChangeLogLockTable");
        }

        private static String requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }

        /**
         * Returns the configuration without the passwords, so a failing test
         * cannot print them.
         *
         * @return a description that omits the passwords
         */
        @Override
        public String toString() {
            return "Configuration[databaseName=" + databaseName
                    + ", ownerUser=" + ownerUser
                    + ", testUser=" + testUser
                    + ", postgresImage=" + postgresImage
                    + ", masterResource=" + masterResource
                    + ", databaseChangeLogTable=" + databaseChangeLogTable
                    + ", databaseChangeLogLockTable=" + databaseChangeLogLockTable
                    + ", fixturesResource=" + fixturesResource
                    + ", extensionInstaller=" + (extensionInstaller != null) + "]";
        }
    }
    private static PostgreSQLContainer postgres;
    /**
     * jOOQ context connected to this test class's private database.
     */
    protected DSLContext dsl;
    private String testDatabaseName;
    private Connection connection;

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

    private static String templateDatabaseName(Configuration configuration) {
        return configuration.databaseName() + "_template";
    }

    /**
     * The container and changelog configuration. The default derives it from the
     * accessors below, so a subclass may override either this method once or the
     * individual accessors; a consumer with both a regular base and an
     * {@link UpgradeTestBase} subclass overrides this method so the two share
     * one value.
     *
     * @return the configuration
     */
    protected Configuration configuration() {
        return new Configuration(
                databaseName(), ownerUser(), ownerPassword(), testUser(), testPassword(),
                defaultPostgresImage(), masterResource(), databaseChangeLogTableName(),
                databaseChangeLogLockTableName(), fixturesResource(), null);
    }

    /**
     * The PostgreSQL image to run, matching the one used for jOOQ codegen: the
     * {@value #POSTGRES_IMAGE_PROPERTY} system property when set (surefire and
     * the CI matrix set it), otherwise the configured image.
     */
    protected String postgresImage() {
        return System.getProperty(POSTGRES_IMAGE_PROPERTY, configuration().postgresImage());
    }

    /**
     * The default PostgreSQL image when the {@code postgres.image} system
     * property is not set. Override to point at the project's custom image,
     * which must bake in the roles created by the init script.
     *
     * @return the default image reference
     */
    protected String defaultPostgresImage() {
        return DEFAULT_POSTGRES_IMAGE;
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
     * Installs extensions and grants in a database before the changelog runs
     * (for example {@code plpgsql_check} or {@code pg_background}). The default
     * runs the configured {@link Configuration.ExtensionInstaller}, so a project
     * that shares one configuration also shares its extension setup; a subclass
     * may override this method instead.
     *
     * @param templateDatabase the database name
     * @throws SQLException if the bootstrap fails
     */
    protected void installExtensions(String templateDatabase) throws SQLException {
        Configuration.ExtensionInstaller installer = configuration().extensionInstaller();
        if (installer == null) {
            return;
        }
        try (Connection admin = adminConnection(templateDatabase)) {
            installer.install(admin);
        }
    }

    /**
     * Starts the shared container and prepares the migrated template database
     * once per JVM.
     */
    @BeforeAll
    void startContainerAndCreateTestDatabase() throws Exception {
        Configuration configuration = configuration();
        synchronized (PostgresTestBase.class) {
            if (postgres == null) {
                postgres = new PostgreSQLContainer(DockerImageName.parse(postgresImage())
                        .asCompatibleSubstituteFor("postgres"))
                        .withDatabaseName(configuration.databaseName());
                try {
                    postgres.start();
                    prepareTemplateDatabase(configuration);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to start the PostgreSQL test container", e);
                }
            }
        }
        createTestDatabase(configuration);
    }

    private void prepareTemplateDatabase(Configuration configuration) {
        String template = templateDatabaseName(configuration);
        String database = configuration.databaseName();
        try {
            // Tolerate a template left behind by an interrupted earlier run in
            // the same container, so setup is repeatable.
            execute(adminConnection(database), "DROP DATABASE IF EXISTS " + template + " WITH (FORCE)");
            execute(adminConnection(database), "CREATE DATABASE " + template);
            // The template is fresh, so grant the owner role the privileges it
            // needs to run Liquibase (as the init script does for the main
            // database): CREATE on the database and on its public schema. The
            // test role may create tables it owns in public too.
            execute(adminConnection(database),
                    "GRANT CREATE ON DATABASE " + template + " TO " + configuration.ownerUser());
            try (Connection admin = adminConnection(template);
                 Statement statement = admin.createStatement()) {
                statement.execute("GRANT CREATE ON SCHEMA public TO " + configuration.ownerUser());
                statement.execute("GRANT CREATE ON SCHEMA public TO " + configuration.testUser());
            }
            installExtensions(template);
            try (Connection owner = connection(template, configuration.ownerUser(),
                    configuration.ownerPassword())) {
                try (Statement statement = owner.createStatement()) {
                    statement.execute("CREATE SCHEMA IF NOT EXISTS " + LIQUIBASE_SCHEMA);
                }
                Database liquibaseDatabase = DatabaseFactory.getInstance()
                        .findCorrectDatabaseImplementation(new JdbcConnection(owner));
                liquibaseDatabase.setLiquibaseSchemaName(LIQUIBASE_SCHEMA);
                liquibaseDatabase.setDatabaseChangeLogTableName(configuration.databaseChangeLogTable());
                liquibaseDatabase.setDatabaseChangeLogLockTableName(configuration.databaseChangeLogLockTable());
                new Liquibase(configuration.masterResource(), new ClassLoaderResourceAccessor(), liquibaseDatabase)
                        .update();
                String fixtures = configuration.fixturesResource();
                if (fixtures != null) {
                    new Liquibase(fixtures, new ClassLoaderResourceAccessor(), liquibaseDatabase).update();
                }
            }
            // Mark as a real template and forbid connections, so a leaked session
            // cannot make CREATE DATABASE ... TEMPLATE fail.
            execute(adminConnection(database),
                    "ALTER DATABASE " + template + " WITH IS_TEMPLATE TRUE ALLOW_CONNECTIONS FALSE");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to prepare template database", e);
        }
    }

    private void createTestDatabase(Configuration configuration) throws SQLException {
        String template = templateDatabaseName(configuration);
        // Bounded and collision-resistant: a hash suffix of the fully qualified
        // name keeps the identifier under PostgreSQL's 63-byte limit and wide
        // enough that two test classes do not collide. Quoted so an unusual
        // class name cannot produce an invalid identifier.
        String suffix = Integer.toUnsignedString(getClass().getName().hashCode(), 16);
        testDatabaseName = "test_" + suffix;
        try (Connection admin = adminConnection(configuration.databaseName());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE \"" + testDatabaseName + "\" TEMPLATE " + template);
        } catch (SQLException e) {
            dropDatabaseQuietly();
            throw e;
        }
        try {
            connection = connection(testDatabaseName, configuration.testUser(), configuration.testPassword());
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
        Configuration configuration = configuration();
        return connection(testDatabaseName, configuration.testUser(), configuration.testPassword());
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
        Configuration configuration = configuration();
        return connection(testDatabaseName, configuration.ownerUser(), configuration.ownerPassword());
    }

    /**
     * Opens a connection to an arbitrary database in the shared container as
     * the test role, for a test that builds its own database (for example the
     * previous-release upgrade test). The caller is responsible for closing it.
     *
     * @param database the database to connect to
     * @return a new connection as the test role
     * @throws SQLException if the connection cannot be opened
     */
    protected Connection openTestConnection(String database) throws SQLException {
        Configuration configuration = configuration();
        return connection(database, configuration.testUser(), configuration.testPassword());
    }

    /**
     * Opens a connection to an arbitrary database in the shared container as
     * the schema owner, for a test that builds its own database. The caller is
     * responsible for closing it.
     *
     * @param database the database to connect to
     * @return a new owner connection
     * @throws SQLException if the connection cannot be opened
     */
    protected Connection openOwnerConnection(String database) throws SQLException {
        Configuration configuration = configuration();
        return connection(database, configuration.ownerUser(), configuration.ownerPassword());
    }

    /**
     * Creates an empty database in the shared container, as the superuser. The
     * caller grants the owner role the privileges it needs and drops the
     * database afterwards.
     *
     * @param database the database to create
     * @throws SQLException if the database cannot be created
     */
    protected void createDatabase(String database) throws SQLException {
        execute(adminConnection(configuration().databaseName()), "CREATE DATABASE \"" + database + "\"");
    }

    /**
     * Drops a database in the shared container, as the superuser, tolerating an
     * already-gone database.
     *
     * @param database the database to drop
     * @throws SQLException if the database cannot be dropped
     */
    protected void dropDatabase(String database) throws SQLException {
        execute(adminConnection(configuration().databaseName()),
                "DROP DATABASE IF EXISTS \"" + database + "\" WITH (FORCE)");
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
     * Returns the {@code pg_locks.mode} spelling of a lock mode written the way
     * {@code LOCK TABLE} expects it, for example {@code ACCESS SHARE} to
     * {@code AccessShareLock}.
     *
     * @param mode the lock mode as written in SQL
     * @return the lock mode as reported by {@code pg_locks}
     */
    private static String lockModeName(String mode) {
        StringBuilder name = new StringBuilder();
        for (String word : mode.trim().split("\\s+")) {
            name.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase());
        }
        return name.append("Lock").toString();
    }

    /**
     * Holds an ACCESS SHARE lock on a table on a second connection, which
     * conflicts with the ACCESS EXCLUSIVE lock an {@code ALTER TABLE} needs.
     * The caller owns the connection and must close it to release the lock.
     *
     * @param connection a connection to this test class's private database
     * @param table      the table to lock
     * @return the backend PID holding the lock
     * @throws SQLException if the lock cannot be taken
     */
    protected int holdAccessShareLock(Connection connection, String table) throws SQLException {
        return holdTableLock(connection, table, "ACCESS SHARE");
    }

    /**
     * Holds a table lock in the given mode on a second connection, so a test
     * can make a routine wait for it. The caller owns the connection and must
     * close it to release the lock.
     *
     * @param connection a connection to this test class's private database
     * @param table      the table to lock
     * @param mode       the PostgreSQL lock mode, for example {@code ACCESS SHARE}
     * @return the backend PID holding the lock, so a wait can be scoped to this
     * exact session rather than any session holding the same lock
     * @throws SQLException if the lock cannot be taken
     */
    protected int holdTableLock(Connection connection, String table, String mode) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("LOCK TABLE " + table + " IN " + mode + " MODE");
        }
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return result.getInt(1);
        }
    }

    /**
     * Waits until the given competing session holds an ACCESS SHARE lock on the
     * table, so a test can be sure its own blocker is in place before calling a
     * routine that must wait for it.
     *
     * @param table     the table to check
     * @param holderPid the backend PID expected to hold the lock
     * @throws InterruptedException if waiting for the lock is interrupted
     */
    protected void awaitAccessShareLockHeld(String table, int holderPid) throws InterruptedException {
        awaitTableLockHeld(table, "AccessShareLock", holderPid);
    }

    /**
     * Waits until the given competing session holds a lock in the given mode on
     * the table, so a test can be sure its own blocker is in place before
     * calling a routine that must wait for it. Scoping the lookup to the holder
     * PID prevents an unrelated session's lock from satisfying the wait.
     *
     * @param table     the table to check
     * @param mode      the PostgreSQL lock mode as reported by {@code pg_locks},
     *                  for example {@code AccessExclusiveLock}
     * @param holderPid the backend PID expected to hold the lock
     * @throws InterruptedException if waiting for the lock is interrupted
     */
    protected void awaitTableLockHeld(String table, String mode, int holderPid) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Object held = dsl.fetchValue(
                    """
                            SELECT EXISTS (
                                SELECT 1
                                FROM pg_locks l
                                JOIN pg_class c ON c.oid = l.relation
                                JOIN pg_namespace n ON n.oid = c.relnamespace
                                WHERE c.relname = ?
                                    AND n.nspname = current_schema()
                                    AND l.mode = ?
                                    AND l.granted
                                    AND l.pid = ?
                            )
                            """,
                    table, mode, holderPid);
            if (Boolean.TRUE.equals(held)) {
                return;
            }
            Thread.sleep(50);
        }
        fail("the competing session did not acquire its " + mode + " lock on " + table);
    }

    /**
     * Rolls back a connection after a delay on a dedicated daemon thread, so a
     * test can release a held lock while the test thread is blocked in a call.
     * The returned future completes once the rollback has run, or fails with
     * the rollback error.
     *
     * @param connection  the connection to roll back
     * @param delayMillis how long to hold before rolling back
     * @return a future that completes when the rollback has run
     */
    protected CompletableFuture<Void> rollbackAfter(Connection connection, long delayMillis) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delayMillis);
                connection.rollback();
                completed.complete(null);
            } catch (Throwable e) {
                completed.completeExceptionally(e);
            }
        }, "lock-holder-rollback");
        thread.setDaemon(true);
        thread.start();
        return completed;
    }

    /**
     * Runs {@code call} while another session holds an ACCESS SHARE lock on
     * {@code table}, and asserts it gives up with SQLSTATE {@code 55P03} within
     * {@code maxMillis}. The settings passed to the call must make the budget
     * short, so the bound distinguishes giving up from retrying for seconds.
     *
     * @param table     the locked table
     * @param maxMillis the maximum expected time to give up
     * @param call      the call expected to fail
     * @throws SQLException         if the competing connection cannot be opened
     * @throws InterruptedException if waiting for the lock is interrupted
     */
    protected void assertGivesUpWhileTableLocked(String table, long maxMillis, Executable call)
            throws SQLException, InterruptedException {
        assertGivesUpWhileTableLocked(table, "ACCESS SHARE", maxMillis, call);
    }

    /**
     * Runs {@code call} while another session holds a lock in the given mode on
     * {@code table}, and asserts it gives up with SQLSTATE {@code 55P03} within
     * {@code maxMillis}. The settings passed to the call must make the budget
     * short, so the bound distinguishes giving up from retrying for seconds.
     * Use {@code ACCESS EXCLUSIVE} for routines whose final statement takes only
     * SHARE UPDATE EXCLUSIVE, which an ACCESS SHARE lock does not block.
     *
     * @param table     the locked table
     * @param mode      the PostgreSQL lock mode, for example {@code ACCESS SHARE}
     * @param maxMillis the maximum expected time to give up
     * @param call      the call expected to fail
     * @throws SQLException         if the competing connection cannot be opened
     * @throws InterruptedException if waiting for the lock is interrupted
     */
    protected void assertGivesUpWhileTableLocked(String table, String mode, long maxMillis, Executable call)
            throws SQLException, InterruptedException {
        try (Connection other = openTestConnection()) {
            int holderPid = holdTableLock(other, table, mode);
            awaitTableLockHeld(table, lockModeName(mode), holderPid);

            long startedAt = System.nanoTime();
            assertSqlState("55P03", call);
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

            assertTrue(elapsedMillis < maxMillis,
                    () -> "the call did not give up within " + maxMillis + " ms; took " + elapsedMillis + " ms");
        }
    }

    /**
     * Runs {@code call} while another session holds an ACCESS SHARE lock on
     * {@code table}, releasing the lock after {@code releaseDelayMillis}.
     *
     * @param table              the locked table
     * @param releaseDelayMillis how long to hold the lock before releasing it
     * @param call               the call to run
     */
    protected void runWhileTableLocked(String table, long releaseDelayMillis, Runnable call) {
        try (Connection other = openTestConnection()) {
            int holderPid = holdAccessShareLock(other, table);
            awaitAccessShareLockHeld(table, holderPid);

            CompletableFuture<Void> release = rollbackAfter(other, releaseDelayMillis);
            Throwable failure = null;
            try {
                call.run();
            } catch (Throwable e) {
                failure = e;
                throw e;
            } finally {
                // Do not let a rollback failure hide the primary failure.
                try {
                    release.join();
                } catch (CompletionException e) {
                    if (failure != null) {
                        failure.addSuppressed(e);
                    } else {
                        throw e;
                    }
                }
            }
        } catch (SQLException | InterruptedException e) {
            throw new IllegalStateException("failed while running under a table lock", e);
        }
    }

    /**
     * Creates a table owned by the test role, so SECURITY INVOKER routines that
     * require ownership can operate on it.
     *
     * @param table   the table name
     * @param columns the column definitions, without the surrounding
     *                parentheses
     */
    protected void createTestTable(String table, String columns) {
        dsl.execute("CREATE TABLE " + table + " (" + columns + ")");
    }

    /**
     * Drops a table created by {@link #createTestTable}, if it exists.
     *
     * @param table the table name
     */
    protected void dropTestTable(String table) {
        dsl.execute("DROP TABLE IF EXISTS " + table);
    }

    /**
     * Returns a column's {@code pg_attribute} row, failing when the column does
     * not exist. Used for attributes that {@code information_schema.columns}
     * does not expose ({@code attidentity}, {@code attstorage},
     * {@code attcompression}, {@code attgenerated}).
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     * @return the column's pg_attribute row
     */
    private Record pgAttribute(String schema, String table, String column) {
        Record record = dsl.fetchOne(
                """
                        SELECT a.attidentity::text AS attidentity,
                               a.attstorage::text AS attstorage,
                               a.attcompression::text AS attcompression,
                               (a.attgenerated <> '') AS attgenerated
                        FROM pg_attribute a
                        JOIN pg_class c ON c.oid = a.attrelid
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = ? AND c.relname = ? AND a.attname = ?
                        """,
                schema, table, column);
        assertNotNull(record, () -> "column not found: " + schema + "." + table + "." + column);
        return record;
    }

    /**
     * Returns a column's identity code: {@code 'a'} for ALWAYS, {@code 'd'} for
     * BY DEFAULT, {@code ''} for no identity.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     * @return the identity code
     */
    protected String columnIdentity(String schema, String table, String column) {
        return pgAttribute(schema, table, column).get("attidentity", String.class);
    }

    /**
     * Returns a column's storage mode name ({@code plain}, {@code external},
     * {@code main} or {@code extended}).
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     * @return the storage mode name
     */
    protected String columnStorage(String schema, String table, String column) {
        // attstorage is a single code: p=plain, e=external, m=main, x=extended.
        String code = pgAttribute(schema, table, column).get("attstorage", String.class);
        return switch (code) {
            case "p" -> "plain";
            case "e" -> "external";
            case "m" -> "main";
            case "x" -> "extended";
            default -> code;
        };
    }

    /**
     * Returns a column's compression method name ({@code pglz}, {@code lz4} or
     * {@code default}).
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     * @return the compression method name
     */
    protected String columnCompression(String schema, String table, String column) {
        // attcompression is a single code: p=pglz, l=lz4, empty=default.
        String code = pgAttribute(schema, table, column).get("attcompression", String.class);
        return switch (code) {
            case "p" -> "pglz";
            case "l" -> "lz4";
            case "" -> "default";
            default -> code;
        };
    }

    /**
     * Returns whether a column is generated.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     * @return {@code true} when the column is generated
     */
    protected boolean isGenerated(String schema, String table, String column) {
        return Boolean.TRUE.equals(pgAttribute(schema, table, column).get("attgenerated", Boolean.class));
    }

    /**
     * Returns whether the named constraint on a table is validated, failing when
     * no such constraint exists on that table. The lookup is scoped to the
     * {@code (schema, table, name)} triple because constraint names are only
     * unique per table.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param name   the constraint name
     * @return {@code true} when the constraint is validated
     */
    protected boolean constraintValidated(String schema, String table, String name) {
        Record record = dsl.fetchOne(
                """
                        SELECT convalidated
                        FROM pg_constraint
                        WHERE conname = ?
                            AND conrelid = (SELECT oid FROM pg_class WHERE relname = ?
                                              AND relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?))
                        """,
                name, table, schema);
        assertNotNull(record, () -> "constraint not found: " + schema + "." + table + "." + name);
        return Boolean.TRUE.equals(record.get("convalidated", Boolean.class));
    }

    /**
     * Asserts that a column is nullable.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     */
    protected void assertNullable(String schema, String table, String column) {
        assertEquals("YES", columnAttribute(schema, table, column, "is_nullable"),
                () -> column + " should be nullable");
    }

    /**
     * Asserts that a column is not nullable.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     */
    protected void assertNotNullable(String schema, String table, String column) {
        assertEquals("NO", columnAttribute(schema, table, column, "is_nullable"),
                () -> column + " should not be nullable");
    }

    /**
     * Asserts that a column has a default expression containing the given text.
     *
     * @param schema            the table schema
     * @param table             the table name
     * @param column            the column name
     * @param expectedSubstring a substring the default expression must contain
     */
    protected void assertColumnDefault(String schema, String table, String column, String expectedSubstring) {
        String defaultExpression = columnAttribute(schema, table, column, "column_default");
        assertNotNull(defaultExpression, () -> column + " should have a default");
        assertTrue(defaultExpression.contains(expectedSubstring),
                () -> "default " + defaultExpression + " should contain " + expectedSubstring);
    }

    /**
     * Asserts that a column has no default expression.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param column the column name
     */
    protected void assertNoColumnDefault(String schema, String table, String column) {
        assertNull(columnAttribute(schema, table, column, "column_default"),
                () -> column + " should have no default");
    }

    /**
     * Asserts that the named constraint on a table is validated.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param name   the constraint name
     */
    protected void assertValidated(String schema, String table, String name) {
        assertTrue(constraintValidated(schema, table, name), () -> name + " should be validated");
    }

    /**
     * Asserts that the named constraint on a table is not validated.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param name   the constraint name
     */
    protected void assertNotValidated(String schema, String table, String name) {
        assertFalse(constraintValidated(schema, table, name), () -> name + " should not be validated");
    }

    /**
     * Returns whether the named constraint exists on a table.
     *
     * @param schema the table schema
     * @param table  the table name
     * @param name   the constraint name
     * @return {@code true} when the constraint exists on that table
     */
    protected boolean constraintExists(String schema, String table, String name) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                """
                        SELECT EXISTS (
                            SELECT 1 FROM pg_constraint
                            WHERE conname = ?
                                AND conrelid = (SELECT oid FROM pg_class WHERE relname = ?
                                                  AND relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?))
                        )
                        """,
                name, table, schema));
    }

    /**
     * Returns whether any constraint with the given name exists in a schema.
     * Constraint names are unique per table, not per schema, so this matches a
     * name on any table in the schema.
     *
     * @param schema the schema name
     * @param name   the constraint name
     * @return {@code true} when a constraint with that name exists in the schema
     */
    protected boolean constraintExists(String schema, String name) {
        return Boolean.TRUE.equals(dsl.fetchValue(
                """
                        SELECT EXISTS (
                            SELECT 1 FROM pg_constraint
                            WHERE conname = ?
                                AND connamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?)
                        )
                        """,
                name, schema));
    }

    /**
     * Returns the {@code contype} of the named constraint in a schema
     * ({@code p} primary key, {@code u} unique, {@code f} foreign key,
     * {@code c} check).
     *
     * @param schema the schema name
     * @param name   the constraint name
     * @return the constraint type code
     */
    protected String constraintType(String schema, String name) {
        Record record = dsl.fetchOne(
                """
                        SELECT contype::text FROM pg_constraint
                        WHERE conname = ? AND connamespace = (SELECT oid FROM pg_namespace WHERE nspname = ?)
                        """,
                name, schema);
        assertNotNull(record, () -> "constraint not found: " + schema + "." + name);
        return record.get(0, String.class);
    }

    /**
     * Drops this class's private database, ignoring an already-gone database, so
     * a failed setup does not leak it.
     */
    private void dropDatabaseQuietly() {
        if (testDatabaseName == null) {
            return;
        }
        try {
            dropDatabase(testDatabaseName);
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
