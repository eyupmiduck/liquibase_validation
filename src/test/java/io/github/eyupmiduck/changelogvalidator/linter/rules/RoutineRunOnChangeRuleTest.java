package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RoutineRunOnChangeRule}: a routine changeset without
 * {@code runOnChange} is reported, while a SQL changeset, a non-PostgreSQL
 * routine or a {@code runOnChange} routine is accepted.
 */
class RoutineRunOnChangeRuleTest {

    private static RuleContext context(boolean runOnChange, SqlSource.Kind kind, String dbms) {
        String sql = kind == SqlSource.Kind.ROUTINE_BODY
                ? "CREATE OR REPLACE FUNCTION f() RETURNS void LANGUAGE sql AS 'SELECT 1';"
                : "CREATE TABLE t (c int);";
        SqlSource source = new SqlSource(kind,
                kind == SqlSource.Kind.INLINE_SQL ? null : RuleTestSupport.FILE,
                kind == SqlSource.Kind.INLINE_SQL ? sql : null,
                kind != SqlSource.Kind.ROUTINE_BODY, ";", true, dbms);
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, runOnChange,
                null, null, null, List.of(source), false, List.of());
        SqlUnit unit = new SqlUnit(source, RuleTestSupport.FILE, sql, SqlLexer.tokenize(sql),
                SqlStatementSplitter.split(sql));
        return new RuleContext(changeSet, List.of(unit), List.of());
    }

    /**
     * A routine body without runOnChange is reported.
     */
    @Test
    void reportsRoutineWithoutRunOnChange() {
        List<Rule.Violation> violations = new RoutineRunOnChangeRule()
                .check(context(false, SqlSource.Kind.ROUTINE_BODY, null));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("runOnChange"));
        assertEquals(Severity.ERROR, new RoutineRunOnChangeRule().defaultSeverity());
        assertTrue(new RoutineRunOnChangeRule().enabledByDefault());
    }

    /**
     * A routine with runOnChange and a SQL-file changeset without it are accepted.
     */
    @Test
    void acceptsRunOnChangeAndNonRoutineChangesets() {
        assertTrue(new RoutineRunOnChangeRule()
                .check(context(true, SqlSource.Kind.ROUTINE_BODY, null)).isEmpty());
        assertTrue(new RoutineRunOnChangeRule()
                .check(context(false, SqlSource.Kind.SQL_FILE, null)).isEmpty());
    }

    /**
     * A routine source scoped to another database does not need runOnChange on
     * the PostgreSQL target.
     */
    @Test
    void ignoresRoutinesForAnotherDatabase() {
        assertTrue(new RoutineRunOnChangeRule()
                .check(context(false, SqlSource.Kind.ROUTINE_BODY, "oracle")).isEmpty());
    }
}
