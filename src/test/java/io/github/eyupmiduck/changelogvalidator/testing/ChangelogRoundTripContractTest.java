package io.github.eyupmiduck.changelogvalidator.testing;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shared {@link ChangelogRoundTripContract} against the library's
 * fixture changelog, so the inherited round-trip logic, the fixture rollback and
 * the profile's baseline changelog are exercised here too.
 */
class ChangelogRoundTripContractTest extends ChangelogRoundTripContract {

    @Override
    protected ProjectProfile projectProfile() {
        return RehearsalFixture.PROFILE;
    }

    /**
     * The installed baseline schema survives the rollback, while the changelog's
     * own schema and the fixture schema are gone.
     */
    @Override
    protected void assertRolledBackBaseline(Map<String, String> baseline, Map<String, String> rolledBack) {
        super.assertRolledBackBaseline(baseline, rolledBack);
        assertTrue(rolledBack.containsKey("schema/roundtrip_baseline"),
                "the baseline changelog's schema should survive the rollback");
        assertFalse(rolledBack.containsKey("schema/roundtrip_app"),
                "the changelog's schema should be gone after rollback");
        assertFalse(rolledBack.containsKey("schema/roundtrip_fixtures"),
                "the fixture changelog's schema should be gone after rollback");
    }
}
