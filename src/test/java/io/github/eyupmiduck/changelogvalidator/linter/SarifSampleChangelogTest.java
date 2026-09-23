package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfigLoader;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangelogModel;
import io.github.eyupmiduck.changelogvalidator.linter.rules.RequireConcurrentIndexCreationRule;
import io.github.eyupmiduck.changelogvalidator.linter.rules.Rules;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the sample changelog used by the SARIF code-scanning workflow still
 * produces exactly the expected finding, so the fixture cannot silently stop
 * demonstrating the upload (the workflow's failing step is advisory).
 */
class SarifSampleChangelogTest {

    /**
     * The sample lints to exactly one {@code require-concurrent-index-creation}
     * finding.
     */
    @Test
    void sampleProducesExactlyOneIndexCreationFinding() throws Exception {
        Path sarif = Path.of(getClass().getClassLoader().getResource("sarif").toURI());
        LinterConfig config = LinterConfigLoader.load(sarif.resolve("sample-changelog-linter.yml"));
        Integer pgVersion = Integer.valueOf(config.pgVersion().split("\\.")[0]);

        List<ChangeSet> changeSets = ChangelogModel.changesets(sarif, sarif.resolve("sample-changelog.xml"));
        List<Finding> findings = new Linter(Rules.all(pgVersion), config).lint(changeSets);

        assertEquals(1, findings.size(), () -> "unexpected findings: " + findings);
        assertEquals(RequireConcurrentIndexCreationRule.ID, findings.get(0).ruleId());
    }
}
