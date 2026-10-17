package io.github.eyupmiduck.changelogvalidator.testing;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * Base class for the incremental-upgrade test: apply the most recent published
 * release's changelog and then the current changelog to a scratch database, in
 * the shared PostgreSQL container.
 *
 * <p>It extends {@link PostgresTestBase} so it reuses the one container, but it
 * does <strong>not</strong> use the migrated template database: a template
 * already at the current schema has nothing to upgrade, and a
 * class-order-dependent template would make the test vacuous. Instead it creates
 * its own database, grants the owner role the privileges the changelog needs,
 * and runs {@link LiquibaseUpgrade} as the owner against it.
 *
 * <p>A subclass supplies {@link #releaseCoordinates()}; the roles, image,
 * tracking table names and changelog resource come from the inherited accessors.
 *
 * <p>The test skips (rather than fails) when there is no published release below
 * the current version, so a project's first release does not break its build. It
 * can also be disabled with {@code -Dskip.upgrade-test=true} for an offline run.
 */
public abstract class UpgradeTestBase extends PostgresTestBase {

    /**
     * The system property that disables the upgrade test.
     */
    public static final String SKIP_PROPERTY = "skip.upgrade-test";

    private String upgradeDatabase;
    private Connection upgradeConnection;
    private DSLContext upgradeDsl;
    private LiquibaseUpgrade.Report report;

    /**
     * The coordinates of the project's own artifact; the current version must be
     * the version this build produces.
     *
     * @return the artifact coordinates
     */
    protected abstract PublishedRelease.Coordinates releaseCoordinates();

    /**
     * Resolves the previous release jar, or empty when there is none. Override
     * to supply the jar another way (for example a fixture in the library's own
     * tests).
     *
     * @return the previous release jar, or empty
     */
    protected Optional<Path> previousReleaseJar() {
        return PublishedRelease.resolve(releaseCoordinates(), cacheDirectory())
                .map(PublishedRelease.Resolved::jar);
    }

    /**
     * The directory the release jar is cached in.
     *
     * @return the cache directory
     */
    protected Path cacheDirectory() {
        return PublishedRelease.DEFAULT_CACHE_DIRECTORY;
    }

    /**
     * The Liquibase tracking settings the owner role uses.
     *
     * @return the tracking settings
     */
    protected LiquibaseUpgrade.TrackingSettings trackingSettings() {
        return new LiquibaseUpgrade.TrackingSettings(
                LIQUIBASE_SCHEMA, databaseChangeLogTableName(), databaseChangeLogLockTableName());
    }

    /**
     * Resolves the previous release, creates a scratch database, applies the
     * previous then the current changelog as the owner, and opens the upgraded
     * database for assertions.
     */
    @BeforeAll
    void upgradeFromThePreviousRelease() throws Exception {
        Assumptions.assumeFalse(Boolean.parseBoolean(System.getProperty(SKIP_PROPERTY, "false")),
                "skipped by -D" + SKIP_PROPERTY + "=true");
        Optional<Path> previousJar = previousReleaseJar();
        Assumptions.assumeTrue(previousJar.isPresent(),
                () -> "no published release below " + releaseCoordinates().currentVersion()
                        + "; nothing to upgrade from");

        upgradeDatabase = "upgrade_" + Integer.toUnsignedString(getClass().getName().hashCode(), 16);
        createDatabase(upgradeDatabase);
        prepareUpgradeDatabase();
        try (Connection owner = openOwnerConnection(upgradeDatabase)) {
            report = LiquibaseUpgrade.apply(owner, trackingSettings(), previousJar.get(), masterResource());
        }
        upgradeConnection = openTestConnection(upgradeDatabase);
        upgradeDsl = DSL.using(upgradeConnection, SQLDialect.POSTGRES);
    }

    /**
     * Drops the scratch database and closes the upgraded connection.
     */
    @AfterAll
    void dropUpgradeDatabase() throws Exception {
        if (upgradeConnection != null) {
            upgradeConnection.close();
        }
        if (upgradeDatabase != null) {
            dropDatabase(upgradeDatabase);
        }
    }

    private void prepareUpgradeDatabase() throws SQLException {
        // The owner creates the changelog's schemas and objects, so grant it
        // what a real deployment would have (the init script does this for the
        // project's main database only).
        try (Connection admin = openAdminConnection(databaseName());
             Statement statement = admin.createStatement()) {
            statement.execute("GRANT CREATE ON DATABASE \"" + upgradeDatabase + "\" TO " + ownerUser());
        }
        try (Connection admin = openAdminConnection(upgradeDatabase);
             Statement statement = admin.createStatement()) {
            statement.execute("GRANT CREATE ON SCHEMA public TO " + ownerUser());
            statement.execute("GRANT CREATE ON SCHEMA public TO " + testUser());
            statement.execute("CREATE SCHEMA IF NOT EXISTS "
                    + trackingSettings().liquibaseSchema() + " AUTHORIZATION " + ownerUser());
        }
    }

    /**
     * The changesets the previous release and the current changelog applied.
     *
     * @return the upgrade report
     */
    protected LiquibaseUpgrade.Report report() {
        return report;
    }

    /**
     * A jOOQ context on the upgraded database, as the test role.
     *
     * @return the upgraded database context
     */
    protected DSLContext upgradeDsl() {
        return upgradeDsl;
    }

    /**
     * The upgraded database's name.
     *
     * @return the database name
     */
    protected String upgradeDatabaseName() {
        return upgradeDatabase;
    }
}
