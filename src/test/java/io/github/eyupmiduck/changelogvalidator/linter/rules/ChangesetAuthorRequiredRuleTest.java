package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ChangesetAuthorRequiredRule}: a blank or whitespace-only author
 * is reported, a non-blank author is accepted, and the rule is an on-by-default
 * error.
 */
class ChangesetAuthorRequiredRuleTest {

    private static RuleContext context(String author) {
        return new RuleContext(new ChangeSet("cs-1", author, Path.of("/changelog.xml"), true, false,
                null, null, null, List.of(), false, List.of()), List.of(), List.of());
    }

    @Test
    void reportsMissingAndBlankAuthors() {
        assertEquals(1, new ChangesetAuthorRequiredRule().check(context("")).size());
        assertEquals(1, new ChangesetAuthorRequiredRule().check(context("   ")).size());
        assertTrue(new ChangesetAuthorRequiredRule().check(context("me")).isEmpty());
        assertEquals(Severity.ERROR, new ChangesetAuthorRequiredRule().defaultSeverity());
        assertTrue(new ChangesetAuthorRequiredRule().enabledByDefault());
    }

    /**
     * The finding points at the changeset declaration line.
     */
    @Test
    void reportsTheChangesetLine() {
        Rule.Violation violation = new ChangesetAuthorRequiredRule().check(context("")).getFirst();
        assertEquals(1, violation.line());
        assertEquals(Path.of("/changelog.xml"), violation.file());
    }
}
