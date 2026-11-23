package io.github.eyupmiduck.changelogvalidator.linter.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The whole changelog graph, as needed by project-level analysis: the visited
 * changelog files and their includes, the effective properties, and the
 * changesets in execution order.
 *
 * <p>{@link #of(List)} creates a minimal project from a changeset list for
 * callers that do not have the files, such as a unit test. File-level rules
 * treat that project as having no changelog root and do nothing.
 *
 * @param root       the changelog root, or {@code null} for a minimal project
 * @param master     the master changelog, or {@code null} for a minimal project
 * @param files      the changelog files in traversal order
 * @param changesets the changesets in execution order
 * @param properties the effective global properties
 */
public record ChangelogProject(Path root, Path master, List<File> files, List<ChangeSet> changesets,
                               Map<String, String> properties) {

    /**
     * Validates and copies the collections.
     */
    public ChangelogProject {
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(changesets, "changesets");
        Objects.requireNonNull(properties, "properties");
        files = List.copyOf(files);
        changesets = List.copyOf(changesets);
        properties = Map.copyOf(properties);
    }

    /**
     * Creates a minimal project from a changeset list.
     *
     * @param changesets the changesets
     * @return the project
     */
    public static ChangelogProject of(List<ChangeSet> changesets) {
        return new ChangelogProject(null, null, List.of(), changesets, Map.of());
    }

    /**
     * One changelog file in the graph.
     *
     * @param path       the absolute, normalized file path
     * @param includes   the included files, in declaration order
     * @param properties the effective properties at the file
     * @param changesets the changesets declared directly in this file
     */
    public record File(Path path, List<Path> includes, Map<String, String> properties,
                       List<ChangeSet> changesets) {

        /**
         * Validates and copies the collections.
         */
        public File {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(includes, "includes");
            Objects.requireNonNull(properties, "properties");
            Objects.requireNonNull(changesets, "changesets");
            includes = List.copyOf(includes);
            properties = Map.copyOf(properties);
            changesets = List.copyOf(changesets);
        }
    }
}
