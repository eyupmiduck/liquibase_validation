package io.github.eyupmiduck.changelogvalidator.linter;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link Finding} and {@link Rule.Violation} validate their one-based
 * positions.
 */
class FindingTest {

    private static final Path FILE = Path.of("/db/x.sql");

    /**
     * A one-based position is accepted.
     */
    @Test
    void acceptsAOneBasedPosition() {
        Finding finding = new Finding("r", Severity.ERROR, "cs", "a", FILE, 3, 7, "m", null);

        assertEquals(3, finding.line());
        assertEquals(7, finding.column());
    }

    /**
     * A zero or negative line/column is rejected instead of producing an invalid
     * SARIF region.
     */
    @Test
    void rejectsAZeroOrNegativePosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new Finding("r", Severity.ERROR, "cs", "a", FILE, 0, 1, "m", null));
        assertThrows(IllegalArgumentException.class,
                () -> new Finding("r", Severity.ERROR, "cs", "a", FILE, 1, 0, "m", null));
        assertThrows(IllegalArgumentException.class,
                () -> new Rule.Violation("m", null, FILE, -1, 1));
    }
}
