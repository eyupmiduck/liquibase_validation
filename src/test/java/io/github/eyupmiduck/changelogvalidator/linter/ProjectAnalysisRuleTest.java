package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.Normalisation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link Linter} runs and configures {@link ProjectAnalysisRule}s
 * with the same id, severity, enablement and whitelist model as every other
 * rule, including a file-level finding with no changeset.
 */
class ProjectAnalysisRuleTest {

    private static final String ID = "test-analysis-rule";

    private static ChangeSet changeSet() {
        return new ChangeSet("001-create", "me", Path.of("/db/changelog.xml"), true, false,
                null, null, null, List.of(), false, List.of(), Normalisation.none(), 4);
    }

    @Test
    void runsAnalysisRulesAndAddsTheContext() throws Exception {
        Linter linter = new Linter(List.of(), List.of(), List.of(new ProbeAnalysisRule(true)),
                LinterConfig.defaults());

        List<Finding> findings = linter.lint(List.of(changeSet()));

        assertEquals(2, findings.size());
        Finding statement = findings.get(0);
        assertEquals(ID, statement.ruleId());
        assertEquals(Severity.WARNING, statement.severity());
        assertEquals("001-create", statement.changeSetId());
        assertEquals("me", statement.changeSetAuthor());
        assertEquals(Path.of("/db/sql.sql"), statement.file());
        assertEquals(7, statement.line());
        assertEquals(3, statement.column());
        assertEquals("statement", statement.message());

        Finding file = findings.get(1);
        assertEquals("", file.changeSetId());
        assertEquals("", file.changeSetAuthor());
        assertEquals(Path.of("/db/routine.sql"), file.file());
        assertEquals("file message", file.message());
    }

    @Test
    void appliesExclusionAndSeverityToAnalysisRules() throws Exception {
        LinterConfig excluded = new LinterConfig(null, Severity.ERROR, List.of(ID), List.of(), Map.of());
        assertTrue(new Linter(List.of(), List.of(), List.of(new ProbeAnalysisRule(true)), excluded)
                .lint(List.of(changeSet())).isEmpty());

        LinterConfig overridden = new LinterConfig(null, Severity.ERROR, List.of(), List.of(),
                Map.of(ID, new LinterConfig.RuleSettings(Severity.ERROR, Map.of())));
        assertEquals(Severity.ERROR,
                new Linter(List.of(), List.of(), List.of(new ProbeAnalysisRule(true)), overridden)
                        .lint(List.of(changeSet())).getFirst().severity());
    }

    @Test
    void honorsAnOptInAnalysisRule() throws Exception {
        ProbeAnalysisRule rule = new ProbeAnalysisRule(false);
        assertTrue(new Linter(List.of(), List.of(), List.of(rule), LinterConfig.defaults())
                .lint(List.of(changeSet())).isEmpty());

        LinterConfig included = new LinterConfig(null, Severity.ERROR, List.of(), List.of(ID), Map.of());
        assertEquals(2, new Linter(List.of(), List.of(), List.of(rule), included)
                .lint(List.of(changeSet())).size());
    }

    @Test
    void rejectsUnknownAndDuplicateAnalysisRuleIds() {
        LinterConfig unknown = new LinterConfig(null, Severity.ERROR, List.of("no-such-rule"), List.of(), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new Linter(List.of(), List.of(), List.of(new ProbeAnalysisRule(true)), unknown));

        assertThrows(IllegalArgumentException.class,
                () -> new Linter(List.of(), List.of(),
                        List.of(new ProbeAnalysisRule(true), new ProbeAnalysisRule(true)),
                        LinterConfig.defaults()));

        ProjectRule sameId = new ProjectRule() {
            @Override
            public String id() {
                return ID;
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.INFO;
            }

            @Override
            public List<Violation> check(List<ChangeSet> changeSets) {
                return List.of();
            }
        };
        assertThrows(IllegalArgumentException.class,
                () -> new Linter(List.of(), List.of(sameId), List.of(new ProbeAnalysisRule(true)),
                        LinterConfig.defaults()));
    }

    private static final class ProbeAnalysisRule implements ProjectAnalysisRule {

        private final boolean enabled;

        private ProbeAnalysisRule(boolean enabled) {
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
        public List<Violation> check(ProjectContext context) {
            ChangeSet changeSet = context.changesets().getFirst().changeSet();
            return List.of(
                    new Violation(changeSet, Path.of("/db/sql.sql"), 7, 3, "stmt", "statement", "help"),
                    new Violation(Path.of("/db/routine.sql"), "file message", "file help"));
        }
    }
}
