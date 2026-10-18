package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link CatalogSnapshot} against a real PostgreSQL: it captures the
 * expected dictionary objects with their definition and comment, leaves
 * extension-owned objects out, and dumps a capture to a file tree.
 *
 * <p>The tests drive their own scratch database, created as the container
 * superuser, so the container's migrated template is untouched.
 */
class CatalogSnapshotTest extends PostgresTestBase {

    private static final String FIXTURE = "fixture";
    private static final List<String> FIXTURE_SQL = List.of(
            "CREATE SCHEMA " + FIXTURE,
            "CREATE DOMAIN " + FIXTURE + ".positive_int AS integer CHECK (VALUE > 0)",
            "CREATE TYPE " + FIXTURE + ".color AS ENUM ('red', 'green')",
            "CREATE TYPE " + FIXTURE + ".pair AS (a integer, b text)",
            "CREATE TABLE " + FIXTURE + ".widget ("
                    + "id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                    + "name text NOT NULL DEFAULT 'x', "
                    + "shade " + FIXTURE + ".color, "
                    + "amount " + FIXTURE + ".positive_int)",
            "CREATE INDEX widget_name_idx ON " + FIXTURE + ".widget (name)",
            "CREATE SEQUENCE " + FIXTURE + ".widget_seq",
            "CREATE FUNCTION " + FIXTURE + ".bump(i integer) RETURNS integer "
                    + "LANGUAGE sql IMMUTABLE AS 'SELECT i + 1'",
            "CREATE FUNCTION " + FIXTURE + ".bump(s text) RETURNS text "
                    + "LANGUAGE sql IMMUTABLE AS 'SELECT s || ''!'''",
            "CREATE FUNCTION " + FIXTURE + ".touch() RETURNS trigger LANGUAGE plpgsql AS "
                    + "$$ BEGIN NEW.name := coalesce(NEW.name, 't'); RETURN NEW; END $$",
            "CREATE TRIGGER widget_touch BEFORE UPDATE ON " + FIXTURE + ".widget "
                    + "FOR EACH ROW EXECUTE FUNCTION " + FIXTURE + ".touch()",
            "COMMENT ON TABLE " + FIXTURE + ".widget IS 'fixture table'",
            "COMMENT ON COLUMN " + FIXTURE + ".widget.name IS 'fixture column'");
    private Connection scratch;
    private String scratchDatabase;

    /**
     * Creates a scratch database with a fixture schema and its objects.
     */
    @BeforeEach
    void createScratchDatabase() throws SQLException {
        scratchDatabase = "catalog_" + Integer.toUnsignedString(UUID.randomUUID().hashCode(), 16);
        createDatabase(scratchDatabase);
        scratch = openAdminConnection(scratchDatabase);
        try (Statement statement = scratch.createStatement()) {
            for (String sql : FIXTURE_SQL) {
                statement.execute(sql);
            }
        }
    }

    /**
     * Drops the scratch database.
     */
    @AfterEach
    void dropScratchDatabase() throws SQLException {
        if (scratch != null) {
            scratch.close();
        }
        if (scratchDatabase != null) {
            dropDatabase(scratchDatabase);
        }
    }

    /**
     * Every fixture object kind is captured under its {@code kind/name} key.
     */
    @Test
    void capturesEachObjectKind() {
        Map<String, String> snapshot = CatalogSnapshot.capture(scratch, List.of(FIXTURE));

        assertTrue(snapshot.containsKey("schema/" + FIXTURE), snapshot.keySet().toString());
        assertTrue(snapshot.containsKey("relation/fixture.widget"));
        assertTrue(snapshot.containsKey("column/fixture.widget.name"));
        assertTrue(snapshot.containsKey("constraint/fixture.widget.widget_pkey"));
        assertTrue(snapshot.containsKey("index/fixture.widget_name_idx"));
        assertTrue(snapshot.containsKey("sequence/fixture.widget_seq"));
        assertTrue(snapshot.containsKey("domain/fixture.positive_int"));
        assertTrue(snapshot.containsKey("type/fixture.color"));
        assertTrue(snapshot.containsKey("type/fixture.pair"));
        assertTrue(snapshot.containsKey("function/fixture.bump(integer)"));
        assertTrue(snapshot.containsKey("function/fixture.bump(text)"));
        assertTrue(snapshot.containsKey("function/fixture.touch()"));
        assertTrue(snapshot.containsKey("trigger/fixture.widget.widget_touch"));
    }

    /**
     * An overloaded routine gets one entry per signature, keyed by argument
     * types, so the two overloads cannot collide or depend on their order.
     */
    @Test
    void keysOverloadsByArgumentTypes() {
        Map<String, String> snapshot = CatalogSnapshot.capture(scratch, List.of(FIXTURE));

        assertEquals(2, snapshot.keySet().stream()
                .filter(key -> key.startsWith("function/fixture.bump(")).count());
        assertFalse(snapshot.get("function/fixture.bump(integer)")
                .equals(snapshot.get("function/fixture.bump(text)")));
    }

    /**
     * A definition and a comment are part of the captured document.
     */
    @Test
    void capturesDefinitionsAndComments() {
        Map<String, String> snapshot = CatalogSnapshot.capture(scratch, List.of(FIXTURE));

        assertTrue(snapshot.get("relation/fixture.widget").contains("fixture table"));
        assertTrue(snapshot.get("column/fixture.widget.name").contains("fixture column"));
        assertTrue(snapshot.get("function/fixture.bump(integer)").contains("integer"));
        assertTrue(snapshot.get("constraint/fixture.widget.widget_pkey").contains("PRIMARY KEY"));
    }

    /**
     * Extension-owned objects (for example {@code citext}'s functions) are not
     * captured, even though they live in the captured {@code public} schema.
     */
    @Test
    void excludesExtensionOwnedObjects() throws SQLException {
        try (Statement statement = scratch.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS citext");
        }

        Map<String, String> snapshot = CatalogSnapshot.capture(scratch, List.of(FIXTURE, "public"));

        assertFalse(snapshot.keySet().stream().anyMatch(key -> key.startsWith("function/public.citext")),
                () -> "extension-owned functions should be excluded: " + snapshot.keySet());
        assertTrue(snapshot.containsKey("extension/citext"), snapshot.keySet().toString());
    }

    /**
     * The capture is dumped one file per object, so two captures can be compared
     * with a directory diff.
     */
    @Test
    void dumpsOneFilePerObject(@TempDir Path root) throws IOException {
        Map<String, String> snapshot = CatalogSnapshot.capture(scratch, List.of(FIXTURE));

        CatalogSnapshot.dump(snapshot, root);

        Path relation = root.resolve("relation/fixture.widget.json");
        assertTrue(Files.isRegularFile(relation), relation.toString());
        assertEquals(snapshot.get("relation/fixture.widget"), Files.readString(relation).strip());
        assertTrue(Files.isRegularFile(root.resolve("function/fixture.bump/(integer).json")));
    }
}
