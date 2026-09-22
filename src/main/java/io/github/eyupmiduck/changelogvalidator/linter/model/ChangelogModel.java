package io.github.eyupmiduck.changelogvalidator.linter.model;

import io.github.eyupmiduck.changelogvalidator.ChangelogValidator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a Liquibase changelog graph into a stream of {@link ChangeSet}s with
 * their attributes and resolved SQL sources.
 *
 * <p>The changelog graph is the one {@link ChangelogValidator} traverses, so
 * this class does not walk {@code <include>} elements itself. Relative paths
 * are resolved against the declaring changelog file; paths without
 * {@code relativeToChangelogFile} are resolved against the changelog root.
 *
 * <p>Formatted-SQL changelogs are not parsed here: a {@code <sqlFile>} that
 * points at a formatted SQL file is exposed as a {@link SqlSource.Kind#SQL_FILE}
 * source, and the caller decides how to read it.
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
        for (Path changelogFile : ChangelogValidator.findReachableChangelogFiles(root, masterChangelog)) {
            changesets.addAll(changesetsIn(root, changelogFile));
        }
        return List.copyOf(changesets);
    }

    private static List<ChangeSet> changesetsIn(Path root, Path changelogFile) {
        Document document = parse(changelogFile);
        NodeList nodes = document.getElementsByTagName("changeSet");
        List<ChangeSet> changesets = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            changesets.add(changeSet(root, changelogFile, (Element) nodes.item(i)));
        }
        return changesets;
    }

    private static ChangeSet changeSet(Path root, Path changelogFile, Element element) {
        List<SqlSource> sqlSources = new ArrayList<>();
        List<SqlSource> rollbackSources = new ArrayList<>();
        boolean rollbackDefined = false;
        for (Element child : childElements(element)) {
            if ("rollback".equals(child.getTagName())) {
                rollbackDefined = true;
                for (Element rollbackChild : childElements(child)) {
                    rollbackSources.addAll(sqlSources(root, changelogFile, rollbackChild));
                }
            } else {
                sqlSources.addAll(sqlSources(root, changelogFile, child));
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
                rollbackSources);
    }

    private static List<SqlSource> sqlSources(Path root, Path changelogFile, Element element) {
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
            case "createProcedure", "createFunction" ->
                    fileSource(SqlSource.Kind.ROUTINE_BODY, root, changelogFile, element);
            default -> List.of();
        };
    }

    private static List<SqlSource> fileSource(SqlSource.Kind kind, Path root, Path changelogFile, Element element) {
        String path = element.getAttribute("path");
        if (path.isBlank()) {
            return List.of();
        }
        boolean relativeToChangelogFile = "true".equalsIgnoreCase(element.getAttribute("relativeToChangelogFile"));
        Path base = relativeToChangelogFile ? changelogFile.getParent() : root;
        return List.of(new SqlSource(
                kind,
                base.resolve(path).normalize(),
                null,
                booleanAttribute(element, "splitStatements", true),
                delimiterAttribute(element),
                booleanAttribute(element, "stripComments", false),
                optionalAttribute(element, "dbms")));
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

    private static Document parse(Path changelogFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(changelogFile.toFile());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse " + changelogFile, e);
        }
    }
}
