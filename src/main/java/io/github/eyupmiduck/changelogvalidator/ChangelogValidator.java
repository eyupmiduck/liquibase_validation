package io.github.eyupmiduck.changelogvalidator;

import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.FormattedSqlChangelog;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Validates a Liquibase changelog that is rooted at a master changelog file.
 *
 * <p>The master file and the changelog files it reaches through nested
 * {@code <include>} and {@code <includeAll>} elements form the changelog graph.
 * An included file may be XML or a Liquibase formatted SQL changelog. Only
 * changesets and SQL files referenced by that graph are considered valid; SQL
 * files that no changelog references are reported as orphaned.
 *
 * <p>SQL files are referenced either by a {@code <sqlFile>} element or as the
 * external body of a {@code <createProcedure>} (or {@code <createFunction>})
 * element through its {@code path} attribute. Stored routines and triggers are
 * usually named after the object rather than with an {@code NNN-} prefix, so SQL
 * files under a dedicated directory ({@code functions}, {@code procedures},
 * {@code triggers}, or their {@code -rollback} variants) are exempt from the
 * naming rule while still being checked for references.
 *
 * <p>Changelog XML is parsed defensively: a {@code DOCTYPE} declaration and
 * external entities are rejected, so a crafted changelog cannot read a local
 * file or reach the network.
 */
public final class ChangelogValidator {

    /**
     * Matches changeSet ids of the form {@code NNN-name} or {@code NNN_name},
     * where {@code NNN} is a three-digit, zero-padded integer.
     */
    private static final Pattern CHANGE_SET_ID = Pattern.compile(ChangelogNaming.NUMBERED_ID);

    /**
     * Matches SQL file names of the form {@code NNN-name.sql} or
     * {@code NNN_name.sql}, where {@code NNN} is a three-digit, zero-padded
     * integer.
     */
    private static final Pattern SQL_FILE_NAME = Pattern.compile(ChangelogNaming.NUMBERED_ID + "\\.sql", Pattern.CASE_INSENSITIVE);

    /**
     * Change types that load SQL from an external file through a {@code path}
     * attribute.
     */
    private static final List<String> SQL_REFERENCE_ELEMENTS = List.of("sqlFile", "createProcedure", "createFunction");

    /**
     * Directory names whose SQL files are named after a stored routine or
     * trigger and are therefore exempt from the {@code NNN-} naming rule.
     */
    private static final List<String> EXEMPT_DIRECTORIES =
            List.of("functions", "procedures", "triggers",
                    "functions-rollback", "procedures-rollback", "triggers-rollback");

    private ChangelogValidator() {
    }

    /**
     * Finds {@code .sql} files under {@code changelogRoot} whose file name does
     * not start with a three-digit, zero-padded integer followed by {@code -}
     * or {@code _} (for example {@code 001-create.sql}). Files under an exempt
     * directory (see {@link #EXEMPT_DIRECTORIES}) are named after a stored
     * routine or trigger and are exempt, and a formatted SQL changelog (a file
     * whose first non-blank line is {@code --liquibase formatted sql}) is a
     * changelog rather than a numbered SQL change, so it is exempt too.
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
                    .filter(ChangelogValidator::hasSqlExtension)
                    .filter(p -> !isExemptSqlFile(root, p))
                    .filter(p -> !isFormattedSqlChangelog(p))
                    .filter(p -> !SQL_FILE_NAME.matcher(p.getFileName().toString()).matches())
                    .map(root::relativize)
                    .forEach(invalid::add);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        invalid.sort(Path::compareTo);
        return invalid;
    }

    private static boolean isFormattedSqlChangelog(Path file) {
        try {
            return ChangelogIncludes.isFormattedSql(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns whether the file name ends in {@code .sql}, ignoring case: a
     * {@code .SQL} file on a case-insensitive filesystem is otherwise invisible
     * to both the naming rule and the orphan check.
     */
    private static boolean hasSqlExtension(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql");
    }

