package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Linter;
import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RequireConcurrentIndexDeletionRule}: a plain {@code DROP INDEX}
 * is reported (including {@code IF EXISTS}, forward or rollback),
 * {@code CONCURRENTLY} and other commands are accepted, and the rule is opt-in.
 */
class RequireConcurrentIndexDeletionRuleTest {

    private static final Path FILE = Path.of("/db/changes.sql");

    private static RuleContext context(String forwardSql, String... rollbackSql) {
        List<SqlUnit> forward = forwardSql == null ? List.of() : List.of(unit(forwardSql));
        List<SqlUnit> rollback = Arrays.stream(rollbackSql).map(RequireConcurrentIndexDeletionRuleTest::unit).toList();
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null, List.of(), false, List.of());
        return new RuleContext(changeSet, forward, rollback);
    }

    private static SqlUnit unit(String sql) {
        return new SqlUnit(new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, ";", true, null),
                FILE, sql, SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql));
    }

    /**
     * A plain {@code DROP INDEX} is reported with the statement label and the
     * statement's location.
     */
    @Test
    void reportsPlainDropIndex() {
        List<Rule.Violation> violations = new RequireConcurrentIndexDeletionRule()
                .check(context("DROP INDEX idx;"));

        assertEquals(1, violations.size());
        Rule.Violation violation = violations.get(0);
        assertEquals("DROP INDEX", violation.statement());
        assertEquals(FILE, violation.file());
        assertEquals(1, violation.line());
        assertEquals(1, violation.column());
    }

    /**
     * {@code DROP INDEX IF EXISTS} is still a plain drop and is reported, while
     * {@code DROP INDEX CONCURRENTLY} and other commands are accepted.
     */
    @Test
    void acceptsConcurrentAndIfExistsIsReported() {
        assertEquals(1, new RequireConcurrentIndexDeletionRule()
                .check(context("DROP INDEX IF EXISTS idx;")).size());
        assertTrue(new RequireConcurrentIndexDeletionRule()
                .check(context("DROP INDEX CONCURRENTLY idx;")).isEmpty());
        assertTrue(new RequireConcurrentIndexDeletionRule()
                .check(context("DROP INDEX CONCURRENTLY IF EXISTS idx;")).isEmpty());
        assertTrue(new RequireConcurrentIndexDeletionRule()
                .check(context("DROP TABLE t;")).isEmpty());
        assertTrue(new RequireConcurrentIndexDeletionRule()
                .check(context("CREATE INDEX idx ON t (c);")).isEmpty());
    }

    /**
     * A plain {@code DROP INDEX} in rollback SQL is reported too.
     */
    @Test
    void checksRollback() {
        assertEquals(1, new RequireConcurrentIndexDeletionRule()
                .check(context(null, "DROP INDEX idx;")).size());
    }

    /**
     * The rule is opt-in, warns by default, and reports through the engine when
     * included.
     */
    @Test
    void isOptInAndWarns() throws Exception {
        assertEquals(Severity.WARNING, new RequireConcurrentIndexDeletionRule().defaultSeverity());
        assertTrue(Linter.withRules(List.of(new RequireConcurrentIndexDeletionRule()))
                .lint(List.of(changeSet("DROP INDEX idx;"))).isEmpty());

        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(),
                List.of(RequireConcurrentIndexDeletionRule.ID), Map.of());
        List<Finding> findings = new Linter(List.of(new RequireConcurrentIndexDeletionRule()), config)
                .lint(List.of(changeSet("DROP INDEX idx;")));

        assertEquals(1, findings.size());
        assertEquals(RequireConcurrentIndexDeletionRule.ID, findings.get(0).ruleId());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }

    private static ChangeSet changeSet(String sql) {
        return new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false, null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, ";", true, null)),
                false, List.of());
    }
}
