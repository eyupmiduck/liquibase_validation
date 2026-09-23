package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.*;
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
 * Verifies {@link SingleStatementRule}: a transaction-forbidden statement in a
 * non-transactional changeset must be that changeset's only statement, in the
 * forward or the rollback context, and the version gate is honoured.
 */
class SingleStatementRuleTest {

    private static final Path FILE = RuleTestSupport.FILE;



    /**
     * A forbidden statement that shares a non-transactional changeset with
     * another statement is reported.
     */
    @Test
    void reportsWhenForbiddenStatementIsNotAlone() {
        List<Rule.Violation> violations = new SingleStatementRule().check(RuleTestSupport.context(false,
                "CREATE INDEX CONCURRENTLY a ON t (c); CREATE INDEX CONCURRENTLY b ON t (c);"));

        assertEquals(1, violations.size());
        Rule.Violation violation = violations.get(0);
        assertTrue(violation.message().contains("must be the only statement"));
        assertTrue(violation.message().contains("found 2 statements"));
        assertEquals(1, violation.line());
        assertEquals(1, violation.column());
    }

    /**
     * A non-transactional changeset with a single forbidden statement is fine.
     */
    @Test
    void acceptsSingleForbiddenStatement() {
        assertTrue(new SingleStatementRule().check(RuleTestSupport.context(false,
                "CREATE INDEX CONCURRENTLY idx ON t (c);")).isEmpty());
    }

    /**
     * Several statements are fine when none of them is transaction-forbidden.
     */
    @Test
    void acceptsMultipleAllowedStatements() {
        assertTrue(new SingleStatementRule().check(RuleTestSupport.context(false, "SELECT 1; SELECT 2;")).isEmpty());
    }

    /**
     * A transactional changeset is left to the run-in-transaction rule.
     */
    @Test
    void ignoresTransactionalChangeset() {
        assertTrue(new SingleStatementRule().check(RuleTestSupport.context(true,
                "CREATE INDEX CONCURRENTLY a ON t (c); CREATE INDEX CONCURRENTLY b ON t (c);")).isEmpty());
    }

    /**
     * The rollback context has the same requirement.
     */
    @Test
    void checksRollback() {
        List<Rule.Violation> violations = new SingleStatementRule().check(RuleTestSupport.context(false,
                "CREATE INDEX CONCURRENTLY idx ON t (c);",
                "DROP INDEX CONCURRENTLY a; DROP INDEX CONCURRENTLY b;"));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("DROP INDEX CONCURRENTLY"));
    }

    /**
     * {@code ALTER TYPE ... ADD VALUE} is reported only for a version below 12.
     */
    @Test
    void versionGatesAlterTypeAddValue() {
        RuleContext context = RuleTestSupport.context(false, "ALTER TYPE mood ADD VALUE 'happy'; SELECT 1;");

        assertEquals(1, new SingleStatementRule(11).check(context).size());
        assertTrue(new SingleStatementRule(12).check(context).isEmpty());
        assertTrue(new SingleStatementRule().check(context).isEmpty());
    }

    /**
     * The engine uses the rule id and default severity.
     */
    @Test
    void reportsThroughTheEngine() throws Exception {
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), false, false,
                null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null,
                        "CREATE INDEX CONCURRENTLY a ON t (c); CREATE INDEX CONCURRENTLY b ON t (c);",
                        true, ";", true, null)),
                false, List.of());

        List<Finding> findings = Linter.withRules(List.of(new SingleStatementRule())).lint(List.of(changeSet));

        assertEquals(1, findings.size());
        assertEquals(SingleStatementRule.ID, findings.get(0).ruleId());
        assertEquals(Severity.ERROR, findings.get(0).severity());
    }
}
