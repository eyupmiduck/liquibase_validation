package io.github.eyupmiduck.changelogvalidator.testing;

import java.util.List;

/**
 * Runs the shared {@link ExtensionContract} against an extension every
 * PostgreSQL installation provides.
 */
class ExtensionContractTest extends ExtensionContract {

    /**
     * {@code plpgsql} is always available and installed.
     *
     * @return the extension list
     */
    @Override
    protected List<String> extensions() {
        return List.of("plpgsql");
    }
}
