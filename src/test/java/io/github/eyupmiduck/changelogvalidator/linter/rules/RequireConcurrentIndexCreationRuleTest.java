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
 * Verifies {@link RequireConcurrentIndexCreationRule}: a plain
 * {@code CREATE [UNIQUE] INDEX} is reported (forward or rollback),
 * {@code CONCURRENTLY} and an index on a table created in the same changeset are
 * accepted, and the rule is opt-in.
 */
class RequireConcurrentIndexCreationRuleTest {

    private static final Path FILE = RuleTestSupport.FILE;


    private static ChangeSet changeSet(String sql) {
        return new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false, null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, ";", true, null)),
                false, List.of());
    }

    /**
     * A plain {@code CREATE INDEX} is reported with the table and the statement's
     * location.
     */
    @Test
    void reportsPlainCreateIndex() {
        List<Rule.Violation> violations = new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE INDEX idx ON t (c);"));

        assertEquals(1, violations.size());
        Rule.Violation violation = violations.get(0);
        assertEquals("CREATE INDEX", violation.statement());
        assertTrue(violation.message().contains("blocks writes on t"));
        assertEquals(FILE, violation.file());
        assertEquals(1, violation.line());
        assertEquals(1, violation.column());
    }

    /**
     * {@code CREATE INDEX CONCURRENTLY}, a {@code UNIQUE} concurrency build, and
     * {@code ONLY} are accepted; other commands are ignored.
     */
    @Test
    void acceptsConcurrentAndIgnoresOtherStatements() {
        assertTrue(new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE INDEX CONCURRENTLY idx ON t (c);")).isEmpty());
        assertTrue(new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE UNIQUE INDEX CONCURRENTLY idx ON t (c);")).isEmpty());
        assertTrue(new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("DROP INDEX idx;")).isEmpty());
        assertTrue(new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE TABLE t (c int);")).isEmpty());
    }

    /**
     * A plain {@code CREATE UNIQUE INDEX} and {@code CREATE INDEX ... ON ONLY}
     * are reported.
     */
    @Test
    void reportsUniqueAndOnly() {
        assertEquals(1, new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE UNIQUE INDEX idx ON t (c);")).size());
        List<Rule.Violation> only = new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE INDEX idx ON ONLY t (c);"));
        assertEquals(1, only.size());
        assertTrue(only.get(0).message().contains("blocks writes on t"));
    }

    /**
     * An index on a table created in the same changeset is accepted, including a
     * schema-qualified one; an unrelated created table does not exempt it.
     */
    @Test
    void exemptsTablesCreatedInTheSameChangeset() {
        assertTrue(new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE TABLE t (c int); CREATE INDEX idx ON t (c);")).isEmpty());
        assertTrue(new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE TABLE public.t (c int); CREATE INDEX idx ON public.t (c);")).isEmpty());
        assertTrue(new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE UNLOGGED TABLE IF NOT EXISTS t (c int); CREATE INDEX idx ON t (c);")).isEmpty());

        assertEquals(1, new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE TABLE other (c int); CREATE INDEX idx ON t (c);")).size());
    }

    /**
     * Identifiers are compared after normalisation, so an unquoted reference does
     * not match a quoted one of a different case.
     */
    @Test
    void comparesNormalisedIdentifiers() {
        assertTrue(new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE TABLE \"T\" (c int); CREATE INDEX idx ON \"T\" (c);")).isEmpty());

        assertEquals(1, new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE TABLE T (c int); CREATE INDEX idx ON \"T\" (c);")).size());
    }

    /**
     * A plain {@code CREATE INDEX} in rollback SQL is reported too.
     */
    @Test
    void checksRollback() {
        List<Rule.Violation> violations = new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context(null, "CREATE INDEX idx ON t (c);"));

        assertEquals(1, violations.size());
    }

    /**
     * A trailing {@code CREATE INDEX} in an unsplit block is still detected, and
     * a table created in the other direction exempts an index.
     */
    @Test
    void detectsIndexesInAnUnsplitBlockAndExemptsAcrossDirections() {
        String sql = "CREATE TABLE other (c int); CREATE INDEX idx ON t (c);";
        SqlUnit unsplit = new SqlUnit(new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, ";", true, null),
                FILE, sql, SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql, false, ";", true));
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null, List.of(), false, List.of());

        List<Rule.Violation> violations = new RequireConcurrentIndexCreationRule()
                .check(new RuleContext(changeSet, List.of(unsplit), List.of()));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).message().contains("blocks writes on t"));

        assertTrue(new RequireConcurrentIndexCreationRule()
                .check(RuleTestSupport.context("CREATE TABLE t (c int);", "CREATE INDEX idx ON t (c);")).isEmpty());
    }

    /**
     * The rule is opt-in, warns by default, and reports through the engine when
     * included.
     */
    @Test
    void isOptInAndWarns() throws Exception {
        assertEquals(Severity.WARNING, new RequireConcurrentIndexCreationRule().defaultSeverity());
        assertTrue(Linter.withRules(List.of(new RequireConcurrentIndexCreationRule()))
                .lint(List.of(changeSet("CREATE INDEX idx ON t (c);"))).isEmpty());

        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(),
                List.of(RequireConcurrentIndexCreationRule.ID), Map.of());
        List<Finding> findings = new Linter(List.of(new RequireConcurrentIndexCreationRule()), config)
                .lint(List.of(changeSet("CREATE INDEX idx ON t (c);")));

        assertEquals(1, findings.size());
        assertEquals(RequireConcurrentIndexCreationRule.ID, findings.get(0).ruleId());
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }
}
