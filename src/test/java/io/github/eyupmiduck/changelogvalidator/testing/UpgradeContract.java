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
 * <p>A consumer extends this class, supplies its
 * {@link #configuration() configuration} (the contract, not its own test base,
 * is the direct superclass), the release {@code releaseCoordinates()} and
 * {@code managedSchemas()}, and implements {@link #assertUpgradedDatabase()}
 * with its project-specific checks.
 */
public abstract class UpgradeContract extends UpgradeTestBase {

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
