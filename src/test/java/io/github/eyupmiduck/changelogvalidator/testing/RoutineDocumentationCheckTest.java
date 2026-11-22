package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the {@link RoutineDocumentationContract} report and parser directly:
 * missing and stale sections, argument mismatches, abbreviations, duplicate
 * sections and the heading/argument parser.
 */
class RoutineDocumentationCheckTest extends PostgresTestBase {

    private static final String SCHEMA = "documentation_check";
    private static final String README = "README.md";

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

    private static RoutineDocumentationContract.DocumentedRoutine documented(String name, String arguments) {
        return new RoutineDocumentationContract.DocumentedRoutine(name, arguments, README, 1);
    }

    private RoutineDocumentationContract.Report inspect(
            Collection<RoutineDocumentationContract.DocumentedRoutine> documented) throws Exception {
        try (Connection owner = openOwnerConnection()) {
            return RoutineDocumentationContract.inspect(owner, List.of(SCHEMA), documented);
        }
    }

    /**
     * Matching headings produce no report entries.
     */
    @Test
    void acceptsMatchingDocumentation() throws Exception {
        RoutineDocumentationContract.Report report = inspect(List.of(
                documented(SCHEMA + ".add", "i_a, i_b"),
                documented(SCHEMA + ".no_args", "")));

        assertEquals(List.of(), report.missing());
        assertEquals(List.of(), report.stale());
        assertEquals(List.of(), report.mismatched());
        assertEquals(2, report.checkedRoutines());
    }

    /**
     * A routine with no heading is missing.
     */
    @Test
    void reportsAMissingRoutine() throws Exception {
        RoutineDocumentationContract.Report report = inspect(List.of(
                documented(SCHEMA + ".no_args", "")));

        assertEquals(List.of(SCHEMA + ".add"), report.missing());
        assertEquals(List.of(), report.stale());
        assertEquals(List.of(), report.mismatched());
    }

    /**
     * A heading for a routine that no longer exists is stale.
     */
    @Test
    void reportsAStaleRoutine() throws Exception {
        RoutineDocumentationContract.Report report = inspect(List.of(
                documented(SCHEMA + ".add", "i_a, i_b"),
                documented(SCHEMA + ".no_args", ""),
                documented(SCHEMA + ".gone", "")));

        assertEquals(List.of(), report.missing());
        assertEquals(1, report.stale().size(), report.stale().toString());
        assertTrue(report.stale().getFirst().startsWith(SCHEMA + ".gone"), report.stale().getFirst());
        assertEquals(List.of(), report.mismatched());
    }

    /**
     * A heading with the wrong arity is a mismatch.
     */
    @Test
    void reportsAnArityMismatch() throws Exception {
        RoutineDocumentationContract.Report report = inspect(List.of(
                documented(SCHEMA + ".add", "i_a"),
                documented(SCHEMA + ".no_args", "")));

        assertEquals(List.of(), report.missing());
        assertEquals(List.of(), report.stale());
        assertEquals(1, report.mismatched().size(), report.mismatched().toString());
        assertTrue(report.mismatched().getFirst().contains("README has (i_a)"),
                report.mismatched().getFirst());
    }

    /**
     * A heading with the wrong argument names is a mismatch.
     */
    @Test
    void reportsAnArgumentNameMismatch() throws Exception {
        RoutineDocumentationContract.Report report = inspect(List.of(
                documented(SCHEMA + ".add", "i_x, i_y"),
                documented(SCHEMA + ".no_args", "")));

        assertEquals(List.of(), report.missing());
        assertEquals(List.of(), report.stale());
        assertEquals(1, report.mismatched().size(), report.mismatched().toString());
        assertTrue(report.mismatched().getFirst().contains("catalog names [i_a, i_b]"),
                report.mismatched().getFirst());
    }

