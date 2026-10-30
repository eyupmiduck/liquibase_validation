package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collection;

/**
 * Contract for a consuming project's {@code plpgsql_check} test: every routine
 * in the given schemas passes the analyser, with no unexpected finding and no
 * stale allow-list entry.
 *
 * <p>A consumer extends this class and supplies its
 * {@link #configuration() configuration} and {@link #routineSchemas()}. Its
 * image and extension hook must install {@code plpgsql_check} into the template
 * database (the library's own build does not run this contract, because its
 * stock PostgreSQL image does not ship the extension).
 */
public abstract class PlpgsqlCheckContract extends PostgresTestBase {

    /**
     * The consumer's container and changelog configuration. It must be supplied
     * because the contract, not the consumer's own test base, is the direct
     * superclass of the consumer's test class.
     *
     * @return the configuration
     */
    @Override
    protected abstract Configuration configuration();

    /**
     * The schemas whose routines are analysed.
     *
     * @return the routine schemas
     */
    protected abstract Collection<String> routineSchemas();

    /**
     * The classpath resource holding the accepted findings.
     *
     * @return the allow-list resource
     */
    protected String whitelistResource() {
        return "plpgsql-check-whitelist.yml";
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
