package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;

/**
 * Contract for a consuming project's {@code plpgsql_check} test: every routine
 * in the given schemas passes the analyser, with no unexpected finding and no
 * stale allow-list entry.
 *
 * <p>A consumer extends this class and returns its one
 * {@link ProjectProfile} from {@link #projectProfile()}; the configuration,
 * routine schemas and allow-list resource come from that profile. Its image and
 * extension hook must install {@code plpgsql_check} into the template database
 * (the library's own build does not run this contract, because its stock
 * PostgreSQL image does not ship the extension).
 */
public abstract class PlpgsqlCheckContract extends PostgresTestBase {

    /**
     * The classpath resource holding the accepted findings, from the profile.
     *
     * @return the allow-list resource
     */
    protected String whitelistResource() {
        return projectProfile().plpgsqlWhitelistResource();
    }

    /**
     * Every {@code plpgsql_check} finding matches a whitelist entry, and every
     * whitelist entry matches a finding (so the whitelist cannot go stale).
     *
     * @throws Exception if the analyser cannot run
     */
    @Test
    void routinesPassPlpgsqlCheck() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            RoutineAssertions.assertRoutinesPassPlpgsqlCheck(owner, routineSchemas(), whitelistResource());
        }
    }
}
