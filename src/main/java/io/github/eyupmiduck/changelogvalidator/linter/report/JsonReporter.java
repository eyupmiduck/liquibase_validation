package io.github.eyupmiduck.changelogvalidator.linter.report;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Renders findings as a JSON array of objects.
 */
public final class JsonReporter implements Reporter {

    @Override
    public void report(List<Finding> findings, Appendable out) throws IOException {
        out.append('[');
        for (int i = 0; i < findings.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            Finding finding = findings.get(i);
            out.append('{')
                    .append("\"rule\":").append(Json.quote(finding.ruleId())).append(',')
                    .append("\"severity\":")
                    .append(Json.quote(finding.severity().name().toLowerCase(Locale.ROOT))).append(',')
                    .append("\"changeSetId\":").append(Json.quote(finding.changeSetId())).append(',')
                    .append("\"changeSetAuthor\":").append(Json.quote(finding.changeSetAuthor())).append(',')
                    .append("\"file\":").append(Json.quote(finding.file().toString())).append(',')
                    .append("\"line\":").append(Integer.toString(finding.line())).append(',')
                    .append("\"column\":").append(Integer.toString(finding.column())).append(',')
                    .append("\"message\":").append(Json.quote(finding.message())).append(',')
                    .append("\"help\":").append(Json.quote(finding.help()))
                    .append('}');
        }
        out.append(']');
    }
}
