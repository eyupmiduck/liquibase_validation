package io.github.eyupmiduck.changelogvalidator.testing;

import java.util.Collection;
import java.util.List;

/**
 * Runs the shared {@link TrackingTablesContract} against the library's fixture
 * template database, so the inherited test and its configuration plumbing are
 * exercised here too.
 */
class TrackingTablesContractTest extends TrackingTablesContract {

    /**
     * The library has no project image or roles, so it uses the shared defaults.
     *
     * @return the default configuration
     */
    @Override
    protected Configuration configuration() {
        return Configuration.DEFAULT;
    }

    /**
     * The fixture database has no application schemas beyond {@code public}.
     *
     * @return the application schemas
     */
    @Override
    protected Collection<String> applicationSchemas() {
        return List.of(PUBLIC_SCHEMA);
    }
}
