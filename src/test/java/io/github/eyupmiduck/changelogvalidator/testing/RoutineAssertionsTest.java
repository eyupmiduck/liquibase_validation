package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link RoutineAssertions} against a real PostgreSQL: the
 * audit-column catalog check and behavioral probe, and the allow-list plumbing
 * of the plpgsql_check assertion.
 */
class RoutineAssertionsTest extends PostgresTestBase {

    @Override
    protected void installExtensions(String templateDatabase) throws SQLException {
        super.installExtensions(templateDatabase);
        try (Connection admin = openAdminConnection(templateDatabase);
             var statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA audited");
            statement.execute("""
                    CREATE TABLE audited.widget (
                        id integer,
                        created_at timestamptz NOT NULL DEFAULT now(),
                        updated_at timestamptz NOT NULL DEFAULT now()
                    )""");
            statement.execute("""
                    CREATE FUNCTION audited.set_updated_at() RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                        NEW.updated_at := now();
                        RETURN NEW;
                    END;
                    $$""");
            statement.execute("""
                    CREATE TRIGGER widget_set_updated_at
                        BEFORE UPDATE ON audited.widget
                        FOR EACH ROW EXECUTE FUNCTION audited.set_updated_at()
                    """);
        }
    }

    /**
     * The audited table follows the convention and its trigger refreshes
     * {@code updated_at}.
     */
    @Test
    void checksAuditColumnsAndProbe() throws Exception {
        dsl.execute("INSERT INTO audited.widget (id) VALUES (1)");
        try (Connection owner = openOwnerConnection()) {
            RoutineAssertions.assertAuditColumnsFollowConvention(owner, List.of("audited"));
            RoutineAssertions.assertUpdateTriggerRefreshesAuditColumns(owner, "audited", List.of("widget"));
        }
    }

    /**
     * A table that violates the convention is reported.
     */
    @Test
    void rejectsATableMissingAuditColumns() throws Exception {
        dsl.execute("CREATE TABLE audited.unaudited (id integer)");
        try (Connection owner = openOwnerConnection()) {
            assertThrows(AssertionError.class,
                    () -> RoutineAssertions.assertAuditColumnsFollowConvention(owner, List.of("audited")));
        } finally {
            dsl.execute("DROP TABLE audited.unaudited");
        }
    }

    /**
     * The whitelist-resource overload fails loudly when the resource is absent,
     * rather than NPE-ing later.
     */
    @Test
    void rejectsAMissingWhitelistResource() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            assertThrows(AssertionError.class, () -> RoutineAssertions.assertRoutinesPassPlpgsqlCheck(
                    owner, List.of("audited"), "no-such-whitelist.yml"));
        }
    }
}
