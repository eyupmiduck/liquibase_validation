package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the {@link RoutineSignatureContract} report directly, including an
 * unexpected overload and a stale allow-list entry.
 */
class RoutineSignatureCheckTest extends PostgresTestBase {

    private static final String SCHEMA = "signature_check";

    /**
     * Creates a routine with two signatures and one with a single signature.
     *
     * @throws Exception if the fixture cannot be created
     */
    @BeforeAll
    void createSignatureFixture() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION "
                    + quoteIdentifier(configuration().ownerUser()));
        }
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + SCHEMA + ".plain() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT 1'");
            statement.execute("CREATE FUNCTION " + SCHEMA + ".overloaded(i_value integer) RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT i_value'");
            statement.execute("CREATE FUNCTION " + SCHEMA + ".overloaded(i_value text) RETURNS text"
                    + " LANGUAGE sql AS 'SELECT i_value'");
        }
    }

    /**
     * An overload outside the allow-list is reported.
     */
    @Test
    void reportsAnUnexpectedOverload() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            RoutineSignatureContract.Report report =
                    RoutineSignatureContract.inspect(owner, List.of(SCHEMA), Set.of());

            assertEquals(1, report.unexpectedOverloads().size(), report.unexpectedOverloads().toString());
            assertTrue(report.unexpectedOverloads().getFirst()
                            .startsWith(SCHEMA + ".overloaded("),
                    report.unexpectedOverloads().getFirst());
            assertEquals(List.of(), report.staleAllowListEntries());
            assertEquals(2, report.checkedRoutines());
        }
    }

    /**
     * A real overload is accepted and an allow-list entry that no longer matches
     * is stale.
     */
    @Test
    void reportsAStaleAllowListEntry() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            RoutineSignatureContract.Report allowed = RoutineSignatureContract.inspect(
                    owner, List.of(SCHEMA), Set.of(SCHEMA + ".overloaded"));
            assertEquals(List.of(), allowed.unexpectedOverloads());
            assertEquals(List.of(), allowed.staleAllowListEntries());

            RoutineSignatureContract.Report stale = RoutineSignatureContract.inspect(
                    owner, List.of(SCHEMA), Set.of(SCHEMA + ".overloaded", SCHEMA + ".gone"));
            assertEquals(List.of(), stale.unexpectedOverloads());
            assertEquals(List.of(SCHEMA + ".gone"), stale.staleAllowListEntries());
        }
    }

    /**
     * A schema with no routines reports a zero count so the contract can fail
     * non-vacuously.
     */
    @Test
    void reportsNoRoutinesForAnEmptySchema() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            RoutineSignatureContract.Report report =
                    RoutineSignatureContract.inspect(owner, List.of(PUBLIC_SCHEMA), Set.of());

            assertEquals(List.of(), report.unexpectedOverloads());
            assertEquals(List.of(), report.staleAllowListEntries());
            assertEquals(0, report.checkedRoutines());
        }
    }
}
