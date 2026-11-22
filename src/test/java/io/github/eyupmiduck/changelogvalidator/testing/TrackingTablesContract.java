package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;

/**
 * Contract for a consuming project's Liquibase tracking-table test: the tracking
 * tables live in the dedicated {@code liquibase} schema with the project's
 * prefix, and the generic {@code databasechangelog} /
 * {@code databasechangeloglock} names are absent from every application schema.
 *
 * <p>A consumer extends this class and returns its one
 * {@link ProjectProfile} from {@link #projectProfile()}; the configuration and
 * {@link #applicationSchemas()} come from that profile. Override
 * {@link #applicationSchemas()} for a one-off schema list.
 */
public abstract class TrackingTablesContract extends PostgresTestBase {

    /**
     * The tracking tables exist in the {@code liquibase} schema with the
     * project's prefix, and the generic names are absent from every application
     * schema.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void trackingTablesLiveInTheLiquibaseSchema() throws Exception {
        Configuration configuration = configuration();
        try (Connection connection = openTestConnection()) {
            TrackingTableAssertions.assertTrackingTablesAreIsolated(connection, LIQUIBASE_SCHEMA,
                    configuration.databaseChangeLogTable(), configuration.databaseChangeLogLockTable(),
                    applicationSchemas());
        }
    }
}
