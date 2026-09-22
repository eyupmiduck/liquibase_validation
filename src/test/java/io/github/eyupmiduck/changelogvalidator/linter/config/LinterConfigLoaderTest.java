package io.github.eyupmiduck.changelogvalidator.linter.config;

import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link LinterConfigLoader}: defaults for missing or empty files,
 * parsing of every field, per-rule severities and options, and fail-fast errors
 * for malformed input.
 */
class LinterConfigLoaderTest {

    @TempDir
    Path tempDir;

    /**
     * A missing or empty file yields the defaults.
     */
    @Test
    void returnsDefaultsForMissingOrEmptyFile() throws IOException {
        LinterConfig missing = LinterConfigLoader.load(tempDir.resolve("absent.yml"));

        assertEquals(LinterConfig.defaults(), missing);

        Path empty = tempDir.resolve("empty.yml");
        Files.writeString(empty, "");
        assertEquals(LinterConfig.defaults(), LinterConfigLoader.load(empty));
    }

    /**
     * Every field is parsed, including a scalar and a mapping rule entry.
     */
    @Test
    void parsesConfiguration() throws IOException {
        Path config = tempDir.resolve(".liquibase-linter.yml");
        Files.writeString(config, """
                pgVersion: '17'
                failOn: warning
                exclude:
                  - prefer-bigint-over-int
                include:
                  - require-concurrent-index-creation
                rules:
                  require-concurrent-index-creation:
                    severity: error
                    options:
                      allowOnNewTables: true
                  ban-drop-table: info
                """);

        LinterConfig loaded = LinterConfigLoader.load(config);

        assertEquals("17", loaded.pgVersion());
        assertEquals(Severity.WARNING, loaded.failOn());
        assertEquals(List.of("prefer-bigint-over-int"), loaded.exclude());
        assertEquals(List.of("require-concurrent-index-creation"), loaded.include());
        LinterConfig.RuleSettings concurrent = loaded.rules().get("require-concurrent-index-creation");
        assertEquals(Severity.ERROR, concurrent.severity());
        assertEquals(Map.of("allowOnNewTables", true), concurrent.options());
        assertEquals(Severity.INFO, loaded.rules().get("ban-drop-table").severity());
        assertNull(loaded.rules().get("ban-drop-table").options().get("missing"));
    }

    /**
     * Unknown top-level keys and unknown per-rule keys are rejected.
     */
    @Test
    void rejectsUnknownKeys() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> load("unknown: 1"));

        assertThrows(IllegalArgumentException.class, () -> load("""
                rules:
                  a-rule:
                    severity: warning
                    nope: 1
                """));
    }

    /**
     * Malformed values are rejected.
     */
    @Test
    void rejectsMalformedValues() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> load("failOn: loud"));
        assertThrows(IllegalArgumentException.class, () -> load("exclude: not-a-list"));
        assertThrows(IllegalArgumentException.class, () -> load("""
                exclude:
                  - 1
                """));
        assertThrows(IllegalArgumentException.class, () -> load("rules: 5"));
        assertThrows(IllegalArgumentException.class, () -> load("""
                rules:
                  a-rule: loud
                """));
        assertThrows(IllegalArgumentException.class, () -> load("""
                rules:
                  a-rule:
                    options: 5
                """));
    }

    /**
     * A rule entry that is only a mapping without a severity keeps the rule's
     * default severity and has no options.
     */
    @Test
    void acceptsRuleMappingWithoutSeverity() throws IOException {
        LinterConfig loaded = load("""
                rules:
                  a-rule: {}
                """);

        assertNull(loaded.rules().get("a-rule").severity());
        assertTrue(loaded.rules().get("a-rule").options().isEmpty());
    }

    /**
     * The referenced rule ids collect exclude, include and per-rule keys.
     */
    @Test
    void collectsReferencedRuleIds() throws IOException {
        LinterConfig loaded = load("""
                exclude: [a]
                include: [b]
                rules:
                  c: warning
                """);

        assertTrue(loaded.referencedRuleIds().containsAll(List.of("a", "b", "c")));
    }

    private LinterConfig load(String yaml) throws IOException {
        Path config = tempDir.resolve("config.yml");
        Files.writeString(config, yaml);
        return LinterConfigLoader.load(config);
    }
}
