package io.github.eyupmiduck.changelogvalidator.testing;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Shared setup for the live round-trip and re-apply contracts: one scratch
 * database in the shared container, prepared like a real deployment, with the
 * project's baseline changelogs applied outside the main changelog's rollback.
 *
 * <p>It extends {@link PostgresTestBase} so it reuses the one container and the
 * project's {@link ProjectProfile} (roles, image, tracking-table names, the
 * extension hook and fixtures). It does not use the migrated template database:
 * the contracts need a database whose changelog they can apply, roll back and
 * apply again themselves.
 *
 * <p>The scratch database is created once per contract class, not per test, and
 * is dropped afterwards.
 */
public abstract class ChangelogRehearsalTestBase extends PostgresTestBase {

    private String rehearsalDatabase;
    private Connection ownerConnection;
    private Connection testConnection;
    private DSLContext rehearsalDsl;

    /**
     * Creates and prepares the scratch database, applies the profile's baseline
     * changelogs, then lets the contract add any non-changelog baseline.
     */
    @BeforeAll
    void createRehearsalDatabase() throws Exception {
        rehearsalDatabase = "rehearsal_" + Integer.toUnsignedString(getClass().getName().hashCode(), 16);
        createDatabase(rehearsalDatabase);
        try {
            prepareScratchDatabase(rehearsalDatabase, trackingSettings().liquibaseSchema());
            ownerConnection = openOwnerConnection(rehearsalDatabase);
            applyBaselineChangelogs();
            prepareBaseline(ownerConnection);
            testConnection = openTestConnection(rehearsalDatabase);
            rehearsalDsl = DSL.using(testConnection, SQLDialect.POSTGRES);
        } catch (Exception e) {
            closeAndDropRehearsalDatabase();
            throw e;
        }
    }

    /**
     * Closes the connections and drops the scratch database.
     *
     * @throws SQLException if the database cannot be dropped
     */
    @AfterAll
    void closeAndDropRehearsalDatabase() throws SQLException {
        try {
            if (testConnection != null) {
                testConnection.close();
            }
        } finally {
            try {
                if (ownerConnection != null) {
                    ownerConnection.close();
                }
            } finally {
                if (rehearsalDatabase != null) {
                    dropDatabase(rehearsalDatabase);
                }
            }
        }
    }

    /**
     * The main changelog resource the contracts apply, by default the one in the
     * profile's configuration.
     *
     * @return the master changelog resource
     */
    protected String masterResource() {
        return configuration().masterResource();
    }

    /**
     * The fixture changelog applied after the main changelog and rolled back
     * before it, or {@code null} when the project has none.
     *
     * @return the fixture changelog resource, or {@code null}
     */
    protected String fixturesResource() {
        return configuration().fixturesResource();
    }

    /**
     * The schemas the round-trip catalog comparison captures, by default the
     * profile's round-trip schemas.
     *
     * @return the round-trip schemas
     */
    protected Collection<String> roundTripSchemas() {
        return projectProfile().roundTripSchemas();
    }

    /**
     * Supplies non-changelog prerequisites (for example a role the changelog
     * grants to) after the profile's baseline changelogs. The default does
     * nothing.
     *
     * @param owner an owner connection to the scratch database
     * @throws Exception if the preparation fails
     */
    protected void prepareBaseline(Connection owner) throws Exception {
    }

    /**
     * An owner connection to the scratch database, which the contracts keep open.
     *
     * @return the owner connection
     */
    protected Connection rehearsalOwnerConnection() {
        return ownerConnection;
    }

    /**
     * A jOOQ context on the scratch database as the test role, for project
     * assertions.
     *
     * @return the scratch database context
     */
    protected DSLContext rehearsalDsl() {
        return rehearsalDsl;
    }

    /**
     * The scratch database's name.
     *
     * @return the database name
     */
    protected String rehearsalDatabaseName() {
        return rehearsalDatabase;
    }

    /**
     * Applies the main changelog and then the fixture changelog, returning the
     * changesets that ran.
     *
     * @return the applied changeset ids, in application order
     */
    protected List<String> applyChangelogs() {
        List<String> applied = new ArrayList<>();
        try (LiquibaseSession master = openSession(masterResource())) {
            applied.addAll(master.apply());
        }
        String fixtures = fixturesResource();
        if (fixtures != null && !fixtures.isBlank()) {
            try (LiquibaseSession session = openSession(fixtures)) {
                applied.addAll(session.apply());
            }
        }
        return List.copyOf(applied);
    }

    /**
     * Rolls back the fixture changelog and then the main changelog, returning the
     * changesets that were undone.
     *
     * @return the rolled-back changeset ids, in reverse application order
     */
    protected List<String> rollbackChangelogs() {
        List<String> rolledBack = new ArrayList<>();
        String fixtures = fixturesResource();
        if (fixtures != null && !fixtures.isBlank()) {
            try (LiquibaseSession session = openSession(fixtures)) {
                rolledBack.addAll(session.rollbackAll());
            }
        }
        try (LiquibaseSession master = openSession(masterResource())) {
            rolledBack.addAll(master.rollbackAll());
        }
        return List.copyOf(rolledBack);
    }

    private LiquibaseSession openSession(String resource) {
        return openSession(resource, trackingSettings());
    }

    private LiquibaseSession openSession(String resource, LiquibaseUpgrade.TrackingSettings tracking) {
        return LiquibaseSession.open(ownerConnection, tracking, resource);
    }

    private void applyBaselineChangelogs() {
        int index = 0;
        for (String resource : projectProfile().baselineChangelogs()) {
            try (LiquibaseSession session = openSession(resource, baselineTracking(index++))) {
                session.apply();
            }
        }
    }

    /**
     * The baseline changelogs are installed dependencies, not part of the main
     * changelog: they get their own tracking table so a changeset identity that
     * repeats across the two graphs (for example both use
     * {@code changes/changes.xml::001-create-schema}) cannot collide.
     */
    private LiquibaseUpgrade.TrackingSettings baselineTracking(int index) {
        String schema = trackingSettings().liquibaseSchema();
        return new LiquibaseUpgrade.TrackingSettings(
                schema, "baseline_" + index + "_changelog", "baseline_" + index + "_lock");
    }
}
