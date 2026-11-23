package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Runs the shared {@link AuditColumnsContract} against an audited fixture created
 * in this class's private database, so both the catalog check and the behavioral
 * probe are exercised here.
 */
class AuditColumnsContractTest extends AuditColumnsContract {

    private static final String SCHEMA = "audited_fixture";
    private static final String TABLE = "widget";

    /**
     * Creates an audited table with the shared trigger and one row to probe.
     *
     * @throws Exception if the fixture cannot be created
     */
    @BeforeAll
    void createAuditedFixture() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION "
                    + quoteIdentifier(configuration().ownerUser()));
        }
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE TABLE " + SCHEMA + "." + TABLE + " ("
                    + "id integer PRIMARY KEY,"
                    + "created_at timestamptz NOT NULL DEFAULT now(),"
                    + "updated_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("CREATE FUNCTION " + SCHEMA + ".set_updated_at() RETURNS trigger"
                    + " LANGUAGE plpgsql AS $$ BEGIN NEW.updated_at := now(); RETURN NEW; END; $$");
            statement.execute("CREATE TRIGGER " + TABLE + "_set_updated_at BEFORE UPDATE ON "
                    + SCHEMA + "." + TABLE
                    + " FOR EACH ROW EXECUTE FUNCTION " + SCHEMA + ".set_updated_at()");
            statement.execute("INSERT INTO " + SCHEMA + "." + TABLE + " (id) VALUES (1)");
        }
    }

    /**
     * The fixture schema is the only schema inspected.
     *
     * @return the fixture schema
     */
    @Override
    protected Collection<String> auditedSchemas() {
        return List.of(SCHEMA);
    }

    /**
     * The seeded table is probed on its real row.
     *
     * @return the probe table map
     */
    @Override
    protected Map<String, Collection<String>> probeTables() {
        return Map.of(SCHEMA, List.of(TABLE));
    }
}
