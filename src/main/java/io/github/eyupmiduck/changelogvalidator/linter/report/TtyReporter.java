package io.github.eyupmiduck.changelogvalidator.linter.report;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Renders findings as one line per finding in a compiler-like format:
 * {@code file:line:column: severity: message [rule-id]}.
 */
public final class TtyReporter implements Reporter {

    @Override
    public void report(List<Finding> findings, Appendable out) throws IOException {
        for (Finding finding : findings) {
            out.append(finding.file().toString())
                    .append(':')
                    .append(Integer.toString(finding.line()))
                    .append(':')
                    .append(Integer.toString(finding.column()))
                    .append(": ")
                    .append(finding.severity().name().toLowerCase(Locale.ROOT))
                    .append(": ")
                    .append(finding.message())
                    .append(" [")
                    .append(finding.ruleId())
                    .append(']');
            if (finding.help() != null) {
                out.append(System.lineSeparator()).append("  help: ").append(finding.help());
            }
            out.append(System.lineSeparator());
        }
    }
}
