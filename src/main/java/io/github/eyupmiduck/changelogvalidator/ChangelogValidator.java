package io.github.eyupmiduck.changelogvalidator;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Validates a Liquibase changelog that is rooted at a master changelog file.
 *
 * <p>The master file and the XML files it reaches through nested
 * {@code <include>} elements form the changelog graph. Only changesets and SQL
 * files referenced by that graph are considered valid; SQL files no XML in the
 * graph references are reported as orphaned.
 */
public final class ChangelogValidator {

    /**
     * Matches changeSet ids of the form {@code NNN-name} or {@code NNN_name},
     * where {@code NNN} is a three-digit, zero-padded integer.
     */
    private static final Pattern CHANGE_SET_ID = Pattern.compile("\\d{3}[-_].+");

    /**
     * Matches SQL file names of the form {@code NNN-name.sql} or
     * {@code NNN_name.sql}, where {@code NNN} is a three-digit, zero-padded
     * integer.
     */
    private static final Pattern SQL_FILE_NAME = Pattern.compile("\\d{3}[-_].+\\.sql");

    private ChangelogValidator() {
    }

    /**
     * A changeset whose id does not follow the {@code NNN-name} convention.
     *
     * @param changelogFile the changelog file that declares the changeset
     * @param id the offending id, or an empty string when the attribute is
     *           missing
     */
    public record InvalidChangeSet(Path changelogFile, String id) {
    }

    /**
     * Finds {@code .sql} files under {@code changelogRoot} whose file name does
     * not start with a three-digit, zero-padded integer followed by {@code -}
     * or {@code _} (for example {@code 001-create.sql}).
     *
     * @param changelogRoot the changelog directory to scan
     * @return the invalidly named SQL files, relative to {@code changelogRoot}
     * @throws IOException if the directory cannot be read
     */
    public static List<Path> findInvalidlyNamedSqlFiles(Path changelogRoot) throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        List<Path> invalid = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .filter(p -> !SQL_FILE_NAME.matcher(p.getFileName().toString()).matches())
                    .map(root::relativize)
                    .forEach(invalid::add);
        }
        invalid.sort(Path::compareTo);
        return invalid;
    }

    /**
     * Finds changesets in the changelog graph rooted at {@code masterChangelog}
     * whose id does not follow the {@code NNN-name} convention.
     *
     * @param changelogRoot the changelog root, used to resolve includes that
     *                      are not relative to the changelog file
     * @param masterChangelog the master changelog file to traverse
     * @return the invalidly named changesets, sorted by file then id
     * @throws IOException if a changelog file cannot be read
     */
    public static List<InvalidChangeSet> findInvalidlyNamedChangeSets(Path changelogRoot, Path masterChangelog)
            throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        List<InvalidChangeSet> invalid = new ArrayList<>();
        for (Path changelogFile : reachableChangelogFiles(root, masterChangelog)) {
            Document document = parse(changelogFile);
            NodeList changeSets = document.getElementsByTagName("changeSet");
            for (int i = 0; i < changeSets.getLength(); i++) {
                String id = ((Element) changeSets.item(i)).getAttribute("id");
                if (!CHANGE_SET_ID.matcher(id).matches()) {
                    invalid.add(new InvalidChangeSet(changelogFile, id));
                }
            }
        }
        invalid.sort(Comparator.comparing(InvalidChangeSet::changelogFile).thenComparing(InvalidChangeSet::id));
        return invalid;
    }

    /**
     * Finds {@code .sql} files under {@code changelogRoot} that no changelog XML
     * reachable from {@code masterChangelog} references via a {@code <sqlFile>}
     * element.
     *
     * @param changelogRoot the changelog directory to scan
     * @param masterChangelog the master changelog file to traverse
     * @return the orphaned SQL files, relative to {@code changelogRoot}
     * @throws IOException if a changelog file cannot be read
     */
    public static List<Path> findOrphanedSqlFiles(Path changelogRoot, Path masterChangelog) throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        Set<Path> referenced = new HashSet<>();
        for (Path changelogFile : reachableChangelogFiles(root, masterChangelog)) {
            referenced.addAll(sqlFileReferences(root, changelogFile));
        }

        List<Path> orphaned = new ArrayList<>();
        for (Path sqlFile : findSqlFiles(root)) {
            if (!referenced.contains(sqlFile)) {
                orphaned.add(sqlFile);
            }
        }
        orphaned.sort(Path::compareTo);
        return orphaned;
    }

    /**
     * Collects the changelog files reachable from {@code masterChangelog} by
     * following nested {@code <include>} elements, guarding against cycles.
     */
    private static List<Path> reachableChangelogFiles(Path root, Path masterChangelog) throws IOException {
        Set<Path> visited = new LinkedHashSet<>();
        Deque<Path> pending = new ArrayDeque<>();
        pending.add(masterChangelog.toAbsolutePath().normalize());
        while (!pending.isEmpty()) {
            Path changelogFile = pending.removeFirst();
            if (!visited.add(changelogFile)) {
                continue;
            }
            Document document = parse(changelogFile);
            NodeList includes = document.getElementsByTagName("include");
            for (int i = 0; i < includes.getLength(); i++) {
                Element include = (Element) includes.item(i);
                String file = include.getAttribute("file");
                if (file.isBlank()) {
                    continue;
                }
                boolean relativeToChangelogFile =
                        "true".equalsIgnoreCase(include.getAttribute("relativeToChangelogFile"));
                Path base = relativeToChangelogFile ? changelogFile.getParent() : root;
                pending.add(base.resolve(file).normalize());
            }
        }
        return List.copyOf(visited);
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

    private static Set<Path> sqlFileReferences(Path root, Path changelogFile) {
        Set<Path> result = new HashSet<>();
        Document document = parse(changelogFile);
        NodeList sqlFiles = document.getElementsByTagName("sqlFile");
        for (int i = 0; i < sqlFiles.getLength(); i++) {
            Element element = (Element) sqlFiles.item(i);
            String path = element.getAttribute("path");
            if (path.isBlank()) {
                continue;
            }
            boolean relativeToChangelogFile =
                    "true".equalsIgnoreCase(element.getAttribute("relativeToChangelogFile"));
            Path base = relativeToChangelogFile ? changelogFile.getParent() : root;
            result.add(root.relativize(base.resolve(path).normalize()));
        }
        return result;
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
