package io.github.eyupmiduck.changelogvalidator.linter.model;

import java.util.List;
import java.util.Map;

/**
 * The changelog-level inputs that turn a changeset's raw SQL into the SQL
 * Liquibase actually runs: the changelog properties substituted into
 * {@code ${...}} placeholders, and the changeset's {@code <modifySql>}
 * transformations.
 *
 * @param properties the changelog properties, by name
 * @param modifySql  the {@code <modifySql>} transformations, in declaration order
 */
public record Normalisation(Map<String, String> properties, List<SqlModification> modifySql) {

    private static final Normalisation NONE = new Normalisation(Map.of(), List.of());

    /**
     * Validates the normalisation and copies its collections.
     */
    public Normalisation {
        properties = Map.copyOf(properties);
        modifySql = List.copyOf(modifySql);
    }

    /**
     * Returns the normalisation that changes nothing.
     *
     * @return the identity normalisation
     */
    public static Normalisation none() {
        return NONE;
    }
}
