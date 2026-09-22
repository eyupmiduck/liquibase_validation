package io.github.eyupmiduck.changelogvalidator.linter.sql;

import io.github.eyupmiduck.changelogvalidator.linter.model.Normalisation;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlModification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link SqlNormalizer}: property substitution (repeated and with
 * undefined placeholders preserved) and the {@code <modifySql>} transformations,
 * including declaration order, the rollback filter and the dbms filter.
 */
class SqlNormalizerTest {

    private static SqlModification append(String value) {
        return new SqlModification(SqlModification.Kind.APPEND, value, null, false, null);
    }

    private static void assertTransform(String sql, SqlModification.Kind kind, String value, String with,
                                        String expected) {
        Normalisation normalisation = new Normalisation(Map.of(),
                List.of(new SqlModification(kind, value, with, false, null)));

        assertEquals(expected, SqlNormalizer.normalise(sql, normalisation, false));
    }

    private static Normalisation withDbms(String dbms, String append) {
        return new Normalisation(Map.of(),
                List.of(new SqlModification(SqlModification.Kind.APPEND, append, null, false, dbms)));
    }

    /**
     * A property value is substituted, a value may reference another property,
     * and an undefined placeholder is left untouched.
     */
    @Test
    void substitutesProperties() {
        Normalisation normalisation = new Normalisation(
                Map.of("table", "t", "column", "c", "alias", "${column}"), List.of());

        assertEquals("SELECT c FROM t;",
                SqlNormalizer.normalise("SELECT ${column} FROM ${table};", normalisation, false));
        assertEquals("SELECT c;",
                SqlNormalizer.normalise("SELECT ${alias};", normalisation, false));
        assertEquals("SELECT ${unknown};",
                SqlNormalizer.normalise("SELECT ${unknown};", normalisation, false));
        assertEquals("SELECT 1;", SqlNormalizer.normalise("SELECT 1;", normalisation, false));
    }

    /**
     * The transformations mirror Liquibase: append and prepend concatenate,
     * replace is literal, regExpReplace uses a regular expression, and
     * appendSqlIfNotPresent appends only when the value is absent.
     */
    @Test
    void appliesModifySqlTransformations() {
        assertTransform("SELECT 1;", SqlModification.Kind.APPEND, " --x", null, "SELECT 1; --x");
        assertTransform("SELECT 1;", SqlModification.Kind.PREPEND, "--x ", null, "--x SELECT 1;");
        assertTransform("SELECT 1;", SqlModification.Kind.REPLACE, "1", "2", "SELECT 2;");
        assertTransform("SELECT a1;", SqlModification.Kind.REGEXP_REPLACE, "a(\\d)", "b$1", "SELECT b1;");
        assertTransform("SELECT 1; --x", SqlModification.Kind.APPEND_IF_NOT_PRESENT, " --x", null, "SELECT 1; --x");
        assertTransform("SELECT 1;", SqlModification.Kind.APPEND_IF_NOT_PRESENT, " --x", null, "SELECT 1; --x");
    }

    /**
     * Transformations apply in declaration order.
     */
    @Test
    void appliesModificationsInDeclarationOrder() {
        Normalisation normalisation = new Normalisation(Map.of(), List.of(
                new SqlModification(SqlModification.Kind.APPEND, " B", null, false, null),
                new SqlModification(SqlModification.Kind.APPEND, " C", null, false, null)));

        assertEquals("A B C", SqlNormalizer.normalise("A", normalisation, false));
    }

    /**
     * Rollback SQL only receives transformations declared for rollback.
     */
    @Test
    void honoursApplyToRollback() {
        Normalisation normalisation = new Normalisation(Map.of(), List.of(
                new SqlModification(SqlModification.Kind.APPEND, " --forward", null, false, null),
                new SqlModification(SqlModification.Kind.APPEND, " --rollback", null, true, null)));

        assertEquals("SELECT 1; --forward --rollback", SqlNormalizer.normalise("SELECT 1;", normalisation, false));
        assertEquals("SELECT 1; --rollback", SqlNormalizer.normalise("SELECT 1;", normalisation, true));
    }

    /**
     * A transformation filtered to another database is skipped, one that names
     * PostgreSQL (or names no database) applies.
     */
    @Test
    void honoursTheDbmsFilter() {
        assertEquals("SELECT 1; --pg", SqlNormalizer.normalise("SELECT 1;",
                withDbms("postgresql", " --pg"), false));
        assertEquals("SELECT 1; --pg", SqlNormalizer.normalise("SELECT 1;",
                withDbms("", " --pg"), false));
        assertEquals("SELECT 1;", SqlNormalizer.normalise("SELECT 1;",
                withDbms("oracle", " --ora"), false));
        assertEquals("SELECT 1; --pg", SqlNormalizer.normalise("SELECT 1;",
                withDbms("oracle, postgresql", " --pg"), false));
    }

    /**
     * The identity normalisation leaves the SQL unchanged.
     */
    @Test
    void identityNormalisationChangesNothing() {
        assertEquals("SELECT ${x};", SqlNormalizer.normalise("SELECT ${x};", Normalisation.none(), false));
    }
}
