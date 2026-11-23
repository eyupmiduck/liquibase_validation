package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;

/**
 * Runs the shared {@link PrivilegeContract} against a fixture with a granted
 * routine, a granted domain and the owner's default privileges, so every
 * inherited check is exercised here.
 */
class PrivilegeContractTest extends PrivilegeContract {

    private static final String SCHEMA = "privilege_fixture";
    private static final String CALLER = "app_caller";

    /**
     * Creates the caller role and the schema, sets the owner's default
     * privileges, and creates a routine and domain granted to the caller.
     *
     * @throws Exception if the fixture cannot be created
     */
    @BeforeAll
    void createPrivilegeFixture() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("""
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_caller') THEN
                            CREATE ROLE app_caller NOLOGIN;
                        END IF;
                    END $$""");
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION "
                    + quoteIdentifier(configuration().ownerUser()));
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE "
                    + quoteIdentifier(configuration().ownerUser())
                    + " REVOKE EXECUTE ON FUNCTIONS FROM public");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE "
                    + quoteIdentifier(configuration().ownerUser())
                    + " REVOKE USAGE ON TYPES FROM public");
        }
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + SCHEMA + ".f() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT 1'");
            statement.execute("REVOKE EXECUTE ON FUNCTION " + SCHEMA + ".f() FROM public");
            statement.execute("GRANT EXECUTE ON FUNCTION " + SCHEMA + ".f() TO " + CALLER);
            statement.execute("CREATE DOMAIN " + SCHEMA + ".clean_domain AS integer CHECK (VALUE > 0)");
            statement.execute("REVOKE USAGE ON DOMAIN " + SCHEMA + ".clean_domain FROM public");
            statement.execute("GRANT USAGE ON DOMAIN " + SCHEMA + ".clean_domain TO " + CALLER);
        }
    }

    @Override
    protected Collection<String> routineSchemas() {
        return List.of(SCHEMA);
    }

    @Override
    protected Collection<String> typeSchemas() {
        return List.of(SCHEMA);
    }

    @Override
    protected String callerRole() {
        return CALLER;
    }

    @Override
    protected Collection<TypeRef> applicationTypes() {
        return List.of(new TypeRef(SCHEMA, "clean_domain"));
    }

    @Override
    protected String grantForwardResource() {
        return "testing/grants-forward.sql";
    }

    @Override
    protected String grantRollbackResource() {
        return "testing/grants-rollback.sql";
    }
}
