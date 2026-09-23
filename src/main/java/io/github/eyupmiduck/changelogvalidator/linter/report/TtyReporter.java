package io.github.eyupmiduck.changelogvalidator.linter.report;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Renders findings as one line per finding in a compiler-like format:
 * {@code file:line:column: severity: message [rule-id]}. Newlines in the message
 * and help are collapsed so each finding stays on one parseable line, and the
 * line terminator is always {@code \n} regardless of the host OS.
 */
public final class TtyReporter implements Reporter {

    private static String oneLine(String value) {
        return value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

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
                    .append(oneLine(finding.message()))
                    .append(" [")
                    .append(finding.ruleId())
                    .append(']');
            if (finding.help() != null) {
                out.append('\n').append("  help: ").append(oneLine(finding.help()));
            }
            out.append('\n');
        }
    }
}
