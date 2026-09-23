package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.*;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RequireRollbackRule}: a changeset without a rollback is
 * reported, while one with a rollback (including a reference rollback) or a
 * {@code runOnChange} routine body is accepted.
 */
class RequireRollbackRuleTest {

    private static final Path FILE = Path.of("/db/changes.sql");

    private static RuleContext context(boolean runOnChange, boolean rollbackDefined, SqlSource.Kind... kinds) {
        List<SqlUnit> forward = List.of(kinds).stream()
                .map(kind -> unit(kind, "CREATE TABLE t (c int);"))
                .toList();
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, runOnChange,
                null, null, null, List.of(), rollbackDefined, List.of());
        return new RuleContext(changeSet, forward, List.of());
    }

    private static SqlUnit unit(SqlSource.Kind kind, String sql) {
        SqlSource source = switch (kind) {
            case ROUTINE_BODY -> new SqlSource(kind, Path.of("/f.sql"), null, true, ";", true, null);
            case SQL_FILE -> new SqlSource(kind, FILE, null, true, ";", true, null);
            case INLINE_SQL -> new SqlSource(kind, null, sql, true, ";", true, null);
        };
        return new SqlUnit(source, FILE, sql, SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql));
    }

    /**
     * A changeset without a rollback is reported.
     */
    @Test
    void reportsMissingRollback() {
        List<Rule.Violation> violations = new RequireRollbackRule()
                .check(context(false, false, SqlSource.Kind.SQL_FILE));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("no rollback"));
        assertEquals(Path.of("/changelog.xml"), violations.get(0).file());
    }

    /**
     * A declared rollback is accepted, including an empty or reference rollback.
     */
    @Test
    void acceptsDeclaredRollback() {
        assertTrue(new RequireRollbackRule().check(context(false, true, SqlSource.Kind.SQL_FILE)).isEmpty());
    }

    /**
     * A {@code runOnChange} stored routine body needs no rollback (re-running the
     * body replaces it), but a {@code runOnChange} SQL changeset still does.
     */
    @Test
    void exemptsRunOnChangeRoutineBodiesOnly() {
        assertTrue(new RequireRollbackRule()
                .check(context(true, false, SqlSource.Kind.ROUTINE_BODY)).isEmpty());
        assertEquals(1, new RequireRollbackRule()
                .check(context(true, false, SqlSource.Kind.SQL_FILE)).size());
        assertEquals(1, new RequireRollbackRule()
                .check(context(false, false, SqlSource.Kind.ROUTINE_BODY)).size());
    }

    /**
     * The rule warns by default, is opt-in, and reports through the engine when
     * included.
     */
    @Test
    void warnsByDefaultThroughTheEngine() throws Exception {
        assertEquals(Severity.WARNING, new RequireRollbackRule().defaultSeverity());

        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, "CREATE TABLE t (c int);", true, ";", true, null)),
                false, List.of());
        assertTrue(Linter.withRules(List.of(new RequireRollbackRule())).lint(List.of(changeSet)).isEmpty());

        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(), List.of(RequireRollbackRule.ID), Map.of());
        List<Finding> findings = new Linter(List.of(new RequireRollbackRule()), config).lint(List.of(changeSet));

        assertEquals(1, findings.size());
        assertEquals(RequireRollbackRule.ID, findings.get(0).ruleId());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }
}
