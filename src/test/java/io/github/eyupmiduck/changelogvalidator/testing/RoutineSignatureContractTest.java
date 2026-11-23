package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;

/**
 * Runs the shared {@link RoutineSignatureContract} against a fixture with one
 * signature per name, so the inherited test and its profile plumbing are
 * exercised here too.
 */
class RoutineSignatureContractTest extends RoutineSignatureContract {

    private static final String SCHEMA = "signature_fixture";

    /**
     * Creates one routine with no arguments and one with named input arguments.
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
            statement.execute("CREATE FUNCTION " + SCHEMA + ".no_args() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT 1'");
            statement.execute("CREATE FUNCTION " + SCHEMA + ".add(i_a integer, i_b text)"
                    + " RETURNS text LANGUAGE sql AS 'SELECT i_a::text || i_b'");
        }
    }

    /**
     * The fixture schema is the only routine schema inspected.
     *
     * @return the fixture schema
     */
    @Override
    protected Collection<String> routineSchemas() {
        return List.of(SCHEMA);
    }
}
