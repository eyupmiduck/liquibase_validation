package io.github.eyupmiduck.changelogvalidator;

import java.sql.*;
import java.util.*;

/**
 * Checks the project-wide comment convention on a set of schemas: every schema,
 * table, table column, domain, composite/enum/range type and function/procedure
 * must have a non-blank {@code COMMENT ON}, and optionally every trigger too.
 *
 * <p>The check is structural and catalog-only. It returns every missing or blank
 * comment as a {@link Violation}; {@link #check} additionally applies an explicit
 * allow-list. An allow-list entry accepts a violation only when all of its set
 * fields match, and an entry that accepts nothing is reported as stale, so an
 * accepted exception cannot silently stay behind after the object is commented
 * or removed.
 *
 * <p>The inspected object kinds are:
 * <ul>
 *   <li>schema;</li>
 *   <li>base, partitioned and foreign table (not a partition, view or
 *       materialized view);</li>
 *   <li>column of those tables;</li>
 *   <li>domain, composite type, enum type, range type and user-defined base
 *       type, excluding array types and the implicit row type of a relation;</li>
 *   <li>function and procedure, including trigger functions, excluding
 *       extension-owned routines and the support routines PostgreSQL creates for
 *       a type;</li>
 *   <li>trigger, when {@link Options#includeTriggers()} is true.</li>
 * </ul>
 *
 * <p>The check fails closed on an empty schema list or a schema that does not
 * exist, so a typo cannot turn it into a vacuous pass. It also reports how many
 * objects it inspected, so a contract can reject an empty catalog.
 */
public final class CatalogCommentsCheck {

    private static final String SCHEMA_QUERY = """
            SELECT n.nspname AS schema_name,
                   pg_catalog.obj_description(n.oid, 'pg_namespace') AS comment
            FROM pg_catalog.pg_namespace AS n
            WHERE n.nspname = ANY (?)
            ORDER BY 1
            """;

    private static final String TABLE_QUERY = """
            SELECT n.nspname AS schema_name,
                   c.relname AS object_name,
                   pg_catalog.obj_description(c.oid, 'pg_class') AS comment
            FROM pg_catalog.pg_class AS c
            JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
            WHERE n.nspname = ANY (?)
              AND c.relkind IN ('r', 'p', 'f')
              AND NOT c.relispartition
            ORDER BY 1, 2
            """;

    private static final String COLUMN_QUERY = """
            SELECT n.nspname AS schema_name,
                   c.relname AS parent_name,
                   a.attname AS object_name,
                   pg_catalog.col_description(c.oid, a.attnum) AS comment
            FROM pg_catalog.pg_class AS c
            JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
            JOIN pg_catalog.pg_attribute AS a ON a.attrelid = c.oid
            WHERE n.nspname = ANY (?)
              AND c.relkind IN ('r', 'p', 'f')
              AND NOT c.relispartition
              AND a.attnum > 0
              AND NOT a.attisdropped
            ORDER BY 1, 2, a.attnum
            """;

    private static final String TYPE_QUERY = """
            SELECT n.nspname AS schema_name,
                   t.typname AS object_name,
                   t.typtype AS type_kind,
                   pg_catalog.obj_description(t.oid, 'pg_type') AS comment
            FROM pg_catalog.pg_type AS t
            JOIN pg_catalog.pg_namespace AS n ON n.oid = t.typnamespace
            LEFT JOIN pg_catalog.pg_class AS tc ON tc.oid = t.typrelid
            WHERE n.nspname = ANY (?)
              AND t.typtype IN ('b', 'c', 'd', 'e', 'r')
              AND NOT (t.typtype = 'b' AND t.typcategory = 'A')
              AND (t.typtype <> 'c' OR tc.relkind = 'c')
            ORDER BY 1, 2
            """;

    private static final String ROUTINE_QUERY = """
            SELECT n.nspname AS schema_name,
                   p.proname AS object_name,
                   p.prokind AS routine_kind,
                   pg_catalog.obj_description(p.oid, 'pg_proc') AS comment
            FROM pg_catalog.pg_proc AS p
            JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
            WHERE n.nspname = ANY (?)
              AND p.prokind IN ('f', 'p', 'a', 'w')
              AND NOT EXISTS (
                  SELECT 1
                  FROM pg_catalog.pg_depend AS d
                  WHERE d.classid = 'pg_catalog.pg_proc'::pg_catalog.regclass
                    AND d.objid = p.oid
                    AND (d.deptype = 'e'
                         OR (d.refclassid = 'pg_catalog.pg_type'::pg_catalog.regclass
                             AND d.deptype = 'i'))
              )
            ORDER BY 1, 2
            """;

