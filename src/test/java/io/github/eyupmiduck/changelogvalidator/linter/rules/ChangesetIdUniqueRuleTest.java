package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectRule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ChangesetIdUniqueRule}: the same id/author/file identity is
 * reported, while a different author, a different file or a unique id is
 * accepted.
 */
class ChangesetIdUniqueRuleTest {

    private static ChangeSet changeSet(String id, String author, Path file, int line) {
        return new ChangeSet(id, author, file, true, false, null, null, null,
                List.of(), false, List.of(), io.github.eyupmiduck.changelogvalidator.linter.model.Normalisation.none(),
                line);
    }

    @Test
    void reportsDuplicateIdentity() {
        ChangeSet first = changeSet("001-create", "me", Path.of("/db/changelog.xml"), 3);
        ChangeSet second = changeSet("001-create", "me", Path.of("/db/changelog.xml"), 10);

        List<ProjectRule.Violation> violations = new ChangesetIdUniqueRule().check(List.of(first, second));

        assertEquals(1, violations.size());
        assertEquals(second, violations.getFirst().changeSet());
        assertTrue(violations.getFirst().message().contains("first declared at line 3"));
        assertEquals(Severity.ERROR, new ChangesetIdUniqueRule().defaultSeverity());
        assertTrue(new ChangesetIdUniqueRule().enabledByDefault());
    }

    @Test
    void acceptsDifferentAuthorOrFile() {
        assertTrue(new ChangesetIdUniqueRule().check(List.of(
                changeSet("001-create", "me", Path.of("/db/changelog.xml"), 1),
                changeSet("001-create", "you", Path.of("/db/changelog.xml"), 2))).isEmpty());
        assertTrue(new ChangesetIdUniqueRule().check(List.of(
                changeSet("001-create", "me", Path.of("/db/one.xml"), 1),
                changeSet("001-create", "me", Path.of("/db/two.xml"), 2))).isEmpty());
    }

    @Test
    void reportsEveryOccurrenceAfterTheFirstAndNormalizesThePath() {
        ChangeSet first = changeSet("001-create", "me", Path.of("/db/changelog.xml"), 1);
        ChangeSet second = changeSet("001-create", "me", Path.of("/db/./changelog.xml"), 2);
        ChangeSet third = changeSet("001-create", "me", Path.of("/db/changelog.xml"), 3);

        List<ProjectRule.Violation> violations = new ChangesetIdUniqueRule().check(List.of(first, second, third));

        assertEquals(2, violations.size());
    }
}
