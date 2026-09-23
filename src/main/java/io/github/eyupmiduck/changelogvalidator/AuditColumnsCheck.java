package io.github.eyupmiduck.changelogvalidator;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks the project-wide audit-column convention on a set of schemas: every
 * base table must have {@code created_at} and {@code updated_at}, both
 * {@code timestamptz NOT NULL DEFAULT now()}, and must carry an enabled
 * {@code BEFORE UPDATE ... FOR EACH ROW} trigger so {@code updated_at} is
 * refreshed on every {@code UPDATE} regardless of the caller.
 *
 * <p>The catalog check ({@link #findViolations}) is structural: it verifies the
 * columns and that a suitable trigger exists. The behavioral probe
 * ({@link #probeUpdate}) goes further and verifies on a real row that an
 * {@code UPDATE} refreshes {@code updated_at} and preserves {@code created_at},
 * rolling the change back afterwards.
 */
public final class AuditColumnsCheck {

    private static final String TIMESTAMPTZ = "timestamp with time zone";

    /**
     * A default expression that evaluates to the transaction timestamp, after
     * allowing for a precision specifier, balanced surrounding parentheses and an
     * explicit cast to a timestamp type, all of which {@code pg_get_expr} may
     * render. An unrelated cast (for example {@code now()::date}) is rejected.
     */
    private static final String NOW_FUNCTION =
            "(?:now\\(\\)|current_timestamp(?:\\(\\d*\\))?|transaction_timestamp\\(\\))";

    private static final Pattern NOW_DEFAULT = Pattern.compile(
            "(?:\\(" + NOW_FUNCTION + "\\)|" + NOW_FUNCTION + ")"
                    + "(?:::(?:timestamptz|timestamp(?:\\(\\d*\\))?(?:\\s+(?:with|without)\\s+time\\s+zone)?))?",
            Pattern.CASE_INSENSITIVE);

    /**
     * The value the probe writes to {@code updated_at}; a working trigger
     * overwrites it with the transaction timestamp.
     */
    private static final OffsetDateTime SENTINEL = OffsetDateTime.parse("2000-01-01T00:00:00Z");

    private static final String VIOLATION_QUERY = """
            SELECT n.nspname AS schema_name,
                   c.relname AS table_name,
                   max(CASE WHEN a.attname = 'created_at'
                       THEN format_type(a.atttypid, NULL) END) AS created_type,
                   max(CASE WHEN a.attname = 'created_at'
                       THEN a.attnotnull::text END) AS created_not_null,
                   max(CASE WHEN a.attname = 'created_at'
                       THEN pg_get_expr(d.adbin, d.adrelid) END) AS created_default,
                   max(CASE WHEN a.attname = 'updated_at'
                       THEN format_type(a.atttypid, NULL) END) AS updated_type,
                   max(CASE WHEN a.attname = 'updated_at'
                       THEN a.attnotnull::text END) AS updated_not_null,
                   max(CASE WHEN a.attname = 'updated_at'
                       THEN pg_get_expr(d.adbin, d.adrelid) END) AS updated_default,
                   (SELECT count(*)
                    FROM pg_catalog.pg_trigger t
                    WHERE t.tgrelid = c.oid
                        AND NOT t.tgisinternal
                        AND t.tgenabled IN ('O', 'A')
                        AND (t.tgtype & 1) <> 0
                        AND (t.tgtype & 2) <> 0
                        AND (t.tgtype & 16) <> 0) AS update_triggers
            FROM pg_catalog.pg_class c
            JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_catalog.pg_attribute a
                ON a.attrelid = c.oid
                    AND a.attname IN ('created_at', 'updated_at')
                    AND a.attnum > 0
                    AND NOT a.attisdropped
            LEFT JOIN pg_catalog.pg_attrdef d
                ON d.adrelid = c.oid AND d.adnum = a.attnum
            WHERE n.nspname = ANY (?)
                AND c.relkind IN ('r', 'p')
                AND NOT c.relispartition
            GROUP BY n.nspname, c.relname, c.oid
            ORDER BY 1, 2
            """;

    private AuditColumnsCheck() {
    }

    /**
     * Finds every base table in {@code schemas} that does not follow the
     * audit-column convention: a missing or mistyped {@code created_at} or
     * {@code updated_at}, a column that is nullable or whose default is not
     * {@code now()}, or a table with no enabled {@code BEFORE UPDATE ... FOR
     * EACH ROW} trigger.
     *
     * @param connection an open connection to the database to check
     * @param schemas    the schemas whose tables are checked
     * @return the violations, ordered by schema and table
     * @throws SQLException if the catalog cannot be read
     */
    public static List<Violation> findViolations(Connection connection, Collection<String> schemas) throws SQLException {
        String[] names = schemas.toArray(String[]::new);
        List<Violation> violations = new ArrayList<>();
        Array schemaArray = connection.createArrayOf("text", names);
        try (PreparedStatement statement = connection.prepareStatement(VIOLATION_QUERY)) {
            statement.setArray(1, schemaArray);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String schema = resultSet.getString("schema_name");
                    String table = resultSet.getString("table_name");
                    checkColumn(violations, schema, table, "created_at",
                            resultSet.getString("created_type"),
                            resultSet.getString("created_not_null"),
                            resultSet.getString("created_default"));
                    checkColumn(violations, schema, table, "updated_at",
                            resultSet.getString("updated_type"),
                            resultSet.getString("updated_not_null"),
                            resultSet.getString("updated_default"));
                    if (resultSet.getInt("update_triggers") == 0) {
                        violations.add(new Violation(schema, table,
                                "no enabled BEFORE UPDATE FOR EACH ROW trigger"));
                    }
                }
            }
        } finally {
            schemaArray.free();
        }
        return violations;
    }

    /**
     * Probes a table's audit columns on a real row: it updates one row's
     * {@code updated_at} to a sentinel in the past and reads the result back,
     * so a working trigger overwrites the sentinel with the transaction
     * timestamp and leaves {@code created_at} alone. The update is rolled back,
     * so the table is left unchanged.
     *
     * <p>The probe manages the connection's transaction state: when the
     * connection is in autocommit mode it starts a transaction and rolls it
     * back, and otherwise it uses a savepoint, so a caller's in-flight
     * transaction is never committed or discarded.
     *
     * @param connection an open connection to the database
     * @param schema     the table's schema
     * @param table      the table to probe
     * @return the probe result, or empty when the table has no rows to probe
     * @throws SQLException if the probe cannot run
     */
    public static Optional<Probe> probeUpdate(Connection connection, String schema, String table)
            throws SQLException {
        String qualified = quoteIdentifier(schema) + "." + quoteIdentifier(table);
        boolean autoCommit = connection.getAutoCommit();
        Savepoint savepoint = null;
        if (autoCommit) {
            connection.setAutoCommit(false);
        } else {
            savepoint = connection.setSavepoint();
        }
        try {
            String ctid;
            OffsetDateTime createdAt;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT ctid::text AS ctid, created_at FROM " + qualified + " LIMIT 1 FOR UPDATE")) {
                try (ResultSet resultSet = select.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    ctid = resultSet.getString("ctid");
                    createdAt = resultSet.getObject("created_at", OffsetDateTime.class);
                }
            }

            OffsetDateTime afterCreatedAt;
            OffsetDateTime afterUpdatedAt;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE " + qualified + " SET updated_at = ? WHERE ctid = ?::tid"
                            + " RETURNING created_at, updated_at")) {
                update.setObject(1, SENTINEL);
                update.setString(2, ctid);
                try (ResultSet resultSet = update.executeQuery()) {
                    if (!resultSet.next()) {
                        return Optional.empty();
                    }
                    afterCreatedAt = resultSet.getObject("created_at", OffsetDateTime.class);
                    afterUpdatedAt = resultSet.getObject("updated_at", OffsetDateTime.class);
                }
            }

            boolean refreshed = afterUpdatedAt != null && afterUpdatedAt.isAfter(SENTINEL);
            boolean createdPreserved = createdAt != null && createdAt.equals(afterCreatedAt);
            return Optional.of(new Probe(schema, table, refreshed, createdPreserved));
        } finally {
            if (autoCommit) {
                try {
                    connection.rollback();
                } finally {
                    connection.setAutoCommit(true);
                }
            } else if (savepoint != null) {
                connection.rollback(savepoint);
                // ROLLBACK TO SAVEPOINT leaves the savepoint defined; release it so a
                // long-lived caller transaction does not accumulate savepoints.
                connection.releaseSavepoint(savepoint);
            }
        }
    }

    private static void checkColumn(List<Violation> violations, String schema, String table, String column,
                                    String type, String notNull, String defaultValue) {
        if (type == null) {
            violations.add(new Violation(schema, table, column + " is missing"));
            return;
        }
        if (!TIMESTAMPTZ.equals(type)) {
            violations.add(new Violation(schema, table, column + " must be " + TIMESTAMPTZ + ", was " + type));
        }
        if (!"true".equals(notNull)) {
            violations.add(new Violation(schema, table, column + " must be NOT NULL"));
        }
        if (defaultValue == null || !NOW_DEFAULT.matcher(defaultValue).matches()) {
            violations.add(new Violation(schema, table, column + " must default to now(), was " + defaultValue));
        }
    }

    private static String quoteIdentifier(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    /**
     * One way a table departs from the audit-column convention.
     *
     * @param schema  the table's schema
     * @param table   the table name
     * @param problem a human-readable description of the violation
     */
    public record Violation(String schema, String table, String problem) {

        /**
         * Formats the violation for an assertion message.
         *
         * @return a human-readable description
         */
        public String describe() {
            return schema + "." + table + ": " + problem;
        }
    }

    /**
     * The result of {@link #probeUpdate}: whether an {@code UPDATE} refreshed
     * {@code updated_at} and preserved {@code created_at}.
     *
     * @param schema             the probed table's schema
     * @param table              the probed table name
     * @param updatedAtRefreshed whether the trigger overwrote the sentinel
     * @param createdAtPreserved whether {@code created_at} was left unchanged
     */
    public record Probe(String schema, String table, boolean updatedAtRefreshed, boolean createdAtPreserved) {

        /**
         * Returns whether both expectations held.
         *
         * @return {@code true} when {@code updated_at} was refreshed and
         * {@code created_at} was preserved
         */
        public boolean passed() {
            return updatedAtRefreshed && createdAtPreserved;
        }

        /**
         * Formats the result for an assertion message.
         *
         * @return a human-readable description
         */
        public String describe() {
            return schema + "." + table + ": updated_at refreshed=" + updatedAtRefreshed
                    + ", created_at preserved=" + createdAtPreserved;
        }
    }
}
