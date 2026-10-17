package io.github.eyupmiduck.changelogvalidator.testing;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Map;
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
 * <p>It also builds a second database from the current changelog alone and
 * compares the two schemas with {@link CatalogSnapshot}, so an upgrade that runs
 * but leaves the schema different from a fresh build fails. The comparison is
 * over {@link #managedSchemas()}, can be turned off with
 * {@link #catalogComparisonEnabled()}, and writes both captures to
 * {@link #catalogDumpDirectory()} for review.
 *
 * <p>A subclass supplies {@link #releaseCoordinates()} and
 * {@link #managedSchemas()}; the roles, image, tracking table names and changelog
 * resource come from the inherited accessors.
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
    private String freshDatabase;
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
     * The schemas the changelog manages, compared between the upgraded database
     * and a from-scratch build.
     *
     * @return the managed schemas
     */
    protected abstract Collection<String> managedSchemas();

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
     * Whether to compare the upgraded schema with a from-scratch build.
     *
     * @return {@code true} to compare (the default)
     */
    protected boolean catalogComparisonEnabled() {
        return true;
    }

    /**
     * The directory the two captures are written to, so a difference can be
     * reviewed with a directory diff and uploaded as a build artifact.
     *
     * @return the dump directory
     */
    protected Path catalogDumpDirectory() {
        return CatalogSnapshot.DEFAULT_DUMP_DIRECTORY;
    }

    /**
     * Resolves the previous release, creates a scratch database, applies the
     * previous then the current changelog as the owner, builds a from-scratch
     * database and compares the two, and opens the upgraded database for
     * assertions.
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
        prepareDatabase(upgradeDatabase);
        try (Connection owner = openOwnerConnection(upgradeDatabase)) {
            report = LiquibaseUpgrade.apply(owner, trackingSettings(), previousJar.get(), masterResource());
        }
        upgradeConnection = openTestConnection(upgradeDatabase);
        upgradeDsl = DSL.using(upgradeConnection, SQLDialect.POSTGRES);

        if (catalogComparisonEnabled()) {
            compareCatalogWithFromScratchBuild();
        }
    }

    /**
     * Builds a database from the current changelog alone and asserts its schema
     * matches the upgraded one.
     */
    private void compareCatalogWithFromScratchBuild() throws SQLException, IOException {
        freshDatabase = "fresh_" + Integer.toUnsignedString(getClass().getName().hashCode(), 16);
        createDatabase(freshDatabase);
        prepareDatabase(freshDatabase);
        try (Connection owner = openOwnerConnection(freshDatabase)) {
            LiquibaseUpgrade.applyCurrent(owner, trackingSettings(), masterResource());
        }

        Map<String, String> fresh;
        Map<String, String> upgrade;
        try (Connection freshConnection = openOwnerConnection(freshDatabase);
             Connection upgradeOwner = openOwnerConnection(upgradeDatabase)) {
            fresh = CatalogSnapshot.capture(freshConnection, managedSchemas());
            upgrade = CatalogSnapshot.capture(upgradeOwner, managedSchemas());
        }
        // Write both trees so a failure can be reviewed with `diff -r` and
        // uploaded as a build artifact.
        CatalogSnapshot.dump(fresh, catalogDumpDirectory().resolve("fresh"));
        CatalogSnapshot.dump(upgrade, catalogDumpDirectory().resolve("upgrade"));
        CatalogSnapshot.assertEquivalent(CatalogSnapshot.compare(fresh, upgrade));
    }

    /**
     * Drops the scratch databases and closes the upgraded connection.
     */
    @AfterAll
    void dropUpgradeDatabase() throws Exception {
        if (upgradeConnection != null) {
            upgradeConnection.close();
        }
        if (upgradeDatabase != null) {
            dropDatabase(upgradeDatabase);
        }
        if (freshDatabase != null) {
            dropDatabase(freshDatabase);
        }
    }

    private void prepareDatabase(String database) throws SQLException {
        // The scratch database is fresh, so the consumer's extension hook (for
        // example plpgsql_check or pg_background, which the changelog's routines
        // reference) must run here too, not only for the template.
        installExtensions(database);
        // The owner creates the changelog's schemas and objects, so grant it
        // what a real deployment would have (the init script does this for the
        // project's main database only).
        try (Connection admin = openAdminConnection(databaseName());
             Statement statement = admin.createStatement()) {
            statement.execute("GRANT CREATE ON DATABASE \"" + database + "\" TO " + ownerUser());
        }
        try (Connection admin = openAdminConnection(database);
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