    /**
     * An abbreviated heading and a heading with optional brackets are both
     * accepted.
     */
    @Test
    void acceptsAbbreviatedAndOptionalArguments() throws Exception {
        RoutineDocumentationContract.Report abbreviated = inspect(List.of(
                documented(SCHEMA + ".add", "..."),
                documented(SCHEMA + ".no_args", "")));
        assertEquals(List.of(), abbreviated.mismatched());

        RoutineDocumentationContract.Report optional = inspect(List.of(
                documented(SCHEMA + ".add", "i_a [, i_b]"),
                documented(SCHEMA + ".no_args", "")));
        assertEquals(List.of(), optional.mismatched());
    }

    /**
     * Documenting the same routine twice is a mismatch.
     */
    @Test
    void reportsDuplicateDocumentation() throws Exception {
        RoutineDocumentationContract.Report report = inspect(List.of(
                documented(SCHEMA + ".add", "i_a, i_b"),
                documented(SCHEMA + ".add", "i_a, i_b"),
                documented(SCHEMA + ".no_args", "")));

        assertEquals(List.of(), report.missing());
        assertEquals(List.of(), report.stale());
        assertEquals(1, report.mismatched().size(), report.mismatched().toString());
        assertTrue(report.mismatched().getFirst().contains("documented more than once"),
                report.mismatched().getFirst());
    }

    /**
     * A schema with no routines reports a zero count so the contract can fail
     * non-vacuously.
     */
    @Test
    void reportsNoRoutinesForAnEmptySchema() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            RoutineDocumentationContract.Report report =
                    RoutineDocumentationContract.inspect(owner, List.of(PUBLIC_SCHEMA), List.of());

            assertEquals(List.of(), report.missing());
            assertEquals(List.of(), report.stale());
            assertEquals(List.of(), report.mismatched());
            assertEquals(0, report.checkedRoutines());
        }
    }

    /**
     * The default parser reads the Markdown heading name, raw argument list and
     * line number.
     */
    @Test
    void parsesMarkdownHeadings() {
        List<RoutineDocumentationContract.DocumentedRoutine> documented =
                new Parser().documentedRoutines(README, """
                        # Routines

                        ### `app.add(i_a, i_b)`

                        ### `app.no_args()`

                        ### not a routine heading
                        """);

        assertEquals(2, documented.size(), documented.toString());
        assertEquals("app.add", documented.get(0).name());
        assertEquals("i_a, i_b", documented.get(0).arguments());
        assertEquals(3, documented.get(0).line());
        assertEquals("app.no_args", documented.get(1).name());
        assertEquals("", documented.get(1).arguments());
        assertEquals(5, documented.get(1).line());
    }

    /**
     * The argument parser handles empty, abbreviated, optional and malformed
     * lists.
     */
    @Test
    void parsesArgumentLists() {
        assertEquals(List.of(), RoutineDocumentationContract.parseArguments(""));
        assertNull(RoutineDocumentationContract.parseArguments("..."));
        assertEquals(List.of("i_a", "i_b"), RoutineDocumentationContract.parseArguments("i_a, i_b"));
        assertEquals(List.of("i_values", "i_prefix"),
                RoutineDocumentationContract.parseArguments("i_values [, i_prefix]"));
        assertNull(RoutineDocumentationContract.parseArguments("i_a,,i_b"));
    }

    /**
     * A documented-routine record requires a one-based line.
     */
    @Test
    void validatesDocumentedRoutine() {
        assertThrows(IllegalArgumentException.class,
                () -> new RoutineDocumentationContract.DocumentedRoutine("app.foo", "", README, 0));
    }

    /**
     * A concrete parser for the non-container assertions; it has no test methods
     * and never opens the database.
     */
    private static final class Parser extends RoutineDocumentationContract {

        @Override
        protected Collection<String> documentedSchemas() {
            return List.of();
        }

        @Override
        protected Collection<String> readmeResources() {
            return List.of();
        }
    }
}
