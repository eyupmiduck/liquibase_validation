package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises {@link GrantAssertions} against a real PostgreSQL: the ACL
 * invariants over routines and types, and the grant/rollback parity check.
 *
 * <p>The objects are created in this class's private database rather than via
 * {@code installExtensions}, because the shared container prepares its template
 * once per JVM and only the first database test class's {@code installExtensions}
 * runs.
 */
class GrantAssertionsTest extends PostgresTestBase {

    /**
     * Creates a clean schema (revoked from PUBLIC and granted to a role), a leaky
     * schema (left with PUBLIC's defaults) and a routine the role cannot execute.
     */
    @BeforeAll
    void createGrantedObjects() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             var statement = admin.createStatement()) {
            statement.execute("""
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'checker') THEN
                            CREATE ROLE checker NOLOGIN;
                        END IF;
                    END $$""");

            statement.execute("CREATE SCHEMA grants_clean");
            statement.execute("CREATE FUNCTION grants_clean.f() RETURNS void LANGUAGE sql AS $$ SELECT NULL::void $$");
            statement.execute("REVOKE EXECUTE ON FUNCTION grants_clean.f() FROM public");
            statement.execute("GRANT EXECUTE ON FUNCTION grants_clean.f() TO checker");
            statement.execute("CREATE DOMAIN grants_clean.clean_domain AS text");
            statement.execute("REVOKE USAGE ON DOMAIN grants_clean.clean_domain FROM public");
            statement.execute("GRANT USAGE ON DOMAIN grants_clean.clean_domain TO checker");

            statement.execute("CREATE SCHEMA grants_leaky");
            statement.execute("CREATE FUNCTION grants_leaky.f() RETURNS void LANGUAGE sql AS $$ SELECT NULL::void $$");
            statement.execute("CREATE DOMAIN grants_leaky.leaky_domain AS text");

            statement.execute("CREATE SCHEMA grants_missing");
            statement.execute("CREATE FUNCTION grants_missing.f() RETURNS void LANGUAGE sql AS $$ SELECT NULL::void $$");
            statement.execute("REVOKE EXECUTE ON FUNCTION grants_missing.f() FROM public");
        }
    }

    /**
     * The clean schema satisfies every invariant.
     */
    @Test
    void cleanSchemaPassesThePrivilegeAssertions() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            GrantAssertions.assertNoRoutineExecutableByPublic(owner, List.of("grants_clean"));
            GrantAssertions.assertRoleCanExecuteEveryCallableRoutine(owner, List.of("grants_clean"), "checker");
            GrantAssertions.assertTypeIsNotUsableByPublic(owner, "grants_clean", "clean_domain");
            GrantAssertions.assertRoleHasExplicitUsageOnType(owner, "grants_clean", "clean_domain", "checker");
        }
    }

    /**
     * A routine or type left with PUBLIC's default privilege is reported.
     */
    @Test
    void publicExecutableRoutineAndTypeAreReported() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            assertThrows(AssertionError.class,
                    () -> GrantAssertions.assertNoRoutineExecutableByPublic(owner, List.of("grants_leaky")));
            assertThrows(AssertionError.class,
                    () -> GrantAssertions.assertTypeIsNotUsableByPublic(owner, "grants_leaky", "leaky_domain"));
        }
    }

    /**
     * A routine the role cannot execute is reported.
     */
    @Test
    void routineTheRoleCannotExecuteIsReported() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            assertThrows(AssertionError.class, () -> GrantAssertions.assertRoleCanExecuteEveryCallableRoutine(
                    owner, List.of("grants_missing"), "checker"));
        }
    }

    /**
     * A rollback that mirrors the forward grants passes; one missing a function
     * is reported.
     */
    @Test
    void rollbackParityPassesWhenMirroredAndFailsWhenNot() throws Exception {
        GrantAssertions.assertGrantRollbackMirrors(
                "testing/grants-forward.sql", "app_caller", "testing/grants-rollback.sql");

        assertThrows(AssertionError.class, () -> GrantAssertions.assertGrantRollbackMirrors(
                "testing/grants-forward-extra.sql", "app_caller", "testing/grants-rollback-one.sql"));
    }
}
