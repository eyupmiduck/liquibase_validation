package io.github.eyupmiduck.changelogvalidator.testing;

import io.github.eyupmiduck.changelogvalidator.SchemaLayeringCheck;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract for a consuming project's schema-layering test: a routine in a lower
 * schema must not reference a higher schema, and every configured schema must
 * contain at least one routine, relation or type.
 *
 * <p>A consumer extends this class and returns its one {@link ProjectProfile}
 * from {@link #projectProfile()}; {@link #schemaLayers()} defaults to the
 * profile's managed schemas, which must be ordered highest to lowest (for
 * example {@code dml_utils}, {@code dml_utils_lib}, {@code dml_utils_data}).
 * Override {@link #schemaLayers()} when the managed schemas are not in
 * dependency order. Add a known exception to {@link #allowedReferences()}; an
 * entry that matches no real reference is stale and fails, so a removed
 * exception cannot hide a new reference.
 *
 * <p>The check reads the routine bodies and signatures from the catalog and
 * looks for {@code schema.object} references. A name in a comment or a string is
 * not a reference; dynamic SQL inside a dollar-quoted body is opaque. The
 * contract is non-vacuous: a schema with no routine, relation or type fails.
 */
public abstract class SchemaLayeringContract extends PostgresTestBase {

    /**
     * The schemas to check, ordered highest to lowest, from the profile's
     * managed schemas.
     *
     * @return the ordered schemas
     */
    protected List<String> schemaLayers() {
        return List.copyOf(projectProfile().managedSchemas());
    }

    /**
     * The references that are intentionally allowed.
     *
     * @return the allowed references, empty by default
     */
    protected Set<SchemaLayeringCheck.Reference> allowedReferences() {
        return Set.of();
    }

    /**
     * No lower schema references a higher schema, every allow-list entry is a
     * real reference, and no configured schema is empty.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void lowerSchemasDoNotReferenceHigherSchemas() throws Exception {
        List<String> layers = schemaLayers();
        try (Connection owner = openOwnerConnection()) {
            SchemaLayeringCheck.Report report =
                    SchemaLayeringCheck.inspect(owner, layers, allowedReferences());
            assertTrue(report.emptySchemas().isEmpty(),
                    () -> "schema layers with no routine, relation or type: " + report.emptySchemas()
                            + "; objects per schema: " + report.objectsBySchema());
            assertEquals(List.of(), report.unexpected(),
                    () -> "lower schemas reference higher schemas (add a deliberate exception to "
                            + "allowedReferences()): "
                            + report.unexpected().stream().map(SchemaLayeringCheck.Reference::describe).toList());
            assertEquals(List.of(), report.staleAllowList(),
                    () -> "allowedReferences() entries that match no reference: "
                            + report.staleAllowList().stream()
                            .map(SchemaLayeringCheck.Reference::describe).toList());
        }
    }
}
