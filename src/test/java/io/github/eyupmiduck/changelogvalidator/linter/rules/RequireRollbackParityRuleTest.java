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
 * Verifies {@link RequireRollbackParityRule}: a rollback with far fewer
 * statements than the forward SQL is reported, a missing rollback and a
 * reference-only rollback are ignored, and the rule is opt-in.
 */
class RequireRollbackParityRuleTest {

    private static final Path FILE = RuleTestSupport.FILE;

    private static RuleContext context(boolean rollbackDefined, String forward, String... rollback) {
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null, List.of(), rollbackDefined, List.of());
        List<SqlUnit> forwardUnits = forward == null ? List.of() : List.of(RuleTestSupport.unit(forward));
        List<SqlUnit> rollbackUnits = java.util.Arrays.stream(rollback).map(RuleTestSupport::unit).toList();
        return new RuleContext(changeSet, forwardUnits, rollbackUnits);
    }


    /**
     * A rollback that reverses none of several forward statements is reported.
     */
    @Test
    void reportsAnUnderCoveringRollback() {
        List<Rule.Violation> violations = new RequireRollbackParityRule().check(
                context(true, "CREATE TABLE a (x int); CREATE TABLE b (x int); CREATE TABLE c (x int);",
                        "DROP TABLE c;"));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("1 statement for 3 forward statements"));
    }

    /**
     * A rollback with about as many statements as the forward side is accepted,
     * including a one-to-many inversion that is only about half.
     */
    @Test
    void acceptsComparableRollbacks() {
        assertTrue(new RequireRollbackParityRule().check(
                context(true, "CREATE TABLE a (x int); CREATE TABLE b (x int);",
                        "DROP TABLE a;", "DROP TABLE b;")).isEmpty());
        assertTrue(new RequireRollbackParityRule().check(
                context(true, "CREATE TABLE a (x int); CREATE TABLE b (x int);", "DROP TABLE a; DROP TABLE b;")).isEmpty());
        assertTrue(new RequireRollbackParityRule().check(
                context(true, "ALTER TABLE t ADD COLUMN a int; ALTER TABLE t ADD COLUMN b int;",
                        "ALTER TABLE t DROP COLUMN a; ALTER TABLE t DROP COLUMN b, DROP COLUMN c;")).isEmpty());
    }

    /**
     * A missing rollback, a reference-only rollback and a routine body are not
     * this rule's concern.
     */
    @Test
    void ignoresMissingAndNonStatementRollbacks() {
        assertTrue(new RequireRollbackParityRule().check(context(false, "CREATE TABLE t (c int);")).isEmpty());
        assertTrue(new RequireRollbackParityRule()
                .check(context(true, "CREATE TABLE t (c int);", "DROP TABLE t;")).isEmpty());
    }

    /**
     * The rule is opt-in, warns by default, and reports through the engine when
     * included.
     */
    @Test
    void isOptInAndWarns() throws Exception {
        assertEquals(Severity.WARNING, new RequireRollbackParityRule().defaultSeverity());

        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, "CREATE TABLE a (x int);", true, ";", true, null)),
                true,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, "DROP TABLE a;", true, ";", true, null)));
        assertTrue(Linter.withRules(List.of(new RequireRollbackParityRule())).lint(List.of(changeSet)).isEmpty());

        ChangeSet more = new ChangeSet("cs-2", "me", Path.of("/changelog.xml"), true, false, null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null,
                        "CREATE TABLE a (x int); CREATE TABLE b (x int); CREATE TABLE c (x int);", true, ";", true, null)),
                true, List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, "DROP TABLE c;", true, ";", true, null)));
        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(),
                List.of(RequireRollbackParityRule.ID), Map.of());
        List<Finding> findings = new Linter(List.of(new RequireRollbackParityRule()), config).lint(List.of(more));

        assertEquals(1, findings.size());
        assertEquals(RequireRollbackParityRule.ID, findings.get(0).ruleId());
    }
}
