package io.github.eyupmiduck.changelogvalidator.linter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link Severity} parsing and ordering.
 */
class SeverityTest {

    /**
     * A known name is parsed case-insensitively.
     */
    @Test
    void parsesKnownNamesIgnoringCase() {
        assertEquals(Severity.ERROR, Severity.from("ERROR"));
        assertEquals(Severity.WARNING, Severity.from(" warning "));
        assertEquals(Severity.INFO, Severity.from("Info"));
    }

    /**
     * A null, blank or unknown value is an illegal argument, not a null pointer.
     */
    @Test
    void rejectsNullAndUnknownWithAnIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> Severity.from(null));
        assertThrows(IllegalArgumentException.class, () -> Severity.from(""));
        assertThrows(IllegalArgumentException.class, () -> Severity.from("loud"));
    }

    /**
     * Ordering uses an explicit level and rejects a null threshold.
     */
    @Test
    void ordersByAnExplicitLevel() {
        assertTrue(Severity.ERROR.atLeast(Severity.WARNING));
        assertTrue(Severity.WARNING.atLeast(Severity.WARNING));
        assertFalse(Severity.INFO.atLeast(Severity.WARNING));
        assertThrows(NullPointerException.class, () -> Severity.ERROR.atLeast(null));
    }
}
