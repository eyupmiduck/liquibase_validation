package io.github.eyupmiduck.changelogvalidator;

import org.w3c.dom.Element;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Shared changelog-include helpers for the validator and the linter's model.
 *
 * <p>{@link #includeAll(Path, Path, Element)} resolves a Liquibase
 * {@code <includeAll>} element to the changelog files it includes. It supports
 * the attributes that affect which files are selected and how they are ordered:
 * {@code path}, {@code relativeToChangelogFile}, {@code errorIfMissingOrEmpty},
 * {@code endsWithFilter}, {@code minDepth} and {@code maxDepth}. An absent
 * {@code endsWithFilter} means no filter, matching Liquibase's empty-string
 * default; every supported changelog file is selected. Files are sorted by their
 * path relative to the include directory, so the order is deterministic. Every
 * file is checked to stay under the changelog root, and a symlink that escapes
 * the root is rejected, matching {@code <include>}.
 *
 * <p>Only XML and SQL files are supported. A YAML or JSON changelog found by
 * {@code includeAll} fails with a clear diagnostic instead of being skipped
 * silently. A non-changelog file (for example a README) is ignored. The
 * attributes that need a caller-supplied class or that change changeset identity
 * ({@code filter}, {@code resourceComparator}, {@code logicalFilePath},
 * {@code ignore}) are rejected; {@code contextFilter}, {@code context} and
 * {@code labels} are ignored because the linter does not apply context or label
 * filters (see ADR 0003).
 */
public final class ChangelogIncludes {

    private static final Set<String> CHANGELOG_EXTENSIONS = Set.of(".xml", ".sql", ".yaml", ".yml", ".json");
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".xml", ".sql");
    private static final Set<String> REJECTED_ATTRIBUTES =
            Set.of("filter", "resourceComparator", "logicalFilePath", "ignore");
    private static final String FORMATTED_SQL_MARKER = "--liquibase formatted sql";

    private ChangelogIncludes() {
    }

    /**
     * Resolves an {@code <includeAll>} element to the changelog files it
     * includes, in deterministic order.
     *
     * @param root          the absolute, normalized changelog root
     * @param changelogFile the absolute, normalized changelog declaring the
     *                      element
     * @param includeAll    the {@code <includeAll>} element
     * @return the included files, sorted by their path under the include
     * directory
     * @throws IOException if the directory or an attribute is invalid, or a file
     *                     escapes the root
     */
    public static List<Path> includeAll(Path root, Path changelogFile, Element includeAll) throws IOException {
        String path = includeAll.getAttribute("path");
        if (path.isBlank()) {
            throw new IOException("includeAll without a path attribute: " + changelogFile);
        }
        for (String attribute : REJECTED_ATTRIBUTES) {
            if (includeAll.hasAttribute(attribute)) {
                throw new IOException("includeAll attribute '" + attribute
                        + "' is not supported by the linter: " + changelogFile);
            }
        }
        boolean relativeToChangelogFile =
                "true".equalsIgnoreCase(includeAll.getAttribute("relativeToChangelogFile"));
        Path base = relativeToChangelogFile ? changelogFile.getParent() : root;
        Path directory = resolveWithin(root, base, path, "includeAll");
        boolean errorIfMissingOrEmpty = booleanAttribute(includeAll, "errorIfMissingOrEmpty", true);
        String endsWithFilter = includeAll.getAttribute("endsWithFilter").strip();
        int minDepth = intAttribute(includeAll, "minDepth", 1);
        int maxDepth = intAttribute(includeAll, "maxDepth", Integer.MAX_VALUE);
        if (minDepth < 0) {
            throw new IOException("includeAll minDepth must not be negative: " + path);
        }
        // Liquibase's internal default is 0, but a file directly in the include
        // directory is depth 1 here (as the documentation describes), so an
        // explicit 0 selects the same files as the default.
        minDepth = Math.max(minDepth, 1);
        if (maxDepth < minDepth) {
            throw new IOException("includeAll maxDepth must not be less than minDepth: " + path);
        }
        if (!Files.isDirectory(directory)) {
            if (errorIfMissingOrEmpty) {
                throw new IOException("includeAll directory not found: " + path);
            }
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        Path realRoot = root.toRealPath();
        try (var stream = Files.walk(directory)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                int depth = directory.relativize(file).getNameCount();
                if (depth < minDepth || depth > maxDepth) {
                    continue;
                }
                if (!endsWithFilter.isEmpty()
                        && !file.getFileName().toString().toLowerCase(Locale.ROOT)
                        .endsWith(endsWithFilter.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String extension = extension(file.getFileName().toString());
                if (!CHANGELOG_EXTENSIONS.contains(extension)) {
                    continue;
                }
                if (!SUPPORTED_EXTENSIONS.contains(extension)) {
                    throw new IOException("includeAll found an unsupported changelog format (" + extension
                            + "); the linter reads XML and formatted SQL changelogs: " + file);
                }
                if (!file.toRealPath().startsWith(realRoot)) {
                    throw new IOException("includeAll file escapes the changelog root via a symlink: " + file);
                }
                files.add(file);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        files.sort(Comparator.comparing(file -> relative(directory, file)));
        if (files.isEmpty() && errorIfMissingOrEmpty) {
            throw new IOException("includeAll directory is empty or no changelog matched: " + path);
        }
        return List.copyOf(files);
    }

    /**
     * Resolves a reference against {@code base} and rejects one that escapes the
     * changelog root, so an include or SQL path cannot reach outside the
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
    public static Path resolveWithin(Path root, Path base, String reference, String kind) throws IOException {
        Path resolved = base.resolve(reference).normalize();
        if (!resolved.startsWith(root)) {
            throw new IOException(kind + " escapes the changelog root: " + reference);
        }
        if (Files.exists(resolved)) {
            Path realRoot = root.toRealPath();
            Path realResolved = resolved.toRealPath();
            if (!realResolved.startsWith(realRoot)) {
                throw new IOException(kind + " escapes the changelog root via a symlink: " + reference);
            }
        }
        return resolved;
    }

    /**
     * Returns whether a file is a SQL file, ignoring case.
     *
     * @param file the file
     * @return {@code true} for a {@code .sql} file
     */
    public static boolean isSqlFile(Path file) {
        return extension(file.getFileName().toString()).equals(".sql");
    }

    /**
     * Returns whether a SQL file is a formatted SQL changelog: its first
     * non-blank line is the {@code --liquibase formatted sql} marker.
     *
     * @param file the file
     * @return {@code true} for a formatted SQL changelog
     * @throws IOException if the file cannot be read
     */
    public static boolean isFormattedSql(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String stripped = line.strip();
                if (stripped.startsWith("\uFEFF")) {
                    stripped = stripped.substring(1).strip();
                }
                if (stripped.isEmpty()) {
                    continue;
                }
                return stripped.equalsIgnoreCase(FORMATTED_SQL_MARKER);
            }
        }
        return false;
    }

    private static String relative(Path directory, Path file) {
        return directory.relativize(file).toString().replace(File.separatorChar, '/');
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static boolean booleanAttribute(Element element, String name, boolean defaultValue) {
        return element.hasAttribute(name) ? Boolean.parseBoolean(element.getAttribute(name)) : defaultValue;
    }

    private static int intAttribute(Element element, String name, int defaultValue) throws IOException {
        if (!element.hasAttribute(name)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(element.getAttribute(name).strip());
        } catch (NumberFormatException e) {
            throw new IOException("includeAll " + name + " is not an integer: "
                    + element.getAttribute(name), e);
        }
    }
}
