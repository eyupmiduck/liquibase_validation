package io.github.eyupmiduck.changelogvalidator.linter.cli;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RuleCatalog}: the JSON output carries every documented field,
 * including the description and the help text.
 */
@SuppressWarnings("unchecked")
class RuleCatalogTest {

    /**
     * The JSON form includes the id, kind, severity, default state, description
     * and help for every rule.
     */
    @Test
    void writesTheFullJsonMetadata() throws Exception {
        StringBuilder out = new StringBuilder();

        RuleCatalog.writeJson(RuleCatalog.entries(LinterConfig.defaults()), out);

        List<Map<String, Object>> entries = new Yaml().load(out.toString());
        assertFalse(entries.isEmpty());
        for (Map<String, Object> entry : entries) {
            assertTrue(entry.containsKey("id"), entry.toString());
            assertTrue(entry.containsKey("kind"), entry.toString());
            assertTrue(entry.containsKey("severity"), entry.toString());
            assertTrue(entry.containsKey("default"), entry.toString());
            assertTrue(entry.containsKey("description"), entry.toString());
            assertTrue(entry.containsKey("help"), entry.toString());
        }
    }
}
