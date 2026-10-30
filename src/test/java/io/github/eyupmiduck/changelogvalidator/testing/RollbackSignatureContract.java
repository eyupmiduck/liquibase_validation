package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collection;

/**
 * Contract for a consuming project's routine/rollback signature test: the
 * rollback files under the given classpath directories drop exactly the
 * routines in the given schemas.
 *
 * <p>A consumer extends this class, supplies its
 * {@link #configuration() configuration} (the contract, not its own test base,
 * is the direct superclass), the routine schemas and the rollback directories
 * to read.
 */
public abstract class RollbackSignatureContract extends PostgresTestBase {

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
     * The schemas whose routines are checked.
     *
     * @return the routine schemas
     */
    protected abstract Collection<String> routineSchemas();

    /**
     * The classpath directories holding the rollback SQL files.
     *
     * @return the rollback directories
     */
    protected abstract Collection<String> rollbackDirectories();

    /**
     * The rollback DROP signatures cover exactly the routines: no routine lacks
     * a rollback, no rollback is stale, and no signature is dropped twice.
     *
     * @throws Exception if the rollback files or the catalog cannot be read
     */
    @Test
    void rollbackDropsMatchTheRoutines() throws Exception {
        try (Connection connection = openTestConnection()) {
            RoutineAssertions.assertRollbackDropsMatchRoutines(
                    connection, routineSchemas(), rollbackDirectories());
        }
    }
}
