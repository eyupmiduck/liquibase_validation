package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link LiquibaseSession} against a real PostgreSQL: apply, list,
 * roll back and re-apply a reversible changelog, surface a rollback failure, and
 * surface a non-idempotent second application.
 */
class LiquibaseSessionTest extends PostgresTestBase {

    private static final String POSITIVE = "testing/rehearsal/positive/db.changelog-master.xml";
    private static final String ROLLBACK_FAILURE =
            "testing/rehearsal/rollback-failure/db.changelog-master.xml";
    private static final String NON_IDEMPOTENT = "testing/rehearsal/non-idempotent/db.changelog-master.xml";

    private String scratchDatabase;

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private String newScratchDatabase() throws SQLException {
        scratchDatabase = "rehearsal_" + Integer.toUnsignedString(UUID.randomUUID().hashCode(), 16);
        createDatabase(scratchDatabase);
        prepareScratchDatabase(scratchDatabase, LIQUIBASE_SCHEMA);
        return scratchDatabase;
    }

    @AfterEach
    void dropScratchDatabase() throws SQLException {
        if (scratchDatabase != null) {
            dropDatabase(scratchDatabase);
        }
    }

    @Test
    void appliesRollsBackAndReapplies() throws SQLException {
        newScratchDatabase();
        try (Connection owner = openOwnerConnection(scratchDatabase)) {
            try (LiquibaseSession session = LiquibaseSession.open(owner, trackingSettings(), POSITIVE,
                    new liquibase.resource.ClassLoaderResourceAccessor())) {
                assertEquals(List.of("001-create-probe"), session.apply());
                assertTrue(tableExists(owner, "public.rehearsal_probe"));
                assertTrue(session.unrunChangeSets().isEmpty());

                assertEquals(List.of("001-create-probe"), session.rollbackAll());
                assertFalse(tableExists(owner, "public.rehearsal_probe"));
                assertEquals(List.of("001-create-probe"), session.unrunChangeSets());

                assertEquals(List.of("001-create-probe"), session.apply());
                assertTrue(tableExists(owner, "public.rehearsal_probe"));
            }
        }
    }

    @Test
    void aSecondApplicationOfANormalChangelogIsANoOp() throws SQLException {
        newScratchDatabase();
        try (Connection owner = openOwnerConnection(scratchDatabase);
             LiquibaseSession session = LiquibaseSession.open(owner, trackingSettings(), POSITIVE)) {
            assertEquals(List.of("001-create-probe"), session.apply());
            assertTrue(session.apply().isEmpty(), "a recorded changeset should not run twice");
        }
    }

    @Test
    void surfacesARollbackFailure() throws SQLException {
        newScratchDatabase();
        try (Connection owner = openOwnerConnection(scratchDatabase);
             LiquibaseSession session = LiquibaseSession.open(owner, trackingSettings(), ROLLBACK_FAILURE)) {
            assertEquals(List.of("001-create-probe"), session.apply());

            IllegalStateException failure = assertThrows(IllegalStateException.class, session::rollbackAll);
            assertTrue(failure.getMessage().contains("could not roll back"), failure.getMessage());
            assertTrue(failure.getMessage().contains("rehearsal_missing"), failure.getMessage());
        }
    }

    @Test
    void surfacesANonIdempotentReapply() throws SQLException {
        newScratchDatabase();
        try (Connection owner = openOwnerConnection(scratchDatabase);
             LiquibaseSession session = LiquibaseSession.open(owner, trackingSettings(), NON_IDEMPOTENT)) {
            assertEquals(List.of("001-run-always"), session.apply());

            IllegalStateException failure = assertThrows(IllegalStateException.class, session::apply);
            assertTrue(failure.getMessage().contains("could not apply"), failure.getMessage());
            assertTrue(failure.getMessage().contains("rehearsal_always"), failure.getMessage());
        }
    }
}
