package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Contract for a consuming project's changelog layout test: every file-based
 * changeset uses the SQL and rollback path its id promises, and every routine or
 * trigger file declares the object its path names.
 *
 * <p>A consumer extends this class, normally without overriding anything, and
 * returns its one {@link ProjectProfile} from {@link #projectProfile()} so the
 * changelog locations come from the same values as the other contracts.
 * Override {@link #rollbackRequired()} when a file-based changeset may
 * intentionally have no rollback file, and {@link #changelogRoot()} or
 * {@link #master()} for a one-off layout.
 *
 * <p>The rule is the living-definition companion to
 * {@link ChangelogNamingContract}: the naming contract checks the shape of an id
 * and a file name, while this one checks that they agree with each other and
 * with the object the SQL declares.
 */
public abstract class ChangelogLayoutContract {

    /**
     * The project profile, used for the changelog locations.
     *
     * @return the project profile
     */
    protected ProjectProfile projectProfile() {
        return ProjectProfile.DEFAULT;
    }

    /**
     * The changelog root directory.
     *
     * @return the changelog root
     */
    protected Path changelogRoot() {
        return projectProfile().changelogRoot();
    }

    /**
     * The master changelog file.
     *
     * @return the master changelog
     */
    protected Path master() {
        return projectProfile().master();
    }

    /**
     * Whether every file-based changeset must have a matching rollback file.
     *
     * @return {@code true} by default
     */
    protected boolean rollbackRequired() {
        return true;
    }

    /**
     * The changeset ids, forward paths, rollback paths and routine/trigger
     * declarations agree.
     *
     * @throws IOException if the changelog graph or a SQL file cannot be read
     */
    @Test
    void layoutFollowsTheConvention() throws IOException {
        ChangelogAssertions.assertLayoutFollowsConvention(changelogRoot(), master(), rollbackRequired());
    }
}
