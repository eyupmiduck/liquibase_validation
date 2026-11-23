package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.Normalisation;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link Linter} runs and configures {@link ProjectRule}s with the
 * same id, severity, enablement and whitelist model as per-changeset rules.
 */
class ProjectRuleTest {

    private static final String ID = "test-project-rule";

    private static ChangeSet changeSet() {
        return new ChangeSet("001-create", "me", Path.of("/db/changelog.xml"), true, false,
                null, null, null, List.of(), false, List.of(), Normalisation.none(), 4);
    }

    @Test
    void runsProjectRulesAndAddsTheChangesetContext() throws Exception {
        Linter linter = Linter.withRules(List.of(), List.of(new ProbeProjectRule(true)));

        List<Finding> findings = linter.lint(List.of(changeSet()));

        assertEquals(1, findings.size());
        Finding finding = findings.getFirst();
        assertEquals(ID, finding.ruleId());
        assertEquals(Severity.WARNING, finding.severity());
        assertEquals("001-create", finding.changeSetId());
        assertEquals("me", finding.changeSetAuthor());
        assertEquals(Path.of("/db/changelog.xml"), finding.file());
        assertEquals(4, finding.line());
        assertEquals(1, finding.column());
        assertEquals("project message", finding.message());
        assertEquals("project help", finding.help());
    }

    @Test
    void aProjectRulesOnlyRunDoesNotReadSqlFiles() throws Exception {
        ChangeSet changeSet = new ChangeSet("001-create", "me", Path.of("/db/changelog.xml"), true, false,
                null, null, null,
                List.of(new SqlSource(SqlSource.Kind.SQL_FILE, Path.of("/not/read.sql"),
                        null, true, ";", true, null)),
                false, List.of(), Normalisation.none(), 4);
        Linter linter = new Linter(List.of(), List.of(new ProbeProjectRule(true)), LinterConfig.defaults());

        assertEquals(1, linter.lint(List.of(changeSet)).size());
    }

    @Test
    void namesTheChangeSetWhenAProjectRuleViolationHasNoChangeSet() {
        NullPointerException error = assertThrows(NullPointerException.class,
                () -> new ProjectRule.Violation(null, null, "message", null));
        assertTrue(error.getMessage().contains("changeSet"), error.getMessage());
    }

    @Test
    void appliesExclusionAndSeverityToProjectRules() throws Exception {
        LinterConfig excluded = new LinterConfig(null, Severity.ERROR, List.of(ID), List.of(), Map.of());
        assertTrue(new Linter(List.of(), List.of(new ProbeProjectRule(true)), excluded)
                .lint(List.of(changeSet())).isEmpty());

        LinterConfig overridden = new LinterConfig(null, Severity.ERROR, List.of(), List.of(),
                Map.of(ID, new LinterConfig.RuleSettings(Severity.ERROR, Map.of())));
        assertEquals(Severity.ERROR, new Linter(List.of(), List.of(new ProbeProjectRule(true)), overridden)
                .lint(List.of(changeSet())).getFirst().severity());
    }

    @Test
    void honorsAnOptInProjectRule() throws Exception {
        ProbeProjectRule rule = new ProbeProjectRule(false);
        assertTrue(Linter.withRules(List.of(), List.of(rule)).lint(List.of(changeSet())).isEmpty());

        LinterConfig included = new LinterConfig(null, Severity.ERROR, List.of(), List.of(ID), Map.of());
        assertEquals(1, new Linter(List.of(), List.of(rule), included)
                .lint(List.of(changeSet())).size());
    }

    @Test
    void rejectsUnknownAndDuplicateProjectRuleIds() {
        LinterConfig unknown = new LinterConfig(null, Severity.ERROR, List.of("no-such-rule"), List.of(), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new Linter(List.of(), List.of(new ProbeProjectRule(true)), unknown));

        assertThrows(IllegalArgumentException.class,
                () -> new Linter(List.of(), List.of(new ProbeProjectRule(true), new ProbeProjectRule(true)),
                        LinterConfig.defaults()));

        Rule sameId = new Rule() {
            @Override
            public String id() {
                return ID;
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.INFO;
            }

            @Override
            public String description() {
                return "test rule";
            }

            @Override
            public String help() {
                return "test help";
            }

            @Override
            public List<Violation> check(RuleContext context) {
                return List.of();
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new Linter(List.of(sameId), List.of(new ProbeProjectRule(true)),
                        LinterConfig.defaults()));
    }

    private static final class ProbeProjectRule implements ProjectRule {

        private final boolean enabled;

        private ProbeProjectRule(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public Severity defaultSeverity() {
            return Severity.WARNING;
        }

        @Override
        public boolean enabledByDefault() {
            return enabled;
        }

        @Override
        public String description() {
            return "test project rule";
        }

        @Override
        public String help() {
            return "project help";
        }

        @Override
        public List<Violation> check(List<ChangeSet> changeSets) {
            return List.of(new Violation(changeSets.getFirst(), "project statement",
                    "project message", "project help"));
        }
    }
}
