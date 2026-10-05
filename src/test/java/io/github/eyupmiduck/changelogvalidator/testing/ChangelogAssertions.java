package io.github.eyupmiduck.changelogvalidator.testing;

import io.github.eyupmiduck.changelogvalidator.ChangelogValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable assertions over a Liquibase changelog graph, so consumers share one
 * implementation of the naming and orphan checks instead of copying them.
 *
 * <p>The static helpers read the changelog as files and therefore require an
 * exploded (non-{@code jar:}) test classpath; see
 * {@link ChangelogTestSupport}.
 */
public final class ChangelogAssertions {

    private ChangelogAssertions() {
    }

    /**
     * Asserts that no SQL file name under {@code changesRoot} violates the
     * {@code NNN-name.sql} pattern (a three-digit prefix followed by a hyphen or
     * underscore).
     *
     * @param changesRoot the changes directory that holds the SQL files
     * @throws IOException if the directory cannot be read
     */
    public static void assertSqlFilesAreNumbered(Path changesRoot) throws IOException {
        List<Path> invalid = ChangelogValidator.findInvalidlyNamedSqlFiles(changesRoot);
        assertTrue(invalid.isEmpty(), () -> "Invalidly named SQL files: " + invalid);
    }

    /**
     * Asserts that every changeSet id reachable from the master changelog
     * matches {@code idPattern}.
     *
     * @param changelogRoot the changelog root directory
     * @param master        the master changelog file
     * @param idPattern     the accepted changeSet id pattern
     * @throws IOException if the changelog graph cannot be read
     */
    public static void assertChangeSetsFollowNaming(Path changelogRoot, Path master, Pattern idPattern)
            throws IOException {
        List<ChangelogValidator.InvalidChangeSet> invalid =
                ChangelogValidator.findInvalidlyNamedChangeSets(changelogRoot, master, idPattern);
        assertTrue(invalid.isEmpty(), () -> "Invalidly named changeSets: " + invalid);
    }

    /**
     * Asserts that every {@code .sql} file in the changelog tree is reached by
     * the changelog graph, and that the graph references at least one SQL file.
     *
     * <p>It enumerates the SQL files with an independent filesystem walk rather
     * than trusting the validator's own traversal to discover them, so a
     * regression in the traversal cannot make the check pass vacuously.
     *
     * @param changelogRoot the changelog root directory
     * @param master        the master changelog file
     * @throws IOException if the changelog tree cannot be read
     */
    public static void assertNoOrphanedSqlFiles(Path changelogRoot, Path master) throws IOException {
        Set<Path> allSql = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(changelogRoot)) {
            walk.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(allSql::add);
        }
        assertFalse(allSql.isEmpty(), "expected the changelog tree to contain SQL files");

        // The validator reports paths relative to the changelog root; resolve
        // them the same way as the independent walk so the two sets compare.
        Set<Path> referenced =
                ChangelogValidator.findReferencedSqlFiles(changelogRoot, master).stream()
                        .map(path -> path.isAbsolute() ? path : changelogRoot.resolve(path))
                        .map(path -> path.toAbsolutePath().normalize())
                        .collect(Collectors.toCollection(TreeSet::new));
        assertFalse(referenced.isEmpty(), "expected the changelog graph to reference SQL files");

        Set<Path> unreferenced = new TreeSet<>(allSql);
        unreferenced.removeAll(referenced);
        assertTrue(unreferenced.isEmpty(), () -> "SQL files the changelog never reaches: " + unreferenced);

        assertTrue(ChangelogValidator.findOrphanedSqlFiles(changelogRoot, master).isEmpty(),
                "the validator should report no orphaned SQL files");
    }
}
