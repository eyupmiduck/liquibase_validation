package io.github.eyupmiduck.changelogvalidator.linter.report;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@link SarifReporter} output conforms to the SARIF 2.1.0 schema
 * GitHub code scanning validates against, for a mixture of findings and for an
 * empty run.
 */
class SarifReporterSchemaTest {

    private static final String SCHEMA = "sarif/sarif-2.1.0.json";

    private static final Finding ERROR = new Finding("changeset-single-statement", Severity.ERROR,
            "007-index", "me", Path.of("/db/changelog/changes/sql_changes/007-index.sql"),
            3, 1, "CREATE INDEX CONCURRENTLY", "must be the only statement", "split it");
    private static final Finding WARNING = new Finding("require-concurrent-index-creation", Severity.WARNING,
            "008-add-index", "me", Path.of("/db/changelog/changes.xml"),
            5, 13, "CREATE INDEX", "blocks writes", null);
    private static final Finding INFO = new Finding("some-info-rule", Severity.INFO,
            "009-note", "me", Path.of("/db/changelog/changes.xml"),
            1, 1, null, "a note", null);

    private static String render(List<Finding> findings) throws IOException {
        StringBuilder out = new StringBuilder();
        new SarifReporter().report(findings, out);
        return out.toString();
    }

    private static Set<String> validationErrors(String sarif) throws IOException {
        Schema schema;
        try (InputStream input = SarifReporterSchemaTest.class.getClassLoader().getResourceAsStream(SCHEMA)) {
            assertNotNull(input, SCHEMA + " not found on the test classpath");
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_7).getSchema(input);
        }
        JsonNode document = new ObjectMapper().readTree(sarif);
        List<Error> messages = schema.validate(document);
        assertTrue(sarif.startsWith("{"), "SARIF must be a JSON object");
        return messages.stream().map(Error::getMessage).collect(Collectors.toSet());
    }

    /**
     * A SARIF document with error, warning and info findings is valid.
     */
    @Test
    void validatesMixedFindings() throws IOException {
        String sarif = render(List.of(ERROR, WARNING, INFO));

        assertEquals(Set.of(), validationErrors(sarif), () -> "invalid SARIF:\n" + sarif);
    }

    /**
     * A SARIF document with no findings is valid.
     */
    @Test
    void validatesAnEmptyRun() throws IOException {
        String sarif = render(List.of());

        assertEquals(Set.of(), validationErrors(sarif));
    }

    /**
     * Every finding carries the mapped level, a location with a line and column,
     * and a markdown message so the code-scanning UI renders help text.
     */
    @Test
    void carriesLevelsLocationsAndMarkdown() throws IOException {
        JsonNode sarif = new ObjectMapper().readTree(render(List.of(ERROR, WARNING, INFO)));
        JsonNode results = sarif.at("/runs/0/results");

        assertEquals(3, results.size());
        assertEquals("error", results.get(0).path("level").asString());
        assertEquals("warning", results.get(1).path("level").asString());
        assertEquals("note", results.get(2).path("level").asString());
        JsonNode region = results.get(0).at("/locations/0/physicalLocation/region");
        assertEquals(3, region.path("startLine").asInt());
        assertEquals(1, region.path("startColumn").asInt());
        assertEquals(ERROR.message() + "\n\n" + ERROR.help(), results.get(0).path("message").path("markdown").asString());

        JsonNode rules = sarif.at("/runs/0/tool/driver/rules");
        assertEquals(3, rules.size());
        assertEquals("error", rules.get(0).path("defaultConfiguration").path("level").asString());
    }
}
