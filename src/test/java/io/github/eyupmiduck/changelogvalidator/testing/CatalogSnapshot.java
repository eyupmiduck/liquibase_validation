package io.github.eyupmiduck.changelogvalidator.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Captures the schema of a set of PostgreSQL schemas as a map of
 * {@code kind/qualified-name -> document}, for comparing an upgraded database
 * with a from-scratch build.
 *
 * <p>Every object is read from the PostgreSQL dictionary ({@code pg_catalog})
 * with a direct query; the document is built server-side with
 * {@code jsonb_build_object}/{@code jsonb_agg} and rendered with
 * {@code jsonb_pretty}, so it is canonical (object keys sorted, one field per
 * line) and needs no JSON library and no client-side normalisation. Long text
 * that spans lines (a routine body, a view) is captured as an array of its
 * lines, so it reads as text in the document rather than one escaped string.
 *
 * <p>Extension-owned objects, the {@code liquibase} tracking schema and the
 * system schemas are excluded, as are row data, OIDs, sequence
 * {@code last_value} and statistics, none of which are schema.
 *
 * <p>The map can be written to a directory tree (one file per object) so a
 * mismatch can be reviewed with a directory diff and uploaded as a build
 * artifact; see {@link #DEFAULT_DUMP_DIRECTORY}. A routine is written one level
 * deeper, {@code function/<schema>.<name>/<signature>.json}, so an overload's
 * directory holds one file per signature.
 */
public final class CatalogSnapshot {

    /**
     * The default directory a capture is dumped to, relative to the module's
     * working directory: one subtree per database under
     * {@code target/catalog-snapshot}.
     */
    public static final Path DEFAULT_DUMP_DIRECTORY = Path.of("target", "catalog-snapshot");

    /**
     * The longest file-name component a dump uses; a longer name (for example a
     * routine signature with many arguments) is replaced by a content hash so it
     * stays unique and under the filesystem's 255-byte limit.
     */
    private static final int MAX_DUMP_NAME_LENGTH = 200;

    /**
     * The most characters of a document shown in a failure message, so a large
     * divergence does not flood the log.
     */
    private static final int MAX_DESCRIBED_DOCUMENT_LENGTH = 2000;

    /**
     * The system schemas that are never captured.
     */
    private static final List<String> SYSTEM_SCHEMAS =
            List.of("pg_catalog", "information_schema", "liquibase");

    private static final List<String> QUERIES = List.of(
            schemaQuery(),
            relationQuery(),
            columnQuery(),
            constraintQuery(),
            indexQuery(),
            sequenceQuery(),
            domainQuery(),
            typeQuery(),
            functionQuery(),
            triggerQuery(),
            extensionQuery());

    private CatalogSnapshot() {
    }

    /**
     * Captures the objects in the given schemas.
     *
     * @param connection a connection to the database to capture
     * @param schemas    the schemas to capture
     * @return the objects by {@code kind/qualified-name}, in a stable order
     */
    public static Map<String, String> capture(Connection connection, Collection<String> schemas) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(schemas, "schemas");
        List<String> wanted = schemas.stream()
                .filter(schema -> !SYSTEM_SCHEMAS.contains(schema))
                .toList();
        Map<String, String> snapshot = new TreeMap<>();
        if (wanted.isEmpty()) {
            return snapshot;
        }
        for (String query : QUERIES) {
            captureKind(connection, query, wanted, snapshot);
        }
        return snapshot;
    }

    /**
     * Writes a capture as one file per object under
     * {@code root/<kind>/<qualified-name>.json} (a routine, one level deeper), so
     * two captures can be compared with a directory diff and uploaded as a build
     * artifact.
     *
     * <p>The directory is replaced, so a stale dump from an earlier run cannot
     * remain.
     *
     * @param snapshot the capture to write
     * @param root     the directory to write under
     * @throws IOException if a file cannot be written
     */
    public static void dump(Map<String, String> snapshot, Path root) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(root, "root");
        Path base = root.normalize();
        deleteRecursively(base);
        for (Map.Entry<String, String> entry : snapshot.entrySet()) {
            Path file = dumpFile(base, entry.getKey());
            if (!file.startsWith(base)) {
                throw new IllegalArgumentException("object name escapes the dump directory: " + entry.getKey());
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue() + System.lineSeparator(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Returns the file an object is dumped to: a routine nests its signature
     * under a directory named for the routine, every other object is a single
     * file under its kind.
     */
    private static Path dumpFile(Path base, String key) {
        int slash = key.indexOf('/');
        String kind = key.substring(0, slash);
        String qualifiedName = key.substring(slash + 1);
        if ("function".equals(kind)) {
            int open = qualifiedName.indexOf('(');
            if (open > 0) {
                String routine = sanitize(qualifiedName.substring(0, open));
                String signature = dumpName(qualifiedName.substring(open));
                return base.resolve(kind).resolve(routine).resolve(signature + ".json");
            }
        }
        return base.resolve(kind).resolve(dumpName(qualifiedName) + ".json");
    }

    /**
     * Returns a file-name component for an object name. A routine signature can
     * be arbitrarily long (a single component is capped at 255 bytes), so a name
     * that does not fit is replaced by a deterministic short hash of itself; the
     * full signature is still in the document's {@code identity_arguments}.
     */
    private static String dumpName(String name) {
        String sanitized = sanitize(name);
        if (sanitized.length() <= MAX_DUMP_NAME_LENGTH) {
            return sanitized;
        }
        return shortHash(sanitized);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16))
                        .append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Compares a from-scratch capture with an upgraded one.
     *
     * @param fresh   the from-scratch capture (the reference)
     * @param upgrade the upgraded capture
     * @return the difference
     */
    public static Report compare(Map<String, String> fresh, Map<String, String> upgrade) {
        Objects.requireNonNull(fresh, "fresh");
        Objects.requireNonNull(upgrade, "upgrade");
        SortedSet<String> missing = new TreeSet<>(fresh.keySet());
        missing.removeAll(upgrade.keySet());
        SortedSet<String> unexpected = new TreeSet<>(upgrade.keySet());
        unexpected.removeAll(fresh.keySet());
        SortedMap<String, Change> changed = new TreeMap<>();
        for (Map.Entry<String, String> entry : fresh.entrySet()) {
            String actual = upgrade.get(entry.getKey());
            if (actual != null && !actual.equals(entry.getValue())) {
                changed.put(entry.getKey(), new Change(entry.getValue(), actual));
            }
        }
        return new Report(missing, unexpected, changed);
    }

    /**
     * Fails with the difference when the upgraded schema does not match the
     * from-scratch build.
     *
     * @param report the comparison result
     */
    public static void assertEquivalent(Report report) {
        if (!report.equivalent()) {
            throw new AssertionError(report.describe(20));
        }
    }

    private static void captureKind(Connection connection, String query, List<String> schemas,
                                    Map<String, String> snapshot) {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            Array array = connection.createArrayOf("text", schemas.toArray());
            try {
                statement.setArray(1, array);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        snapshot.put(resultSet.getString(1), resultSet.getString(2));
                    }
                }
            } finally {
                array.free();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not capture the schema catalog", e);
        }
    }

    private static String sanitize(String name) {
        StringBuilder sanitized = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            // Parentheses and commas are kept so a routine signature stays
            // readable: function/<schema>.<name>/(integer, text).json.
            sanitized.append(Character.isLetterOrDigit(character) || character == '.' || character == '_'
                    || character == '-' || character == '(' || character == ')' || character == ','
                    ? character : '_');
        }
        return sanitized.toString();
    }

    /**
     * Renders a text value as an array of its lines, so a routine body or a view
     * reads as text in the pretty document instead of one escaped string.
     */
    private static String lines(String expression) {
        return "to_jsonb(string_to_array(" + expression + ", chr(10)))";
    }

    /**
     * The ACL of an object as a sorted array of grants, so the order of
     * {@code aclexplode} cannot make two equivalent ACLs differ.
     */
    private static String acl(String column) {
        return "COALESCE((SELECT jsonb_agg(jsonb_build_object("
                + "'grantee', COALESCE(pg_get_userbyid(x.grantee), 'PUBLIC'), "
                + "'privilege', x.privilege_type, 'grantable', x.is_grantable) "
                + "ORDER BY COALESCE(pg_get_userbyid(x.grantee), 'PUBLIC'), x.privilege_type) "
                + "FROM aclexplode(" + column + ") x), '[]'::jsonb)";
    }

    private static String schemaQuery() {
        return "SELECT 'schema/' || n.nspname, jsonb_pretty(jsonb_build_object("
                + "'owner', pg_get_userbyid(n.nspowner), "
                + "'acl', " + acl("n.nspacl") + ", "
                + "'comment', obj_description(n.oid, 'pg_namespace'))) "
                + "FROM pg_namespace n WHERE n.nspname = ANY (?)";
    }

    private static String relationQuery() {
        return "SELECT 'relation/' || n.nspname || '.' || c.relname, jsonb_pretty(jsonb_build_object("
                + "'kind', CASE c.relkind WHEN 'r' THEN 'table' WHEN 'p' THEN 'partitioned_table' "
                + "WHEN 'v' THEN 'view' WHEN 'm' THEN 'materialized_view' WHEN 'f' THEN 'foreign_table' END, "
                + "'owner', pg_get_userbyid(c.relowner), "
                + "'persistence', c.relpersistence, "
                + "'view_definition', CASE WHEN c.relkind IN ('v', 'm') "
                + "THEN " + lines("pg_get_viewdef(c.oid, true)") + " END, "
                + "'partition_key', CASE WHEN c.relkind = 'p' THEN pg_get_partkeydef(c.oid) END, "
                + "'partition_bound', CASE WHEN c.relispartition THEN pg_get_expr(c.relpartbound, c.oid) END, "
                + "'options', to_jsonb(c.reloptions), "
                + "'acl', " + acl("c.relacl") + ", "
                + "'comment', obj_description(c.oid, 'pg_class'))) "
                + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ANY (?) AND c.relkind IN ('r', 'p', 'v', 'm', 'f')";
    }

    private static String columnQuery() {
        return "SELECT 'column/' || n.nspname || '.' || c.relname || '.' || a.attname, jsonb_pretty(jsonb_build_object("
                + "'type', format_type(a.atttypid, a.atttypmod), "
                + "'not_null', a.attnotnull, "
                + "'identity', NULLIF(a.attidentity, ''), "
                + "'generated', NULLIF(a.attgenerated, ''), "
                + "'collation', CASE WHEN a.attcollation <> t.typcollation "
                + "THEN (SELECT col.collname FROM pg_collation col WHERE col.oid = a.attcollation) END, "
                + "'default', pg_get_expr(ad.adbin, ad.adrelid), "
                + "'comment', col_description(c.oid, a.attnum))) "
                + "FROM pg_attribute a "
                + "JOIN pg_class c ON c.oid = a.attrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "JOIN pg_type t ON t.oid = a.atttypid "
                + "LEFT JOIN pg_attrdef ad ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum "
                + "WHERE n.nspname = ANY (?) AND c.relkind IN ('r', 'p', 'v', 'm', 'f') "
                + "AND a.attnum > 0 AND NOT a.attisdropped";
    }

    private static String constraintQuery() {
        return "SELECT 'constraint/' || n.nspname || '.' || c.relname || '.' || con.conname, "
                + "jsonb_pretty(jsonb_build_object("
                + "'type', con.contype, "
                + "'definition', pg_get_constraintdef(con.oid, true), "
                + "'deferrable', con.condeferrable, "
                + "'deferred', con.condeferred, "
                + "'validated', con.convalidated, "
                + "'comment', obj_description(con.oid, 'pg_constraint'))) "
                + "FROM pg_constraint con "
                + "JOIN pg_class c ON c.oid = con.conrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ANY (?)";
    }

    private static String indexQuery() {
        return "SELECT 'index/' || n.nspname || '.' || ic.relname, jsonb_pretty(jsonb_build_object("
                + "'table', tn.nspname || '.' || tc.relname, "
                + "'definition', pg_get_indexdef(i.indexrelid), "
                + "'primary', i.indisprimary, 'unique', i.indisunique, 'valid', i.indisvalid, "
                + "'comment', obj_description(ic.oid, 'pg_class'))) "
                + "FROM pg_index i "
                + "JOIN pg_class ic ON ic.oid = i.indexrelid "
                + "JOIN pg_namespace n ON n.oid = ic.relnamespace "
                + "JOIN pg_class tc ON tc.oid = i.indrelid "
                + "JOIN pg_namespace tn ON tn.oid = tc.relnamespace "
                + "WHERE n.nspname = ANY (?)";
    }

    private static String sequenceQuery() {
        return "SELECT 'sequence/' || n.nspname || '.' || c.relname, jsonb_pretty(jsonb_build_object("
                + "'data_type', format_type(s.seqtypid, NULL), "
                + "'start', s.seqstart, 'increment', s.seqincrement, "
                + "'min', s.seqmin, 'max', s.seqmax, 'cache', s.seqcache, 'cycle', s.seqcycle, "
                + "'owner', pg_get_userbyid(c.relowner), "
                + "'acl', " + acl("c.relacl") + ", "
                + "'comment', obj_description(c.oid, 'pg_class'))) "
                + "FROM pg_sequence s "
                + "JOIN pg_class c ON c.oid = s.seqrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ANY (?)";
    }

    private static String domainQuery() {
        return "SELECT 'domain/' || n.nspname || '.' || t.typname, jsonb_pretty(jsonb_build_object("
                + "'base_type', format_type(t.typbasetype, t.typtypmod), "
                + "'not_null', t.typnotnull, "
                + "'default', t.typdefault, "
                + "'owner', pg_get_userbyid(t.typowner), "
                + "'acl', " + acl("t.typacl") + ", "
                + "'comment', obj_description(t.oid, 'pg_type'), "
                + "'constraints', COALESCE((SELECT jsonb_agg(jsonb_build_object("
                + "'name', con.conname, 'definition', pg_get_constraintdef(con.oid, true)) ORDER BY con.conname) "
                + "FROM pg_constraint con WHERE con.contypid = t.oid), '[]'::jsonb))) "
                + "FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace "
                + "WHERE n.nspname = ANY (?) AND t.typtype = 'd'";
    }

    private static String typeQuery() {
        return "SELECT 'type/' || n.nspname || '.' || t.typname, jsonb_pretty(jsonb_build_object("
                + "'typtype', t.typtype, "
                + "'owner', pg_get_userbyid(t.typowner), "
                + "'comment', obj_description(t.oid, 'pg_type'), "
                + "'attributes', CASE WHEN t.typtype = 'c' THEN (SELECT jsonb_agg(jsonb_build_object("
                + "'name', a.attname, 'type', format_type(a.atttypid, a.atttypmod)) ORDER BY a.attnum) "
                + "FROM pg_attribute a WHERE a.attrelid = t.typrelid AND a.attnum > 0 AND NOT a.attisdropped) END, "
                + "'enum_labels', CASE WHEN t.typtype = 'e' THEN (SELECT jsonb_agg(e.enumlabel ORDER BY e.enumsortorder) "
                + "FROM pg_enum e WHERE e.enumtypid = t.oid) END, "
                + "'range_subtype', CASE WHEN t.typtype = 'r' THEN format_type(r.rngsubtype, NULL) END)) "
                + "FROM pg_type t "
                + "JOIN pg_namespace n ON n.oid = t.typnamespace "
                + "LEFT JOIN pg_class c ON c.oid = t.typrelid "
                + "LEFT JOIN pg_range r ON r.rngtypid = t.oid "
                + "WHERE n.nspname = ANY (?) AND (t.typtype IN ('e', 'r') OR (t.typtype = 'c' AND c.relkind = 'c'))";
    }

    private static String functionQuery() {
        // The key is the identity by argument type only (not
        // pg_get_function_identity_arguments, which also embeds parameter
        // names), so overloads are distinct, order-independent entries and a
        // parameter rename shows up as a changed document rather than a
        // missing/unexpected pair.
        return "SELECT 'function/' || n.nspname || '.' || p.proname || '(' "
                + "|| COALESCE((SELECT string_agg(format_type(t.argtype, NULL), ', ' ORDER BY t.ord) "
                + "FROM unnest(p.proargtypes) WITH ORDINALITY AS t(argtype, ord)), '') || ')', "
                + "jsonb_pretty(jsonb_build_object("
                + "'schema', n.nspname, 'name', p.proname, "
                + "'identity_arguments', pg_get_function_identity_arguments(p.oid), "
                + "'returns', pg_get_function_result(p.oid), "
                + "'kind', p.prokind, 'language', l.lanname, "
                + "'volatility', p.provolatile, 'strict', p.proisstrict, "
                + "'security_definer', p.prosecdef, 'leakproof', p.proleakproof, "
                + "'parallel', p.proparallel, 'cost', p.procost, 'rows', p.prorows, "
                + "'owner', pg_get_userbyid(p.proowner), "
                + "'acl', " + acl("p.proacl") + ", "
                + "'comment', obj_description(p.oid, 'pg_proc'), "
                + "'definition', " + lines("pg_get_functiondef(p.oid)") + ")) "
                + "FROM pg_proc p "
                + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                + "JOIN pg_language l ON l.oid = p.prolang "
                + "WHERE n.nspname = ANY (?) AND p.prokind IN ('f', 'p') "
                + "AND NOT EXISTS (SELECT 1 FROM pg_depend d "
                + "WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')";
    }

    private static String triggerQuery() {
        return "SELECT 'trigger/' || n.nspname || '.' || c.relname || '.' || t.tgname, jsonb_pretty(jsonb_build_object("
                + "'definition', pg_get_triggerdef(t.oid, true), "
                + "'enabled', t.tgenabled, "
                + "'comment', obj_description(t.oid, 'pg_trigger'))) "
                + "FROM pg_trigger t "
                + "JOIN pg_class c ON c.oid = t.tgrelid "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = ANY (?) AND NOT t.tgisinternal";
    }

    private static String extensionQuery() {
        return "SELECT 'extension/' || e.extname, jsonb_pretty(jsonb_build_object("
                + "'version', e.extversion, "
                + "'schema', n.nspname, "
                + "'comment', obj_description(e.oid, 'pg_extension'))) "
                + "FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace "
                + "WHERE n.nspname = ANY (?) AND e.extname <> 'plpgsql'";
    }

    /**
     * A document that differs between the two captures.
     *
     * @param expected the from-scratch document
     * @param actual   the upgraded document
     */
    public record Change(String expected, String actual) {
    }

    /**
     * The difference between a from-scratch capture and an upgraded one.
     *
     * @param missing    objects the from-scratch build has but the upgrade does
     *                   not (the dangerous case: the upgrade path is incomplete)
     * @param unexpected objects the upgrade has but a from-scratch build does
     *                   not
     * @param changed    objects present in both with a different document
     */
    public record Report(SortedSet<String> missing, SortedSet<String> unexpected,
                         SortedMap<String, Change> changed) {

        /**
         * Defensively copies and orders the collections.
         */
        public Report {
            missing = Collections.unmodifiableSortedSet(new TreeSet<>(missing));
            unexpected = Collections.unmodifiableSortedSet(new TreeSet<>(unexpected));
            changed = Collections.unmodifiableSortedMap(new TreeMap<>(changed));
        }

        private static void appendKeys(StringBuilder message, String heading,
                                       SortedSet<String> keys, int limit) {
            if (keys.isEmpty()) {
                return;
            }
            message.append(System.lineSeparator())
                    .append("  ").append(heading).append(" (").append(keys.size()).append("):");
            keys.stream().limit(limit)
                    .forEach(key -> message.append(System.lineSeparator()).append("    ").append(key));
        }

        private static String abbreviate(String document) {
            if (document.length() <= MAX_DESCRIBED_DOCUMENT_LENGTH) {
                return document;
            }
            return document.substring(0, MAX_DESCRIBED_DOCUMENT_LENGTH) + "...";
        }

        /**
         * Returns whether the two captures describe the same schema.
         *
         * @return {@code true} when there is no difference
         */
        public boolean equivalent() {
            return missing.isEmpty() && unexpected.isEmpty() && changed.isEmpty();
        }

        /**
         * Renders the difference for a failure message, limiting each section so
         * a large divergence does not flood the log.
         *
         * @param limit the most entries to list per section
         * @return a human-readable description
         */
        public String describe(int limit) {
            if (equivalent()) {
                return "the upgraded schema matches the from-scratch build";
            }
            StringBuilder message = new StringBuilder(
                    "the upgraded schema differs from the from-scratch build");
            appendKeys(message, "missing in the upgraded database", missing, limit);
            appendKeys(message, "unexpected in the upgraded database", unexpected, limit);
            if (!changed.isEmpty()) {
                message.append(System.lineSeparator())
                        .append("  changed (").append(changed.size()).append("):");
                changed.entrySet().stream().limit(limit).forEach(entry -> message
                        .append(System.lineSeparator()).append("    ").append(entry.getKey())
                        .append(System.lineSeparator()).append("      from-scratch: ")
                        .append(abbreviate(entry.getValue().expected()))
                        .append(System.lineSeparator()).append("      upgraded:     ")
                        .append(abbreviate(entry.getValue().actual())));
            }
            return message.toString();
        }
    }
}
