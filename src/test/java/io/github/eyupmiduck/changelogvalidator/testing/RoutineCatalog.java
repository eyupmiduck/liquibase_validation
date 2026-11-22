package io.github.eyupmiduck.changelogvalidator.testing;

import java.sql.*;
import java.util.*;

/**
 * Reads the functions and procedures in the configured schemas, so the routine
 * signature and documentation contracts share one catalog query and one
 * definition of a routine.
 *
 * <p>Extension-owned routines are excluded: they are not the project's own
 * objects to document or rename. A trigger function is included like any other
 * function.
 */
final class RoutineCatalog {

    private RoutineCatalog() {
    }

    /**
     * Returns every function and procedure in {@code schemas}, one row per
     * signature.
     *
     * @param connection an open connection to the migrated database
     * @param schemas    the schemas whose routines are read
     * @return the routines, ordered by schema, name and OID
     * @throws SQLException             if the catalog cannot be read
     * @throws IllegalArgumentException if {@code schemas} is empty or names a
     *                                  schema that does not exist
     */
    static List<Routine> routines(Connection connection, Collection<String> schemas) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(schemas, "schemas");
        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("at least one schema is required");
        }
        String[] names = schemas.toArray(String[]::new);
        Array schemaArray = connection.createArrayOf("text", names);
        try {
            assertSchemasExist(connection, schemaArray, schemas);
            String sql = """
                    SELECT n.nspname AS schema_name,
                           p.proname AS routine_name,
                           p.pronargs::int AS input_count,
                           pg_catalog.pg_get_function_identity_arguments(p.oid) AS identity_arguments,
                           pg_catalog.array_to_string(p.proargnames[1:p.pronargs], ',') AS input_names,
                           p.prosecdef AS security_definer,
                           p.prokind = 'p' AS procedure,
                           (SELECT substring(setting FROM length('search_path=') + 1)
                            FROM pg_catalog.unnest(p.proconfig) AS setting
                            WHERE setting LIKE 'search_path=%') AS search_path
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
            List<Routine> routines = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setArray(1, schemaArray);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        String inputNames = resultSet.getString("input_names");
                        routines.add(new Routine(
                                resultSet.getString("schema_name"),
                                resultSet.getString("routine_name"),
                                resultSet.getString("identity_arguments"),
                                resultSet.getInt("input_count"),
                                inputNames == null || inputNames.isBlank()
                                        ? List.of()
                                        : List.of(inputNames.split(",", -1)),
                                resultSet.getBoolean("security_definer"),
                                resultSet.getString("search_path"),
                                resultSet.getBoolean("procedure")));
                    }
                }
            }
            return List.copyOf(routines);
        } finally {
            schemaArray.free();
        }
    }

    private static void assertSchemasExist(Connection connection, Array schemaArray, Collection<String> schemas)
            throws SQLException {
        Set<String> missing = new LinkedHashSet<>(schemas);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT nspname FROM pg_catalog.pg_namespace WHERE nspname = ANY (?)")) {
            statement.setArray(1, schemaArray);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    missing.remove(resultSet.getString(1));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("schema not found: " + missing);
        }
    }

    /**
     * One routine signature.
     *
     * @param schema            the schema
     * @param name              the routine name
     * @param identityArguments the argument types, as PostgreSQL renders them
     * @param inputCount        the number of input arguments
     * @param inputNames        the input argument names, empty when unnamed
     * @param securityDefiner   whether the routine is {@code SECURITY DEFINER}
     * @param searchPath        the pinned {@code search_path}, or {@code null}
     * @param procedure         whether the routine is a procedure
     */
    record Routine(String schema, String name, String identityArguments, int inputCount, List<String> inputNames,
                   boolean securityDefiner, String searchPath, boolean procedure) {

        /**
         * Validates and copies the argument names.
         */
        Routine {
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(identityArguments, "identityArguments");
            inputNames = List.copyOf(inputNames);
            if (inputCount < 0) {
                throw new IllegalArgumentException("inputCount must not be negative");
            }
        }

        /**
         * The schema-qualified routine name.
         *
         * @return {@code schema.name}
         */
        String qualifiedName() {
            return schema + "." + name;
        }
    }
}
