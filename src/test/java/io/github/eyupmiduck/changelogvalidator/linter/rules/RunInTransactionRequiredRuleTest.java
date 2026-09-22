package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Linter;
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
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RunInTransactionRequiredRule}: a transaction-forbidden
 * statement in a transactional changeset is reported (forward or rollback), a
 * changeset that already sets {@code runInTransaction="false"} is accepted, and
 * the version gate is honoured.
 */
class RunInTransactionRequiredRuleTest {

    private static final Path FILE = Path.of("/db/changes.sql");

    /**
     * A forbidden statement in a transactional changeset is reported with the
     * command and the statement's location.
     */
    @Test
    void requiresRunInTransaction() {
        List<Rule.Violation> violations = new RunInTransactionRequiredRule()
                .check(context(true, "CREATE INDEX CONCURRENTLY idx ON t (c);"));

        assertEquals(1, violations.size());
        Rule.Violation violation = violations.get(0);
        assertTrue(violation.message().contains("CREATE INDEX CONCURRENTLY cannot run inside a transaction"));
        assertEquals(FILE, violation.file());
        assertEquals(1, violation.line());
        assertEquals(1, violation.column());
    }

    /**
     * A changeset that already sets {@code runInTransaction="false"} is accepted.
     */
    @Test
    void acceptsRunInTransactionFalse() {
        assertTrue(new RunInTransactionRequiredRule()
                .check(context(false, "CREATE INDEX CONCURRENTLY idx ON t (c);")).isEmpty());
    }

    /**
     * A statement that a transaction permits is not reported.
     */
    @Test
    void ignoresAllowedStatements() {
        assertTrue(new RunInTransactionRequiredRule()
                .check(context(true, "CREATE INDEX idx ON t (c);")).isEmpty());
    }

    /**
     * A forbidden rollback statement is reported too.
     */
    @Test
    void checksRollback() {
        List<Rule.Violation> violations = new RunInTransactionRequiredRule()
                .check(context(true, null, "DROP INDEX CONCURRENTLY idx;"));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("DROP INDEX CONCURRENTLY"));
    }

    /**
     * {@code ALTER TYPE ... ADD VALUE} is reported only for a version below 12.
     */
    @Test
    void versionGatesAlterTypeAddValue() {
        RuleContext context = context(true, "ALTER TYPE mood ADD VALUE 'happy';");

        assertEquals(1, new RunInTransactionRequiredRule(11).check(context).size());
        assertTrue(new RunInTransactionRequiredRule(12).check(context).isEmpty());
        assertTrue(new RunInTransactionRequiredRule().check(context).isEmpty());
    }

    /**
     * The engine uses the rule id and default severity.
     */
    @Test
    void reportsThroughTheEngine() throws Exception {
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null,
                        "CREATE INDEX CONCURRENTLY idx ON t (c);", true, ";", true, null)),
                false, List.of());

        List<Finding> findings = Linter.withRules(List.of(new RunInTransactionRequiredRule()))
                .lint(List.of(changeSet));

        assertEquals(1, findings.size());
        assertEquals(RunInTransactionRequiredRule.ID, findings.get(0).ruleId());
        assertEquals(Severity.ERROR, findings.get(0).severity());
    }

    private static RuleContext context(boolean runInTransaction, String forwardSql, String... rollbackSql) {
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), runInTransaction, false,
                null, null, null, List.of(), false, List.of());
        List<SqlUnit> forward = forwardSql == null ? List.of() : List.of(unit(forwardSql));
        List<SqlUnit> rollback = Arrays.stream(rollbackSql).map(RunInTransactionRequiredRuleTest::unit).toList();
        return new RuleContext(changeSet, forward, rollback);
    }

    private static SqlUnit unit(String sql) {
        return new SqlUnit(new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, ";", true, null),
                FILE, sql, SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql));
    }
}
