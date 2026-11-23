package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract for a consuming project's live changelog round trip: apply the
 * configured changelog to a scratch database, roll every changeset back in
 * reverse order, assert the managed schemas and objects are gone or restored to
 * the declared baseline, then re-apply and assert the same state comes back.
 *
 * <p>A consumer extends this class, normally without overriding anything, and
 * returns its one {@link ProjectProfile} from {@link #projectProfile()}. The
 * profile supplies the changelog, fixtures, baseline changelogs and the schemas
 * the catalog comparison captures. {@link #prepareBaseline(java.sql.Connection)}
 * adds any non-changelog prerequisite. Override
 * {@link #assertRolledBackBaseline} or {@link #assertReapplied} for
 * project-specific checks; the default compares the catalog of
 * {@link #roundTripSchemas()} before the changelog, after rollback and after
 * re-apply.
 *
 * <p>This is a live Testcontainers test: it belongs in {@code verify}, not in
 * the per-change unit-test loop. It shares the project's one container and
 * creates only a scratch database.
 */
public abstract class ChangelogRoundTripContract extends ChangelogRehearsalTestBase {

    /**
     * Applies the changelog, rolls it back, checks the baseline, and re-applies
     * it, comparing the catalog at each step.
     */
    @Test
    void roundTripsTheChangelog() {
        Collection<String> schemas = roundTripSchemas();
        assertFalse(schemas.isEmpty(), "roundTripSchemas() must not be empty");

        Map<String, String> baseline = CatalogSnapshot.capture(rehearsalOwnerConnection(), schemas);
        assertBaseline(baseline);

        List<String> applied = applyChangelogs();
        assertFalse(applied.isEmpty(), "the changelog should apply at least one changeset");
        assertApplied(applied);
        Map<String, String> appliedCatalog = CatalogSnapshot.capture(rehearsalOwnerConnection(), schemas);

        List<String> rolledBack = rollbackChangelogs();
        assertEquals(applied.size(), rolledBack.size(),
                () -> "every applied changeset should roll back; applied=" + applied + ", rolledBack=" + rolledBack);
        assertTrue(rolledBack.containsAll(applied),
                () -> "rollback should undo every applied changeset; applied=" + applied + ", rolledBack=" + rolledBack);

        Map<String, String> rolledBackCatalog = CatalogSnapshot.capture(rehearsalOwnerConnection(), schemas);
        CatalogSnapshot.assertEquivalent(CatalogSnapshot.compare(baseline, rolledBackCatalog));
        assertRolledBackBaseline(baseline, rolledBackCatalog);

        List<String> reapplied = applyChangelogs();
        assertEquals(applied, reapplied, "re-applying should apply the same changesets");
        Map<String, String> reappliedCatalog = CatalogSnapshot.capture(rehearsalOwnerConnection(), schemas);
        CatalogSnapshot.assertEquivalent(CatalogSnapshot.compare(appliedCatalog, reappliedCatalog));
        assertReapplied(appliedCatalog, reappliedCatalog);
    }

    /**
     * Checks the database before the changelog runs. The default does nothing;
     * the catalog comparison is the baseline assertion.
     *
     * @param baseline the baseline catalog
     */
    protected void assertBaseline(Map<String, String> baseline) {
    }

    /**
     * Checks the database after the first application. The default does nothing.
     *
     * @param applied the applied changeset ids
     */
    protected void assertApplied(List<String> applied) {
    }

    /**
     * Checks the database after every changeset is rolled back. The default
     * asserts each managed schema exists if and only if it was in the baseline;
     * the full catalog comparison is made before this hook.
     *
     * @param baseline   the baseline catalog
     * @param rolledBack the catalog after rollback
     */
    protected void assertRolledBackBaseline(Map<String, String> baseline, Map<String, String> rolledBack) {
        for (String schema : managedSchemas()) {
            String key = "schema/" + schema;
            assertEquals(baseline.containsKey(key), rolledBack.containsKey(key),
                    () -> "schema " + schema + " should be "
                            + (baseline.containsKey(key) ? "restored to the baseline" : "gone after rollback"));
        }
    }

    /**
     * Checks the database after re-applying the changelog. The default asserts
     * every managed schema exists; the full catalog comparison is made before
     * this hook.
     *
     * @param applied   the catalog after the first application
     * @param reapplied the catalog after re-applying
     */
    protected void assertReapplied(Map<String, String> applied, Map<String, String> reapplied) {
        for (String schema : managedSchemas()) {
            assertTrue(reapplied.containsKey("schema/" + schema),
                    () -> "schema " + schema + " should exist after re-applying");
        }
    }
}
