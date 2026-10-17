package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link CatalogSnapshot#compare} reports missing, unexpected and
 * changed objects, and treats overloaded routines independently.
 */
class CatalogSnapshotCompareTest {

    private static final Map<String, String> FRESH = Map.of(
            "schema/fixture", "{\"comment\": \"fresh\"}",
            "relation/fixture.widget", "{\"kind\": \"table\"}",
            "function/fixture.bump(integer)", "{\"identity_arguments\": \"i integer\"}",
            "function/fixture.bump(text)", "{\"identity_arguments\": \"s text\"}");

    /**
     * Two identical captures are equivalent.
     */
    @Test
    void reportsEquivalentWhenIdentical() {
        CatalogSnapshot.Report report = CatalogSnapshot.compare(FRESH, Map.copyOf(FRESH));

        assertTrue(report.equivalent());
        assertTrue(report.describe(10).contains("matches"));
    }

    /**
     * A missing, an unexpected and a changed object are each reported.
     */
    @Test
    void reportsMissingUnexpectedAndChanged() {
        Map<String, String> upgrade = new HashMap<>(FRESH);
        upgrade.remove("relation/fixture.widget");
        upgrade.put("index/fixture.extra_idx", "{}");
        upgrade.put("schema/fixture", "{\"comment\": \"upgraded\"}");

        CatalogSnapshot.Report report = CatalogSnapshot.compare(FRESH, upgrade);

        assertFalse(report.equivalent());
        assertEquals(Set.of("relation/fixture.widget"), report.missing());
        assertEquals(Set.of("index/fixture.extra_idx"), report.unexpected());
        assertEquals(Set.of("schema/fixture"), report.changed().keySet());
        assertEquals("{\"comment\": \"fresh\"}", report.changed().get("schema/fixture").expected());
        assertEquals("{\"comment\": \"upgraded\"}", report.changed().get("schema/fixture").actual());
    }

    /**
     * One overload changing does not implicate the other.
     */
    @Test
    void comparesOverloadsIndependently() {
        Map<String, String> upgrade = new HashMap<>(FRESH);
        upgrade.put("function/fixture.bump(integer)", "{\"identity_arguments\": \"i bigint\"}");

        CatalogSnapshot.Report report = CatalogSnapshot.compare(FRESH, upgrade);

        assertEquals(Set.of("function/fixture.bump(integer)"), report.changed().keySet());
        assertTrue(report.unexpected().isEmpty());
        assertTrue(report.missing().isEmpty());
    }

    /**
     * The assertion fails naming a missing object and shows both documents of a
     * change.
     */
    @Test
    void assertionFailsWithTheDifference() {
        Map<String, String> upgrade = Map.of("schema/fixture", "{\"comment\": \"upgraded\"}");

        CatalogSnapshot.Report report = CatalogSnapshot.compare(FRESH, upgrade);
        AssertionError error = assertThrows(AssertionError.class,
                () -> CatalogSnapshot.assertEquivalent(report));

        assertTrue(error.getMessage().contains("relation/fixture.widget"), error.getMessage());
        assertTrue(error.getMessage().contains("from-scratch"), error.getMessage());
        assertTrue(error.getMessage().contains("upgraded"), error.getMessage());
    }

    /**
     * The assertion passes an equivalent report.
     */
    @Test
    void assertionPassesEquivalentReport() {
        CatalogSnapshot.assertEquivalent(CatalogSnapshot.compare(FRESH, FRESH));
    }
}
