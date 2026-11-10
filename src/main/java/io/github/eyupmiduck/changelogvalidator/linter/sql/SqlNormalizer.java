package io.github.eyupmiduck.changelogvalidator.linter.sql;

import io.github.eyupmiduck.changelogvalidator.linter.model.Normalisation;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlModification;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 * another property). A repeated state (a cycle), a substitution that does not
 * settle within the number of properties, or output exceeding the safety bound
 * fails loudly, so a valid long chain is not rejected by an arbitrary depth
 * limit and a pathological expansion cannot exhaust the heap. A placeholder with
 * no property is left as-is, because the linter cannot see properties supplied on the
 * Liquibase command line (see ADR 0003).
 */
public final class SqlNormalizer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    /**
     * Upper bound on the substituted text. Property expansion can duplicate a
     * value exponentially; without this bound a pathological definition can
     * exhaust the heap before the cycle/pass check reports an error.
     */
    private static final int MAX_SUBSTITUTED_LENGTH = 10_000_000;

    private SqlNormalizer() {
    }

    /**
     * Normalises {@code sql} for a changeset.
     *
     * @param sql           the raw SQL
     * @param normalisation the changeset's properties and modifications
     * @param rollback      whether {@code sql} is rollback SQL
     * @return the SQL Liquibase would run
     * @throws IllegalStateException if property substitution cycles, does not
     *                               converge, or exceeds the output safety bound
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
        Set<String> seen = new HashSet<>();
        // A chain can visit each property at most once; a self-expanding value
        // changes the string forever, so the property count bounds the passes.
        for (int pass = 0; pass <= properties.size(); pass++) {
            if (!seen.add(current)) {
                throw new IllegalStateException(
                        "property substitution does not converge; check for a circular property reference");
            }
            Matcher matcher = PLACEHOLDER.matcher(current);
            StringBuilder replaced = new StringBuilder();
            while (matcher.find()) {
                String value = properties.get(matcher.group(1));
                matcher.appendReplacement(replaced,
                        Matcher.quoteReplacement(value == null ? matcher.group() : value));
            }
            matcher.appendTail(replaced);
            String next = replaced.toString();
            if (next.length() > MAX_SUBSTITUTED_LENGTH) {
                throw new IllegalStateException(
                        "property substitution exceeded " + MAX_SUBSTITUTED_LENGTH
                                + " characters; check for an expanding property reference");
            }
            if (next.equals(current)) {
                // A fixed point: no property changed (including a self or
                // circular reference that substitutes to the same text).
                return current;
            }
            current = next;
        }
        throw new IllegalStateException(
                "property substitution does not converge within " + (properties.size() + 1)
                        + " passes; check for a circular or expanding property reference");
    }
}
