package io.github.eyupmiduck.changelogvalidator.linter.model;

import io.github.eyupmiduck.changelogvalidator.ChangelogValidator;
import io.github.eyupmiduck.changelogvalidator.XmlDocuments;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads a Liquibase changelog graph into a stream of {@link ChangeSet}s with
 * their attributes and resolved SQL sources.
 *
 * <p>The graph is walked in Liquibase parse order: a file's children (its
 * properties, changesets and includes) are processed in document order, and an
 * include is followed immediately before the next sibling. Relative paths are
 * resolved against the declaring changelog file; paths without
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
 * <p>Formatted-SQL changelogs are not parsed here: a {@code <sqlFile>} that
 * points at a formatted SQL file is exposed as a {@link SqlSource.Kind#SQL_FILE}
 * source, and the caller decides how to read it.
 *
 * <p>The model also captures what the rules need beyond the raw SQL: the
 * changelog {@code <property>} values, each changeset's {@code <modifySql>}
 * transformations, and a best-effort SQL rendering of the structured change
 * types that rules match on ({@code createTable}, {@code createIndex},
 * {@code dropIndex}). Structured types that are not rendered simply contribute
 * no source. See ADR 0003.
 */
public final class ChangelogModel {

    private static final String DEFAULT_END_DELIMITER = ";";

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
        Path root = changelogRoot.toAbsolutePath().normalize();
        List<ChangeSet> changesets = new ArrayList<>();
        visit(root, masterChangelog.toAbsolutePath().normalize(), new LinkedHashMap<>(),
                new LinkedHashMap<>(), changesets, new LinkedHashSet<>());
        return List.copyOf(changesets);
    }

    /**
     * Walks one changelog file in document order, applying its properties before
     * the changesets that follow them and following includes where they appear.
     * Global properties are shared across the graph; local properties are copied
     * into the include chain so a child's local properties do not leak back to a
     * parent or sibling. The visited set prevents an include cycle from looping.
     */
    private static void visit(Path root, Path changelogFile, Map<String, String> globals,
                              Map<String, String> inheritedLocals, List<ChangeSet> changesets,
                              Set<Path> visited) throws IOException {
        Path normalized = changelogFile.toAbsolutePath().normalize();
        if (!visited.add(normalized)) {
            return;
        }
        Map<String, String> locals = new LinkedHashMap<>(inheritedLocals);
        Document document = parse(normalized);
        for (Element child : childElements(document.getDocumentElement())) {
            switch (child.getTagName()) {
                case "property" -> addProperty(globals, locals, child);
                case "include" -> {
                    String file = child.getAttribute("file");
                    if (file.isBlank()) {
                        continue;
                    }
                    boolean relativeToChangelogFile =
                            "true".equalsIgnoreCase(child.getAttribute("relativeToChangelogFile"));
                    Path base = relativeToChangelogFile ? normalized.getParent() : root;
                    visit(root, resolveWithin(root, base, file, "include"), globals, locals,
                            changesets, visited);
                }
                case "changeSet" -> changesets.add(changeSet(root, normalized, child, snapshot(globals, locals)));
                case "includeAll" -> throw new IOException(
                        "includeAll is not supported by the linter; list the included changelogs explicitly: "
                                + child.getAttribute("path"));
                default -> {
                    // Other databaseChangeLog children (preConditions, ...) carry no
                    // changeset or property this model needs.
                }
            }
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
                                       Map<String, String> properties) throws IOException {
        List<SqlSource> sqlSources = new ArrayList<>();
        List<SqlSource> rollbackSources = new ArrayList<>();
        List<SqlModification> modifySql = new ArrayList<>();
        boolean rollbackDefined = false;
        for (Element child : childElements(element)) {
            switch (child.getTagName()) {
                case "rollback" -> {
                    rollbackDefined = true;
                    for (Element rollbackChild : childElements(child)) {
                        rollbackSources.addAll(sourcesFor(root, changelogFile, rollbackChild));
                    }
                }
                case "modifySql" -> modifySql.addAll(modifications(child));
                default -> sqlSources.addAll(sourcesFor(root, changelogFile, child));
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
                new Normalisation(properties, modifySql));
    }

    /**
     * Resolves a changeSet child into SQL sources: an inline or file source, or,
     * for a structured change type the model renders, a synthetic inline source.
     * The same resolution is used for forward and rollback children.
     */
    private static List<SqlSource> sourcesFor(Path root, Path changelogFile, Element child) throws IOException {
        List<SqlSource> sources = sqlSources(root, changelogFile, child);
        if (sources.isEmpty()) {
            String structured = structuredSql(child);
            if (structured != null) {
                sources = List.of(inlineSql(structured));
            }
        }
        return sources;
    }

    private static List<SqlModification> modifications(Element modifySql) {
        String dbms = optionalAttribute(modifySql, "dbms");
        boolean applyToRollback = booleanAttribute(modifySql, "applyToRollback", false);
        List<SqlModification> modifications = new ArrayList<>();
        for (Element child : childElements(modifySql)) {
            SqlModification.Kind kind = SqlModification.Kind.from(child.getTagName());
            if (kind == null) {
                continue;
            }
            String value;
            String with = null;
            if (kind == SqlModification.Kind.REPLACE || kind == SqlModification.Kind.REGEXP_REPLACE) {
                value = child.getAttribute("replace");
                with = child.getAttribute("with");
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

    private static SqlSource inlineSql(String sql) {
        return new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, DEFAULT_END_DELIMITER, false, null);
    }

    private static String structuredSql(Element change) {
        return switch (change.getTagName()) {
            case "createTable" -> createTableSql(change);
            case "createIndex" -> createIndexSql(change);
            case "dropIndex" -> dropIndexSql(change);
            default -> null;
        };
    }

    private static String createTableSql(Element change) {
        String schema = change.getAttribute("schemaName");
        String table = qualified(schema, change.getAttribute("tableName"));
        if (table == null) {
            return null;
        }
        List<String> columns = new ArrayList<>();
        for (Element column : childElements(change)) {
            if (!"column".equals(column.getTagName()) || column.getAttribute("name").isBlank()) {
                continue;
            }
            String type = column.getAttribute("type");
            columns.add(type.isBlank() ? column.getAttribute("name")
                    : column.getAttribute("name") + " " + type);
        }
        String body = columns.isEmpty() ? "" : " (" + String.join(", ", columns) + ")";
        return "CREATE TABLE " + table + body + ";";
    }

    private static String createIndexSql(Element change) {
        String schema = change.getAttribute("schemaName");
        String index = qualified(schema, change.getAttribute("indexName"));
        String table = qualified(schema, change.getAttribute("tableName"));
        if (index == null || table == null) {
            return null;
        }
        List<String> columns = new ArrayList<>();
        for (Element column : childElements(change)) {
            if ("column".equals(column.getTagName()) && !column.getAttribute("name").isBlank()) {
                columns.add(column.getAttribute("name"));
            }
        }
        String unique = booleanAttribute(change, "unique", false) ? "CREATE UNIQUE INDEX " : "CREATE INDEX ";
        String columnsText = columns.isEmpty() ? "" : " (" + String.join(", ", columns) + ")";
        return unique + index + " ON " + table + columnsText + ";";
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

    private static List<SqlSource> sqlSources(Path root, Path changelogFile, Element element) throws IOException {
        return switch (element.getTagName()) {
            case "sql" -> List.of(new SqlSource(
                    SqlSource.Kind.INLINE_SQL,
                    null,
                    element.getTextContent(),
                    booleanAttribute(element, "splitStatements", true),
                    delimiterAttribute(element),
                    booleanAttribute(element, "stripComments", false),
                    optionalAttribute(element, "dbms")));
            case "sqlFile" -> fileSource(SqlSource.Kind.SQL_FILE, root, changelogFile, element);
            case "createProcedure", "createFunction" -> routineSource(root, changelogFile, element);
            default -> List.of();
        };
    }

    /**
     * Reads a {@code <createProcedure>}/{@code <createFunction>} source: from the
     * {@code path} attribute when present, otherwise from the element's inline
     * text (Liquibase also allows the routine body as the element's content).
     */
    private static List<SqlSource> routineSource(Path root, Path changelogFile, Element element) throws IOException {
        if (!element.getAttribute("path").isBlank()) {
            return fileSource(SqlSource.Kind.ROUTINE_BODY, root, changelogFile, element);
        }
        String text = element.getTextContent();
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return List.of(new SqlSource(
                SqlSource.Kind.ROUTINE_BODY,
                null,
                text,
                booleanAttribute(element, "splitStatements", true),
                delimiterAttribute(element),
                booleanAttribute(element, "stripComments", false),
                optionalAttribute(element, "dbms")));
    }

    private static List<SqlSource> fileSource(SqlSource.Kind kind, Path root, Path changelogFile, Element element)
            throws IOException {
        String path = element.getAttribute("path");
        if (path.isBlank()) {
            return List.of();
        }
        boolean relativeToChangelogFile = "true".equalsIgnoreCase(element.getAttribute("relativeToChangelogFile"));
        Path base = relativeToChangelogFile ? changelogFile.getParent() : root;
        return List.of(new SqlSource(
                kind,
                resolveWithin(root, base, path, "SQL file reference"),
                null,
                booleanAttribute(element, "splitStatements", true),
                delimiterAttribute(element),
                booleanAttribute(element, "stripComments", false),
                optionalAttribute(element, "dbms")));
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
        Path resolved = base.resolve(reference).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException(kind + " escapes the changelog root: " + reference);
        }
        return resolved;
    }

    private static boolean booleanAttribute(Element element, String name, boolean defaultValue) {
        return element.hasAttribute(name) ? Boolean.parseBoolean(element.getAttribute(name)) : defaultValue;
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
}
