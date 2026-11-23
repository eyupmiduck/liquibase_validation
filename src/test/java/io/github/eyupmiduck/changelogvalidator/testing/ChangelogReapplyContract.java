package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for a consuming project's live re-apply: apply the configured
 * changelog twice. The second run must be a no-op (no changeset runs, no error),
 * proving that the changesets are recorded, repeatable and safe to re-run.
 *
 * <p>A consumer extends this class, normally without overriding anything, and
 * returns its one {@link ProjectProfile} from {@link #projectProfile()}. The
 * profile supplies the changelog and fixtures. A project with a deliberate
 * {@code runAlways} changeset can override {@link #repeatableChangeSetIds()} to
 * name the ids the second run may apply, but the default is strict.
 *
 * <p>This is a live Testcontainers test: it belongs in {@code verify}, not in
 * the per-change unit-test loop. It shares the project's one container and
 * creates only a scratch database.
 */
public abstract class ChangelogReapplyContract extends ChangelogRehearsalTestBase {

    /**
     * Applies the changelog and fixtures, then applies them again and asserts the
     * second run has nothing to do.
     */
    @Test
    void secondApplicationIsANoOp() {
        List<String> first = applyChangelogs();
        assertFalse(first.isEmpty(), "the changelog should apply at least one changeset");
        assertFirstApplication(first);

        List<String> second = applyChangelogs();
        List<String> unexpected = second.stream()
                .filter(id -> !repeatableChangeSetIds().contains(id))
                .toList();
        assertTrue(unexpected.isEmpty(),
                () -> "re-applying should apply no changeset, but applied " + unexpected
                        + " (first run: " + first + ")");
        assertSecondApplication(second);
    }

    /**
     * The changeset ids the second run may apply because they are deliberately
     * {@code runAlways}. The default is empty, so any repeated changeset fails.
     *
     * @return the allowed repeated ids
     */
    protected List<String> repeatableChangeSetIds() {
        return List.of();
    }

    /**
     * Checks the database after the first application. The default does nothing.
     *
     * @param applied the applied changeset ids
     */
    protected void assertFirstApplication(List<String> applied) {
    }

    /**
     * Checks the database after the second, no-op application. The default does
     * nothing.
     *
     * @param applied the changesets the second run applied (empty unless a
     *                repeatable changeset was allowed)
     */
    protected void assertSecondApplication(List<String> applied) {
    }
}
