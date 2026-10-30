package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collection;

/**
 * Contract for a consuming project's Liquibase tracking-table test: the tracking
 * tables live in the dedicated {@code liquibase} schema with the project's
 * prefix, and the generic {@code databasechangelog} /
 * {@code databasechangeloglock} names are absent from every application schema.
 *
 * <p>A consumer extends this class and supplies its
 * {@link #configuration() configuration} and {@link #applicationSchemas()},
 * normally with the inherited {@link #PUBLIC_SCHEMA} plus the project's own
 * schemas.
 */
public abstract class TrackingTablesContract extends PostgresTestBase {

    /**
     * The consumer's container and changelog configuration. It must be supplied
     * because the contract, not the consumer's own test base, is the direct
     * superclass of the consumer's test class.
     *
     * @return the configuration
     */
    @Override
    protected abstract Configuration configuration();

    /**
     * The schemas that must not hold Liquibase's generic tracking-table names.
     *
     * @return the application schemas
     */
    protected abstract Collection<String> applicationSchemas();

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
