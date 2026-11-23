package io.github.eyupmiduck.changelogvalidator;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;

import java.sql.*;
import java.util.*;

/**
 * Checks that a lower schema does not reference a higher one, using the routine
 * bodies and signatures in the PostgreSQL catalog.
 *
 * <p>The schema list is ordered highest to lowest, for example
 * {@code dml_utils}, {@code dml_utils_lib}, {@code dml_utils_data}. A routine in
 * a lower schema may reference a schema below it, but not one above it. The
 * check tokenizes each routine body and its arguments (including default
 * expressions) and result, so a schema name in a comment or a string is not a
 * reference and a quoted identifier is handled. Dynamic SQL inside a
 * dollar-quoted string is opaque, so a reference that only appears at run time
 * is not seen. A reference that is not written as {@code schema.object} is not
 * seen either; the check deliberately avoids guessing from unqualified names.
 *
 * <p>Extension-owned routines are excluded, like the other routine checks. An
 * explicit {@link Reference} allow-list suppresses a known exception; an entry
 * that matches nothing is stale and reported. The report also counts the
 * routines and objects in each schema, so a contract can fail when a schema is
 * empty and the check would otherwise pass vacuously.
 */
public final class SchemaLayeringCheck {

    private SchemaLayeringCheck() {
    }

