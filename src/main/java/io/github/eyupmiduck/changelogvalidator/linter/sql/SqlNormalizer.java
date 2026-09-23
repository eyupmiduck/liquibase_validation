package io.github.eyupmiduck.changelogvalidator.linter.sql;

import io.github.eyupmiduck.changelogvalidator.linter.model.Normalisation;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlModification;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the raw SQL of a changeset into the SQL Liquibase would run: it
 * substitutes {@code ${property}} placeholders and then applies the changeset's
 * {@code <modifySql>} transformations in declaration order.
 *
 * <p>The transformations mirror Liquibase's {@code SqlVisitor}s: append and
 * prepend concatenate, replace is a literal replacement, regExpReplace is a
 * {@link java.util.regex.Pattern} replacement, and appendSqlIfNotPresent appends
 * unless the SQL already ends with the value. Rollback SQL only receives the
 * transformations declared with {@code applyToRollback="true"}; a transformation
 * whose {@code dbms} does not include PostgreSQL is skipped.
 *
 * <p>Property substitution is repeated to a fixed point (a value may reference
 * another property), up to a small depth; a circular reference therefore stops
 * rather than looping, and exceeding the depth fails loudly. A placeholder with
 * no property is left as-is, because the linter cannot see properties supplied
 * on the Liquibase command line (see ADR 0003).
 */
public final class SqlNormalizer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final int MAX_SUBSTITUTION_DEPTH = 10;

    private SqlNormalizer() {
    }

    /**
     * Normalises {@code sql} for a changeset.
     *
     * @param sql           the raw SQL
     * @param normalisation the changeset's properties and modifications
     * @param rollback      whether {@code sql} is rollback SQL
     * @return the SQL Liquibase would run
     */
    public static String normalise(String sql, Normalisation normalisation, boolean rollback) {
        String result = substituteProperties(sql, normalisation.properties());
        for (SqlModification modification : normalisation.modifySql()) {
            if (rollback && !modification.applyToRollback()) {
                continue;
            }
            if (!modification.appliesToPostgres()) {
                continue;
            }
            result = modification.apply(result);
        }
        return result;
    }

    private static String substituteProperties(String sql, Map<String, String> properties) {
        if (properties.isEmpty() || sql.indexOf("${") < 0) {
            return sql;
        }
        String current = sql;
        for (int depth = 0; depth < MAX_SUBSTITUTION_DEPTH; depth++) {
            Matcher matcher = PLACEHOLDER.matcher(current);
            StringBuilder replaced = new StringBuilder();
            while (matcher.find()) {
                String value = properties.get(matcher.group(1));
                matcher.appendReplacement(replaced,
                        Matcher.quoteReplacement(value == null ? matcher.group() : value));
            }
            matcher.appendTail(replaced);
            String next = replaced.toString();
            if (next.equals(current)) {
                // A fixed point: no property changed (including a self or
                // circular reference that substitutes to the same text).
                return current;
            }
            current = next;
        }
        throw new IllegalStateException("property substitution exceeded " + MAX_SUBSTITUTION_DEPTH
                + " passes; check for a circular property reference");
    }
}
