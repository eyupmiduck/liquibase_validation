package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.*;
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
 * Verifies {@link PreferSingleStatementRule}: a multi-statement
 * {@code runInTransaction="false"} changeset is reported (forward or rollback), a
 * single statement is accepted, and the rule stays quiet when the single-statement
 * error rule already covers a transaction-forbidden statement.
 */
class PreferSingleStatementRuleTest {

    private static final Path FILE = Path.of("/db/changes.sql");

    private static RuleContext context(boolean runInTransaction, String forwardSql, String... rollbackSql) {
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), runInTransaction, false,
                null, null, null, List.of(), false, List.of());
        List<SqlUnit> forward = forwardSql == null ? List.of() : List.of(unit(forwardSql));
        List<SqlUnit> rollback = Arrays.stream(rollbackSql).map(PreferSingleStatementRuleTest::unit).toList();
        return new RuleContext(changeSet, forward, rollback);
    }

    private static SqlUnit unit(String sql) {
        return new SqlUnit(new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, ";", true, null),
                FILE, sql, SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql));
    }

    private static ChangeSet changeSet(String sql) {
        return new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), false, false, null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, ";", true, null)),
                false, List.of());
    }

    /**
     * Two statements in a non-transactional changeset are reported with the
     * statement count and the location of the first statement.
     */
    @Test
    void warnsOnMultipleStatements() {
        List<Rule.Violation> violations = new PreferSingleStatementRule()
                .check(context(false, "CREATE INDEX idx ON t (c); DROP INDEX idx;"));

        assertEquals(1, violations.size());
        Rule.Violation violation = violations.get(0);
        assertTrue(violation.message().contains("2 statements"));
        assertTrue(violation.message().contains("runInTransaction=\"false\""));
        assertEquals(FILE, violation.file());
        assertEquals(1, violation.line());
        assertEquals(1, violation.column());
    }

    /**
     * A non-transactional changeset with a single statement is accepted.
     */
    @Test
    void acceptsASingleStatement() {
        assertTrue(new PreferSingleStatementRule()
                .check(context(false, "DROP INDEX CONCURRENTLY idx;")).isEmpty());
    }

    /**
     * A transactional changeset is not this rule's concern, however many
     * statements it has, and a transaction-forbidden statement is left to the
     * single-statement error rule.
     */
    @Test
    void ignoresTransactionalAndForbiddenChangesets() {
        assertTrue(new PreferSingleStatementRule()
                .check(context(true, "CREATE INDEX idx ON t (c); DROP INDEX idx;")).isEmpty());
        assertTrue(new PreferSingleStatementRule()
                .check(context(false, "CREATE INDEX CONCURRENTLY idx ON t (c); DROP INDEX idx;")).isEmpty());
    }

    /**
     * A multi-statement non-transactional rollback is reported too.
     */
    @Test
    void checksRollback() {
        List<Rule.Violation> violations = new PreferSingleStatementRule()
                .check(context(false, null, "DROP INDEX idx; DROP INDEX idx2;"));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("2 statements"));
    }

    /**
     * Forward and rollback findings are distinguishable, so a changeset with
     * several statements in both directions does not produce two byte-identical
     * findings.
     */
    @Test
    void distinguishesForwardAndRollback() {
        List<Rule.Violation> violations = new PreferSingleStatementRule()
                .check(context(false, "DROP INDEX idx; DROP INDEX idx2;", "DROP INDEX idx3; DROP INDEX idx4;"));

        assertEquals(2, violations.size());
        assertTrue(violations.stream().anyMatch(violation -> violation.message().contains("forward")));
        assertTrue(violations.stream().anyMatch(violation -> violation.message().contains("rollback")));
    }

    /**
     * The rule is opt-in, warns by default, and reports through the engine when
     * included.
     */
    @Test
    void isOptInAndWarns() throws Exception {
        assertEquals(Severity.WARNING, new PreferSingleStatementRule().defaultSeverity());
        assertTrue(Linter.withRules(List.of(new PreferSingleStatementRule()))
                .lint(List.of(changeSet("CREATE INDEX idx ON t (c); DROP INDEX idx;"))).isEmpty());

        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(), List.of(PreferSingleStatementRule.ID),
                Map.of());
        List<Finding> findings = new Linter(List.of(new PreferSingleStatementRule()), config)
                .lint(List.of(changeSet("CREATE INDEX idx ON t (c); DROP INDEX idx;")));

        assertEquals(1, findings.size());
        assertEquals(PreferSingleStatementRule.ID, findings.get(0).ruleId());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }
}
