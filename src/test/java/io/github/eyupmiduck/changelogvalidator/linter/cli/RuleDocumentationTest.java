package io.github.eyupmiduck.changelogvalidator.linter.cli;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails when the checked-in README rule reference disagrees with the rule
 * implementations, so the table cannot drift from the code.
 */
class RuleDocumentationTest {

    private static final Path README = Path.of("README.md");

    /**
     * The generated table between the markers is exactly what the rule metadata
     * produces.
     */
    @Test
    void readmeRuleTableMatchesTheRuleMetadata() throws IOException {
        String readme = Files.readString(README);
        String expected = RuleDocumentation.tableBlock(LinterConfig.defaults());
        int start = readme.indexOf(RuleDocumentation.START);
        int end = readme.indexOf(RuleDocumentation.END);
        assertTrue(start >= 0, () -> "README is missing " + RuleDocumentation.START);
        assertTrue(end > start, () -> "README is missing " + RuleDocumentation.END);
        String actual = readme.substring(start, end + RuleDocumentation.END.length());
        assertEquals(expected, actual,
                "README rule table is out of date; replace the block between the markers with "
                        + "RuleDocumentation.tableBlock(LinterConfig.defaults())");
    }

    /**
     * Every registered rule has a per-rule section, so the table link does not
     * point at a missing heading.
     */
    @Test
    void everyRuleHasAPerRuleSection() throws IOException {
        String readme = Files.readString(README);
        for (RuleCatalog.Entry entry : RuleCatalog.entries(LinterConfig.defaults())) {
            assertTrue(readme.contains("#### " + entry.id() + "\n"),
                    () -> "README is missing a section for " + entry.id());
        }
    }
}
