package io.github.eyupmiduck.changelogvalidator.linter.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link SqlSource} enforces its kind-specific shape and reports whether
 * it is inline.
 */
class SqlSourceTest {

    /**
     * Each kind's valid shape is accepted, and {@code isInline} follows the path.
     */
    @Test
    void acceptsValidShapes() {
        assertTrue(new SqlSource(SqlSource.Kind.INLINE_SQL, null, "SELECT 1;", true, ";", false, null).isInline());
        assertFalse(new SqlSource(SqlSource.Kind.SQL_FILE, Path.of("/db/x.sql"), null, true, ";", false, null)
                .isInline());
        assertFalse(new SqlSource(SqlSource.Kind.ROUTINE_BODY, Path.of("/db/f.sql"), null, true, ";", false, null)
                .isInline());
        assertTrue(new SqlSource(SqlSource.Kind.ROUTINE_BODY, null, "BEGIN END", true, ";", false, null)
                .isInline());
    }

    /**
     * A source that does not match its kind fails fast instead of NPE-ing later.
     */
    @Test
    void rejectsInvalidShapes() {
        assertThrows(NullPointerException.class,
                () -> new SqlSource(SqlSource.Kind.INLINE_SQL, null, null, true, ";", false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlSource(SqlSource.Kind.INLINE_SQL, Path.of("/x.sql"), "SELECT 1;", true, ";", false, null));
        assertThrows(NullPointerException.class,
                () -> new SqlSource(SqlSource.Kind.SQL_FILE, null, null, true, ";", false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlSource(SqlSource.Kind.SQL_FILE, Path.of("/x.sql"), "SELECT 1;", true, ";", false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlSource(SqlSource.Kind.ROUTINE_BODY, null, null, true, ";", false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlSource(SqlSource.Kind.ROUTINE_BODY, Path.of("/x.sql"), "BEGIN END", true, ";", false,
                        null));
    }
}
