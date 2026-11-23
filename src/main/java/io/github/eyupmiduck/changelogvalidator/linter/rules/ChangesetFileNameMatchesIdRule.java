package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.ChangelogNaming;
import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reports a numbered changeset whose SQL file does not match its id.
 *
 * <p>A {@code NNN-name} changeset must use {@code sql_changes/NNN-name.sql} for
 * its forward SQL and {@code rollback/NNN-name-rollback.sql} for its rollback.
 * The id and the path are two names for the same definition, so a mismatch makes
 * the changelog harder to follow and is exactly the drift the in-place layout
 * check protects against. Routine and trigger ids are owned by their own path
 * convention and are not checked here.
 *
 * <p>The rule is an error and on by default. It reports the changeset's
 * declaration line; it does not read the SQL file.
 */
public final class ChangesetFileNameMatchesIdRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "changeset-file-name-matches-id";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public List<Violation> check(RuleContext context) {
        String id = context.changeSet().id();
        if (!ChangelogNaming.isNumbered(id)) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        check(context, context.forward(), ChangelogNaming.numberedForwardPath(id), violations);
        check(context, context.rollback(), ChangelogNaming.numberedRollbackPath(id), violations);
        return List.copyOf(violations);
    }

    private void check(RuleContext context, List<SqlUnit> units, String expectedPath,
                       List<Violation> violations) {
        int slash = expectedPath.indexOf('/');
        String directory = expectedPath.substring(0, slash);
        String fileName = expectedPath.substring(slash + 1);
        for (SqlUnit unit : units) {
            SqlSource source = unit.source();
            if (source.path() == null) {
                continue;
            }
            Path path = source.path();
            String actualDirectory = path.getParent() == null || path.getParent().getFileName() == null
                    ? "" : path.getParent().getFileName().toString();
            String actualFileName = path.getFileName().toString();
            if (actualDirectory.equalsIgnoreCase(directory) && actualFileName.equalsIgnoreCase(fileName)) {
                continue;
            }
            int line = source.line();
            violations.add(new Violation(
                    "sqlFile",
                    "SQL file " + actualDirectory + "/" + actualFileName
                            + " does not match changeset id " + context.changeSet().id(),
                    "Use " + expectedPath + " (or rename the changeset id).",
                    context.changeSet().changelogFile(), line, 1));
        }
    }

    @Override
    public String description() {
        return "a numbered changeset whose SQL or rollback path does not match its id";
    }

    @Override
    public String help() {
        return "Rename the SQL or rollback file to match the changeset id.";
    }
}
