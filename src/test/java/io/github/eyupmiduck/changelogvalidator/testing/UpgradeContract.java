package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Contract for a consuming project's previous-release upgrade test: the most
 * recent published release's changelog is applied to a scratch database, then
 * the current changelog, and the upgraded database must be the expected end
 * state.
 *
 * <p>{@link UpgradeTestBase} already supplies the mechanics. This contract adds
 * the assertions every project makes: the published release contributes at
 * least one changeset, and the upgraded database passes
 * {@link #assertUpgradedDatabase()}.
 *
 * <p>A consumer extends this class, returns its one {@link ProjectProfile} from
 * {@link #projectProfile()} (the configuration and managed schemas come from
 * it), supplies the release {@code releaseCoordinates()}, and implements
 * {@link #assertUpgradedDatabase()} with its project-specific checks.
 */
public abstract class UpgradeContract extends UpgradeTestBase {

    /**
     * Asserts the upgraded database is the expected end state: the schemas,
     * routines or other objects the project checks after an upgrade.
     */
    protected abstract void assertUpgradedDatabase();

    /**
     * The published release contributes changesets, the current changelog
     * applies cleanly on top, and the upgraded database passes the project's
     * checks.
     */
    @Test
    void upgradesThePublishedRelease() {
        assertFalse(report().previousRelease().isEmpty(),
                "the published release should apply at least one changeset");
        assertUpgradedDatabase();
    }
}
