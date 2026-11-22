package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the hooks {@link PlpgsqlCheckContract} adds to
 * {@link PostgresTestBase}. The inherited analyser test needs the
 * {@code plpgsql_check} extension, which the library's stock image does not
 * provide, so the consumer projects exercise that test; here only the profile
 * defaults are covered.
 */
class PlpgsqlCheckContractTest {

    /**
     * The allow-list defaults to the conventional resource name, and the routine
     * schemas come from the profile.
     */
    @Test
    void defaultsToTheConventionalWhitelist() {
        Probe probe = new Probe();

        assertEquals("plpgsql-check-whitelist.yml", probe.whitelistResource());
        assertEquals(List.of("probe_schema"), probe.routineSchemas());
    }

    /**
     * A minimal concrete contract for the hook assertions; JUnit does not
     * discover it as a test class because it is not selected and has no
     * distinctive test name.
     */
    private static final class Probe extends PlpgsqlCheckContract {

        private static final ProjectProfile PROFILE = ProjectProfile.builder(Configuration.DEFAULT)
                .managedSchemas("probe_schema")
                .routineSchemas("probe_schema")
                .build();

        @Override
        protected ProjectProfile projectProfile() {
            return PROFILE;
        }
    }
}
