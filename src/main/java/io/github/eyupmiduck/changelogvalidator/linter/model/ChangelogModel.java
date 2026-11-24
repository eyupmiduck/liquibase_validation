package io.github.eyupmiduck.changelogvalidator.linter.model;

import io.github.eyupmiduck.changelogvalidator.ChangelogIncludes;
import io.github.eyupmiduck.changelogvalidator.ChangelogValidator;
import io.github.eyupmiduck.changelogvalidator.XmlDocuments;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Reads a Liquibase changelog graph into a stream of {@link ChangeSet}s with
 * their attributes and resolved SQL sources.
 *
 * <p>The graph is walked in Liquibase parse order: a file's children (its
 * properties, changesets and includes) are processed in document order, and an
 * include is followed immediately before the next sibling. An {@code <includeAll>}
 * is expanded in alphabetical path order at the point where it appears. Relative
 * paths are resolved against the declaring changelog file; paths without
 * {@code relativeToChangelogFile} are resolved against the changelog root. A
 * reference that escapes the root is rejected, matching the validator.
 *
 * <p>Properties are captured as a snapshot at each changeset, so a property
 * defined later does not affect an earlier changeset. Global properties are
 * shared across the graph, while a {@code global="false"} property is local to
 * its file and included files. Property definitions whose {@code dbms} filter
 * excludes PostgreSQL are ignored, so a later applicable definition can win.
 * Context and label filters remain unapplied because the linter has no run
 * context (see ADR 0003).
 *
 * <p>Formatted-SQL changelogs are parsed: a {@code .sql} file included through
 * {@code <include>} (or used as the master) is read as a Liquibase formatted SQL
 * changelog, with its {@code --changeset} and {@code --rollback} directives. A
 * {@code .sql} file found by {@code <includeAll>} is parsed the same way when it
 * carries the marker; an unformatted file is a single raw change, matching
 * Liquibase's content-based parser selection.
 *
 * <p>The model also captures what the rules need beyond the raw SQL: the
 * changelog {@code <property>} values, each changeset's {@code <modifySql>}
 * transformations, and a best-effort SQL rendering of the structured change
 * types that rules match on. The rendered types are {@code createTable},
 * {@code createIndex}, {@code dropIndex}, {@code addColumn}, {@code dropColumn},
 * {@code renameColumn}, {@code modifyDataType}, the not-null and default-value
 * changes, the foreign-key/unique/check/primary-key changes, and the sequence
 * changes. A structured type that is not rendered, or one missing a required
 * attribute, simply contributes no source. See ADR 0003.
 */
public final class ChangelogModel {

    private static final String DEFAULT_END_DELIMITER = ";";
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern INTEGER_OR_DECIMAL = Pattern.compile("-?\\d+(?:\\.\\d+)?");

    private ChangelogModel() {
    }

    /**
     * Reads the changesets of the changelog graph rooted at {@code
     * masterChangelog}.
     *
     * @param changelogRoot   the changelog root, used to resolve references that
     *                        are not relative to the changelog file
     * @param masterChangelog the master changelog file to traverse
     * @return the changesets, in traversal order
     * @throws IOException if a changelog file cannot be read
     */
    public static List<ChangeSet> changesets(Path changelogRoot, Path masterChangelog) throws IOException {
        return project(changelogRoot, masterChangelog).changesets();
    }

