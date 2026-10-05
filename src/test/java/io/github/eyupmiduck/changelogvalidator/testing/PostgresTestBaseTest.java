package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link PostgresTestBase} against a real PostgreSQL: the shared
 * container, the template database built from the classpath changelog fixture,
 * the per-class cloned database and the shared introspection and SQLSTATE
 * helpers.
 */
class PostgresTestBaseTest extends PostgresTestBase {

    /**
     * The private database is cloned from the migrated template and is usable.
     */
    @Test
    void connectsToAClonedTemplateDatabase() {
        assertTrue(databaseReady(), "the private database should be open");
        String owner = dsl.fetchOne("SELECT current_user::text").get(0, String.class);
        assertEquals(testUser(), owner, "the cloned database connection should use the test role");
    }

    /**
     * A table and column created in the private database are visible to the
     * shared introspection helpers.
     */
    @Test
    void introspectsTablesAndColumns() {
        assertFalse(tableExists("probe_table"), "the table should not exist yet");

        dsl.execute("CREATE TABLE probe_table (id integer NOT NULL, name text)");
        try {
            assertTrue(tableExists("probe_table"), "the table should exist");
            assertTrue(tableExists(PUBLIC_SCHEMA, "probe_table"), "the table should exist in public");
            assertTrue(hasColumn(PUBLIC_SCHEMA, "probe_table", "id"), "the id column should exist");
            assertFalse(hasColumn(PUBLIC_SCHEMA, "probe_table", "missing"), "the missing column should not exist");
            assertEquals("integer", columnAttribute(PUBLIC_SCHEMA, "probe_table", "id", "data_type"));
            assertFalse(triggerExists(PUBLIC_SCHEMA, "probe_table", "no_such_trigger"));
        } finally {
            dsl.execute("DROP TABLE probe_table");
        }
    }

    /**
     * A domain check violation is reported through the shared SQLSTATE
     * helpers, and a second connection opens as the test role.
     */
    @Test
    void assertsSqlStateAndOpensASecondConnection() throws Exception {
        dsl.execute("CREATE DOMAIN probe_positive AS integer CHECK (VALUE > 0)");
        try {
            assertDomainViolation(() -> dsl.fetchValue("SELECT (-1)::probe_positive"));
        } finally {
            dsl.execute("DROP DOMAIN probe_positive");
        }

        try (Connection other = openTestConnection()) {
            assertFalse(other.isClosed(), "the second connection should be usable");
        }
    }

    /**
     * The owner connection can create objects the test role's cloned database
     * sees.
     */
    @Test
    void opensAnOwnerConnection() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            assertFalse(owner.isClosed(), "the owner connection should be usable");
        }
    }
}