    private static final String TRIGGER_QUERY = """
            SELECT n.nspname AS schema_name,
                   c.relname AS parent_name,
                   t.tgname AS object_name,
                   pg_catalog.obj_description(t.oid, 'pg_trigger') AS comment
            FROM pg_catalog.pg_trigger AS t
            JOIN pg_catalog.pg_class AS c ON c.oid = t.tgrelid
            JOIN pg_catalog.pg_namespace AS n ON n.oid = c.relnamespace
            WHERE n.nspname = ANY (?)
              AND NOT t.tgisinternal
              AND c.relkind IN ('r', 'p', 'f')
              AND NOT c.relispartition
            ORDER BY 1, 2, 3
            """;

    private CatalogCommentsCheck() {
    }

    /**
     * Finds every missing or blank comment with the default options.
     *
     * @param connection an open connection to the database to check
     * @param schemas    the schemas whose objects are checked
     * @return the violations, sorted by kind, schema and object
     * @throws SQLException             if the catalog cannot be read
     * @throws IllegalArgumentException if {@code schemas} is empty or names a
     *                                  schema that does not exist
     */
    public static List<Violation> findViolations(Connection connection, Collection<String> schemas)
            throws SQLException {
        return findViolations(connection, schemas, Options.DEFAULT);
    }

    /**
     * Finds every missing or blank comment.
     *
     * @param connection an open connection to the database to check
     * @param schemas    the schemas whose objects are checked
     * @param options    the object kinds to inspect
     * @return the violations, sorted by kind, schema and object
     * @throws SQLException             if the catalog cannot be read
     * @throws IllegalArgumentException if {@code schemas} is empty or names a
     *                                  schema that does not exist
     */
    public static List<Violation> findViolations(Connection connection, Collection<String> schemas, Options options)
            throws SQLException {
        return missing(inspect(connection, schemas, options));
    }

    /**
     * Checks the comments against an allow-list with the default options.
     *
     * @param connection an open connection to the database to check
     * @param schemas    the schemas whose objects are checked
     * @param allowed    the accepted exceptions
     * @return the report; empty unexpected and stale lists when the convention
     * holds
     * @throws SQLException if the catalog cannot be read
     */
    public static Report check(Connection connection, Collection<String> schemas,
                               Collection<AllowedObject> allowed) throws SQLException {
        return check(connection, schemas, allowed, Options.DEFAULT);
    }

