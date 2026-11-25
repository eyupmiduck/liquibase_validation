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
import java.util.Collection;
import java.util.List;
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
 * <p>A subclass returns its one {@link ProjectProfile} from
 * {@link #projectProfile()} (it supplies the roles, image, tracking table names,
 * changelog resource and managed schemas) and supplies
 * {@link #releaseCoordinates()}, so the regular test base and this class read
 * the same values.
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
     * Closes {@code connection} and drops every non-null database, collecting
     * failures instead of stopping at the first one. Package-private so the
     * cleanup contract can be tested without a real container.
     *
     * @param connection the connection to close, or {@code null}
     * @param databases  the database names to drop, possibly with nulls
     * @param dropper    the drop operation
     * @return the first failure, with later failures suppressed, or {@code null}
     */
    static Exception closeAndDropAll(Connection connection, List<String> databases, DatabaseDropper dropper) {
        Exception failure = null;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                failure = e;
            }
        }
        for (String database : databases) {
            if (database == null) {
                continue;
            }
            try {
                dropper.drop(database);
            } catch (Exception e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        return failure;
    }

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

        upgradeDatabase = "upgrade_" + databaseNameSuffix(getClass());
        createScratchDatabase(upgradeDatabase);
        prepareDatabase(upgradeDatabase);
        try (Connection owner = openOwnerConnection(upgradeDatabase)) {
            report = LiquibaseUpgrade.apply(owner, trackingSettings(), previousJar.get(),
                    configuration().masterResource());
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
        Collection<String> schemas = managedSchemas();
        if (schemas.isEmpty()) {
            throw new IllegalStateException(
                    "managedSchemas() must not be empty when catalog comparison is enabled");
        }
        freshDatabase = "fresh_" + databaseNameSuffix(getClass());
        createScratchDatabase(freshDatabase);
        prepareDatabase(freshDatabase);
        try (Connection owner = openOwnerConnection(freshDatabase)) {
            LiquibaseUpgrade.applyCurrent(owner, trackingSettings(), configuration().masterResource());
        }

        Map<String, String> fresh;
        Map<String, String> upgrade;
        try (Connection freshConnection = openOwnerConnection(freshDatabase);
             Connection upgradeOwner = openOwnerConnection(upgradeDatabase)) {
            fresh = CatalogSnapshot.capture(freshConnection, schemas);
            upgrade = CatalogSnapshot.capture(upgradeOwner, schemas);
        }
        // Write both trees so a failure can be reviewed with `diff -r` and
        // uploaded as a build artifact.
        CatalogSnapshot.dump(fresh, catalogDumpDirectory().resolve("fresh"));
        CatalogSnapshot.dump(upgrade, catalogDumpDirectory().resolve("upgrade"));
        CatalogSnapshot.assertEquivalent(CatalogSnapshot.compare(fresh, upgrade));
    }

    /**
     * Closes the upgraded connection and drops both scratch databases, even when
     * an earlier step fails. The first failure is rethrown; later cleanup
     * failures are attached as suppressed so none is silently lost.
     */
    @AfterAll
    void dropUpgradeDatabase() throws Exception {
        Exception failure = closeAndDropAll(upgradeConnection,
                java.util.Arrays.asList(upgradeDatabase, freshDatabase), this::dropDatabase);
        if (failure != null) {
            throw failure;
        }
    }

    private void prepareDatabase(String database) throws SQLException {
        prepareScratchDatabase(database, trackingSettings().liquibaseSchema());
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

    /**
     * A database drop operation that may fail.
     */
    @FunctionalInterface
    interface DatabaseDropper {

        /**
         * Drops a database.
         *
         * @param database the database name
         * @throws Exception if the drop fails
         */
        void drop(String database) throws Exception;
    }
}
