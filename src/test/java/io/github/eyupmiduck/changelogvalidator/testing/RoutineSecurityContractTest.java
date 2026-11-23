package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Runs the shared {@link RoutineSecurityContract} against a compliant fixture:
 * an invoker, a definer with a pinned path, a catalog routine with the pin and a
 * definer procedure.
 */
class RoutineSecurityContractTest extends RoutineSecurityContract {

    private static final String SCHEMA = "security_fixture";

    /**
     * Creates the compliant routines.
     *
     * @throws Exception if the fixture cannot be created
     */
    @BeforeAll
    void createSecurityFixture() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION "
                    + quoteIdentifier(configuration().ownerUser()));
        }
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + SCHEMA + ".invoker_fn() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT 1'");
            statement.execute("CREATE FUNCTION " + SCHEMA + ".safe_definer() RETURNS integer"
                    + " LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp"
                    + " AS 'SELECT 1'");
            statement.execute("CREATE FUNCTION " + SCHEMA + ".catalog_pinned() RETURNS integer"
                    + " LANGUAGE sql SET search_path = pg_catalog AS 'SELECT 1'");
            statement.execute("CREATE PROCEDURE " + SCHEMA + ".safe_proc() LANGUAGE plpgsql"
                    + " SECURITY DEFINER SET search_path = pg_catalog AS $$ BEGIN NULL; END; $$");
        }
    }

    @Override
    protected Collection<String> routineSchemas() {
        return List.of(SCHEMA);
    }

    @Override
    protected Set<String> allowedSecurityDefiners() {
        return Set.of(SCHEMA + ".safe_definer", SCHEMA + ".safe_proc");
    }

    @Override
    protected Set<String> catalogRoutines() {
        return Set.of(SCHEMA + ".catalog_pinned");
    }

    @Override
    protected Set<String> expectedProcedures() {
        return Set.of(SCHEMA + ".safe_proc");
    }
}