    /**
     * Inspects the configured schemas.
     *
     * @param connection the database connection
     * @param schemas    the schemas, ordered highest to lowest
     * @param allowed    the accepted exceptions
     * @return the report
     * @throws SQLException             if the catalog cannot be read
     * @throws IllegalArgumentException if the schema list is empty or contains
     *                                  duplicates
     */
    public static Report inspect(Connection connection, List<String> schemas, Set<Reference> allowed)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(schemas, "schemas");
        Objects.requireNonNull(allowed, "allowed");
        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("at least one schema is required");
        }
        if (new HashSet<>(schemas).size() != schemas.size()) {
            throw new IllegalArgumentException("schemas must not contain duplicates: " + schemas);
        }
        Map<String, Integer> routines = new LinkedHashMap<>();
        for (String schema : schemas) {
            routines.put(schema, 0);
        }
        Set<Reference> found = new LinkedHashSet<>();
        Array schemaArray = connection.createArrayOf("text", schemas.toArray());
        try {
            String sql = """
                    SELECT n.nspname AS schema_name,
                           p.proname AS routine_name,
                           p.prosrc AS body,
                           pg_catalog.pg_get_function_arguments(p.oid) AS arguments,
                           pg_catalog.pg_get_function_result(p.oid) AS result
                    FROM pg_catalog.pg_proc AS p
                    JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
                    WHERE n.nspname = ANY (?)
                      AND p.prokind IN ('f', 'p')
                      AND NOT EXISTS (
                          SELECT 1
                          FROM pg_catalog.pg_depend AS d
                          WHERE d.classid = 'pg_catalog.pg_proc'::pg_catalog.regclass
                            AND d.objid = p.oid
                            AND d.deptype = 'e'
                      )
                    ORDER BY 1, 2, p.oid
                    """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setArray(1, schemaArray);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String fromSchema = rows.getString("schema_name");
                        String routine = rows.getString("routine_name");
                        routines.merge(fromSchema, 1, Integer::sum);
                        int index = schemas.indexOf(fromSchema);
                        for (int higher = 0; higher < index; higher++) {
                            String toSchema = schemas.get(higher);
                            for (String object : references(rows.getString("body"), toSchema)) {
                                found.add(new Reference(fromSchema, routine, toSchema, object));
                            }
                            String signature = rows.getString("arguments") + " " + rows.getString("result");
                            for (String object : references(signature, toSchema)) {
                                found.add(new Reference(fromSchema, routine, toSchema, object));
                            }
                        }
                    }
                }
            }
            Map<String, Integer> objects = objectsBySchema(connection, schemas, schemaArray);
            List<Reference> unexpected = new ArrayList<>();
            for (Reference reference : found) {
                if (allowed.stream().noneMatch(entry -> matches(entry, reference))) {
                    unexpected.add(reference);
                }
            }
            List<Reference> stale = allowed.stream()
                    .filter(entry -> found.stream().noneMatch(reference -> matches(entry, reference)))
                    .toList();
            return new Report(unexpected, stale, routines, objects);
        } finally {
            schemaArray.free();
        }
    }

    private static Map<String, Integer> objectsBySchema(Connection connection, List<String> schemas,
                                                        Array schemaArray) throws SQLException {
        Map<String, Integer> objects = new LinkedHashMap<>();
        for (String schema : schemas) {
            objects.put(schema, 0);
        }
        String sql = """
                SELECT n.nspname AS schema_name, count(*)::int AS object_count
                FROM (
                    SELECT p.pronamespace AS namespace
                    FROM pg_catalog.pg_proc AS p
                    WHERE p.prokind IN ('f', 'p')
                    UNION ALL
                    SELECT c.relnamespace AS namespace
                    FROM pg_catalog.pg_class AS c
                    WHERE c.relkind <> 't'
                    UNION ALL
                    SELECT t.typnamespace AS namespace
                    FROM pg_catalog.pg_type AS t
                    WHERE t.typtype IN ('b', 'd', 'e', 'r', 'c', 'm')
                      AND t.typelem = 0
                      AND NOT EXISTS (
                          SELECT 1 FROM pg_catalog.pg_class AS c WHERE c.reltype = t.oid
                      )
                ) AS objects
                JOIN pg_catalog.pg_namespace AS n ON n.oid = objects.namespace
                WHERE n.nspname = ANY (?)
                GROUP BY 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, schemaArray);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    objects.put(rows.getString("schema_name"), rows.getInt("object_count"));
                }
            }
        }
        return objects;
    }

    private static boolean matches(Reference allowed, Reference actual) {
        return allowed.fromSchema().equals(actual.fromSchema())
                && allowed.fromRoutine().equals(actual.fromRoutine())
                && allowed.toSchema().equals(actual.toSchema())
                && (allowed.toObject() == null || allowed.toObject().equals(actual.toObject()));
    }

    /**
     * Returns the objects a SQL fragment references as {@code schema.object}.
     * Comments and strings are trivia or string tokens, so a mention there is not
     * a reference.
     */
    private static Set<String> references(String sql, String schema) {
        Set<String> objects = new LinkedHashSet<>();
        List<Token> tokens = SqlLexer.tokenize(sql).stream()
                .filter(token -> !token.isTrivia())
                .toList();
        for (int i = 0; i + 2 < tokens.size(); i++) {
            Token first = tokens.get(i);
            if (!identifier(first).equalsIgnoreCase(schema)
                    || tokens.get(i + 1).type() != TokenType.PUNCTUATION
                    || !tokens.get(i + 1).text().equals(".")) {
                continue;
            }
            String object = identifier(tokens.get(i + 2));
            if (!object.isBlank()) {
                objects.add(object);
            }
        }
        return objects;
    }

    private static String identifier(Token token) {
        return switch (token.type()) {
            case WORD -> token.text();
            case QUOTED_IDENTIFIER -> token.text().substring(1, token.text().length() - 1)
                    .replace("\"\"", "\"");
            default -> "";
        };
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * A reference from a routine to an object in another schema. A {@code null}
     * {@code toObject} in an allow-list entry matches any object.
     *
     * @param fromSchema  the referencing schema
     * @param fromRoutine the referencing routine
     * @param toSchema    the referenced schema
     * @param toObject    the referenced object, or {@code null} to match any
     */
    public record Reference(String fromSchema, String fromRoutine, String toSchema, String toObject) {

        /**
         * Validates the required values; only {@code toObject} may be null.
         */
        public Reference {
            fromSchema = requireNonBlank(fromSchema, "fromSchema");
            fromRoutine = requireNonBlank(fromRoutine, "fromRoutine");
            toSchema = requireNonBlank(toSchema, "toSchema");
            if (toObject != null && toObject.isBlank()) {
                throw new IllegalArgumentException("toObject must be null or non-blank");
            }
        }

        /**
         * Formats the reference for a test failure.
         *
         * @return a one-line description
         */
        public String describe() {
            return fromSchema + "." + fromRoutine + " -> " + toSchema
                    + (toObject == null ? "" : "." + toObject);
        }
    }

    /**
     * The layering report.
     *
     * @param unexpected       the references not covered by the allow-list
     * @param staleAllowList   the allow-list entries that matched nothing
     * @param routinesBySchema the routine count per configured schema
     * @param objectsBySchema  the routine, relation and type count per configured
     *                         schema, for the non-vacuity check
     */
    public record Report(List<Reference> unexpected, List<Reference> staleAllowList,
                         Map<String, Integer> routinesBySchema,
                         Map<String, Integer> objectsBySchema) {

        /**
         * Copies the collections and keeps the configured schema order.
         */
        public Report {
            unexpected = List.copyOf(unexpected);
            staleAllowList = List.copyOf(staleAllowList);
            routinesBySchema = Collections.unmodifiableMap(new LinkedHashMap<>(routinesBySchema));
            objectsBySchema = Collections.unmodifiableMap(new LinkedHashMap<>(objectsBySchema));
        }

        /**
         * The total number of routines inspected.
         *
         * @return the routine count
         */
        public int routines() {
            return routinesBySchema.values().stream().mapToInt(Integer::intValue).sum();
        }

        /**
         * The configured schemas that contain no routine, relation or type.
         *
         * @return the empty schemas, in configured order
         */
        public Set<String> emptySchemas() {
            Set<String> empty = new LinkedHashSet<>();
            for (String schema : routinesBySchema.keySet()) {
                if (objectsBySchema.getOrDefault(schema, 0) == 0) {
                    empty.add(schema);
                }
            }
            return empty;
        }
    }
}
