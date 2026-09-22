package io.github.eyupmiduck.changelogvalidator.linter.report;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders findings as a SARIF v2.1.0 document, so GitHub code scanning can show
 * them inline on a pull request next to CodeQL and other tools.
 *
 * <p>The document carries the {@code $schema} and version, one {@code run} whose
 * driver is {@code liquibase-linter} with the rule ids and their default
 * severities, and one result per finding with the mapped level, the message and
 * the file/line/column location. The message is also written as a
 * {@code markdown} string so the code-scanning UI renders it; a test validates
 * the shape against the SARIF schema in {@code src/test/resources/sarif}.
 */
public final class SarifReporter implements Reporter {

    private static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String SCHEMA_VERSION = "2.1.0";
    private static final String TOOL_NAME = "liquibase-linter";
    private static final String INFORMATION_URI = "https://github.com/eyupmiduck/liquibase_validation";

    private static String level(Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
        };
    }

    @Override
    public void report(List<Finding> findings, Appendable out) throws IOException {
        Map<String, Severity> ruleSeverities = new LinkedHashMap<>();
        for (Finding finding : findings) {
            ruleSeverities.putIfAbsent(finding.ruleId(), finding.severity());
        }
        out.append('{')
                .append("\"$schema\":").append(Json.quote(SCHEMA)).append(',')
                .append("\"version\":").append(Json.quote(SCHEMA_VERSION)).append(',')
                .append("\"runs\":[{\"tool\":{\"driver\":{\"name\":").append(Json.quote(TOOL_NAME))
                .append(",\"informationUri\":").append(Json.quote(INFORMATION_URI))
                .append(",\"rules\":[");
        int ruleIndex = 0;
        for (Map.Entry<String, Severity> entry : ruleSeverities.entrySet()) {
            if (ruleIndex++ > 0) {
                out.append(',');
            }
            out.append("{\"id\":").append(Json.quote(entry.getKey()))
                    .append(",\"defaultConfiguration\":{\"level\":").append(Json.quote(level(entry.getValue())))
                    .append("}}");
        }
        out.append("]}},\"results\":[");
        for (int i = 0; i < findings.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            Finding finding = findings.get(i);
            out.append("{\"ruleId\":").append(Json.quote(finding.ruleId()))
                    .append(",\"level\":").append(Json.quote(level(finding.severity())))
                    .append(",\"message\":{\"text\":").append(Json.quote(finding.message()))
                    .append(",\"markdown\":").append(Json.quote(markdown(finding))).append('}')
                    .append(",\"locations\":[{\"physicalLocation\":{\"artifactLocation\":{\"uri\":")
                    .append(Json.quote(finding.file().toString().replace('\\', '/')))
                    .append("},\"region\":{\"startLine\":").append(Integer.toString(finding.line()))
                    .append(",\"startColumn\":").append(Integer.toString(finding.column()))
                    .append("}}}]}");
        }
        out.append("]}]}");
    }

    private static String markdown(Finding finding) {
        StringBuilder message = new StringBuilder(finding.message());
        if (finding.help() != null) {
            message.append("\n\n").append(finding.help());
        }
        return message.toString();
    }
}
