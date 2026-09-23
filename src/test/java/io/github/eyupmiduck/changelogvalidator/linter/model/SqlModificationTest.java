package io.github.eyupmiduck.changelogvalidator.linter.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link SqlModification}: Liquibase's dbms filter semantics and the
 * validation of the replacement value.
 */
class SqlModificationTest {

    private static SqlModification append(String dbms) {
        return new SqlModification(SqlModification.Kind.APPEND, " --x", null, false, dbms);
    }

    /**
     * The dbms filter follows Liquibase: unset/all/plain postgresql include, none
     * and !postgresql exclude, and an exclusion-only list includes everything else.
     */
    @Test
    void honoursLiquibaseDbmsFilterSemantics() {
        assertTrue(append(null).appliesToPostgres());
        assertTrue(append("").appliesToPostgres());
        assertTrue(append("postgresql").appliesToPostgres());
        assertTrue(append("all").appliesToPostgres());
        assertTrue(append("all,!h2").appliesToPostgres());
        assertTrue(append("!h2").appliesToPostgres());

        assertFalse(append("oracle").appliesToPostgres());
        assertFalse(append("none").appliesToPostgres());
        assertFalse(append("!postgresql").appliesToPostgres());
        assertFalse(append("all,!postgresql").appliesToPostgres());
    }

    /**
     * An empty replacement is allowed (replacing with nothing), while a missing one
     * is a data error, not a null pointer.
     */
    @Test
    void allowsAnEmptyWithAndRejectsAMissingOne() {
        SqlModification empty = new SqlModification(SqlModification.Kind.REPLACE, "x", "", false, null);
        assertTrue(empty.with().isEmpty());

        assertThrows(IllegalArgumentException.class,
                () -> new SqlModification(SqlModification.Kind.REPLACE, "x", null, false, null));
    }
}
