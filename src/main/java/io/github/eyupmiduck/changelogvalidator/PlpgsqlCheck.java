package io.github.eyupmiduck.changelogvalidator;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Runs the {@code plpgsql_check} static analyser over the PL/pgSQL functions in
 * a set of schemas and validates the findings against an allow-list, so a
 * project can fail its build on any unexpected warning.
 *
 * <p>The database must have the {@code plpgsql_check} extension installed.
 * Findings are produced by {@code plpgsql_check_function_tb(...,
 * all_warnings => true)}.
 */
public final class PlpgsqlCheck {

    private PlpgsqlCheck() {
    }

    /**
     * Finds every plpgsql_check warning or error for the functions in
     * {@code schemas}, with all warning categories enabled.
     *
     * @param connection an open connection to the database to check
     * @param schemas    the schemas whose functions are checked
     * @return the findings, ordered by schema, function and line
     * @throws SQLException if the check cannot be run
     */
    public static List<Finding> findFindings(Connection connection, Collection<String> schemas) throws SQLException {
        String[] names = schemas.toArray(String[]::new);
        List<Finding> findings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT n.nspname, p.proname, (issue).lineno, (issue).level,
                       (issue).statement, (issue).message
                FROM pg_catalog.pg_proc AS p
                JOIN pg_catalog.pg_namespace AS n ON n.oid = p.pronamespace
                JOIN pg_catalog.pg_language AS l ON l.oid = p.prolang
                CROSS JOIN LATERAL plpgsql_check_function_tb(
                        p.oid::regprocedure, all_warnings => true
                    ) AS issue
                WHERE n.nspname = ANY (?)
                    AND p.prokind = 'f'
                    AND l.lanname = 'plpgsql'
                ORDER BY 1, 2, 3
                """)) {
            statement.setArray(1, connection.createArrayOf("text", names));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    findings.add(new Finding(
                            resultSet.getString(1),
                            resultSet.getString(2),
                            resultSet.getInt(3),
                            resultSet.getString(4),
                            resultSet.getString(5),
                            resultSet.getString(6)));
                }
            }
        }
        return findings;
    }

    /**
     * Checks the functions in {@code schemas} against the allow-list and
     * reports findings no entry accepted and entries that matched nothing.
     *
     * @param connection an open connection to the database to check
     * @param schemas    the schemas whose functions are checked
     * @param allowed    the accepted findings
     * @return the report; empty when every finding was accepted and no entry is
     * stale
     * @throws SQLException if the check cannot be run
     */
    public static Report check(Connection connection, Collection<String> schemas, List<AllowedFinding> allowed)
            throws SQLException {
        List<Finding> findings = findFindings(connection, schemas);

        List<Finding> unexpected = new ArrayList<>();
        boolean[] matched = new boolean[allowed.size()];
        for (Finding finding : findings) {
            boolean accepted = false;
            for (int i = 0; i < allowed.size(); i++) {
                if (allowed.get(i).matches(finding)) {
                    accepted = true;
                    matched[i] = true;
                }
            }
            if (!accepted) {
                unexpected.add(finding);
            }
        }

        List<AllowedFinding> stale = new ArrayList<>();
        for (int i = 0; i < allowed.size(); i++) {
            if (!matched[i]) {
                stale.add(allowed.get(i));
            }
        }
        return new Report(unexpected, stale);
    }

    /**
     * Reads an allow-list from a YAML document: a list of mappings with
     * {@code schema}, {@code function}, {@code level}, {@code statement} and
     * {@code message} keys (any other keys are ignored). Each entry must set at
     * least one of those keys, so a misspelled entry cannot silently become a
     * catch-all.
     *
     * @param input the YAML document
     * @return the accepted findings
     * @throws IOException if the document is not a list of mappings, or an
     *                     entry sets none of the recognised keys
     */
    public static List<AllowedFinding> loadWhitelist(InputStream input) throws IOException {
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
        if (!(loaded instanceof List<?> entries)) {
            throw new IOException("plpgsql_check whitelist must be a YAML list");
        }

        List<AllowedFinding> allowed = new ArrayList<>();
        for (Object item : entries) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IOException("plpgsql_check whitelist entries must be mappings");
            }
            AllowedFinding finding = new AllowedFinding(
                    asString(entry.get("schema")),
                    asString(entry.get("function")),
                    asString(entry.get("level")),
                    asString(entry.get("statement")),
                    asString(entry.get("message")));
            if (finding.schema() == null && finding.function() == null && finding.level() == null
                    && finding.statement() == null && finding.message() == null) {
                throw new IOException("plpgsql_check whitelist entries must set at least one of "
                        + "schema, function, level, statement or message");
            }
            allowed.add(finding);
        }
        return allowed;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * One warning or error reported by {@code plpgsql_check_function_tb}.
     *
     * @param schema    the function's schema
     * @param function  the function name
     * @param line      the line number the finding points at
     * @param level     the finding level (for example {@code error} or
     *                  {@code warning})
     * @param statement the PL/pgSQL statement type
     * @param message   the finding message
     */
    public record Finding(String schema, String function, int line, String level,
                          String statement, String message) {

        /**
         * Formats the finding for an assertion message.
         *
         * @return a human-readable description
         */
        public String describe() {
            return schema + "." + function + ":" + line + ": " + level + ": " + message;
        }
    }

    /**
     * One accepted finding. A {@code null} field matches any value, so an entry
     * can be narrowed to the fields that identify it.
     *
     * @param schema    the function's schema, or {@code null}
     * @param function  the function name, or {@code null}
     * @param level     the finding level, or {@code null}
     * @param statement the PL/pgSQL statement type, or {@code null}
     * @param message   the finding message, or {@code null}
     */
    public record AllowedFinding(String schema, String function, String level,
                                 String statement, String message) {

        private static boolean matches(String expected, String actual) {
            return expected == null || expected.equals(actual);
        }

        /**
         * Returns whether this entry accepts the given finding.
         *
         * @param finding the finding to test
         * @return {@code true} when every non-null field matches
         */
        public boolean matches(Finding finding) {
            return matches(schema, finding.schema())
                    && matches(function, finding.function())
                    && matches(level, finding.level())
                    && matches(statement, finding.statement())
                    && matches(message, finding.message());
        }

        /**
         * Formats the entry for an assertion message.
         *
         * @return a human-readable description
         */
        public String describe() {
            return schema + "." + function + " " + level + ": " + message;
        }
    }

    /**
     * The result of {@link #check}: findings that were not allowed and entries
     * that matched nothing.
     *
     * @param unexpected findings no entry accepted
     * @param stale      entries that matched no finding
     */
    public record Report(List<Finding> unexpected, List<AllowedFinding> stale) {

        /**
         * Creates an immutable report.
         */
        public Report {
            unexpected = List.copyOf(unexpected);
            stale = List.copyOf(stale);
        }

        /**
         * Returns whether the check passed.
         *
         * @return {@code true} when there are no unexpected findings and no
         * stale entries
         */
        public boolean isEmpty() {
            return unexpected.isEmpty() && stale.isEmpty();
        }
    }
}
