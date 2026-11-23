package io.github.eyupmiduck.changelogvalidator.testing;

import io.github.eyupmiduck.changelogvalidator.CatalogCommentsCheck;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Collection;
import java.util.List;

/**
 * Contract for a consuming project's catalog-comment test: every schema, table,
 * column, domain, type, function and procedure (and, by default, trigger) in the
 * configured schemas has a non-blank comment.
 *
 * <p>A consumer extends this class and returns its one {@link ProjectProfile}
 * from {@link #projectProfile()}; {@link #commentedSchemas()} defaults to the
 * profile's managed schemas. Override {@link #allowedObjects()} for an explicit
 * exception, and {@link #includeTriggers()} when the project does not comment
 * triggers.
 *
 * <p>The contract is non-vacuous: it fails when no object was inspected, so a
 * misspelled schema cannot pass. An allow-list entry that accepts nothing is
 * stale and fails too.
 */
public abstract class CommentContract extends PostgresTestBase {

    /**
     * The schemas whose objects must have comments, from the profile's managed
     * schemas.
     *
     * @return the schemas to check
     */
    protected Collection<String> commentedSchemas() {
        return projectProfile().managedSchemas();
    }

    /**
     * Whether trigger comments are required.
     *
     * @return {@code true} by default
     */
    protected boolean includeTriggers() {
        return true;
    }

    /**
     * The accepted missing-comment exceptions.
     *
     * @return the allow-list, empty by default
     */
    protected Collection<CatalogCommentsCheck.AllowedObject> allowedObjects() {
        return List.of();
    }

    /**
     * Every inspected object has a comment, every allow-list entry accepts a
     * violation, and the check inspected at least one object.
     *
     * @throws Exception if the catalog cannot be read
     */
    @Test
    void catalogObjectsHaveComments() throws Exception {
        try (Connection owner = openOwnerConnection()) {
            RoutineAssertions.assertCatalogObjectsHaveComments(
                    owner, commentedSchemas(), allowedObjects(), includeTriggers());
        }
    }
}
