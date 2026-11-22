package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;

/**
 * Runs the shared {@link RoutineDocumentationContract} against a documented
 * fixture, so the inherited test and the default heading parser are exercised
 * here too.
 */
class RoutineDocumentationContractTest extends RoutineDocumentationContract {

    private static final String SCHEMA = "documentation_fixture";

    /**
     * Creates one routine with named arguments and one with no arguments.
     *
     * @throws Exception if the fixture cannot be created
     */
    @BeforeAll
    void createDocumentationFixture() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION "
                    + quoteIdentifier(configuration().ownerUser()));
        }
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + SCHEMA + ".add(i_a integer, i_b text)"
                    + " RETURNS text LANGUAGE sql AS 'SELECT i_a::text || i_b'");
            statement.execute("CREATE FUNCTION " + SCHEMA + ".no_args() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT 1'");
        }
    }

    /**
     * The fixture schema is the only documented schema.
     *
     * @return the fixture schema
     */
    @Override
    protected Collection<String> documentedSchemas() {
        return List.of(SCHEMA);
    }

    /**
     * The fixture README documents both routines.
     *
     * @return the fixture README
     */
    @Override
    protected Collection<String> readmeResources() {
        return List.of("routine-documentation-fixture/README.md");
    }
}
