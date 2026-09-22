package io.github.eyupmiduck.changelogvalidator.linter.report;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the reporters: the tty line format, valid JSON, and a SARIF v2.1.0
 * document with rule metadata, levels and locations.
 */
@SuppressWarnings("unchecked")
class ReporterTest {

    private static final Finding WARNING = new Finding("rule-a", Severity.WARNING, "cs-1", "me",
            Path.of("/db/changelog.xml"), 3, 5, "bad thing", "fix it");
    private static final Finding INFO = new Finding("rule-b", Severity.INFO, "cs-2", "you",
            Path.of("/db/other.sql"), 1, 1, "a note", null);

    /**
     * The tty reporter writes one line per finding plus an optional help line.
     */
    @Test
    void rendersTty() throws IOException {
        StringBuilder out = new StringBuilder();

        new TtyReporter().report(List.of(WARNING, INFO), out);

        String text = out.toString();
        assertTrue(text.contains("/db/changelog.xml:3:5: warning: bad thing [rule-a]"));
        assertTrue(text.contains("  help: fix it"));
        assertTrue(text.contains("/db/other.sql:1:1: info: a note [rule-b]"));
    }

    /**
     * The JSON reporter emits a valid array with every field.
     */
    @Test
    void rendersJson() throws IOException {
        StringBuilder out = new StringBuilder();

        new JsonReporter().report(List.of(WARNING, INFO), out);

        List<Map<String, Object>> parsed = new Yaml().load(out.toString());
        assertEquals(2, parsed.size());
        assertEquals("rule-a", parsed.get(0).get("rule"));
        assertEquals("warning", parsed.get(0).get("severity"));
        assertEquals("cs-1", parsed.get(0).get("changeSetId"));
        assertEquals("me", parsed.get(0).get("changeSetAuthor"));
        assertEquals("/db/changelog.xml", parsed.get(0).get("file"));
        assertEquals(3, parsed.get(0).get("line"));
        assertEquals(5, parsed.get(0).get("column"));
        assertEquals("bad thing", parsed.get(0).get("message"));
        assertEquals("fix it", parsed.get(0).get("help"));
        assertTrue(parsed.get(0).containsKey("help"));
        assertNull(parsed.get(1).get("help"));
    }

    /**
     * The SARIF reporter emits a v2.1.0 document with rule metadata, mapped
     * levels and a location.
     */
    @Test
    void rendersSarif() throws IOException {
        StringBuilder out = new StringBuilder();

        new SarifReporter().report(List.of(WARNING, INFO), out);

        Map<String, Object> sarif = new Yaml().load(out.toString());
        assertEquals("2.1.0", sarif.get("version"));
        List<Map<String, Object>> runs = (List<Map<String, Object>>) sarif.get("runs");
        Map<String, Object> run = runs.get(0);
        Map<String, Object> driver = (Map<String, Object>) run.get("tool");
        driver = (Map<String, Object>) driver.get("driver");
        assertEquals("liquibase-linter", driver.get("name"));
        assertEquals(2, ((List<?>) driver.get("rules")).size());

        List<Map<String, Object>> results = (List<Map<String, Object>>) run.get("results");
        assertEquals("rule-a", results.get(0).get("ruleId"));
        assertEquals("warning", results.get(0).get("level"));
        assertEquals("note", results.get(1).get("level"));
        Map<String, Object> location = (Map<String, Object>) ((List<?>) results.get(0).get("locations")).get(0);
        Map<String, Object> physical = (Map<String, Object>) location.get("physicalLocation");
        Map<String, Object> artifact = (Map<String, Object>) physical.get("artifactLocation");
        assertEquals("/db/changelog.xml", artifact.get("uri"));
    }

    /**
     * The empty findings list still produces valid JSON and SARIF.
     */
    @Test
    void rendersEmptyFindings() throws IOException {
        StringBuilder json = new StringBuilder();
        new JsonReporter().report(List.of(), json);
        assertTrue(((List<?>) new Yaml().load(json.toString())).isEmpty());

        StringBuilder sarif = new StringBuilder();
        new SarifReporter().report(List.of(), sarif);
        assertFalse(sarif.toString().isBlank());
    }

    /**
     * The JSON quoting escapes control characters and quotes.
     */
    @Test
    void quotesJsonStrings() {
        assertEquals("null", Json.quote(null));
        assertEquals("\"a\\\"b\\nc\"", Json.quote("a\"b\nc"));
        assertEquals("\"\\u0001\"", Json.quote("\u0001"));
    }
}
