package io.github.eyupmiduck.changelogvalidator;

import io.github.eyupmiduck.changelogvalidator.testing.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link CatalogCommentsCheck} against a real PostgreSQL: every
 * inspected object kind, the missing and blank comment paths, the allow-list and
 * its stale-entry rule, and the trigger option.
 */
class CatalogCommentsCheckTest extends PostgresTestBase {

    private static final String SCHEMA = "comments_fixture";

    /**
     * Recreates the fixture schema before each test.
     *
     * @throws Exception if the schema cannot be recreated
     */
    @BeforeEach
    void resetSchema() throws Exception {
        String database = dsl.fetchOne("SELECT pg_catalog.current_database()").get(0, String.class);
        try (Connection admin = openAdminConnection(database);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + SCHEMA + " AUTHORIZATION "
                    + configuration().ownerUser());
        }
    }

    private void execute(String... statements) throws Exception {
        try (Connection owner = openOwnerConnection();
             Statement statement = owner.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private List<String> problems(boolean includeTriggers) throws Exception {
        try (Connection owner = openOwnerConnection()) {
            return CatalogCommentsCheck.findViolations(owner, List.of(SCHEMA),
                            new CatalogCommentsCheck.Options(includeTriggers))
                    .stream()
                    .map(CatalogCommentsCheck.Violation::describe)
                    .toList();
        }
    }

    private void createUncommentedCatalog() throws Exception {
        execute(
                "CREATE TABLE " + SCHEMA + ".widget ("
                        + "id integer NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())",
                "CREATE DOMAIN " + SCHEMA + ".positive_integer AS integer CHECK (VALUE > 0)",
                "CREATE TYPE " + SCHEMA + ".pair AS (left_value integer, right_value text)",
                "CREATE TYPE " + SCHEMA + ".mood AS ENUM ('ok', 'sad')",
                "CREATE TYPE " + SCHEMA + ".price_range AS RANGE (subtype = numeric)",
                "CREATE FUNCTION " + SCHEMA + ".add_one(i_value integer) RETURNS integer"
                        + " LANGUAGE sql AS 'SELECT i_value + 1'",
                "CREATE PROCEDURE " + SCHEMA + ".do_nothing() LANGUAGE plpgsql"
                        + " AS $$ BEGIN NULL; END; $$",
                "CREATE FUNCTION " + SCHEMA + ".set_updated_at() RETURNS trigger"
                        + " LANGUAGE plpgsql AS $$ BEGIN NEW.updated_at := now(); RETURN NEW; END; $$",
                "CREATE TRIGGER widget_set_updated_at BEFORE UPDATE ON " + SCHEMA + ".widget"
                        + " FOR EACH ROW EXECUTE FUNCTION " + SCHEMA + ".set_updated_at()");
    }

    private void createCommentedCatalog() throws Exception {
        execute(
                "COMMENT ON SCHEMA " + SCHEMA + " IS 'fixture schema'",
                "CREATE TABLE " + SCHEMA + ".widget ("
                        + "id integer NOT NULL, updated_at timestamptz NOT NULL DEFAULT now())",
                "COMMENT ON TABLE " + SCHEMA + ".widget IS 'fixture table'",
                "COMMENT ON COLUMN " + SCHEMA + ".widget.id IS 'fixture id'",
                "COMMENT ON COLUMN " + SCHEMA + ".widget.updated_at IS 'fixture updated_at'",
                "CREATE DOMAIN " + SCHEMA + ".positive_integer AS integer CHECK (VALUE > 0)",
                "COMMENT ON DOMAIN " + SCHEMA + ".positive_integer IS 'fixture domain'",
                "CREATE TYPE " + SCHEMA + ".pair AS (left_value integer, right_value text)",
                "COMMENT ON TYPE " + SCHEMA + ".pair IS 'fixture composite'",
                "CREATE TYPE " + SCHEMA + ".mood AS ENUM ('ok', 'sad')",
                "COMMENT ON TYPE " + SCHEMA + ".mood IS 'fixture enum'",
                "CREATE TYPE " + SCHEMA + ".price_range AS RANGE (subtype = numeric)",
                "COMMENT ON TYPE " + SCHEMA + ".price_range IS 'fixture range'",
                "COMMENT ON TYPE " + SCHEMA + ".price_multirange IS 'fixture multirange'",
                "CREATE FUNCTION " + SCHEMA + ".add_one(i_value integer) RETURNS integer"
                        + " LANGUAGE sql AS 'SELECT i_value + 1'",
                "COMMENT ON FUNCTION " + SCHEMA + ".add_one(integer) IS 'fixture function'",
                "CREATE PROCEDURE " + SCHEMA + ".do_nothing() LANGUAGE plpgsql"
                        + " AS $$ BEGIN NULL; END; $$",
                "COMMENT ON PROCEDURE " + SCHEMA + ".do_nothing() IS 'fixture procedure'",
                "CREATE FUNCTION " + SCHEMA + ".set_updated_at() RETURNS trigger"
                        + " LANGUAGE plpgsql AS $$ BEGIN NEW.updated_at := now(); RETURN NEW; END; $$",
                "COMMENT ON FUNCTION " + SCHEMA + ".set_updated_at() IS 'fixture trigger function'",
                "CREATE TRIGGER widget_set_updated_at BEFORE UPDATE ON " + SCHEMA + ".widget"
                        + " FOR EACH ROW EXECUTE FUNCTION " + SCHEMA + ".set_updated_at()",
                "COMMENT ON TRIGGER widget_set_updated_at ON " + SCHEMA + ".widget IS 'fixture trigger'");
    }

    /**
     * An empty or misspelled schema list is rejected instead of returning a
     * vacuous pass.
     */
    @Test
    void rejectsEmptyAndMissingSchemas() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogCommentsCheck.findViolations(owner, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> CatalogCommentsCheck.findViolations(owner, List.of("missing_schema")));
        }
    }

    /**
     * A catalog whose schema and every object kind are commented produces no
     * violations, and the report counts the inspected objects.
     */
    @Test
    void acceptsACommentedCatalog() throws Exception {
        createCommentedCatalog();

        try (Connection owner = openOwnerConnection()) {
            assertEquals(List.of(), problems(true));
            CatalogCommentsCheck.Report report = CatalogCommentsCheck.check(
                    owner, List.of(SCHEMA), List.of());
            assertEquals(List.of(), report.unexpected().stream()
                    .map(CatalogCommentsCheck.Violation::describe).toList());
            assertEquals(List.of(), report.stale().stream()
                    .map(CatalogCommentsCheck.AllowedObject::describe).toList());
            assertTrue(report.checkedObjects() > 0, "the fixture should contain objects");
            assertTrue(report.isEmpty());
        }
    }

    /**
     * Every inspected object kind is reported when its comment is missing.
     */
    @Test
    void reportsMissingSchemaAndEveryObjectKind() throws Exception {
        createUncommentedCatalog();

        List<String> problems = problems(true);

        for (String expected : List.of(
                "schema " + SCHEMA,
                "table " + SCHEMA + ".widget",
                "column " + SCHEMA + ".widget.id",
                "column " + SCHEMA + ".widget.updated_at",
                "domain " + SCHEMA + ".positive_integer",
                "composite type " + SCHEMA + ".pair",
                "enum type " + SCHEMA + ".mood",
                "range type " + SCHEMA + ".price_range",
                "multirange type " + SCHEMA + ".price_multirange",
                "function " + SCHEMA + ".add_one",
                "procedure " + SCHEMA + ".do_nothing",
                "function " + SCHEMA + ".set_updated_at",
                "trigger " + SCHEMA + ".widget.widget_set_updated_at")) {
            assertTrue(problems.stream().anyMatch(problem -> problem.startsWith(expected)),
                    () -> "expected " + expected + " in " + problems);
        }
    }

    /**
     * An aggregate is a user-created {@code pg_proc} row and needs a comment too.
     */
    @Test
    void reportsAnUncommentedAggregate() throws Exception {
        execute("CREATE AGGREGATE " + SCHEMA + ".sum_int(integer) (SFUNC = int4pl, STYPE = integer)");

        List<String> problems = problems(true);
        assertTrue(problems.stream()
                        .anyMatch(problem -> problem.startsWith("function " + SCHEMA + ".sum_int")),
                () -> "expected the aggregate to be reported in " + problems);
    }

    /**
     * A blank comment is as invalid as a missing one.
     */
    @Test
    void reportsBlankComments() throws Exception {
        execute(
                "COMMENT ON SCHEMA " + SCHEMA + " IS '   '",
                "CREATE TABLE " + SCHEMA + ".widget (id integer)",
                "COMMENT ON TABLE " + SCHEMA + ".widget IS '   '",
                "COMMENT ON COLUMN " + SCHEMA + ".widget.id IS '   '");

        List<String> problems = problems(true);

        assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("schema " + SCHEMA)),
                () -> "expected a blank schema comment in " + problems);
        assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("table " + SCHEMA + ".widget")),
                () -> "expected a blank table comment in " + problems);
        assertTrue(problems.stream().anyMatch(problem -> problem.startsWith("column " + SCHEMA + ".widget.id")),
                () -> "expected a blank column comment in " + problems);
    }

    /**
     * A domain over an array is inspected, while its implicit array type is not.
     */
    @Test
    void inspectsADomainOverAnArrayButNotItsArrayType() throws Exception {
        execute(
                "COMMENT ON SCHEMA " + SCHEMA + " IS 'fixture schema'",
                "CREATE DOMAIN " + SCHEMA + ".int_array AS integer[]");

        List<String> problems = problems(true);

        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("domain " + SCHEMA + ".int_array"),
                problems.getFirst());

        execute("COMMENT ON DOMAIN " + SCHEMA + ".int_array IS 'fixture domain'");
        assertEquals(List.of(), problems(true));
    }

    /**
     * A range type's auto-created multirange companion is inspected too.
     */
    @Test
    void inspectsTheMultirangeCompanionOfARangeType() throws Exception {
        execute(
                "COMMENT ON SCHEMA " + SCHEMA + " IS 'fixture schema'",
                "CREATE TYPE " + SCHEMA + ".price_range AS RANGE (subtype = numeric)",
                "COMMENT ON TYPE " + SCHEMA + ".price_range IS 'fixture range'");

        assertEquals(List.of("multirange type " + SCHEMA + ".price_multirange: missing or blank comment"),
                problems(true));

        execute("COMMENT ON TYPE " + SCHEMA + ".price_multirange IS 'fixture multirange'");
        assertEquals(List.of(), problems(true));
    }

    /**
     * An allow-list entry accepts the one missing comment it names.
     */
    @Test
    void acceptsAnAllowListedMissingComment() throws Exception {
        execute(
                "COMMENT ON SCHEMA " + SCHEMA + " IS 'fixture schema'",
                "CREATE TABLE " + SCHEMA + ".widget (id integer NOT NULL)",
                "COMMENT ON COLUMN " + SCHEMA + ".widget.id IS 'fixture id'");

        try (Connection owner = openOwnerConnection()) {
            CatalogCommentsCheck.Report report = CatalogCommentsCheck.check(
                    owner, List.of(SCHEMA),
                    List.of(new CatalogCommentsCheck.AllowedObject(
                            CatalogCommentsCheck.Kind.TABLE, SCHEMA, null, "widget")));

            assertEquals(List.of(), report.unexpected().stream()
                    .map(CatalogCommentsCheck.Violation::describe).toList());
            assertEquals(List.of(), report.stale().stream()
                    .map(CatalogCommentsCheck.AllowedObject::describe).toList());
            assertTrue(report.isEmpty());
        }
    }

    /**
     * An allow-list entry whose object is already commented is stale.
     */
    @Test
    void reportsAStaleAllowListEntry() throws Exception {
        execute(
                "COMMENT ON SCHEMA " + SCHEMA + " IS 'fixture schema'",
                "CREATE TABLE " + SCHEMA + ".widget (id integer NOT NULL)",
                "COMMENT ON TABLE " + SCHEMA + ".widget IS 'fixture table'",
                "COMMENT ON COLUMN " + SCHEMA + ".widget.id IS 'fixture id'");

        try (Connection owner = openOwnerConnection()) {
            CatalogCommentsCheck.Report report = CatalogCommentsCheck.check(
                    owner, List.of(SCHEMA),
                    List.of(new CatalogCommentsCheck.AllowedObject(
                            CatalogCommentsCheck.Kind.TABLE, SCHEMA, null, "widget")));

            assertEquals(List.of(), report.unexpected().stream()
                    .map(CatalogCommentsCheck.Violation::describe).toList());
            assertEquals(List.of("table " + SCHEMA + ".widget"), report.stale().stream()
                    .map(CatalogCommentsCheck.AllowedObject::describe).toList());
            assertFalse(report.isEmpty());
        }
    }

    /**
     * A missing trigger comment is reported only when triggers are included.
     */
    @Test
    void reportsTriggerCommentsOnlyWhenIncluded() throws Exception {
        execute(
                "COMMENT ON SCHEMA " + SCHEMA + " IS 'fixture schema'",
                "CREATE TABLE " + SCHEMA + ".widget (id integer NOT NULL)",
                "COMMENT ON TABLE " + SCHEMA + ".widget IS 'fixture table'",
                "COMMENT ON COLUMN " + SCHEMA + ".widget.id IS 'fixture id'",
                "CREATE FUNCTION " + SCHEMA + ".set_updated_at() RETURNS trigger"
                        + " LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END; $$",
                "COMMENT ON FUNCTION " + SCHEMA + ".set_updated_at() IS 'fixture trigger function'",
                "CREATE TRIGGER widget_set_updated_at BEFORE UPDATE ON " + SCHEMA + ".widget"
                        + " FOR EACH ROW EXECUTE FUNCTION " + SCHEMA + ".set_updated_at()");

        assertEquals(List.of(), problems(false));
        List<String> problems = problems(true);
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.getFirst().startsWith("trigger " + SCHEMA + ".widget.widget_set_updated_at"),
                problems.getFirst());
    }

    /**
     * An allow-list entry matches only when every set selector matches.
     */
    @Test
    void allowListMatchesAllSetSelectors() {
        CatalogCommentsCheck.ObjectRef table = new CatalogCommentsCheck.ObjectRef(
                CatalogCommentsCheck.Kind.TABLE, "app", null, "widget");
        CatalogCommentsCheck.ObjectRef column = new CatalogCommentsCheck.ObjectRef(
                CatalogCommentsCheck.Kind.COLUMN, "app", "widget", "id");

        assertTrue(new CatalogCommentsCheck.AllowedObject(
                CatalogCommentsCheck.Kind.TABLE, "app", null, "widget").matches(table));
        assertTrue(new CatalogCommentsCheck.AllowedObject(null, "app", null, null).matches(table));
        assertFalse(new CatalogCommentsCheck.AllowedObject(
                CatalogCommentsCheck.Kind.COLUMN, "app", null, "widget").matches(table));
        assertFalse(new CatalogCommentsCheck.AllowedObject(
                CatalogCommentsCheck.Kind.TABLE, "other", null, "widget").matches(table));
        assertFalse(new CatalogCommentsCheck.AllowedObject(
                CatalogCommentsCheck.Kind.TABLE, "app", null, "other").matches(table));
        assertTrue(new CatalogCommentsCheck.AllowedObject(
                CatalogCommentsCheck.Kind.COLUMN, "app", "widget", "id").matches(column));
        assertFalse(new CatalogCommentsCheck.AllowedObject(
                CatalogCommentsCheck.Kind.COLUMN, "app", "other", "id").matches(column));

        assertThrows(IllegalArgumentException.class,
                () -> new CatalogCommentsCheck.AllowedObject(null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogCommentsCheck.AllowedObject(
                        CatalogCommentsCheck.Kind.TABLE, " ", null, "widget"));
    }

    /**
     * Object, violation and report descriptions are useful; malformed references
     * are rejected.
     */
    @Test
    void describesObjectsViolationsAndReports() {
        CatalogCommentsCheck.ObjectRef schema = new CatalogCommentsCheck.ObjectRef(
                CatalogCommentsCheck.Kind.SCHEMA, "app", null, null);
        CatalogCommentsCheck.ObjectRef table = new CatalogCommentsCheck.ObjectRef(
                CatalogCommentsCheck.Kind.TABLE, "app", null, "widget");
        CatalogCommentsCheck.ObjectRef column = new CatalogCommentsCheck.ObjectRef(
                CatalogCommentsCheck.Kind.COLUMN, "app", "widget", "id");
        CatalogCommentsCheck.ObjectRef function = new CatalogCommentsCheck.ObjectRef(
                CatalogCommentsCheck.Kind.FUNCTION, "app", null, "add_one");
        CatalogCommentsCheck.ObjectRef trigger = new CatalogCommentsCheck.ObjectRef(
                CatalogCommentsCheck.Kind.TRIGGER, "app", "widget", "widget_trg");

        assertEquals("schema app", schema.describe());
        assertEquals("table app.widget", table.describe());
        assertEquals("column app.widget.id", column.describe());
        assertEquals("function app.add_one", function.describe());
        assertEquals("trigger app.widget.widget_trg", trigger.describe());
        assertEquals("table app.widget: missing or blank comment",
                new CatalogCommentsCheck.Violation(table, "missing or blank comment").describe());
        assertTrue(new CatalogCommentsCheck.Report(List.of(), List.of(), 1).isEmpty());
        assertFalse(new CatalogCommentsCheck.Report(
                List.of(new CatalogCommentsCheck.Violation(table, "missing")), List.of(), 1).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new CatalogCommentsCheck.ObjectRef(
                CatalogCommentsCheck.Kind.SCHEMA, "app", null, "name"));
        assertThrows(IllegalArgumentException.class, () -> new CatalogCommentsCheck.ObjectRef(
                CatalogCommentsCheck.Kind.TABLE, "app", "parent", "widget"));
        assertThrows(IllegalArgumentException.class, () -> new CatalogCommentsCheck.ObjectRef(
                CatalogCommentsCheck.Kind.COLUMN, "app", null, "id"));
        assertThrows(IllegalArgumentException.class, () -> new CatalogCommentsCheck.Violation(table, " "));
        assertThrows(IllegalArgumentException.class, () -> new CatalogCommentsCheck.Report(
                List.of(), List.of(), -1));
    }

    /**
     * Null arguments fail fast instead of silently skipping the check.
     */
    @Test
    void rejectsNullArguments() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            assertThrows(NullPointerException.class,
                    () -> CatalogCommentsCheck.findViolations(owner, null));
            assertThrows(NullPointerException.class,
                    () -> CatalogCommentsCheck.findViolations(owner, List.of(SCHEMA), null));
            assertThrows(NullPointerException.class,
                    () -> CatalogCommentsCheck.check(owner, List.of(SCHEMA), null));
            assertThrows(NullPointerException.class,
                    () -> CatalogCommentsCheck.check(owner, List.of(SCHEMA),
                            List.of((CatalogCommentsCheck.AllowedObject) null)));
        }
    }
}