    /**
     * Checks the comments against an allow-list.
     *
     * @param connection an open connection to the database to check
     * @param schemas    the schemas whose objects are checked
     * @param allowed    the accepted exceptions; an entry must set at least one
     *                   field
     * @param options    the object kinds to inspect
     * @return the report; empty unexpected and stale lists when the convention
     * holds
     * @throws SQLException if the catalog cannot be read
     */
    public static Report check(Connection connection, Collection<String> schemas,
                               Collection<AllowedObject> allowed, Options options) throws SQLException {
        Objects.requireNonNull(allowed, "allowed");
        for (AllowedObject entry : allowed) {
            Objects.requireNonNull(entry, "allowed element");
        }
        List<AllowedObject> entries = List.copyOf(allowed);
        List<ObjectComment> objects = inspect(connection, schemas, options);
        List<Violation> missing = missing(objects);

        boolean[] matched = new boolean[entries.size()];
        List<Violation> unexpected = new ArrayList<>();
        for (Violation violation : missing) {
            boolean accepted = false;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).matches(violation.object())) {
                    accepted = true;
                    matched[i] = true;
                }
            }
            if (!accepted) {
                unexpected.add(violation);
            }
        }

        List<AllowedObject> stale = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!matched[i]) {
                stale.add(entries.get(i));
            }
        }
        return new Report(unexpected, stale, objects.size());
    }

    private static List<ObjectComment> inspect(Connection connection, Collection<String> schemas, Options options)
            throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(schemas, "schemas");
        Objects.requireNonNull(options, "options");
        if (schemas.isEmpty()) {
            throw new IllegalArgumentException("at least one schema is required");
        }
        String[] names = schemas.toArray(String[]::new);
        Array schemaArray = connection.createArrayOf("text", names);
        try {
            assertSchemasExist(connection, schemaArray, schemas);
            List<ObjectComment> objects = new ArrayList<>();
            addObjects(connection, schemaArray, SCHEMA_QUERY, objects, resultSet ->
                    new ObjectComment(new ObjectRef(Kind.SCHEMA,
                            resultSet.getString("schema_name"), null, null),
                            resultSet.getString("comment")));
            addObjects(connection, schemaArray, TABLE_QUERY, objects, resultSet ->
                    new ObjectComment(new ObjectRef(Kind.TABLE,
                            resultSet.getString("schema_name"), null, resultSet.getString("object_name")),
                            resultSet.getString("comment")));
            addObjects(connection, schemaArray, COLUMN_QUERY, objects, resultSet ->
                    new ObjectComment(new ObjectRef(Kind.COLUMN,
                            resultSet.getString("schema_name"),
                            resultSet.getString("parent_name"),
                            resultSet.getString("object_name")),
                            resultSet.getString("comment")));
            addObjects(connection, schemaArray, TYPE_QUERY, objects, resultSet ->
                    new ObjectComment(new ObjectRef(typeKind(resultSet.getString("type_kind")),
                            resultSet.getString("schema_name"), null, resultSet.getString("object_name")),
                            resultSet.getString("comment")));
            addObjects(connection, schemaArray, ROUTINE_QUERY, objects, resultSet ->
                    new ObjectComment(new ObjectRef(
                            "p".equals(resultSet.getString("routine_kind")) ? Kind.PROCEDURE : Kind.FUNCTION,
                            resultSet.getString("schema_name"), null, resultSet.getString("object_name")),
                            resultSet.getString("comment")));
            if (options.includeTriggers()) {
                addObjects(connection, schemaArray, TRIGGER_QUERY, objects, resultSet ->
                        new ObjectComment(new ObjectRef(Kind.TRIGGER,
                                resultSet.getString("schema_name"),
                                resultSet.getString("parent_name"),
                                resultSet.getString("object_name")),
                                resultSet.getString("comment")));
            }
            return List.copyOf(objects);
        } finally {
            schemaArray.free();
        }
    }

    private static void addObjects(Connection connection, Array schemaArray, String sql,
                                   List<ObjectComment> objects, RowMapper mapper) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setArray(1, schemaArray);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    objects.add(mapper.map(resultSet));
                }
            }
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

    private static List<Violation> missing(List<ObjectComment> objects) {
        return objects.stream()
                .filter(object -> object.comment() == null || object.comment().isBlank())
                .map(object -> new Violation(object.object(), "missing or blank comment"))
                .sorted(Comparator.comparing(Violation::object))
                .toList();
    }

    private static Kind typeKind(String typeKind) {
        return switch (typeKind) {
            case "b" -> Kind.BASE_TYPE;
            case "c" -> Kind.COMPOSITE_TYPE;
            case "d" -> Kind.DOMAIN;
            case "e" -> Kind.ENUM_TYPE;
            case "r" -> Kind.RANGE_TYPE;
            default -> throw new IllegalArgumentException("unsupported type kind: " + typeKind);
        };
    }

    /**
     * The object kinds the check inspects.
     */
    public enum Kind {
        /**
         * A database schema.
         */
        SCHEMA("schema"),
        /**
         * A base, partitioned or foreign table.
         */
        TABLE("table"),
        /**
         * A table column.
         */
        COLUMN("column"),
        /**
         * A domain.
         */
        DOMAIN("domain"),
        /**
         * A composite type.
         */
        COMPOSITE_TYPE("composite type"),
        /**
         * An enum type.
         */
        ENUM_TYPE("enum type"),
        /**
         * A range type.
         */
        RANGE_TYPE("range type"),
        /**
         * A user-defined base type.
         */
        BASE_TYPE("base type"),
        /**
         * A function, including a trigger function.
         */
        FUNCTION("function"),
        /**
         * A procedure.
         */
        PROCEDURE("procedure"),
        /**
         * A table trigger.
         */
        TRIGGER("trigger");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /**
         * The human-readable label used in diagnostics.
         *
         * @return the label
         */
        public String label() {
            return label;
        }
    }

    @FunctionalInterface
    private interface RowMapper {
        ObjectComment map(ResultSet resultSet) throws SQLException;
    }

    /**
     * Options controlling which object kinds are inspected.
     *
     * @param includeTriggers whether trigger comments are required
     */
    public record Options(boolean includeTriggers) {

        /**
         * The default options: triggers are included.
         */
        public static final Options DEFAULT = new Options(true);
    }

    /**
     * A catalog object that should carry a comment.
     *
     * @param kind   the object kind
     * @param schema the schema that owns the object; for {@link Kind#SCHEMA}
     *               this is the schema itself
     * @param parent the table for a column or trigger, otherwise {@code null}
     * @param name   the object name; {@code null} for {@link Kind#SCHEMA}
     */
    public record ObjectRef(Kind kind, String schema, String parent, String name)
            implements Comparable<ObjectRef> {

        /**
         * Validates the kind-specific shape.
         */
        public ObjectRef {
            Objects.requireNonNull(kind, "kind");
            requireNonBlank(schema, "schema");
            if (kind == Kind.SCHEMA) {
                if (parent != null || name != null) {
                    throw new IllegalArgumentException("a schema object must not set parent or name");
                }
            } else {
                requireNonBlank(name, "name");
                if (kind == Kind.COLUMN || kind == Kind.TRIGGER) {
                    requireNonBlank(parent, "parent");
                } else if (parent != null) {
                    throw new IllegalArgumentException(kind + " must not set parent");
                }
            }
        }

        private static void requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
        }

        /**
         * Formats the object for a diagnostic.
         *
         * @return the object description
         */
        public String describe() {
            StringBuilder description = new StringBuilder(kind.label()).append(' ').append(schema);
            if (parent != null) {
                description.append('.').append(parent);
            }
            if (name != null) {
                description.append('.').append(name);
            }
            return description.toString();
        }

        @Override
        public int compareTo(ObjectRef other) {
            return Comparator.comparing(ObjectRef::kind)
                    .thenComparing(ObjectRef::schema)
                    .thenComparing(ObjectRef::parent, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(ObjectRef::name, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .compare(this, other);
        }
    }

    /**
     * A missing or blank comment.
     *
     * @param object  the object
     * @param message what is wrong
     */
    public record Violation(ObjectRef object, String message) {

        /**
         * Validates the required components.
         */
        public Violation {
            Objects.requireNonNull(object, "object");
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
        }

        /**
         * Formats the violation for a test failure.
         *
         * @return a one-line description
         */
        public String describe() {
            return object.describe() + ": " + message;
        }
    }

    /**
     * An accepted missing-comment exception. At least one field must be set; an
     * unset field matches any value. An entry is stale when it accepts no
     * violation.
     *
     * @param kind   the object kind, or {@code null} to match any
     * @param schema the schema, or {@code null} to match any
     * @param parent the table for a column or trigger, or {@code null} to match any
     * @param name   the object name, or {@code null} to match any
     */
    public record AllowedObject(Kind kind, String schema, String parent, String name) {

        /**
         * Requires at least one selector and rejects a blank selector.
         */
        public AllowedObject {
            if (kind == null && schema == null && parent == null && name == null) {
                throw new IllegalArgumentException("an allowed object must set at least one field");
            }
            requireNonBlankIfPresent(schema, "schema");
            requireNonBlankIfPresent(parent, "parent");
            requireNonBlankIfPresent(name, "name");
        }

        private static void requireNonBlankIfPresent(String value, String name) {
            if (value != null && value.isBlank()) {
                throw new IllegalArgumentException(name + " must be null or non-blank");
            }
        }

        /**
         * Returns whether every set selector matches the object.
         *
         * @param object the object
         * @return {@code true} when the entry accepts the object
         */
        public boolean matches(ObjectRef object) {
            Objects.requireNonNull(object, "object");
            return (kind == null || kind == object.kind())
                    && (schema == null || schema.equals(object.schema()))
                    && (parent == null || parent.equals(object.parent()))
                    && (name == null || name.equals(object.name()));
        }

        /**
         * Formats the entry for a stale-entry diagnostic.
         *
         * @return the entry description
         */
        public String describe() {
            StringBuilder description = new StringBuilder();
            if (kind != null) {
                description.append(kind.label()).append(' ');
            }
            if (schema != null) {
                description.append(schema);
            }
            if (parent != null) {
                description.append('.').append(parent);
            }
            if (name != null) {
                description.append('.').append(name);
            }
            return description.toString();
        }
    }

    /**
     * The result of a comment check.
     *
     * @param unexpected     violations no allow-list entry accepted
     * @param stale          allow-list entries that accepted no violation
     * @param checkedObjects the number of objects inspected
     */
    public record Report(List<Violation> unexpected, List<AllowedObject> stale, int checkedObjects) {

        /**
         * Copies the lists and validates the count.
         */
        public Report {
            unexpected = List.copyOf(unexpected);
            stale = List.copyOf(stale);
            if (checkedObjects < 0) {
                throw new IllegalArgumentException("checkedObjects must not be negative");
            }
        }

        /**
         * Returns whether the check passed.
         *
         * @return {@code true} when there are no unexpected violations and no
         * stale entries
         */
        public boolean isEmpty() {
            return unexpected.isEmpty() && stale.isEmpty();
        }
    }

    private record ObjectComment(ObjectRef object, String comment) {
    }
}
