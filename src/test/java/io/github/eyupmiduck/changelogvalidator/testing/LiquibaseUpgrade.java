package io.github.eyupmiduck.changelogvalidator.testing;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;

/**
 * Applies a previous release's Liquibase changelog and then the current
 * changelog to the same database, exercising the incremental upgrade a consumer
 * performs.
 *
 * <p>The previous release is read from its published jar through a
 * {@link URLClassLoader} with a {@code null} parent, so the current changelog on
 * the test classpath cannot shadow it. Both runs address the changelog at
 * {@link ChangelogTestSupport#MASTER_RESOURCE}, which makes Liquibase record the
 * same {@code FILENAME} for a shared changeset; the second run therefore applies
 * only the delta, and a changeset that was released and then edited fails
 * Liquibase's checksum validation.
 *
 * <p>The returned {@link Report} lists the changesets each run applied (the
 * unrun set just before the update), so a caller can assert that the release
 * contributed changes and that the current run applied only the delta.
 */
public final class LiquibaseUpgrade {

    private LiquibaseUpgrade() {
    }

    /**
     * Applies the previous release jar's changelog, then the current changelog
     * on the test classpath.
     *
     * @param connection     an owner connection to a database with no applied
     *                       changelog
     * @param tracking       the Liquibase tracking settings
     * @param previousJar    the published release jar
     * @param masterResource the changelog master resource, relative to the
     *                       classpath (and to the jar)
     * @return the applied changesets per run
     */
    public static Report apply(Connection connection, TrackingSettings tracking, Path previousJar,
                               String masterResource) {
        return apply(connection, tracking, previousJar, new ClassLoaderResourceAccessor(), masterResource);
    }

    /**
     * Applies a previous release jar's changelog, then a caller-supplied current
     * changelog.
     *
     * @param connection     an owner connection to a database with no applied
     *                       changelog
     * @param tracking       the Liquibase tracking settings
     * @param previousJar    the published release jar
     * @param current        the current changelog accessor
     * @param masterResource the changelog master resource
     * @return the applied changesets per run
     */
    static Report apply(Connection connection, TrackingSettings tracking, Path previousJar,
                        ResourceAccessor current, String masterResource) {
        Objects.requireNonNull(previousJar, "previousJar");
        try (URLClassLoader loader =
                     new URLClassLoader(new URL[]{previousJar.toUri().toURL()}, null)) {
            return apply(connection, tracking, new ClassLoaderResourceAccessor(loader), current, masterResource);
        } catch (IOException e) {
            throw new IllegalStateException("could not open " + previousJar, e);
        }
    }

    /**
     * Applies a previous changelog and then a current changelog.
     *
     * @param connection     an owner connection to a database with no applied
     *                       changelog
     * @param tracking       the Liquibase tracking settings
     * @param previous       the previous release's changelog accessor
     * @param current        the current changelog accessor
     * @param masterResource the changelog master resource
     * @return the applied changesets per run
     */
    static Report apply(Connection connection, TrackingSettings tracking, ResourceAccessor previous,
                        ResourceAccessor current, String masterResource) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tracking, "tracking");
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(masterResource, "masterResource");
        try {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setLiquibaseSchemaName(tracking.liquibaseSchema());
            database.setDatabaseChangeLogTableName(tracking.changeLogTable());
            database.setDatabaseChangeLogLockTableName(tracking.changeLogLockTable());
            List<String> previousApplied = run(database, previous, masterResource);
            List<String> currentApplied = run(database, current, masterResource);
            return new Report(previousApplied, currentApplied);
        } catch (LiquibaseException e) {
            throw new IllegalStateException("the Liquibase upgrade failed: " + e.getMessage(), e);
        }
    }

    private static List<String> run(Database database, ResourceAccessor accessor, String masterResource)
            throws LiquibaseException {
        Liquibase liquibase = new Liquibase(masterResource, accessor, database);
        List<String> unrun = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression()).stream()
                .map(changeSet -> changeSet.getId())
                .toList();
        liquibase.update(new Contexts());
        return unrun;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * Where Liquibase keeps its tracking tables.
     *
     * @param liquibaseSchema    the schema holding the tracking tables
     * @param changeLogTable     the changelog tracking table name
     * @param changeLogLockTable the changelog lock table name
     */
    public record TrackingSettings(String liquibaseSchema, String changeLogTable,
                                   String changeLogLockTable) {

        /**
         * Validates the settings.
         */
        public TrackingSettings {
            requireNonBlank(liquibaseSchema, "liquibaseSchema");
            requireNonBlank(changeLogTable, "changeLogTable");
            requireNonBlank(changeLogLockTable, "changeLogLockTable");
        }
    }

    /**
     * The changesets each run applied, by id, in changelog order.
     *
     * @param previousRelease the changesets applied from the previous release
     * @param currentRelease  the changesets the current changelog added on top
     */
    public record Report(List<String> previousRelease, List<String> currentRelease) {

        /**
         * Defensively copies the lists.
         */
        public Report {
            previousRelease = List.copyOf(previousRelease);
            currentRelease = List.copyOf(currentRelease);
        }
    }
}
