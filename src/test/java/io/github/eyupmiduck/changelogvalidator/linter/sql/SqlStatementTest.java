package io.github.eyupmiduck.changelogvalidator.linter.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies {@link SqlStatement} validates its offset range.
 */
class SqlStatementTest {

    /**
     * A valid, including empty, range is accepted.
     */
    @Test
    void acceptsAValidRange() {
        assertEquals("SELECT 1", new SqlStatement("SELECT 1", 0, 8).text());
        assertEquals("", new SqlStatement("", 0, 0).text());
    }

    /**
     * A negative start or an end before the start is rejected.
     */
    @Test
    void rejectsAnInvalidRange() {
        assertThrows(IllegalArgumentException.class, () -> new SqlStatement("x", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SqlStatement("x", 5, 4));
        assertThrows(NullPointerException.class, () -> new SqlStatement(null, 0, 0));
    }
}
