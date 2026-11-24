package io.github.eyupmiduck.changelogvalidator.linter.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
     * is a data error, not a null pointer. A REGEXP_REPLACE pattern is compiled
     * eagerly so an invalid regular expression fails when the changelog is read.
     */
    @Test
    void allowsAnEmptyWithAndRejectsAMissingOne() {
        SqlModification empty = new SqlModification(SqlModification.Kind.REPLACE, "x", "", false, null);
        assertTrue(empty.with().isEmpty());

        assertThrows(IllegalArgumentException.class,
                () -> new SqlModification(SqlModification.Kind.REPLACE, "x", null, false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new SqlModification(SqlModification.Kind.APPEND, "x", "ignored", false, null));
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> new SqlModification(SqlModification.Kind.REGEXP_REPLACE, "[[", "", false, null));
        assertTrue(invalid.getMessage().contains("invalid regular expression"));
    }

    /**
     * A malformed REGEXP_REPLACE replacement fails when the changelog is read
     * instead of escaping as a raw runtime exception during linting.
     */
    @Test
    void rejectsAMalformedRegExpReplacement() {
        IllegalArgumentException badGroup = assertThrows(IllegalArgumentException.class,
                () -> new SqlModification(SqlModification.Kind.REGEXP_REPLACE, "a(\\d)", "$x", false, null));
        assertTrue(badGroup.getMessage().contains("invalid replacement"), badGroup.getMessage());

        IllegalArgumentException dangling = assertThrows(IllegalArgumentException.class,
                () -> new SqlModification(SqlModification.Kind.REGEXP_REPLACE, "a(\\d)", "\\", false, null));
        assertTrue(dangling.getMessage().contains("invalid replacement"), dangling.getMessage());
    }

    /**
     * {@code !all} excludes every database, just as {@code none} does.
     */
    @Test
    void treatsNotAllAsAnExclusionOfEverything() {
        assertFalse(append("!all").appliesToPostgres());
        assertFalse(append("!all,postgresql").appliesToPostgres());
    }
}
