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

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A Liquibase run against one connection, exposing the operations the live
 * changelog contracts need: list the pending changesets, apply them, and roll
 * back every applied changeset in reverse order.
 *
 * <p>The session wraps the caller's connection in a view whose {@code close()} is
 * a no-op, so closing the session never closes the connection the test still
 * owns. That makes it safe to open a session per changelog (the main changelog
 * and its fixture/install changelog) on the same connection.
 *
 * <p>The returned change-set ids are those Liquibase itself reports as unrendered
 * before an update, so a session does not have to parse the changeset XML.
 */
public final class LiquibaseSession implements AutoCloseable {

    private final Database database;
    private final Liquibase liquibase;

    private LiquibaseSession(Database database, Liquibase liquibase) {
        this.database = database;
        this.liquibase = liquibase;
    }

    /**
     * Opens a session for a changelog on the test classpath.
     *
     * @param connection     the owner connection
     * @param tracking       the Liquibase tracking settings
     * @param masterResource the changelog master resource, relative to the
     *                       classpath
     * @return the session
     */
    public static LiquibaseSession open(Connection connection, LiquibaseUpgrade.TrackingSettings tracking,
                                        String masterResource) {
        return open(connection, tracking, masterResource, new ClassLoaderResourceAccessor());
    }

    /**
     * Opens a session for a changelog held by {@code accessor}.
     *
     * @param connection     the owner connection
     * @param tracking       the Liquibase tracking settings
     * @param masterResource the changelog master resource, relative to
     *                       {@code accessor}
     * @param accessor       the changelog accessor
     * @return the session
     */
    public static LiquibaseSession open(Connection connection, LiquibaseUpgrade.TrackingSettings tracking,
                                        String masterResource, ResourceAccessor accessor) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(tracking, "tracking");
        Objects.requireNonNull(masterResource, "masterResource");
        Objects.requireNonNull(accessor, "accessor");
        try {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(PostgresTestBase.closeIgnoring(connection)));
            database.setLiquibaseSchemaName(tracking.liquibaseSchema());
            database.setDatabaseChangeLogTableName(tracking.changeLogTable());
            database.setDatabaseChangeLogLockTableName(tracking.changeLogLockTable());
            return new LiquibaseSession(database, new Liquibase(masterResource, accessor, database));
        } catch (LiquibaseException e) {
            throw new IllegalStateException("could not open " + masterResource + ": " + e.getMessage(), e);
        }
    }

    /**
     * The changesets that would run on the next update, in changelog order. A run
     * of an already-applied changelog returns an empty list; a {@code runAlways}
     * changeset is always present.
     *
     * @return the pending changeset ids
     */
    public List<String> unrunChangeSets() {
        try {
            return liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression()).stream()
                    .map(changeSet -> changeSet.getId())
                    .toList();
        } catch (LiquibaseException e) {
            throw new IllegalStateException("could not list unrun changesets: " + e.getMessage(), e);
        }
    }

    /**
     * Applies the changelog, returning the changesets that ran.
     *
     * @return the applied changeset ids, in changelog order
     */
    public List<String> apply() {
        List<String> pending = unrunChangeSets();
        try {
            liquibase.update(new Contexts());
        } catch (LiquibaseException e) {
            throw new IllegalStateException("could not apply the changelog: " + e.getMessage(), e);
        }
        return pending;
    }

    /**
     * Rolls back every applied changeset in reverse execution order, returning
     * the ids that were undone.
     *
     * @return the rolled-back changeset ids, in reverse execution order
     */
    public List<String> rollbackAll() {
        List<String> before = unrunChangeSets();
        try {
            liquibase.rollback(Integer.MAX_VALUE, new Contexts(), new LabelExpression());
        } catch (LiquibaseException e) {
            throw new IllegalStateException("could not roll back the changelog: " + e.getMessage(), e);
        }
        List<String> rolledBack = new ArrayList<>(unrunChangeSets());
        for (String id : before) {
            rolledBack.remove(id);
        }
        Collections.reverse(rolledBack);
        return List.copyOf(rolledBack);
    }

    @Override
    public void close() {
        try {
            database.close();
        } catch (LiquibaseException e) {
            throw new IllegalStateException("could not close the Liquibase session: " + e.getMessage(), e);
        }
    }
}
