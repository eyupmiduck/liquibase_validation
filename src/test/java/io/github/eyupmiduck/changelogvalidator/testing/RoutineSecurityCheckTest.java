package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the {@link RoutineSecurityContract} report directly: unexpected and
 * stale definers, a definer without a pin, caller-controlled search paths, a
 * definer that includes {@code public}, catalog routines without the pin and a
 * procedure-kind mismatch.
 */
class RoutineSecurityCheckTest extends PostgresTestBase {

    private static final String SAFE = "security_safe";
    private static final String CHECK = "security_check";

    /**
     * Creates a compliant schema and a schema with one routine per violation.
     *
     * @throws Exception if the fixture cannot be created
     */
    @BeforeAll
    void createSecurityFixtures() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SAFE + " CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS " + CHECK + " CASCADE");
            statement.execute("CREATE SCHEMA " + SAFE + " AUTHORIZATION "
                    + quoteIdentifier(configuration().ownerUser()));
            statement.execute("CREATE SCHEMA " + CHECK + " AUTHORIZATION "
                    + quoteIdentifier(configuration().ownerUser()));
        }
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + SAFE + ".invoker_fn() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT 1'");
            statement.execute("CREATE FUNCTION " + SAFE + ".safe_definer() RETURNS integer"
                    + " LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, pg_temp"
                    + " AS 'SELECT 1'");
            statement.execute("CREATE FUNCTION " + SAFE + ".catalog_pinned() RETURNS integer"
                    + " LANGUAGE sql SET search_path = pg_catalog AS 'SELECT 1'");
            statement.execute("CREATE PROCEDURE " + SAFE + ".safe_proc() LANGUAGE plpgsql"
                    + " SECURITY DEFINER SET search_path = pg_catalog AS $$ BEGIN NULL; END; $$");

            statement.execute("""
                    CREATE FUNCTION security_check.bare_definer() RETURNS integer
                        LANGUAGE sql SECURITY DEFINER
                        AS 'SELECT 1'
                    """);
            statement.execute("""
                    CREATE FUNCTION security_check.user_path_definer() RETURNS integer
                        LANGUAGE sql SECURITY DEFINER
                        SET search_path = "$user", pg_catalog
                        AS 'SELECT 1'
                    """);
            statement.execute("""
                    CREATE FUNCTION security_check.unsafe_invoker() RETURNS integer
                        LANGUAGE sql
                        SET search_path = "$user"
                        AS 'SELECT 1'
                    """);
            statement.execute("""
                    CREATE FUNCTION security_check.public_definer() RETURNS integer
                        LANGUAGE sql SECURITY DEFINER
                        SET search_path = pg_catalog, public
                        AS 'SELECT 1'
                    """);
            statement.execute("CREATE FUNCTION " + CHECK + ".catalog_no_pin() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT 1'");
            statement.execute("CREATE FUNCTION " + CHECK + ".not_a_procedure() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT 1'");
        }
    }

    private RoutineSecurityContract.Report inspect(String schema, Set<String> definers,
                                                   Set<String> catalog, Set<String> procedures)
            throws Exception {
        try (Connection owner = openOwnerConnection()) {
            return RoutineSecurityContract.inspect(owner, List.of(schema), definers, catalog, procedures);
        }
    }

    /**
     * The compliant fixture has no report entries.
     */
    @Test
    void acceptsACompliantFixture() throws Exception {
        RoutineSecurityContract.Report report = inspect(SAFE,
                Set.of(SAFE + ".safe_definer", SAFE + ".safe_proc"),
                Set.of(SAFE + ".catalog_pinned"),
                Set.of(SAFE + ".safe_proc"));

        assertEquals(List.of(), report.unexpectedSecurityDefiners());
        assertEquals(List.of(), report.staleSecurityDefiners());
        assertEquals(List.of(), report.unsafeSearchPaths());
        assertEquals(List.of(), report.missingCatalogPins());
        assertEquals(List.of(), report.procedureKindMismatches());
        assertEquals(4, report.checkedRoutines());
    }

    /**
     * A definer outside the allow-list is reported.
     */
    @Test
    void reportsUnexpectedSecurityDefiners() throws Exception {
        RoutineSecurityContract.Report report = inspect(CHECK, Set.of(), Set.of(), Set.of());

        assertTrue(report.unexpectedSecurityDefiners().contains(CHECK + ".bare_definer"),
                report.unexpectedSecurityDefiners().toString());
        assertTrue(report.unexpectedSecurityDefiners().contains(CHECK + ".user_path_definer"),
                report.unexpectedSecurityDefiners().toString());
    }

    /**
     * An allow-list entry without a real definer is stale.
     */
    @Test
    void reportsAStaleSecurityDefiner() throws Exception {
        RoutineSecurityContract.Report report = inspect(CHECK, Set.of(CHECK + ".gone"), Set.of(), Set.of());

        assertEquals(List.of(CHECK + ".gone"), report.staleSecurityDefiners());
    }

    /**
     * A definer without a pinned search path is reported.
     */
    @Test
    void reportsADefinerWithoutASearchPath() throws Exception {
        RoutineSecurityContract.Report report = inspect(
                CHECK, Set.of(CHECK + ".bare_definer"), Set.of(), Set.of());

        assertTrue(report.unsafeSearchPaths().stream()
                        .anyMatch(problem -> problem.startsWith(CHECK + ".bare_definer")
                                && problem.contains("no pinned search_path")),
                report.unsafeSearchPaths().toString());
    }

    /**
     * A caller-controlled {@code $user} search path is reported for a definer and
     * an invoker.
     */
    @Test
    void reportsCallerControlledSearchPaths() throws Exception {
        RoutineSecurityContract.Report report = inspect(
                CHECK, Set.of(CHECK + ".user_path_definer"), Set.of(), Set.of());

        assertTrue(report.unsafeSearchPaths().stream()
                        .anyMatch(problem -> problem.startsWith(CHECK + ".user_path_definer")
                                && problem.contains("caller-controlled")),
                report.unsafeSearchPaths().toString());
        assertTrue(report.unsafeSearchPaths().stream()
                        .anyMatch(problem -> problem.startsWith(CHECK + ".unsafe_invoker")
                                && problem.contains("caller-controlled")),
                report.unsafeSearchPaths().toString());
    }

    /**
     * A definer whose pinned path includes {@code public} is reported.
     */
    @Test
    void reportsADefinerThatIncludesPublic() throws Exception {
        RoutineSecurityContract.Report report = inspect(
                CHECK, Set.of(CHECK + ".public_definer"), Set.of(), Set.of());

        assertTrue(report.unsafeSearchPaths().stream()
                        .anyMatch(problem -> problem.startsWith(CHECK + ".public_definer")
                                && problem.contains("must not include public")),
                report.unsafeSearchPaths().toString());
    }

    /**
     * A configured catalog routine without the {@code pg_catalog} pin is
     * reported, including a missing routine.
     */
    @Test
    void reportsCatalogRoutinesWithoutThePin() throws Exception {
        RoutineSecurityContract.Report report = inspect(
                CHECK, Set.of(), Set.of(CHECK + ".catalog_no_pin", CHECK + ".gone"), Set.of());

        assertTrue(report.missingCatalogPins().stream()
                        .anyMatch(problem -> problem.startsWith(CHECK + ".catalog_no_pin")
                                && problem.contains("expected search_path=pg_catalog")),
                report.missingCatalogPins().toString());
        assertTrue(report.missingCatalogPins().contains(CHECK + ".gone: routine not found"),
                report.missingCatalogPins().toString());
    }

    /**
     * A declared procedure that is actually a function is reported, including a
     * missing routine.
     */
    @Test
    void reportsProcedureKindMismatches() throws Exception {
        RoutineSecurityContract.Report report = inspect(
                CHECK, Set.of(), Set.of(),
                Set.of(CHECK + ".not_a_procedure", CHECK + ".gone"));

        assertTrue(report.procedureKindMismatches().contains(
                        CHECK + ".not_a_procedure: expected a procedure, got a function"),
                report.procedureKindMismatches().toString());
        assertTrue(report.procedureKindMismatches().contains(CHECK + ".gone: routine not found"),
                report.procedureKindMismatches().toString());
    }

    /**
     * A schema with no routines reports a zero count so the contract can fail
     * non-vacuously.
     */
    @Test
    void reportsNoRoutinesForAnEmptySchema() throws Exception {
        RoutineSecurityContract.Report report = inspect(PUBLIC_SCHEMA, Set.of(), Set.of(), Set.of());

        assertEquals(List.of(), report.unexpectedSecurityDefiners());
        assertEquals(List.of(), report.staleSecurityDefiners());
        assertEquals(List.of(), report.unsafeSearchPaths());
        assertEquals(List.of(), report.missingCatalogPins());
        assertEquals(List.of(), report.procedureKindMismatches());
        assertEquals(0, report.checkedRoutines());
    }
}
