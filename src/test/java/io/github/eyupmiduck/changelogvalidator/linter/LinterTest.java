package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
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
        public List<Violation> check(RuleContext context) {
            List<Violation> violations = new ArrayList<>();
            for (SqlUnit unit : context.forward()) {
                for (Token token : unit.tokens()) {
                    if (token.matchesKeyword("bad")) {
                        violations.add(new Violation("uses the forbidden word", "remove it",
                                unit.file(), token.line(), token.column()));
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
        public List<Violation> check(RuleContext context) {
            return List.of(new Violation("opt-in", null, context.changeSet().changelogFile(), 1, 1));
        }
    }
}
