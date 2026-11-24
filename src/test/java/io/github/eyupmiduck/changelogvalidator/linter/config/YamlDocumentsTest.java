package io.github.eyupmiduck.changelogvalidator.linter.config;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies {@link YamlDocuments}: the safe loader and the block-style dump the
 * whitelist writer depends on.
 */
class YamlDocumentsTest {

    /**
     * The dump uses block style with two-space indentation, matching the
     * whitelist format, and loads back to the same value.
     */
    @Test
    void dumpsBlockStyleYaml() {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("rule", "rule-a");
        entry.put("file", "db/x.sql");
        entry.put("reason", "accepted");

        String yaml = YamlDocuments.dump(List.of(entry));

        assertEquals("""
                - rule: rule-a
                  file: db/x.sql
                  reason: accepted
                """, yaml);
        assertEquals(List.of(entry), YamlDocuments.safe().load(yaml));
    }
}
