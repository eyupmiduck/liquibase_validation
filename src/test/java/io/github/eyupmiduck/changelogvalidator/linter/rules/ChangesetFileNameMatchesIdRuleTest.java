package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ChangesetFileNameMatchesIdRule}: a numbered changeset's forward
 * and rollback paths must match its id, while a routine id or an inline source is
 * skipped.
 */
class ChangesetFileNameMatchesIdRuleTest {

    private static final Path CHANGELOG = Path.of("/db/changelog.xml");

    private static SqlUnit unit(Path path) {
        SqlSource source = new SqlSource(SqlSource.Kind.SQL_FILE, path, null, true, ";", true, null);
        return new SqlUnit(source, path, "", List.of(), List.of());
    }

    private static RuleContext context(String id, Path forward, Path rollback) {
        List<SqlSource> forwardSources = forward == null ? List.of() : List.of(
                new SqlSource(SqlSource.Kind.SQL_FILE, forward, null, true, ";", true, null));
        List<SqlSource> rollbackSources = rollback == null ? List.of() : List.of(
                new SqlSource(SqlSource.Kind.SQL_FILE, rollback, null, true, ";", true, null));
        ChangeSet changeSet = new ChangeSet(id, "me", CHANGELOG, true, false, null, null, null,
                forwardSources, rollback != null, rollbackSources);
        List<SqlUnit> forwardUnits = forward == null ? List.of() : List.of(unit(forward));
        List<SqlUnit> rollbackUnits = rollback == null ? List.of() : List.of(unit(rollback));
        return new RuleContext(changeSet, forwardUnits, rollbackUnits);
    }

    @Test
    void acceptsMatchingPaths() {
        RuleContext context = context("001-create",
                Path.of("/db/sql_changes/001-create.sql"),
                Path.of("/db/rollback/001-create-rollback.sql"));
        assertTrue(new ChangesetFileNameMatchesIdRule().check(context).isEmpty());
        assertEquals(Severity.ERROR, new ChangesetFileNameMatchesIdRule().defaultSeverity());
        assertTrue(new ChangesetFileNameMatchesIdRule().enabledByDefault());
    }

    @Test
    void reportsAForwardFileNameOrDirectoryMismatch() {
        assertEquals(1, new ChangesetFileNameMatchesIdRule().check(context("001-create",
                Path.of("/db/sql_changes/001-other.sql"),
                Path.of("/db/rollback/001-create-rollback.sql"))).size());
        assertEquals(1, new ChangesetFileNameMatchesIdRule().check(context("001-create",
                Path.of("/db/other/001-create.sql"),
                Path.of("/db/rollback/001-create-rollback.sql"))).size());
    }

    @Test
    void reportsARollbackMismatch() {
        assertEquals(1, new ChangesetFileNameMatchesIdRule().check(context("001-create",
                Path.of("/db/sql_changes/001-create.sql"),
                Path.of("/db/rollback/001-other.sql"))).size());
    }

    @Test
    void skipsNonNumberedIdsAndInlineSources() {
        assertTrue(new ChangesetFileNameMatchesIdRule().check(context("function-app.foo",
                Path.of("/db/functions/app/foo.sql"),
                Path.of("/db/functions-rollback/app/foo-rollback.sql"))).isEmpty());

        ChangeSet inline = new ChangeSet("001-create", "me", CHANGELOG, true, false, null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, "SELECT 1", true, ";", true, null)),
                false, List.of());
        assertTrue(new ChangesetFileNameMatchesIdRule().check(new RuleContext(inline, List.of(), List.of())).isEmpty());
    }

    @Test
    void reportsOneOfMultipleForwardFiles() {
        Path good = Path.of("/db/sql_changes/001-create.sql");
        Path bad = Path.of("/db/sql_changes/other.sql");
        List<SqlSource> sources = List.of(
                new SqlSource(SqlSource.Kind.SQL_FILE, good, null, true, ";", true, null),
                new SqlSource(SqlSource.Kind.SQL_FILE, bad, null, true, ";", true, null));
        ChangeSet changeSet = new ChangeSet("001-create", "me", CHANGELOG, true, false, null, null, null,
                sources, false, List.of());
        RuleContext context = new RuleContext(changeSet, List.of(unit(good), unit(bad)), List.of());

        assertEquals(1, new ChangesetFileNameMatchesIdRule().check(context).size());
    }
}
