package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collection;
import java.util.Map;

/**
 * Contract for a consuming project's audit-column test: every base table in the
 * configured schemas has the {@code created_at}/{@code updated_at} columns and
 * the {@code BEFORE UPDATE} trigger, and, when probe tables are supplied, the
 * trigger actually refreshes {@code updated_at} and preserves
 * {@code created_at} on a real row.
 *
 * <p>A consumer extends this class and returns its one {@link ProjectProfile}
 * from {@link #projectProfile()}; {@link #auditedSchemas()} defaults to the
 * profile's managed schemas. Override {@link #probeTables()} with a schema to
 * table-list map to run the behavioral probe; leave it empty to run only the
 * catalog check. A probe table must contain at least one row.
 */
public abstract class AuditColumnsContract extends PostgresTestBase {

    /**
     * The schemas whose tables must follow the audit-column convention, from the
     * profile's managed schemas.
     *
     * @return the audited schemas
     */
    protected Collection<String> auditedSchemas() {
        return projectProfile().managedSchemas();
    }

    /**
     * The tables to probe on a real row, grouped by schema.
     *
     * @return the probe tables, empty by default
     */
    protected Map<String, Collection<String>> probeTables() {
        return Map.of();
    }

    /**
     * Every base table follows the audit-column convention, and at least one
     * table was inspected.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void auditColumnsFollowTheConvention() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            RoutineAssertions.assertAuditColumnsFollowConvention(owner, auditedSchemas());
        }
    }

    /**
     * The shared trigger refreshes {@code updated_at} and preserves
     * {@code created_at} on a real row in each configured probe table.
     *
     * @throws Exception if the probe cannot run
     */
    @Test
    void updateTriggerRefreshesAuditColumns() throws Exception {
        Map<String, Collection<String>> tables = probeTables();
        Assumptions.assumeFalse(tables.isEmpty(), "no probe tables configured");
        try (Connection owner = openOwnerConnection()) {
            RoutineAssertions.assertUpdateTriggerRefreshesAuditColumns(owner, tables);
        }
    }
}
