package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangelogProject;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Reports a routine SQL file under {@code functions/}, {@code procedures/} or
 * {@code triggers/} (or their rollback directories) that no changeset
 * references.
 *
 * <p>The changelog graph is the source of truth: a routine file that is not
 * loaded by a {@code createProcedure}/{@code createFunction}/{@code sqlFile}
 * changeset, forward or rollback, is dead. This overlaps with
 * {@code ChangelogSqlFilesContract}; the project rule keeps the check in the
 * linter for a project that does not adopt the contracts. It is an opt-in
 * warning and does nothing for a minimal project with no changelog root.
 */
public final class UnregisteredRoutineFileRule implements ProjectAnalysisRule {

    /**
     * The rule id.
     */
    public static final String ID = "project-unregistered-routine";

    private static final Set<String> ROUTINE_DIRECTORIES = Set.of(
            "functions", "functions-rollback",
            "procedures", "procedures-rollback",
            "triggers", "triggers-rollback");

    private static void addPaths(Set<Path> paths, List<SqlSource> sources) {
        for (SqlSource source : sources) {
            if (source.path() != null) {
                paths.add(source.path().toAbsolutePath().normalize());
            }
        }
    }

    private static boolean isRoutineFile(Path root, Path file) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        for (Path segment : relative) {
            if (ROUTINE_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARNING;
    }

    @Override
    public List<Violation> check(ProjectContext context) throws IOException {
        ChangelogProject project = context.project();
        if (project.root() == null || project.files().isEmpty()) {
            return List.of();
        }
        Set<Path> referenced = new HashSet<>();
        for (ChangeSet changeSet : project.changesets()) {
            addPaths(referenced, changeSet.sqlSources());
            addPaths(referenced, changeSet.rollbackSources());
        }
        List<Violation> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(project.root())) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.getFileName().toString().endsWith(".sql") || !isRoutineFile(project.root(), path)) {
                    continue;
                }
                Path normalized = path.toAbsolutePath().normalize();
                if (!referenced.contains(normalized)) {
                    violations.add(new Violation(normalized,
                            "routine SQL file is not referenced by a changeset",
                            "Add a changeset that references this file, or remove it."));
                }
            }
        }
        return List.copyOf(violations);
    }

    @Override
    public String description() {
        return "a routine SQL file no changeset references";
    }

    @Override
    public String help() {
        return "Reference the file from a changeset or remove it.";
    }
}
