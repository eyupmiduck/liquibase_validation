package io.github.eyupmiduck.changelogvalidator.linter.config;

import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link LinterConfig}, in particular the parsing of the configured
 * PostgreSQL version for the version-gated rules.
 */
class LinterConfigTest {

    private static LinterConfig config(String pgVersion) {
        return new LinterConfig(pgVersion, Severity.ERROR, List.of(), List.of(), Map.of());
    }

    /**
     * A blank or absent version yields no major version, so the rules run
     * without version gating.
     */
    @Test
    void noMajorVersionWhenTheVersionIsAbsentOrBlank() {
        assertNull(LinterConfig.defaults().postgresMajorVersion());
        assertNull(config(null).postgresMajorVersion());
        assertNull(config("  ").postgresMajorVersion());
    }

    /**
     * A major version or a full version string yields its major version.
     */
    @Test
    void parsesTheMajorVersion() {
        assertEquals(17, config("17").postgresMajorVersion());
        assertEquals(16, config("16.4").postgresMajorVersion());
        assertEquals(18, config(" 18 ").postgresMajorVersion());
    }

    /**
     * A non-numeric version is rejected with a message naming the setting, so a
     * typo cannot silently disable version gating.
     */
    @Test
    void rejectsANonNumericVersion() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> config("latest").postgresMajorVersion());
        assertEquals("invalid pgVersion: latest", error.getMessage());
    }
}
