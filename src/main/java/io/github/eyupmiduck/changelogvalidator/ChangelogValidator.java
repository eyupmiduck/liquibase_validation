package io.github.eyupmiduck.changelogvalidator;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Validates a Liquibase changelog that is rooted at a master changelog file.
 *
 * <p>The master file and the XML files it reaches through nested
 * {@code <include>} elements form the changelog graph. Only changesets and SQL
 * files referenced by that graph are considered valid; SQL files no XML in the
 * graph references are reported as orphaned.
 *
 * <p>SQL files are referenced either by a {@code <sqlFile>} element or as the
 * external body of a {@code <createProcedure>} (or {@code <createFunction>})
 * element through its {@code path} attribute. Stored-routine bodies are usually
 * named after the routine rather than with an {@code NNN-} prefix, so SQL files
 * under a stored-routine directory ({@code functions}, {@code procedures}, or
 * their {@code -rollback} variants) are exempt from the naming rule while still
 * being checked for references.
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

    /**
     * Change types that load SQL from an external file through a {@code path}
     * attribute.
     */
    private static final List<String> SQL_REFERENCE_ELEMENTS = List.of("sqlFile", "createProcedure", "createFunction");

    /**
     * Directory names whose SQL files are routine bodies and are therefore
     * exempt from the {@code NNN-} naming rule.
     */
    private static final List<String> ROUTINE_DIRECTORIES =
            List.of("functions", "procedures", "functions-rollback", "procedures-rollback");

    private ChangelogValidator() {
    }

    /**
     * Finds {@code .sql} files under {@code changelogRoot} whose file name does
     * not start with a three-digit, zero-padded integer followed by {@code -}
     * or {@code _} (for example {@code 001-create.sql}). Files under a
     * stored-routine directory (see {@link #ROUTINE_DIRECTORIES}) are routine
     * bodies and are exempt.
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
                    .filter(p -> !isRoutineSqlFile(root, p))
                    .filter(p -> !SQL_FILE_NAME.matcher(p.getFileName().toString()).matches())
                    .map(root::relativize)
                    .forEach(invalid::add);
        }
        invalid.sort(Path::compareTo);
        return invalid;
    }

    private static boolean isRoutineSqlFile(Path root, Path sqlFile) {
        for (Path segment : root.relativize(sqlFile)) {
            if (ROUTINE_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds changesets in the changelog graph rooted at {@code masterChangelog}
     * whose id does not follow the {@code NNN-name} convention.
     *
     * @param changelogRoot   the changelog root, used to resolve includes that
     *                        are not relative to the changelog file
     * @param masterChangelog the master changelog file to traverse
     * @return the invalidly named changesets, sorted by file then id
     * @throws IOException if a changelog file cannot be read
     */
    public static List<InvalidChangeSet> findInvalidlyNamedChangeSets(Path changelogRoot, Path masterChangelog)
            throws IOException {
        return findInvalidlyNamedChangeSets(changelogRoot, masterChangelog, CHANGE_SET_ID);
    }

    /**
     * Finds changesets in the changelog graph rooted at {@code masterChangelog}
     * whose id does not match {@code changeSetIdPattern}.
     *
     * <p>Projects whose stored-routine changesets are named after the routine
     * rather than with an {@code NNN-} prefix can pass a pattern that also
     * accepts those ids, while the default {@link #findInvalidlyNamedChangeSets(Path, Path)}
     * keeps the {@code NNN-name} rule.
     *
     * @param changelogRoot      the changelog root, used to resolve includes
     *                           that are not relative to the changelog file
     * @param masterChangelog    the master changelog file to traverse
     * @param changeSetIdPattern the pattern a changeSet id must match
     * @return the invalidly named changesets, sorted by file then id
     * @throws IOException if a changelog file cannot be read
     */
    public static List<InvalidChangeSet> findInvalidlyNamedChangeSets(Path changelogRoot, Path masterChangelog,
                                                                      Pattern changeSetIdPattern) throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        List<InvalidChangeSet> invalid = new ArrayList<>();
        for (Path changelogFile : reachableChangelogFiles(root, masterChangelog)) {
            Document document = parse(changelogFile);
            NodeList changeSets = document.getElementsByTagName("changeSet");
            for (int i = 0; i < changeSets.getLength(); i++) {
                String id = ((Element) changeSets.item(i)).getAttribute("id");
                if (!changeSetIdPattern.matcher(id).matches()) {
                    invalid.add(new InvalidChangeSet(changelogFile, id));
                }
            }
        }
        invalid.sort(Comparator.comparing(InvalidChangeSet::changelogFile).thenComparing(InvalidChangeSet::id));
        return invalid;
    }

    /**
     * Finds {@code .sql} files under {@code changelogRoot} that no changelog XML
     * reachable from {@code masterChangelog} references, either via a
     * {@code <sqlFile>} element or as the external body of a
     * {@code <createProcedure>} or {@code <createFunction>} element.
     *
     * @param changelogRoot   the changelog directory to scan
     * @param masterChangelog the master changelog file to traverse
     * @return the orphaned SQL files, relative to {@code changelogRoot}
     * @throws IOException if a changelog file cannot be read
     */
    public static List<Path> findOrphanedSqlFiles(Path changelogRoot, Path masterChangelog) throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        Set<Path> referenced = new HashSet<>(findReferencedSqlFiles(root, masterChangelog));

        List<Path> orphaned = new ArrayList<>();
        for (Path sqlFile : findSqlFiles(root)) {
            if (!referenced.contains(sqlFile)) {
                orphaned.add(sqlFile);
            }
        }
        return orphaned;
    }

    /**
     * Finds the {@code .sql} files referenced by the changelog graph rooted at
     * {@code masterChangelog}, relative to {@code changelogRoot}. Callers can
     * use this to assert that the graph references content, so an orphan check
     * cannot pass while traversing nothing.
     *
     * @param changelogRoot   the changelog root, used to resolve references
     *                        that are not relative to the changelog file
     * @param masterChangelog the master changelog file to traverse
     * @return the referenced SQL files, relative to {@code changelogRoot},
     * sorted
     * @throws IOException if a changelog file cannot be read
     */
    public static List<Path> findReferencedSqlFiles(Path changelogRoot, Path masterChangelog) throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        Set<Path> referenced = new HashSet<>();
        for (Path changelogFile : reachableChangelogFiles(root, masterChangelog)) {
            referenced.addAll(referencedSqlFiles(root, changelogFile));
        }
        List<Path> result = new ArrayList<>(referenced);
        result.sort(Path::compareTo);
        return result;
    }

    /**
     * Finds the changelog XML files reachable from {@code masterChangelog} by
     * following nested {@code <include>} elements, starting with the master and
     * guarding against cycles.
     *
     * <p>Callers that need the changelog graph itself (for example the linter's
     * changelog model) can use this instead of walking the includes again. The
     * traversal order is not execution order.
     *
     * @param changelogRoot   the changelog root, used to resolve includes that
     *                        are not relative to the changelog file
     * @param masterChangelog the master changelog file to traverse
     * @return the reachable changelog files, absolute and normalized
     * @throws IOException if a changelog file cannot be read
     */
    public static List<Path> findReachableChangelogFiles(Path changelogRoot, Path masterChangelog)
            throws IOException {
        return reachableChangelogFiles(changelogRoot.toAbsolutePath().normalize(), masterChangelog);
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
                pending.add(resolveWithin(root, base, file, "include"));
            }
        }
        return List.copyOf(visited);
    }

    /**
     * Finds every {@code .sql} file under {@code changelogRoot}, relative to
     * that root. Callers can use this to assert that validation saw content.
     *
     * @param changelogRoot the changelog directory to scan
     * @return the SQL files, relative to {@code changelogRoot}, sorted
     * @throws IOException if the directory cannot be read
     */
    public static List<Path> findSqlFiles(Path changelogRoot) throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        List<Path> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .map(root::relativize)
                    .map(Path::normalize)
                    .forEach(result::add);
        }
        result.sort(Path::compareTo);
        return result;
    }

    private static Set<Path> referencedSqlFiles(Path root, Path changelogFile) throws IOException {
        Set<Path> result = new HashSet<>();
        Document document = parse(changelogFile);
        for (String elementName : SQL_REFERENCE_ELEMENTS) {
            NodeList elements = document.getElementsByTagName(elementName);
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                String path = element.getAttribute("path");
                if (path.isBlank()) {
                    continue;
                }
                boolean relativeToChangelogFile =
                        "true".equalsIgnoreCase(element.getAttribute("relativeToChangelogFile"));
                Path base = relativeToChangelogFile ? changelogFile.getParent() : root;
                result.add(root.relativize(resolveWithin(root, base, path, "SQL file reference")));
            }
        }
        return result;
    }

    /**
     * Resolves a reference against {@code base} and rejects it when it escapes
     * the changelog root, so an include or SQL path cannot reach outside the
     * changelog (for example {@code ../../secret.xml}) or make
     * {@link Path#relativize} fail on a different filesystem root.
     *
     * @param root      the absolute, normalized changelog root
     * @param base      the directory the reference is resolved against
     * @param reference the reference from the changelog
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

    private static Document parse(Path changelogFile) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            return factory.newDocumentBuilder().parse(changelogFile.toFile());
        } catch (SAXException e) {
            throw new IOException("failed to parse changelog " + changelogFile, e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("failed to configure the XML parser", e);
        }
    }

    /**
     * A changeset whose id does not follow the {@code NNN-name} convention.
     *
     * @param changelogFile the changelog file that declares the changeset
     * @param id            the offending id, or an empty string when the attribute is
     *                      missing
     */
    public record InvalidChangeSet(Path changelogFile, String id) {
    }
}
