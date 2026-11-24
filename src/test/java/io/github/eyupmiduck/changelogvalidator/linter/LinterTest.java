package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link Linter}: it evaluates rules over inline and file SQL, adds the
 * rule id, severity and changeset context, honors configuration exclusions and
 * severity overrides, supports opt-in rules, rejects unknown rule ids, and
 * applies the {@code failOn} threshold.
 */
class LinterTest {

    private static final String RULE_ID = "test-forbidden-word";

    @TempDir
    Path tempDir;

    private static Finding findingWith(Severity severity) {
        return new Finding(RULE_ID, severity, "cs-1", "me", Path.of("/changelog.xml"), 1, 1, "m", null);
    }

    private static ChangeSet inlineChangeSet(String sql) {
        return new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false, null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, sql, true, ";", true, null)),
                false, List.of());
    }

    /**
     * A rule violation carries the rule id, severity, changeset context and the
     * location of the offending token.
     */
    @Test
    void reportsFindingsWithContext() throws IOException {
        Linter linter = Linter.withRules(List.of(new ForbiddenWordRule()));

        List<Finding> findings = linter.lint(List.of(inlineChangeSet("SELECT bad")));

        assertEquals(1, findings.size());
        Finding finding = findings.get(0);
        assertEquals(RULE_ID, finding.ruleId());
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals("cs-1", finding.changeSetId());
        assertEquals("me", finding.changeSetAuthor());
        assertEquals(Path.of("/changelog.xml"), finding.file());
        assertEquals(1, finding.line());
        assertEquals(8, finding.column());
        assertEquals("uses the forbidden word", finding.message());
        assertEquals("remove it", finding.help());
    }

    /**
     * An inline source's token line is shifted to the declaring XML element, so a
     * finding does not point at line 1 of the changelog.
     */
    @Test
    void usesTheDeclaredLineForInlineSql() throws IOException {
        SqlSource source = new SqlSource(SqlSource.Kind.INLINE_SQL, null, "SELECT bad", true, ";", true, null, 7);
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null, List.of(source), false, List.of());

        List<Finding> findings = Linter.withRules(List.of(new ForbiddenWordRule())).lint(List.of(changeSet));

        assertEquals(1, findings.size());
        assertEquals(7, findings.get(0).line());
        assertEquals(8, findings.get(0).column());
    }

    /**
     * A formatted SQL changelog is parsed through {@code <include>} and a finding
     * points at the SQL line in the {@code .sql} file.
     */
    @Test
    void lintsAFormattedSqlChangelogAtTheFileLine() throws IOException {
        Path changelog = tempDir.resolve("db.changelog.sql");
        Files.writeString(changelog, """
                --liquibase formatted sql
                --changeset me:1
                SELECT bad;
                """);
        Path master = tempDir.resolve("master.xml");
        Files.writeString(master, """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                    <include file="db.changelog.sql" relativeToChangelogFile="true"/>
                </databaseChangeLog>
                """);

        ChangelogProject project = ChangelogModel.project(tempDir, master);
        List<Finding> findings = Linter.withRules(List.of(new ForbiddenWordRule())).lint(project);

        assertEquals(1, findings.size());
        assertEquals(changelog, findings.get(0).file());
        assertEquals(3, findings.get(0).line());
    }

    /**
     * A file-based source is read and reported against the SQL file, and inline
     * self-closing sources are not read.
     */
    @Test
    void readsFileSources() throws IOException {
        Path sqlFile = tempDir.resolve("bad.sql");
        Files.writeString(sqlFile, "SELECT bad;");
        ChangeSet changeSet = new ChangeSet("cs-file", "me", tempDir.resolve("changelog.xml"), true, false,
                null, null, null,
                List.of(new SqlSource(SqlSource.Kind.SQL_FILE, sqlFile, null, true, ";", true, null)),
                false, List.of());

        List<Finding> findings = Linter.withRules(List.of(new ForbiddenWordRule())).lint(List.of(changeSet));

        assertEquals(1, findings.size());
        assertEquals(sqlFile, findings.get(0).file());
    }

    /**
     * A rule listed under {@code exclude} does not run.
     */
    @Test
    void skipsExcludedRules() throws IOException {
        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(RULE_ID), List.of(), Map.of());
        Linter linter = new Linter(List.of(new ForbiddenWordRule()), config);

        assertTrue(linter.lint(List.of(inlineChangeSet("SELECT bad"))).isEmpty());
    }

    /**
     * A per-rule severity override replaces the rule's default.
     */
    @Test
    void appliesSeverityOverride() throws IOException {
        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(), List.of(),
                Map.of(RULE_ID, new LinterConfig.RuleSettings(Severity.ERROR, Map.of())));
        Linter linter = new Linter(List.of(new ForbiddenWordRule()), config);

        List<Finding> findings = linter.lint(List.of(inlineChangeSet("SELECT bad")));

        assertEquals(Severity.ERROR, findings.get(0).severity());
    }

    /**
     * An opt-in rule runs only when the configuration includes it.
     */
    @Test
    void enablesOptInRules() throws IOException {
        Rule optIn = new OptInRule();
        assertTrue(Linter.withRules(List.of(optIn)).lint(List.of(inlineChangeSet("SELECT bad"))).isEmpty());

        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(), List.of(optIn.id()), Map.of());
        assertEquals(1, new Linter(List.of(optIn), config).lint(List.of(inlineChangeSet("SELECT bad"))).size());
    }

    /**
     * A configuration that references an unknown rule id is rejected.
     */
    @Test
    void rejectsUnknownRuleIds() {
        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of("no-such-rule"), List.of(), Map.of());

        assertThrows(IllegalArgumentException.class, () -> new Linter(List.of(new ForbiddenWordRule()), config));
    }

    /**
     * Registering two rules with the same id is rejected, so a rule cannot report
     * the same violation twice.
     */
    @Test
    void rejectsDuplicateRuleIds() {
        assertThrows(IllegalArgumentException.class,
                () -> Linter.withRules(List.of(new ForbiddenWordRule(), new ForbiddenWordRule())));
    }

    /**
     * The {@code failOn} threshold decides whether findings fail the run.
     */
    @Test
    void appliesFailOnThreshold() throws IOException {
        Linter linter = Linter.withRules(List.of(new ForbiddenWordRule()));

        assertFalse(linter.fails(List.of()));
        assertFalse(linter.fails(List.of(findingWith(Severity.INFO))));
        assertTrue(linter.fails(List.of(findingWith(Severity.ERROR))));
    }

    /**
     * A null threshold ({@code failOn: none}) never fails, even for errors.
     */
    @Test
    void neverFailsWithoutAFailureThreshold() {
        LinterConfig config = new LinterConfig(null, null, List.of(), List.of(), Map.of());
        Linter linter = new Linter(List.of(new ForbiddenWordRule()), config);

        assertFalse(linter.fails(List.of(findingWith(Severity.ERROR))));
    }

    /**
     * An inline suppression with a reason hides the finding for the changeset.
     */
    @Test
    void suppressesFindingsWithAnInlineComment() throws IOException {
        List<Finding> findings = Linter.withRules(List.of(new ForbiddenWordRule()))
                .lint(List.of(inlineChangeSet("""
                        -- liquibase-linter:ignore reason=accepted for this test
                        SELECT bad""")));

        assertTrue(findings.isEmpty(), findings.toString());
    }

    /**
     * A suppression naming another known rule does not hide this rule's finding.
     */
    @Test
    void suppressesOnlyTheNamedRule() throws IOException {
        List<Finding> findings = Linter.withRules(List.of(new ForbiddenWordRule(), new OptInRule()))
                .lint(List.of(inlineChangeSet("""
                        -- liquibase-linter:ignore test-opt-in reason=accepted
                        SELECT bad""")));

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(RULE_ID, findings.getFirst().ruleId());
    }

    /**
     * A suppression without a reason fails closed instead of hiding a finding.
     */
    @Test
    void rejectsASuppressionWithoutAReason() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Linter.withRules(List.of(new ForbiddenWordRule()))
                        .lint(List.of(inlineChangeSet("""
                                -- liquibase-linter:ignore
                                SELECT bad"""))));

        assertTrue(failure.getMessage().contains("reason"), failure.getMessage());
    }

    /**
     * A suppression naming an unknown rule fails closed, so a typo cannot look
     * like a working suppression.
     */
    @Test
    void rejectsAnUnknownRuleInASuppression() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Linter.withRules(List.of(new ForbiddenWordRule()))
                        .lint(List.of(inlineChangeSet("""
                                -- liquibase-linter:ignore no-such-rule reason=typo
                                SELECT bad"""))));

        assertTrue(failure.getMessage().contains("unknown rule id"), failure.getMessage());
    }

    /**
     * A suppression must start its line: the same marker after SQL code on the
     * same line is an ordinary trailing comment and does not suppress.
     */
    @Test
    void ignoresASuppressionMarkerAfterSqlOnTheSameLine() throws IOException {
        List<Finding> findings = Linter.withRules(List.of(new ForbiddenWordRule()))
                .lint(List.of(inlineChangeSet("""
                        SELECT bad -- liquibase-linter:ignore reason=oops""")));

        assertEquals(1, findings.size(), findings.toString());
        assertEquals(RULE_ID, findings.getFirst().ruleId());
    }

    /**
     * A suppression indented with horizontal whitespace still starts its line.
     */
    @Test
    void acceptsAnIndentedSuppression() throws IOException {
        List<Finding> findings = Linter.withRules(List.of(new ForbiddenWordRule()))
                .lint(List.of(inlineChangeSet("""
                        \t-- liquibase-linter:ignore reason=accepted
                        SELECT bad""")));

        assertTrue(findings.isEmpty(), findings.toString());
    }

    /**
     * A rules part made only of separators names no rule, so it fails closed like
     * an unknown rule id instead of suppressing nothing silently.
     */
    @Test
    void rejectsASuppressionThatNamesNoRule() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> Linter.withRules(List.of(new ForbiddenWordRule()))
                        .lint(List.of(inlineChangeSet("""
                                -- liquibase-linter:ignore , , reason=oops
                                SELECT bad"""))));

        assertTrue(failure.getMessage().contains("names no rule ids"), failure.getMessage());
    }

    /**
     * The case-insensitive search returns an index into the original text even
     * when lowercasing would change the string's length.
     */
    @Test
    void indexOfIgnoreCaseKeepsTheOriginalOffsets() {
        assertEquals(3, Linter.indexOfIgnoreCase("\u0130\u0130xreason=", "reason="));
        assertEquals(-1, Linter.indexOfIgnoreCase("no marker", "reason="));
    }

    /**
     * A suppression inside a routine body is found by re-lexing the body.
     */
    @Test
    void suppressesFindingsInsideARoutineBody() throws IOException {
        SqlSource source = new SqlSource(SqlSource.Kind.ROUTINE_BODY, null, """
                CREATE FUNCTION f() RETURNS void AS $$
                -- liquibase-linter:ignore reason=accepted in the body
                BEGIN
                    PERFORM bad;
                END;
                $$ LANGUAGE plpgsql;
                """, false, ";", true, null);
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null, List.of(source), false, List.of());

        List<Finding> findings = Linter.withRules(List.of(new ForbiddenWordRule())).lint(List.of(changeSet));

        assertTrue(findings.isEmpty(), findings.toString());
    }

    /**
     * A rule that flags the word {@code bad} in forward SQL.
     */
    private static final class ForbiddenWordRule implements Rule {

        @Override
        public String id() {
            return RULE_ID;
        }

        @Override
        public Severity defaultSeverity() {
            return Severity.WARNING;
        }

        @Override
        public String description() {
            return "test rule for the word bad";
        }

        @Override
        public String help() {
            return "remove it";
        }

        @Override
        public List<Violation> check(RuleContext context) {
            List<Violation> violations = new ArrayList<>();
            for (RoutineBodies.Body body : RoutineBodies.find(context.forward())) {
                for (Token token : body.tokens()) {
                    if (token.matchesKeyword("bad")) {
                        violations.add(new Violation("uses the forbidden word", "remove it",
                                body.unit().file(), token.line(), token.column()));
                    }
                }
            }
            return violations;
        }
    }

    /**
     * A rule that is disabled unless included.
     */
    private static final class OptInRule implements Rule {

        @Override
        public String id() {
            return "test-opt-in";
        }

        @Override
        public Severity defaultSeverity() {
            return Severity.INFO;
        }

        @Override
        public boolean enabledByDefault() {
            return false;
        }

        @Override
        public String description() {
            return "test opt-in rule";
        }

        @Override
        public String help() {
            return "none";
        }

        @Override
        public List<Violation> check(RuleContext context) {
            return List.of(new Violation("opt-in", null, context.changeSet().changelogFile(), 1, 1));
        }
    }
}
