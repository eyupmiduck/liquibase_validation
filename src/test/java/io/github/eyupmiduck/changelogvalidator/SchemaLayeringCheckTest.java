package io.github.eyupmiduck.changelogvalidator;

import io.github.eyupmiduck.changelogvalidator.testing.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link SchemaLayeringCheck} against a live catalog: a lower schema may
 * reference a lower one, but not a higher one, and the allow-list and
 * non-vacuity reporting behave as documented.
 */
class SchemaLayeringCheckTest extends PostgresTestBase {

    private static final String TOP = "layering_top";
    private static final String MID = "layering_mid";
    private static final String BOTTOM = "layering_bottom";
    private static final String EMPTY = "layering_empty";

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Recreates four schemas with a compliant three-layer dependency:
     * {@code top -> mid -> bottom}, and an empty schema for the non-vacuity
     * check.
     *
     * @throws Exception if the fixture cannot be created
     */
    @BeforeEach
    void createFixture() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            for (String schema : List.of(TOP, MID, BOTTOM, EMPTY)) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                statement.execute("CREATE SCHEMA " + schema + " AUTHORIZATION "
                        + quote(configuration().ownerUser()));
            }
        }
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE DOMAIN " + TOP + ".top_domain AS text");
            statement.execute("CREATE FUNCTION " + BOTTOM + ".bottom_fn() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT 1'");
            statement.execute("CREATE FUNCTION " + MID + ".mid_fn() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT " + BOTTOM + ".bottom_fn()'");
            statement.execute("CREATE FUNCTION " + TOP + ".top_fn() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT " + MID + ".mid_fn()'");
        }
    }

    private SchemaLayeringCheck.Report inspect(Set<SchemaLayeringCheck.Reference> allowed) throws SQLException {
        try (Connection owner = openOwnerConnection()) {
            return SchemaLayeringCheck.inspect(owner, List.of(TOP, MID, BOTTOM), allowed);
        }
    }

    /**
     * A lower schema may reference a lower one; the fixture has no violation.
     */
    @Test
    void acceptsReferencesToLowerSchemas() throws SQLException {
        SchemaLayeringCheck.Report report = inspect(Set.of());

        assertEquals(List.of(), report.unexpected(), report.unexpected().toString());
        assertEquals(List.of(), report.staleAllowList(), report.staleAllowList().toString());
        assertEquals(Set.of(), report.emptySchemas());
        assertEquals(3, report.routines());
    }

    /**
     * A body reference from a lower schema to a higher one is reported.
     */
    @Test
    void reportsALowerSchemaThatReferencesAHigherSchema() throws SQLException {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + BOTTOM + ".bad() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT " + TOP + ".top_fn()'");
        }

        SchemaLayeringCheck.Report report = inspect(Set.of());
        assertEquals(List.of(new SchemaLayeringCheck.Reference(BOTTOM, "bad", TOP, "top_fn")),
                report.unexpected());
    }

    /**
     * A signature type from a higher schema is a reference too.
     */
    @Test
    void reportsAHigherSchemaReferenceInASignature() throws SQLException {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + BOTTOM + ".bad_signature(i_value "
                    + TOP + ".top_domain) RETURNS integer LANGUAGE sql AS 'SELECT 1'");
        }

        SchemaLayeringCheck.Report report = inspect(Set.of());
        assertEquals(1, report.unexpected().size(), report.unexpected().toString());
        SchemaLayeringCheck.Reference reference = report.unexpected().getFirst();
        assertEquals(BOTTOM, reference.fromSchema());
        assertEquals("bad_signature", reference.fromRoutine());
        assertEquals(TOP, reference.toSchema());
        assertEquals("top_domain", reference.toObject());
    }

    /**
     * A schema name in a string literal or a comment is not a reference.
     */
    @Test
    void ignoresSchemaMentionsInStringsAndComments() throws SQLException {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + BOTTOM + ".harmless() RETURNS text"
                    + " LANGUAGE sql AS $$ SELECT '" + TOP + ".top_fn'::text /* "
                    + TOP + ".top_fn */ $$");
        }

        SchemaLayeringCheck.Report report = inspect(Set.of());
        assertEquals(List.of(), report.unexpected(), report.unexpected().toString());
    }

    /**
     * A quoted schema and object identifier is still a reference.
     */
    @Test
    void reportsAQuotedHigherSchemaReference() throws SQLException {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + BOTTOM + ".bad_quoted() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT \"" + TOP + "\".\"top_fn\"()'");
        }

        SchemaLayeringCheck.Report report = inspect(Set.of());
        assertEquals(List.of(new SchemaLayeringCheck.Reference(BOTTOM, "bad_quoted", TOP, "top_fn")),
                report.unexpected());
    }

    /**
     * An allow-list entry suppresses a known exception and is not stale.
     */
    @Test
    void suppressesAnAllowedReference() throws SQLException {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + BOTTOM + ".bad() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT " + TOP + ".top_fn()'");
        }

        SchemaLayeringCheck.Reference allowed =
                new SchemaLayeringCheck.Reference(BOTTOM, "bad", TOP, "top_fn");
        SchemaLayeringCheck.Report report = inspect(Set.of(allowed));
        assertEquals(List.of(), report.unexpected(), report.unexpected().toString());
        assertEquals(List.of(), report.staleAllowList(), report.staleAllowList().toString());
    }

    /**
     * An allow-list entry with no object matches any object in the schema.
     */
    @Test
    void suppressesAnyObjectInAnAllowedSchema() throws SQLException {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + BOTTOM + ".bad() RETURNS integer"
                    + " LANGUAGE sql AS 'SELECT " + TOP + ".top_fn()'");
        }

        SchemaLayeringCheck.Reference allowed =
                new SchemaLayeringCheck.Reference(BOTTOM, "bad", TOP, null);
        SchemaLayeringCheck.Report report = inspect(Set.of(allowed));
        assertEquals(List.of(), report.unexpected(), report.unexpected().toString());
        assertEquals(List.of(), report.staleAllowList(), report.staleAllowList().toString());
    }

    /**
     * An allow-list entry that matches no reference is stale.
     */
    @Test
    void reportsAStaleAllowListEntry() throws SQLException {
        SchemaLayeringCheck.Reference stale =
                new SchemaLayeringCheck.Reference(BOTTOM, "does_not_exist", TOP, "top_fn");
        SchemaLayeringCheck.Report report = inspect(Set.of(stale));

        assertEquals(List.of(stale), report.staleAllowList());
    }

    /**
     * A configured schema with no routine, relation or type is reported, so a
     * misspelled schema cannot pass vacuously.
     */
    @Test
    void reportsAnEmptySchema() throws SQLException {
        try (Connection owner = openOwnerConnection()) {
            SchemaLayeringCheck.Report report = SchemaLayeringCheck.inspect(
                    owner, List.of(TOP, MID, BOTTOM, EMPTY), Set.of());
            assertEquals(Set.of(EMPTY), report.emptySchemas());
        }
    }

    /**
     * A schema with only a relation is not empty, even with no routine.
     */
    @Test
    void acceptsASchemaWithOnlyARelation() throws SQLException {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            statement.execute("CREATE TABLE " + EMPTY + ".empty_table (id integer)");
        }

        try (Connection owner = openOwnerConnection()) {
            SchemaLayeringCheck.Report report = SchemaLayeringCheck.inspect(
                    owner, List.of(TOP, MID, BOTTOM, EMPTY), Set.of());
            assertEquals(Set.of(), report.emptySchemas());
        }
    }

    /**
     * The schema list must not be empty or contain duplicates.
     */
    @Test
    void rejectsAnInvalidSchemaList() throws SQLException {
        try (Connection owner = openOwnerConnection()) {
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaLayeringCheck.inspect(owner, List.of(), Set.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> SchemaLayeringCheck.inspect(owner, List.of(TOP, TOP), Set.of()));
        }
    }

    /**
     * A reference's required values must be non-blank; only the object may be
     * null.
     */
    @Test
    void rejectsAnInvalidReference() {
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaLayeringCheck.Reference("", "routine", "schema", null));
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaLayeringCheck.Reference("from", "", "schema", null));
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaLayeringCheck.Reference("from", "routine", "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new SchemaLayeringCheck.Reference("from", "routine", "schema", " "));
    }

    /**
     * The required arguments are not null.
     */
    @Test
    void rejectsNullArguments() throws SQLException {
        try (Connection owner = openOwnerConnection()) {
            assertThrows(NullPointerException.class,
                    () -> SchemaLayeringCheck.inspect(null, List.of(TOP), Set.of()));
            assertThrows(NullPointerException.class,
                    () -> SchemaLayeringCheck.inspect(owner, null, Set.of()));
            assertThrows(NullPointerException.class,
                    () -> SchemaLayeringCheck.inspect(owner, List.of(TOP), null));
        }
    }
}
