package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link MavenVersion}'s ordering and snapshot detection, the rules
 * {@link PublishedRelease} uses to pick the release below the current version.
 */
class MavenVersionTest {

    /**
     * Numeric segments compare numerically, and a missing trailing segment is
     * zero.
     */
    @Test
    void comparesNumericSegments() {
        assertLess("1.5.1", "1.5.2");
        assertLess("1.5", "1.5.1");
        assertLess("1.9.0", "1.10.0");
        assertLess("1.9.9", "2.0.0");
        assertEquals(0, MavenVersion.compare("1.5.0", "1.5.0"));
    }

    /**
     * A qualifier sorts below the release: a snapshot precedes its release.
     */
    @Test
    void ordersQualifiersBelowReleases() {
        assertLess("1.5.2-SNAPSHOT", "1.5.2");
        assertLess("1.5.2-RC1", "1.5.2");
        assertLess("1.5.2-rc1", "1.5.2");
    }

    /**
     * Snapshot detection is case-insensitive and recognises common forms.
     */
    @Test
    void detectsSnapshots() {
        assertTrue(MavenVersion.isSnapshot("1.5.2-SNAPSHOT"));
        assertTrue(MavenVersion.isSnapshot("1.5.2-snapshot"));
        assertTrue(MavenVersion.isSnapshot("1.5.2.SNAPSHOT"));
        assertFalse(MavenVersion.isSnapshot("1.5.2"));
        assertFalse(MavenVersion.isSnapshot("1.5.2-RC1"));
    }

    private static void assertLess(String left, String right) {
        assertTrue(MavenVersion.compare(left, right) < 0,
                () -> left + " should sort below " + right);
        assertTrue(MavenVersion.compare(right, left) > 0,
                () -> right + " should sort above " + left);
    }
}