    private static boolean isExemptSqlFile(Path root, Path sqlFile) {
        for (Path segment : root.relativize(sqlFile)) {
            String name = segment.toString();
            for (String exempt : EXEMPT_DIRECTORIES) {
                if (exempt.equalsIgnoreCase(name)) {
                    return true;
                }
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
        Reachable reachable = parsedReachableChangelogFiles(root, masterChangelog);
        for (Map.Entry<Path, Document> changelog : reachable.xmlFiles().entrySet()) {
            NodeList changeSets = changelog.getValue().getElementsByTagNameNS("*", "changeSet");
            for (int i = 0; i < changeSets.getLength(); i++) {
                String id = ((Element) changeSets.item(i)).getAttribute("id");
                if (!changeSetIdPattern.matcher(id).matches()) {
                    invalid.add(new InvalidChangeSet(changelog.getKey(), id));
                }
            }
        }
        for (Map.Entry<Path, Boolean> sqlFile : reachable.sqlFiles().entrySet()) {
            if (!sqlFile.getValue()) {
                // An unformatted SQL file found by includeAll is one raw change
                // with a generated id, so its id is not the project's naming
                // choice.
                continue;
            }
            for (ChangeSet changeSet : reachable.parsedSql().getOrDefault(sqlFile.getKey(), List.of())) {
                if (!changeSetIdPattern.matcher(changeSet.id()).matches()) {
                    invalid.add(new InvalidChangeSet(sqlFile.getKey(), changeSet.id()));
                }
            }
        }
        invalid.sort(Comparator.comparing(InvalidChangeSet::changelogFile).thenComparing(InvalidChangeSet::id));
        return invalid;
    }

    /**
     * Finds {@code .sql} files under {@code changelogRoot} that no changelog
     * file reachable from {@code masterChangelog} references, either via a
     * {@code <sqlFile>} element or as the external body of a
     * {@code <createProcedure>} or {@code <createFunction>} element.
     *
     * @param changelogRoot   the changelog directory to scan
     * @param masterChangelog the master changelog file to traverse
     * @return the orphaned SQL files, relative to {@code changelogRoot}
     * @throws IOException if a changelog file cannot be read, or a SQL
     *                     reference points to a file that does not exist
     */
    public static List<Path> findOrphanedSqlFiles(Path changelogRoot, Path masterChangelog) throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        Set<String> referenced = new HashSet<>();
        for (Path reference : findReferencedSqlFiles(root, masterChangelog)) {
            referenced.add(pathKey(reference));
        }

        List<Path> orphaned = new ArrayList<>();
        for (Path sqlFile : findSqlFiles(root)) {
            // Compare on a case-insensitive key so a reference spelled with a
            // different case than the file on disk is not reported as an orphan
            // on a case-insensitive filesystem. On a case-sensitive filesystem a
            // case-mismatched reference already fails Files.isRegularFile, so
            // this cannot mask a real mismatch there.
            if (!referenced.contains(pathKey(sqlFile))) {
                orphaned.add(sqlFile);
            }
        }
        return orphaned;
    }

    /**
     * Finds the {@code .sql} files referenced by the changelog graph rooted at
     * {@code masterChangelog}, relative to {@code changelogRoot}. A formatted SQL
     * changelog included by the graph is itself a referenced SQL file. Callers can
     * use this to assert that the graph references content, so an orphan check
     * cannot pass while traversing nothing.
     *
     * @param changelogRoot   the changelog root, used to resolve references
     *                        that are not relative to the changelog file
     * @param masterChangelog the master changelog file to traverse
     * @return the referenced SQL files, relative to {@code changelogRoot},
     * sorted
     * @throws IOException if a changelog file cannot be read, or a reference
     *                     points to a file that does not exist
     */
    public static List<Path> findReferencedSqlFiles(Path changelogRoot, Path masterChangelog) throws IOException {
        Path root = changelogRoot.toAbsolutePath().normalize();
        Set<Path> referenced = new HashSet<>();
        Reachable reachable = parsedReachableChangelogFiles(root, masterChangelog);
        for (Map.Entry<Path, Document> changelog : reachable.xmlFiles().entrySet()) {
            referenced.addAll(referencedSqlFiles(root, changelog.getKey(), changelog.getValue()));
        }
        for (Path sqlFile : reachable.sqlFiles().keySet()) {
            referenced.add(root.relativize(sqlFile));
        }
        List<Path> result = new ArrayList<>(referenced);
        result.sort(Path::compareTo);
        return result;
    }

    /**
     * Finds the changelog files reachable from {@code masterChangelog} by
     * following nested {@code <include>} and {@code <includeAll>} elements,
     * starting with the master and guarding against cycles. XML and formatted SQL
     * changelogs are included.
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
        Path root = changelogRoot.toAbsolutePath().normalize();
        return parsedReachableChangelogFiles(root, masterChangelog).allFiles();
    }

    /**
     * Collects the changelog files reachable from {@code masterChangelog} by
     * following nested {@code <include>} and {@code <includeAll>} elements,
     * guarding against cycles. XML documents are returned parsed so callers do
     * not parse them again; SQL changelog files are recorded with whether they
     * were reached through {@code <include>} (a formatted SQL changelog) or
     * {@code <includeAll>} (one raw change).
     */
    private static Reachable parsedReachableChangelogFiles(Path root, Path masterChangelog)
            throws IOException {
        // Deduplicate on a case-insensitive key while keeping the first real path
        // for I/O and for the returned graph. A case-variant include names the
        // same file on a case-insensitive filesystem, so visiting it twice would
        // report its changesets twice.
        Set<String> visited = new LinkedHashSet<>();
        Map<Path, Document> documents = new LinkedHashMap<>();
        Map<Path, Boolean> sqlFiles = new LinkedHashMap<>();
        Map<String, Path> sqlFilesByKey = new HashMap<>();
        Map<Path, List<ChangeSet>> parsedSql = new LinkedHashMap<>();
        Deque<Pending> pending = new ArrayDeque<>();
        pending.add(new Pending(masterChangelog.toAbsolutePath().normalize(),
                ChangelogIncludes.isSqlFile(masterChangelog)));
        while (!pending.isEmpty()) {
            Pending next = pending.removeFirst();
            Path changelogFile = next.file();
            String key = pathKey(changelogFile);
            if (ChangelogIncludes.isSqlFile(changelogFile)) {
                // A SQL file enqueues nothing, so re-visits cannot loop; the
                // merge and the parsedSql guard dedupe its state.
                Path canonical = sqlFilesByKey.computeIfAbsent(key, ignored -> changelogFile);
                sqlFiles.merge(canonical, next.formattedSql(), Boolean::logicalOr);
                if (next.formattedSql() && !parsedSql.containsKey(canonical)) {
                    // Parse once and keep the changesets for the naming check, so
                    // a file reached through several includes is read only once.
                    parsedSql.put(canonical, FormattedSqlChangelog.parse(canonical, Map.of()));
                }
                continue;
            }
            if (!visited.add(key)) {
                continue;
            }
            Document document = parse(changelogFile);
            // Match by local name so a prefixed Liquibase namespace (<db:include>)
            // is traversed like the default namespace, as Liquibase itself does.
            NodeList includeAlls = document.getElementsByTagNameNS("*", "includeAll");
            for (int i = 0; i < includeAlls.getLength(); i++) {
                Element includeAll = (Element) includeAlls.item(i);
                for (Path included : ChangelogIncludes.includeAll(root, changelogFile, includeAll)) {
                    pending.add(new Pending(included, ChangelogIncludes.isSqlFile(included)
                            && ChangelogIncludes.isFormattedSql(included)));
                }
            }
            NodeList includes = document.getElementsByTagNameNS("*", "include");
            for (int i = 0; i < includes.getLength(); i++) {
                Element include = (Element) includes.item(i);
                String file = include.getAttribute("file");
                if (file.isBlank()) {
                    throw new IOException("include without a file attribute: " + changelogFile);
                }
                boolean relativeToChangelogFile =
                        booleanAttribute(include, "relativeToChangelogFile", false);
                Path base = relativeToChangelogFile ? changelogFile.getParent() : root;
                Path resolved = resolveWithin(root, base, file, "include");
                boolean sql = ChangelogIncludes.isSqlFile(resolved);
                if (sql && !ChangelogIncludes.isFormattedSql(resolved)) {
                    // Validate the marker at the include site so the diagnostic
                    // names the including changelog, rather than relying on the
                    // formatted-SQL parser to reject the file later.
                    throw new IOException("include of a SQL file without the --liquibase formatted sql"
                            + " marker: " + resolved + " in " + changelogFile);
                }
                pending.add(new Pending(resolved, sql));
            }
            documents.put(changelogFile, document);
        }
        return new Reachable(documents, sqlFiles, parsedSql);
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
                    .filter(ChangelogValidator::hasSqlExtension)
                    .map(root::relativize)
                    .map(Path::normalize)
                    .forEach(result::add);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        result.sort(Path::compareTo);
        return result;
    }

    private static Set<Path> referencedSqlFiles(Path root, Path changelogFile, Document document) throws IOException {
        Set<Path> result = new HashSet<>();
        for (String elementName : SQL_REFERENCE_ELEMENTS) {
            NodeList elements = document.getElementsByTagNameNS("*", elementName);
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                String path = element.getAttribute("path");
                if (path.isBlank()) {
                    if (!"sqlFile".equals(elementName)) {
                        // A routine body may be the element's inline text instead of a
                        // path; only sqlFile requires the attribute.
                        continue;
                    }
                    // Liquibase requires the path on sqlFile; a typo in the attribute
                    // name must not drop the reference silently.
                    throw new IOException(elementName + " without a path attribute: " + changelogFile);
                }
                boolean relativeToChangelogFile =
                        booleanAttribute(element, "relativeToChangelogFile", false);
                Path base = relativeToChangelogFile ? changelogFile.getParent() : root;
                Path resolved = resolveWithin(root, base, path, "SQL file reference");
                if (!Files.isRegularFile(resolved)) {
                    throw new IOException("SQL file reference not found: " + path
                            + " in " + changelogFile);
                }
                result.add(root.relativize(resolved));
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
        return ChangelogIncludes.resolveWithin(root, base, reference, kind);
    }

    /**
     * Returns the key used to compare two paths that may name the same file on
     * a case-insensitive filesystem. The real path is kept separately for I/O.
     */
    private static String pathKey(Path file) {
        return file.toString().toLowerCase(Locale.ROOT);
    }

    /**
     * Reads a boolean XML attribute strictly: only {@code true} and {@code false}
     * (case-insensitive) are accepted, so a typo cannot silently select the
     * default and resolve a path against the wrong base.
     */
    private static boolean booleanAttribute(Element element, String name, boolean defaultValue)
            throws IOException {
        if (!element.hasAttribute(name)) {
            return defaultValue;
        }
        String value = element.getAttribute(name).strip();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IOException(name + " is not a boolean: " + value);
        }
        return "true".equalsIgnoreCase(value);
    }

    private static Document parse(Path changelogFile) throws IOException {
        return XmlDocuments.parse(changelogFile);
    }

    /**
     * The reachable changelog graph: XML documents, SQL changelog files, and the
     * parsed changesets of every formatted SQL file.
     */
    private record Reachable(Map<Path, Document> xmlFiles, Map<Path, Boolean> sqlFiles,
                             Map<Path, List<ChangeSet>> parsedSql) {

        private List<Path> allFiles() {
            List<Path> files = new ArrayList<>(xmlFiles.keySet());
            files.addAll(sqlFiles.keySet());
            return List.copyOf(files);
        }
    }

    /**
     * A changelog file waiting to be visited. {@code formattedSql} is true when
     * the file is a formatted SQL changelog: an included SQL file must carry the
     * marker, and an {@code includeAll} SQL file is checked by content.
     */
    private record Pending(Path file, boolean formattedSql) {
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
