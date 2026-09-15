package io.github.eyupmiduck.changelogvalidator;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Validates a Liquibase changelog directory for orphaned SQL files.
 */
public final class ChangelogValidator {

    /**
     * Matches changelog file names of the form {@code NNN-name.ext} or
     * {@code NNN_name.ext}, where {@code NNN} is a three-digit, zero-padded
     * integer and {@code ext} is {@code sql} or {@code xml}.
     */
    private static final Pattern CHANGELOG_FILE_NAME = Pattern.compile("\\d{3}[-_].+\\.(sql|xml)");

    private ChangelogValidator() {
    }

    /**
     * Finds {@code .sql} and {@code .xml} files under {@code changelogRoot}
     * whose file name does not start with a three-digit, zero-padded integer
     * followed by {@code -} or {@code _} (for example {@code 001-create.sql}).
     *
     * @param changelogRoot the changelog directory to scan
     * @return the invalidly named files, relative to {@code changelogRoot}
     * @throws IOException if the directory cannot be read
     */
    public static List<Path> findInvalidlyNamedFiles(Path changelogRoot) throws IOException {
        List<Path> invalid = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(changelogRoot)) {
            paths.filter(Files::isRegularFile)
                    .filter(ChangelogValidator::isChangelogFile)
                    .filter(p -> !CHANGELOG_FILE_NAME.matcher(p.getFileName().toString()).matches())
                    .map(changelogRoot::relativize)
                    .forEach(invalid::add);
        }
        invalid.sort(Path::compareTo);
        return invalid;
    }

    private static boolean isChangelogFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".sql") || name.endsWith(".xml");
    }

    /**
     * Finds {@code .sql} files under {@code changelogRoot} that are not
     * referenced by any changelog XML file via a {@code <sqlFile>} element.
     *
     * @param changelogRoot the changelog directory to scan
     * @return the orphaned SQL files, relative to {@code changelogRoot}
     * @throws IOException if the directory cannot be read
     */
    public static List<Path> findOrphanedSqlFiles(Path changelogRoot) throws IOException {
        Set<Path> sqlFiles = findSqlFiles(changelogRoot);
        Set<Path> referenced = findReferencedSqlFiles(changelogRoot);

        List<Path> orphaned = new ArrayList<>();
        for (Path sqlFile : sqlFiles) {
            if (!referenced.contains(sqlFile)) {
                orphaned.add(sqlFile);
            }
        }
        orphaned.sort(Path::compareTo);
        return orphaned;
    }

    private static Set<Path> findSqlFiles(Path root) throws IOException {
        Set<Path> result = new HashSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .forEach(p -> result.add(root.relativize(p).normalize()));
        }
        return result;
    }

    private static Set<Path> findReferencedSqlFiles(Path root) throws IOException {
        Set<Path> result = new HashSet<>();
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> xmlFiles = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".xml"))
                    .toList();
            for (Path xmlFile : xmlFiles) {
                result.addAll(referencesIn(xmlFile, root));
            }
        }
        return result;
    }

    private static Set<Path> referencesIn(Path xmlFile, Path root) {
        Set<Path> result = new HashSet<>();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            Document document = factory.newDocumentBuilder().parse(xmlFile.toFile());
            NodeList sqlFiles = document.getElementsByTagName("sqlFile");
            for (int i = 0; i < sqlFiles.getLength(); i++) {
                Element element = (Element) sqlFiles.item(i);
                String path = element.getAttribute("path");
                if (path == null || path.isBlank()) {
                    continue;
                }
                boolean relativeToChangelogFile =
                        "true".equalsIgnoreCase(element.getAttribute("relativeToChangelogFile"));
                Path resolved = relativeToChangelogFile
                        ? xmlFile.getParent().resolve(path)
                        : root.resolve(path);
                result.add(root.relativize(resolved).normalize());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse " + xmlFile, e);
        }
        return result;
    }
}
