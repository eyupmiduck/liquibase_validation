package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link TrackingTableAssertions} against the default tracking-table
 * configuration.
 */
class TrackingTableAssertionsTest extends PostgresTestBase {

    /**
     * The default tracking tables live in the liquibase schema and the generic
     * names are absent from the application schema.
     */
    @Test
    void defaultTrackingTablesAreIsolated() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            TrackingTableAssertions.assertTrackingTablesAreIsolated(owner, LIQUIBASE_SCHEMA,
                    databaseChangeLogTableName(), databaseChangeLogLockTableName(), List.of(PUBLIC_SCHEMA));
        }
    }

    /**
     * A missing tracking table is reported.
     */
    @Test
    void missingTrackingTableIsReported() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            assertThrows(AssertionError.class, () -> TrackingTableAssertions.assertTrackingTablesAreIsolated(
                    owner, LIQUIBASE_SCHEMA, "no_such_table", databaseChangeLogLockTableName(),
                    List.of(PUBLIC_SCHEMA)));
        }
    }
}
