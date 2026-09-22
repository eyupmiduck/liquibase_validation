package io.github.eyupmiduck.changelogvalidator.linter.report;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders findings as a SARIF v2.1.0 document for GitHub code scanning.
 */
public final class SarifReporter implements Reporter {

    private static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final String TOOL_NAME = "liquibase-linter";

    @Override
    public void report(List<Finding> findings, Appendable out) throws IOException {
        Map<String, Severity> ruleSeverities = new LinkedHashMap<>();
        for (Finding finding : findings) {
            ruleSeverities.putIfAbsent(finding.ruleId(), finding.severity());
        }
        out.append('{')
                .append("\"$schema\":").append(Json.quote(SCHEMA)).append(',')
                .append("\"version\":").append(Json.quote("2.1.0")).append(',')
                .append("\"runs\":[{\"tool\":{\"driver\":{\"name\":").append(Json.quote(TOOL_NAME))
                .append(",\"rules\":[");
        int ruleIndex = 0;
        for (Map.Entry<String, Severity> entry : ruleSeverities.entrySet()) {
            if (ruleIndex++ > 0) {
                out.append(',');
            }
            out.append("{\"id\":").append(Json.quote(entry.getKey())).append('}');
        }
        out.append("]}},\"results\":[");
        for (int i = 0; i < findings.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            Finding finding = findings.get(i);
            out.append("{\"ruleId\":").append(Json.quote(finding.ruleId()))
                    .append(",\"level\":").append(Json.quote(level(finding.severity())))
                    .append(",\"message\":{\"text\":").append(Json.quote(finding.message())).append('}')
                    .append(",\"locations\":[{\"physicalLocation\":{\"artifactLocation\":{\"uri\":")
                    .append(Json.quote(finding.file().toString().replace('\\', '/')))
                    .append("},\"region\":{\"startLine\":").append(Integer.toString(finding.line()))
                    .append(",\"startColumn\":").append(Integer.toString(finding.column()))
                    .append("}}}]}");
        }
        out.append("]}]}");
    }

    private static String level(Severity severity) {
        return switch (severity) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
        };
    }
}