    /**
     * Reads the whole changelog graph: the visited files and their includes, the
     * effective properties, and the changesets in execution order.
     *
     * @param changelogRoot   the changelog root, used to resolve references that
     *                        are not relative to the changelog file
     * @param masterChangelog the master changelog file to traverse
     * @return the project
     * @throws IOException if a changelog file cannot be read
     */
    public static ChangelogProject project(Path changelogRoot, Path masterChangelog) throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        Path master = masterChangelog.toAbsolutePath().normalize();
        Map<String, String> globals = new LinkedHashMap<>();
        ProjectAccumulator accumulator = new ProjectAccumulator();
        visit(root, master, globals, new LinkedHashMap<>(), accumulator, new LinkedHashSet<>());
        return accumulator.project(root, master, globals);
    }

    /**
     * Walks one changelog file in document order, applying its properties before
     * the changesets that follow them and following includes where they appear.
     * Global properties are shared across the graph; local properties are copied
     * into the include chain so a child's local properties do not leak back to a
     * parent or sibling. Only the active include chain is tracked, so cycles are
     * rejected while a legitimate repeated include in another branch is still
     * visited.
     */
    private static void visit(Path root, Path changelogFile, Map<String, String> globals,
                              Map<String, String> inheritedLocals, ProjectAccumulator accumulator,
                              Set<Path> active) throws IOException {
        Path normalized = changelogFile.toAbsolutePath().normalize();
        if (active.contains(normalized)) {
            throw new IOException("circular changelog include: " + normalized);
        }
        active.add(normalized);
        Map<String, String> locals = new LinkedHashMap<>(inheritedLocals);
        FileBuilder file = new FileBuilder(normalized, snapshot(globals, inheritedLocals));
        accumulator.files.add(file);
        try {
            if (ChangelogIncludes.isSqlFile(normalized)) {
                for (ChangeSet changeSet : FormattedSqlChangelog.parse(normalized,
                        snapshot(globals, locals))) {
                    accumulator.changesets.add(changeSet);
                    file.changesets.add(changeSet);
                }
            } else {
                Document document = parse(normalized);
                Map<Element, Integer> lines = elementLines(document, XmlDocuments.startTagLines(normalized));
                for (Element child : childElements(document.getDocumentElement())) {
                    switch (tagName(child)) {
                        case "property" -> addProperty(globals, locals, child);
                        case "include" -> {
                            String included = child.getAttribute("file");
                            if (included.isBlank()) {
                                throw new IOException("include without a file attribute: " + normalized);
                            }
                            boolean relativeToChangelogFile =
                                    booleanAttribute(child, "relativeToChangelogFile", false);
                            Path base = relativeToChangelogFile ? normalized.getParent() : root;
                            Path resolved = resolveWithin(root, base, included, "include");
                            file.includes.add(resolved);
                            visit(root, resolved, globals, locals, accumulator, active);
                        }
                        case "changeSet" -> {
                            ChangeSet changeSet = changeSet(root, normalized, child,
                                    snapshot(globals, locals), lines);
                            accumulator.changesets.add(changeSet);
                            file.changesets.add(changeSet);
                        }
                        case "includeAll" -> {
                            for (Path included : ChangelogIncludes.includeAll(root, normalized, child)) {
                                file.includes.add(included);
                                if (ChangelogIncludes.isSqlFile(included)
                                        && !ChangelogIncludes.isFormattedSql(included)) {
                                    addIncludeAllSql(root, included, globals, locals, accumulator);
                                } else {
                                    visit(root, included, globals, locals, accumulator, active);
                                }
                            }
                        }
                        default -> {
                            // Other databaseChangeLog children (preConditions, ...) carry no
                            // changeset or property this model needs.
                        }
                    }
                }
            }
        } finally {
            file.properties = snapshot(globals, locals);
            active.remove(normalized);
        }
    }

    /**
     * Adds one unformatted SQL file found by {@code <includeAll>}. Liquibase
     * treats such a file as a single raw change rather than a formatted SQL
     * changelog, so its changeset id is the file path relative to the changelog
     * root and its author is {@code includeAll}. The SQL file's own content is
     * read by the linter. A file with the formatted-SQL marker is parsed as a
     * changelog instead.
     */
    private static void addIncludeAllSql(Path root, Path file, Map<String, String> globals,
                                         Map<String, String> locals, ProjectAccumulator accumulator) {
        Path normalized = file.toAbsolutePath().normalize();
        Map<String, String> properties = snapshot(globals, locals);
        FileBuilder sqlFile = new FileBuilder(normalized, properties);
        accumulator.files.add(sqlFile);
        String id = root.relativize(normalized).toString().replace(File.separatorChar, '/');
        SqlSource source = new SqlSource(SqlSource.Kind.SQL_FILE, normalized, null,
                true, DEFAULT_END_DELIMITER, false, null, 1);
        ChangeSet changeSet = new ChangeSet(id, "includeAll", normalized, true, false,
                null, null, null, List.of(source), false, List.of(),
                new Normalisation(properties, List.of()), 1);
        accumulator.changesets.add(changeSet);
        sqlFile.changesets.add(changeSet);
    }

    /**
     * Returns the local element name, so a prefixed Liquibase namespace is handled
     * the same as the default namespace.
     */
    private static String tagName(Element element) {
        String local = element.getLocalName();
        return local == null ? element.getTagName() : local;
    }

    /**
     * Maps each DOM element to the line of its start tag (its closing line for a
     * tag whose attributes wrap). The SAX parse and the DOM recursion both visit
     * elements in document order, so each tag name's line queue supplies the
     * matching element.
     */
    private static Map<Element, Integer> elementLines(Document document,
                                                      Map<String, Deque<Integer>> linesByTag) {
        Map<Element, Integer> lines = new IdentityHashMap<>();
        assignLines(document.getDocumentElement(), linesByTag, lines);
        return lines;
    }

    private static void assignLines(Element element, Map<String, Deque<Integer>> linesByTag,
                                    Map<Element, Integer> lines) {
        Deque<Integer> tagLines = linesByTag.get(element.getTagName());
        if (tagLines != null && !tagLines.isEmpty()) {
            lines.put(element, tagLines.removeFirst());
        }
        for (Element child : childElements(element)) {
            assignLines(child, linesByTag, lines);
        }
    }

    /**
     * Returns the property snapshot visible at a changeset. Liquibase consults
     * global parameters before local ones, so a global definition wins when both
     * are present.
     */
    private static Map<String, String> snapshot(Map<String, String> globals, Map<String, String> locals) {
        Map<String, String> visible = new LinkedHashMap<>(globals);
        locals.forEach(visible::putIfAbsent);
        return visible;
    }

    /**
     * Records a property whose {@code dbms} filter includes PostgreSQL. A
     * property scoped to another database is ignored so it cannot shadow the
     * first applicable definition. Global properties are shared graph-wide; a
     * local property lives in the current file and its includes.
     */
    private static void addProperty(Map<String, String> globals, Map<String, String> locals, Element property) {
        String name = property.getAttribute("name");
        if (name.isBlank() || !property.hasAttribute("value")) {
            return;
        }
        String dbms = optionalAttribute(property, "dbms");
        if (!SqlModification.appliesToPostgres(dbms)) {
            return;
        }
        Map<String, String> target = booleanAttribute(property, "global", true) ? globals : locals;
        target.putIfAbsent(name, property.getAttribute("value"));
    }

    private static ChangeSet changeSet(Path root, Path changelogFile, Element element,
                                       Map<String, String> properties,
                                       Map<Element, Integer> lines) throws IOException {
        List<SqlSource> sqlSources = new ArrayList<>();
        List<SqlSource> rollbackSources = new ArrayList<>();
        List<SqlModification> modifySql = new ArrayList<>();
        boolean rollbackDefined = false;
        for (Element child : childElements(element)) {
            switch (tagName(child)) {
                case "rollback" -> {
                    rollbackDefined = true;
                    List<Element> rollbackChildren = childElements(child);
                    if (rollbackChildren.isEmpty()) {
                        String text = child.getTextContent();
                        if (text != null && !text.isBlank()) {
                            rollbackSources.add(inlineSql(text, lines.getOrDefault(child, 1)));
                        }
                    } else {
                        for (Element rollbackChild : rollbackChildren) {
                            rollbackSources.addAll(sourcesFor(root, changelogFile, rollbackChild,
                                    lines.getOrDefault(rollbackChild, 1)));
                        }
                    }
                }
                case "modifySql" -> modifySql.addAll(modifications(child));
                default -> sqlSources.addAll(sourcesFor(root, changelogFile, child,
                        lines.getOrDefault(child, 1)));
            }
        }
        return new ChangeSet(
                element.getAttribute("id"),
                element.getAttribute("author"),
                changelogFile,
                booleanAttribute(element, "runInTransaction", true),
                booleanAttribute(element, "runOnChange", false),
                optionalAttribute(element, "dbms"),
                optionalAttribute(element, "context"),
                optionalAttribute(element, "labels"),
                sqlSources,
                rollbackDefined,
                rollbackSources,
                new Normalisation(properties, modifySql),
                lines.getOrDefault(element, 1));
    }

    /**
     * Resolves a changeSet child into SQL sources: an inline or file source, or,
     * for a structured change type the model renders, a synthetic inline source.
     * The same resolution is used for forward and rollback children.
     */
    private static List<SqlSource> sourcesFor(Path root, Path changelogFile, Element child, int line)
            throws IOException {
        List<SqlSource> sources = sqlSources(root, changelogFile, child, line);
        if (sources.isEmpty()) {
            String structured = structuredSql(child);
            if (structured != null) {
                sources = List.of(inlineSql(structured, line));
            }
        }
        return sources;
    }

    private static List<SqlModification> modifications(Element modifySql) {
        String dbms = optionalAttribute(modifySql, "dbms");
        boolean applyToRollback = booleanAttribute(modifySql, "applyToRollback", false);
        List<SqlModification> modifications = new ArrayList<>();
        for (Element child : childElements(modifySql)) {
            SqlModification.Kind kind = SqlModification.Kind.from(tagName(child));
            if (kind == null) {
                continue;
            }
            String value;
            String with = null;
            if (kind == SqlModification.Kind.REPLACE || kind == SqlModification.Kind.REGEXP_REPLACE) {
                value = child.getAttribute("replace");
                with = child.hasAttribute("with") ? child.getAttribute("with") : null;
            } else {
                value = child.getAttribute("value");
            }
            if (value.isEmpty()) {
                continue;
            }
            modifications.add(new SqlModification(kind, value, with, applyToRollback, dbms));
        }
        return modifications;
    }

    private static SqlSource inlineSql(String sql, int line) {
        return new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, DEFAULT_END_DELIMITER, false, null, line);
    }

    private static String structuredSql(Element change) {
        return switch (tagName(change)) {
            case "createTable" -> createTableSql(change);
            case "createIndex" -> createIndexSql(change);
            case "dropIndex" -> dropIndexSql(change);
            case "addColumn" -> addColumnSql(change);
            case "dropColumn" -> dropColumnSql(change);
            case "renameColumn" -> renameColumnSql(change);
            case "modifyDataType" -> modifyDataTypeSql(change);
            case "addNotNullConstraint" -> notNullSql(change, "SET NOT NULL");
            case "dropNotNullConstraint" -> notNullSql(change, "DROP NOT NULL");
            case "addDefaultValue" -> addDefaultValueSql(change);
            case "dropDefaultValue" -> dropDefaultValueSql(change);
            case "addForeignKeyConstraint" -> addForeignKeySql(change);
            case "addUniqueConstraint" -> addUniqueSql(change);
            case "addCheckConstraint" -> addCheckSql(change);
            case "addPrimaryKey" -> addPrimaryKeySql(change);
            case "dropPrimaryKey" -> dropPrimaryKeySql(change);
            case "createSequence" -> createSequenceSql(change);
            case "dropSequence" -> dropSequenceSql(change);
            default -> null;
        };
    }

    private static String addColumnSql(Element change) {
        String table = table(change);
        if (table == null) {
            return null;
        }
        List<String> clauses = new ArrayList<>();
        for (Element column : childElements(change)) {
            if (!"column".equals(tagName(column))) {
                continue;
            }
            String name = column.getAttribute("name");
            String type = column.getAttribute("type");
            if (name.isBlank() || type.isBlank()) {
                continue;
            }
            StringBuilder clause = new StringBuilder("ADD COLUMN ").append(name).append(' ').append(type);
            if (notNull(column)) {
                clause.append(" NOT NULL");
            }
            clauses.add(clause.toString());
        }
        return clauses.isEmpty() ? null : "ALTER TABLE " + table + " " + String.join(", ", clauses) + ";";
    }

    private static boolean notNull(Element column) {
        for (Element child : childElements(column)) {
            if ("constraints".equals(tagName(child))
                    && "false".equalsIgnoreCase(child.getAttribute("nullable"))) {
                return true;
            }
        }
        return false;
    }

    private static String dropColumnSql(Element change) {
        String table = table(change);
        if (table == null) {
            return null;
        }
        List<String> columns = new ArrayList<>();
        String columnName = change.getAttribute("columnName");
        if (!columnName.isBlank()) {
            columns.add(columnName);
        }
        for (Element column : childElements(change)) {
            if ("column".equals(tagName(column)) && !column.getAttribute("name").isBlank()) {
                columns.add(column.getAttribute("name"));
            }
        }
        if (columns.isEmpty()) {
            return null;
        }
        List<String> clauses = columns.stream().map(column -> "DROP COLUMN " + column).toList();
        return "ALTER TABLE " + table + " " + String.join(", ", clauses) + ";";
    }

    private static String renameColumnSql(Element change) {
        String table = table(change);
        String oldName = change.getAttribute("oldColumnName");
        String newName = change.getAttribute("newColumnName");
        if (table == null || oldName.isBlank() || newName.isBlank()) {
            return null;
        }
        return "ALTER TABLE " + table + " RENAME COLUMN " + oldName + " TO " + newName + ";";
    }

    private static String modifyDataTypeSql(Element change) {
        String table = table(change);
        String column = change.getAttribute("columnName");
        String type = change.getAttribute("newDataType");
        if (table == null || column.isBlank() || type.isBlank()) {
            return null;
        }
        return "ALTER TABLE " + table + " ALTER COLUMN " + column + " TYPE " + type + ";";
    }

    private static String notNullSql(Element change, String action) {
        String table = table(change);
        String column = change.getAttribute("columnName");
        if (table == null || column.isBlank()) {
            return null;
        }
        return "ALTER TABLE " + table + " ALTER COLUMN " + column + " " + action + ";";
    }

    private static String addDefaultValueSql(Element change) {
        String table = table(change);
        String column = change.getAttribute("columnName");
        String expression = defaultExpression(change);
        if (table == null || column.isBlank() || expression == null) {
            return null;
        }
        return "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DEFAULT " + expression + ";";
    }

    private static String defaultExpression(Element change) {
        String computed = change.getAttribute("defaultValueComputed");
        if (!computed.isBlank()) {
            return computed;
        }
        String sequence = change.getAttribute("defaultValueSequenceNext");
        if (!sequence.isBlank()) {
            return "nextval(" + sqlString(sequence) + "::regclass)";
        }
        String numeric = change.getAttribute("defaultValueNumeric");
        if (INTEGER_OR_DECIMAL.matcher(numeric).matches()) {
            return numeric;
        }
        String bool = change.getAttribute("defaultValueBoolean");
        if ("true".equalsIgnoreCase(bool) || "false".equalsIgnoreCase(bool)) {
            return bool.toUpperCase(Locale.ROOT);
        }
        if (change.hasAttribute("defaultValueDate") && !change.getAttribute("defaultValueDate").isBlank()) {
            return sqlString(change.getAttribute("defaultValueDate"));
        }
        if (change.hasAttribute("defaultValue")) {
            return sqlString(change.getAttribute("defaultValue"));
        }
        return null;
    }

    private static String dropDefaultValueSql(Element change) {
        String table = table(change);
        String column = change.getAttribute("columnName");
        if (table == null || column.isBlank()) {
            return null;
        }
        return "ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP DEFAULT;";
    }

    private static String addForeignKeySql(Element change) {
        String base = qualified(firstNonBlank(change.getAttribute("baseTableSchemaName"),
                change.getAttribute("schemaName")), change.getAttribute("baseTableName"));
        String referenced = qualified(change.getAttribute("referencedTableSchemaName"),
                change.getAttribute("referencedTableName"));
        String baseColumns = change.getAttribute("baseColumnNames");
        String referencedColumns = change.getAttribute("referencedColumnNames");
        if (base == null || referenced == null
                || baseColumns.isBlank() || referencedColumns.isBlank()) {
            return null;
        }
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(base).append(" ADD ");
        appendConstraintName(sql, change);
        sql.append("FOREIGN KEY (").append(baseColumns).append(") REFERENCES ")
                .append(referenced).append(" (").append(referencedColumns).append(')');
        appendAction(sql, "ON DELETE", change.getAttribute("onDelete"));
        appendAction(sql, "ON UPDATE", change.getAttribute("onUpdate"));
        appendDeferrable(sql, change);
        if (!booleanAttribute(change, "validate", true)) {
            sql.append(" NOT VALID");
        }
        return sql.append(';').toString();
    }

    private static String addUniqueSql(Element change) {
        String table = table(change);
        String columns = columnNames(change);
        if (table == null || columns == null) {
            return null;
        }
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(table).append(" ADD ");
        appendConstraintName(sql, change);
        sql.append("UNIQUE (").append(columns).append(')');
        appendDeferrable(sql, change);
        // PostgreSQL only accepts NOT VALID for CHECK and FOREIGN KEY.
        return sql.append(';').toString();
    }

    private static String addCheckSql(Element change) {
        String table = table(change);
        String body = change.getAttribute("constraintBody");
        if (table == null || body.isBlank()) {
            return null;
        }
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(table).append(" ADD ");
        appendConstraintName(sql, change);
        sql.append("CHECK (").append(body).append(')');
        if (!booleanAttribute(change, "validate", true)) {
            sql.append(" NOT VALID");
        }
        return sql.append(';').toString();
    }

    private static String addPrimaryKeySql(Element change) {
        String table = table(change);
        if (table == null) {
            return null;
        }
        String index = change.getAttribute("forIndexName");
        String indexSchema = change.getAttribute("forIndexSchemaName");
        String tableSchema = change.getAttribute("schemaName");
        if (!index.isBlank() && !indexSchema.isBlank() && !indexSchema.equalsIgnoreCase(tableSchema)) {
            // PostgreSQL requires the index to be in the table's schema; a
            // cross-schema index would make the rendered statement invalid.
            return null;
        }
        StringBuilder sql = new StringBuilder("ALTER TABLE ").append(table).append(" ADD ");
        appendConstraintName(sql, change);
        sql.append("PRIMARY KEY ");
        if (!index.isBlank()) {
            sql.append("USING INDEX ").append(index);
        } else {
            String columns = columnNames(change);
            if (columns == null) {
                return null;
            }
            sql.append('(').append(columns).append(')');
        }
        return sql.append(';').toString();
    }

    private static String dropPrimaryKeySql(Element change) {
        String table = table(change);
        String constraint = change.getAttribute("constraintName");
        if (table == null || constraint.isBlank()) {
            return null;
        }
        return "ALTER TABLE " + table + " DROP CONSTRAINT " + constraint + ";";
    }

    private static String createSequenceSql(Element change) {
        String sequence = qualified(change.getAttribute("schemaName"), change.getAttribute("sequenceName"));
        if (sequence == null) {
            return null;
        }
        StringBuilder sql = new StringBuilder("CREATE SEQUENCE ").append(sequence);
        String dataType = change.getAttribute("dataType");
        if ("smallint".equalsIgnoreCase(dataType)
                || "integer".equalsIgnoreCase(dataType)
                || "bigint".equalsIgnoreCase(dataType)) {
            sql.append(" AS ").append(dataType);
        }
        appendNumeric(sql, "INCREMENT BY", change.getAttribute("incrementBy"));
        appendBound(sql, "MINVALUE", "NOMINVALUE", change.getAttribute("minValue"));
        appendBound(sql, "MAXVALUE", "NOMAXVALUE", change.getAttribute("maxValue"));
        appendNumeric(sql, "START WITH", change.getAttribute("startValue"));
        appendNumeric(sql, "CACHE", change.getAttribute("cacheSize"));
        if (booleanAttribute(change, "cycle", false)) {
            sql.append(" CYCLE");
        }
        // ORDER is Liquibase's Oracle RAC option; PostgreSQL's CREATE SEQUENCE has
        // no ORDER clause, so it is not rendered.
        return sql.append(';').toString();
    }

    private static String dropSequenceSql(Element change) {
        String sequence = qualified(change.getAttribute("schemaName"), change.getAttribute("sequenceName"));
        return sequence == null ? null : "DROP SEQUENCE " + sequence + ";";
    }

    private static String table(Element change) {
        return qualified(change.getAttribute("schemaName"), change.getAttribute("tableName"));
    }

    private static String columnNames(Element change) {
        String names = change.getAttribute("columnNames");
        if (!names.isBlank()) {
            return names;
        }
        List<String> columns = new ArrayList<>();
        for (Element column : childElements(change)) {
            if ("column".equals(tagName(column)) && !column.getAttribute("name").isBlank()) {
                columns.add(column.getAttribute("name"));
            }
        }
        return columns.isEmpty() ? null : String.join(", ", columns);
    }

    private static void appendConstraintName(StringBuilder sql, Element change) {
        String name = change.getAttribute("constraintName");
        if (!name.isBlank()) {
            sql.append("CONSTRAINT ").append(name).append(' ');
        }
    }

    private static void appendAction(StringBuilder sql, String clause, String action) {
        if (!action.isBlank()) {
            sql.append(' ').append(clause).append(' ').append(action.toUpperCase(Locale.ROOT));
        }
    }

    private static void appendDeferrable(StringBuilder sql, Element change) {
        if (booleanAttribute(change, "deferrable", false)) {
            sql.append(" DEFERRABLE");
            if (booleanAttribute(change, "initiallyDeferred", false)) {
                sql.append(" INITIALLY DEFERRED");
            }
        }
    }

    private static void appendNumeric(StringBuilder sql, String clause, String value) {
        if (INTEGER.matcher(value).matches()) {
            sql.append(' ').append(clause).append(' ').append(value);
        }
    }

    private static void appendBound(StringBuilder sql, String clause, String noToken, String value) {
        if (value.isBlank()) {
            return;
        }
        if (noToken.equalsIgnoreCase(value)) {
            sql.append(" NO ").append(clause);
        } else if (INTEGER.matcher(value).matches()) {
            sql.append(' ').append(clause).append(' ').append(value);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String sqlString(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String createTableSql(Element change) {
        String schema = change.getAttribute("schemaName");
        String table = qualified(schema, change.getAttribute("tableName"));
        if (table == null) {
            return null;
        }
        List<String> columns = new ArrayList<>();
        for (Element column : childElements(change)) {
            if (!"column".equals(tagName(column)) || column.getAttribute("name").isBlank()) {
                continue;
            }
            String type = column.getAttribute("type");
            columns.add(type.isBlank() ? column.getAttribute("name")
                    : column.getAttribute("name") + " " + type);
        }
        if (columns.isEmpty()) {
            // A table needs a column list; rendering "CREATE TABLE t;" would feed
            // invalid SQL to the token rules. ADR 0003: no render, no source.
            return null;
        }
        return "CREATE TABLE " + table + " (" + String.join(", ", columns) + ");";
    }

    private static String createIndexSql(Element change) {
        String schema = change.getAttribute("schemaName");
        String index = qualified(schema, change.getAttribute("indexName"));
        String table = qualified(schema, change.getAttribute("tableName"));
        if (table == null) {
            return null;
        }
        List<String> columns = new ArrayList<>();
        for (Element column : childElements(change)) {
            if ("column".equals(tagName(column)) && !column.getAttribute("name").isBlank()) {
                columns.add(column.getAttribute("name"));
            }
        }
        if (columns.isEmpty()) {
            // An index needs a column list; "CREATE INDEX i ON t;" is invalid SQL.
            return null;
        }
        // indexName is optional in Liquibase: PostgreSQL generates the name for an
        // unnamed CREATE INDEX, so the change still contributes SQL to the rules.
        String name = index == null ? "" : index + " ";
        String unique = booleanAttribute(change, "unique", false) ? "CREATE UNIQUE INDEX " : "CREATE INDEX ";
        return unique + name + "ON " + table + " (" + String.join(", ", columns) + ");";
    }

    private static String dropIndexSql(Element change) {
        String index = qualified(change.getAttribute("schemaName"), change.getAttribute("indexName"));
        return index == null ? null : "DROP INDEX " + index + ";";
    }

    private static String qualified(String schema, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return schema == null || schema.isBlank() ? name : schema + "." + name;
    }

    private static List<SqlSource> sqlSources(Path root, Path changelogFile, Element element, int line)
            throws IOException {
        return switch (tagName(element)) {
            case "sql" -> List.of(new SqlSource(
                    SqlSource.Kind.INLINE_SQL,
                    null,
                    element.getTextContent(),
                    booleanAttribute(element, "splitStatements", true),
                    delimiterAttribute(element),
                    booleanAttribute(element, "stripComments", false),
                    optionalAttribute(element, "dbms"),
                    line));
            case "sqlFile" -> fileSource(SqlSource.Kind.SQL_FILE, root, changelogFile, element, line);
            case "createProcedure", "createFunction" -> routineSource(root, changelogFile, element, line);
            default -> List.of();
        };
    }

    /**
     * Reads a {@code <createProcedure>}/{@code <createFunction>} source: from the
     * {@code path} attribute when present, otherwise from the element's inline
     * text (Liquibase also allows the routine body as the element's content).
     */
    private static List<SqlSource> routineSource(Path root, Path changelogFile, Element element, int line)
            throws IOException {
        if (!element.getAttribute("path").isBlank()) {
            return fileSource(SqlSource.Kind.ROUTINE_BODY, root, changelogFile, element, line);
        }
        String text = element.getTextContent();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(new SqlSource(
                SqlSource.Kind.ROUTINE_BODY,
                null,
                text,
                // Liquibase executes a routine body as one statement and does not
                // support splitStatements on these change types.
                false,
                delimiterAttribute(element),
                booleanAttribute(element, "stripComments", false),
                optionalAttribute(element, "dbms"),
                line));
    }

    private static List<SqlSource> fileSource(SqlSource.Kind kind, Path root, Path changelogFile, Element element,
                                              int line) throws IOException {
        String path = element.getAttribute("path");
        if (path.isBlank()) {
            // fileSource is only reached for sqlFile (routineSource handles an inline
            // body first), and Liquibase requires sqlFile/@path.
            throw new IOException("sqlFile without a path attribute: " + changelogFile);
        }
        boolean relativeToChangelogFile = booleanAttribute(element, "relativeToChangelogFile", false);
        Path base = relativeToChangelogFile ? changelogFile.getParent() : root;
        return List.of(new SqlSource(
                kind,
                resolveWithin(root, base, path, "SQL file reference"),
                null,
                // Liquibase executes a routine body as one statement and does not
                // support splitStatements on createProcedure/createFunction.
                kind != SqlSource.Kind.ROUTINE_BODY
                        && booleanAttribute(element, "splitStatements", true),
                delimiterAttribute(element),
                booleanAttribute(element, "stripComments", false),
                optionalAttribute(element, "dbms"),
                line));
    }

    /**
     * Resolves a SQL reference against {@code base} and rejects one that escapes
     * the changelog root, so the linter applies the same containment policy as
     * {@link ChangelogValidator}.
     *
     * @param root      the absolute, normalized changelog root
     * @param base      the directory the reference is resolved against
     * @param reference the path from the changelog
     * @param kind      what is being resolved, for the error message
     * @return the absolute, normalized path, guaranteed to be under {@code root}
     * @throws IOException if the reference escapes the changelog root
     */
    private static Path resolveWithin(Path root, Path base, String reference, String kind) throws IOException {
        return ChangelogIncludes.resolveWithin(root, base, reference, kind);
    }

    private static boolean booleanAttribute(Element element, String name, boolean defaultValue) {
        if (!element.hasAttribute(name)) {
            return defaultValue;
        }
        Boolean parsed = LiquibaseBoolean.parse(element.getAttribute(name));
        // Liquibase's BooleanParser falls back to the caller's default for an
        // unrecognised spelling; do the same so a malformed value does not
        // silently flip a default-true attribute to false.
        return parsed == null ? defaultValue : parsed;
    }

    private static String delimiterAttribute(Element element) {
        return element.hasAttribute("endDelimiter") ? element.getAttribute("endDelimiter") : DEFAULT_END_DELIMITER;
    }

    private static String optionalAttribute(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                elements.add(child);
            }
        }
        return elements;
    }

    private static Document parse(Path changelogFile) throws IOException {
        return XmlDocuments.parse(changelogFile);
    }

    /**
     * Accumulates the graph while {@link #visit} walks it.
     */
    private static final class ProjectAccumulator {

        private final List<FileBuilder> files = new ArrayList<>();
        private final List<ChangeSet> changesets = new ArrayList<>();

        private ChangelogProject project(Path root, Path master, Map<String, String> properties) {
            List<ChangelogProject.File> finished = files.stream()
                    .map(FileBuilder::build)
                    .toList();
            return new ChangelogProject(root, master, finished, changesets, properties);
        }
    }

    /**
     * A mutable file record while the traversal is still adding its includes and
     * changesets.
     */
    private static final class FileBuilder {

        private final Path path;
        private final List<Path> includes = new ArrayList<>();
        private final List<ChangeSet> changesets = new ArrayList<>();
        private Map<String, String> properties;

        private FileBuilder(Path path, Map<String, String> properties) {
            this.path = path;
            this.properties = properties;
        }

        private ChangelogProject.File build() {
            return new ChangelogProject.File(path, includes, properties, changesets);
        }
    }
}
