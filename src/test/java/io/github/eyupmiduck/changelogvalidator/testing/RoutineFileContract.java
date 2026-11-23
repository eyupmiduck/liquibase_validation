package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Contract for a consuming project's routine-file test: every
 * {@code functions/<schema>/<name>.sql}, {@code procedures/<schema>/<name>.sql}
 * and {@code triggers/<schema>/<name>.sql} declares exactly the object its path
 * names, and every matching rollback file drops it.
 *
 * <p>A consumer extends this class, normally without overriding anything, and
 * returns its one {@link ProjectProfile} from {@link #projectProfile()} so the
 * changes directory comes from the same values as the other contracts. Override
 * {@link #changesRoot()} for a one-off layout.
 *
 * <p>The rule is the directory-oriented companion to
 * {@link ChangelogLayoutContract}: layout joins a changeset id to its files,
 * while this one walks the routine trees directly, so it also catches a file no
 * changeset references. A {@code DO $$ ... $$} wrapper around a
 * {@code DROP TRIGGER} is understood, and non-SQL files such as a directory
 * README are ignored.
 */
public abstract class RoutineFileContract {

    /**
     * The project profile, used for the changes directory.
     *
     * @return the project profile
     */
    protected ProjectProfile projectProfile() {
        return ProjectProfile.DEFAULT;
    }

    /**
     * The changes directory holding the routine and trigger trees.
     *
     * @return the changes directory
     */
    protected Path changesRoot() {
        return projectProfile().changesRoot();
    }

    /**
     * The routine and trigger files declare the objects their paths name, and
     * the rollback files drop them.
     *
     * @throws IOException if a SQL file cannot be read
     */
    @Test
    void routineAndTriggerFilesMatchTheirPaths() throws IOException {
        ChangelogAssertions.assertRoutineFilesFollowConvention(changesRoot());
    }
}
