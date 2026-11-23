package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Contract for a consuming project's routine/rollback signature test: the
 * rollback files under the given classpath directories drop exactly the
 * routines in the given schemas.
 *
 * <p>A consumer extends this class and returns its one
 * {@link ProjectProfile} from {@link #projectProfile()}; the configuration,
 * routine schemas and rollback directories come from that profile. Override
 * {@link #rollbackDirectories()} for a one-off directory list.
 */
public abstract class RollbackSignatureContract extends PostgresTestBase {

    /**
     * The classpath directories holding the rollback SQL files: the profile's
     * routine and trigger rollback directories, in that order.
     *
     * @return the rollback directories
     */
    protected Collection<String> rollbackDirectories() {
        List<String> directories = new ArrayList<>();
        directories.addAll(projectProfile().routineRollbackDirectories());
        directories.addAll(projectProfile().triggerRollbackDirectories());
        return List.copyOf(directories);
    }

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
