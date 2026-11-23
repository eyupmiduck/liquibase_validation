package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

/**
 * Runs the shared {@link SchemaLayeringContract} against a compliant two-layer
 * fixture, so the inherited test and its profile plumbing are exercised here
 * too.
 */
class SchemaLayeringContractTest extends SchemaLayeringContract {

    private static final String TOP = "contract_layering_top";
    private static final String BOTTOM = "contract_layering_bottom";

    /**
     * Creates a top schema whose routine references a bottom schema.
     *
     * @throws Exception if the fixture cannot be created
     */
    @BeforeAll
    void createFixture() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            for (String schema : List.of(TOP, BOTTOM)) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                statement.execute("CREATE SCHEMA " + schema + " AUTHORIZATION \""
                        + configuration().ownerUser().replace("\"", "\"\"") + "\"");
            }
        }
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + BOTTOM + ".bottom_fn() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT 1'");
            statement.execute("CREATE FUNCTION " + TOP + ".top_fn() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT " + BOTTOM + ".bottom_fn()'");
        }
    }

    /**
     * The fixture's two schemas are the layers, highest first.
     *
     * @return the ordered schemas
     */
    @Override
    protected List<String> schemaLayers() {
        return List.of(TOP, BOTTOM);
    }
}
