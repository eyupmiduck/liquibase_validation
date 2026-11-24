package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;

/**
 * Runs the shared {@link CommentContract} against a commented fixture created in
 * this class's private database, so the inherited test and its profile plumbing
 * are exercised here too.
 */
class CommentContractTest extends CommentContract {

    private static final String SCHEMA = "commented_fixture";

    /**
     * Creates and comments a schema, table, columns, domain, composite, enum,
     * range and its multirange companion, function, procedure, trigger function
     * and trigger.
     *
     * @throws Exception if the fixture cannot be created
     */
    @BeforeAll
    void createCommentedFixture() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION "
                    + quoteIdentifier(configuration().ownerUser()));
        }
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("COMMENT ON SCHEMA " + SCHEMA + " IS 'fixture schema'");
            statement.execute("CREATE TABLE " + SCHEMA + ".widget ("
                    + "id integer NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())");
            statement.execute("COMMENT ON TABLE " + SCHEMA + ".widget IS 'fixture table'");
            statement.execute("COMMENT ON COLUMN " + SCHEMA + ".widget.id IS 'fixture id'");
            statement.execute("COMMENT ON COLUMN " + SCHEMA + ".widget.updated_at IS 'fixture updated_at'");
            statement.execute("CREATE DOMAIN " + SCHEMA + ".positive_integer AS integer CHECK (VALUE > 0)");
            statement.execute("COMMENT ON DOMAIN " + SCHEMA + ".positive_integer IS 'fixture domain'");
            statement.execute("CREATE TYPE " + SCHEMA + ".pair AS (left_value integer, right_value text)");
            statement.execute("COMMENT ON TYPE " + SCHEMA + ".pair IS 'fixture composite'");
            statement.execute("CREATE TYPE " + SCHEMA + ".mood AS ENUM ('ok', 'sad')");
            statement.execute("COMMENT ON TYPE " + SCHEMA + ".mood IS 'fixture enum'");
            statement.execute("CREATE TYPE " + SCHEMA + ".price_range AS RANGE (subtype = numeric)");
            statement.execute("COMMENT ON TYPE " + SCHEMA + ".price_range IS 'fixture range'");
            statement.execute("COMMENT ON TYPE " + SCHEMA + ".price_multirange IS 'fixture multirange'");
            statement.execute("CREATE FUNCTION " + SCHEMA + ".add_one(i_value integer) RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT i_value + 1'");
            statement.execute("COMMENT ON FUNCTION " + SCHEMA + ".add_one(integer) IS 'fixture function'");
            statement.execute("CREATE PROCEDURE " + SCHEMA + ".do_nothing() LANGUAGE plpgsql"
                    + " AS $$ BEGIN NULL; END; $$");
            statement.execute("COMMENT ON PROCEDURE " + SCHEMA + ".do_nothing() IS 'fixture procedure'");
            statement.execute("CREATE FUNCTION " + SCHEMA + ".set_updated_at() RETURNS trigger"
                    + " LANGUAGE plpgsql AS $$ BEGIN NEW.updated_at := now(); RETURN NEW; END; $$");
            statement.execute("COMMENT ON FUNCTION " + SCHEMA + ".set_updated_at() IS 'fixture trigger function'");
            statement.execute("CREATE TRIGGER widget_set_updated_at BEFORE UPDATE ON " + SCHEMA + ".widget"
                    + " FOR EACH ROW EXECUTE FUNCTION " + SCHEMA + ".set_updated_at()");
            statement.execute("COMMENT ON TRIGGER widget_set_updated_at ON " + SCHEMA
                    + ".widget IS 'fixture trigger'");
        }
    }

    /**
     * The fixture schema is the only schema the contract inspects.
     *
     * @return the fixture schema
     */
    @Override
    protected Collection<String> commentedSchemas() {
        return List.of(SCHEMA);
    }
}
