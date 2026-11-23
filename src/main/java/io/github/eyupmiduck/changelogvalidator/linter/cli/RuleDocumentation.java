package io.github.eyupmiduck.changelogvalidator.linter.cli;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;

import java.util.List;
import java.util.Locale;

/**
 * Generates the README rule-reference table from the rule metadata.
 *
 * <p>The generated block is delimited by {@link #START} and {@link #END} in the
 * README, and {@code RuleDocumentationTest} fails when the checked-in block and
 * the rule implementations disagree. Replace the block with
 * {@link #tableBlock(LinterConfig)} output after changing a rule's id, severity,
 * default state, description or help.
 */
public final class RuleDocumentation {

    /**
     * The marker before the generated table.
     */
    public static final String START = "<!-- rule-metadata:start -->";

    /**
     * The marker after the generated table.
     */
    public static final String END = "<!-- rule-metadata:end -->";

    private RuleDocumentation() {
    }

    /**
     * Renders the rule table, including the start and end markers.
     *
     * @param config the configuration used to build the rule instances
     * @return the generated block
     */
    public static String tableBlock(LinterConfig config) {
        List<RuleCatalog.Entry> entries = RuleCatalog.entries(config);
        int ruleWidth = width("Rule", entries.stream().map(RuleDocumentation::link).toList());
        int severityWidth = width("Severity", entries.stream()
                .map(RuleDocumentation::severity).toList());
        int defaultWidth = width("Default", entries.stream()
                .map(RuleCatalog.Entry::defaultState).toList());
        int descriptionWidth = width("What it detects", entries.stream()
                .map(entry -> cell(entry.description())).toList());
        StringBuilder table = new StringBuilder();
        table.append(START).append('\n');
        appendRow(table, "Rule", "Severity", "Default", "What it detects",
                ruleWidth, severityWidth, defaultWidth, descriptionWidth);
        appendSeparator(table, ruleWidth, severityWidth, defaultWidth, descriptionWidth);
        for (RuleCatalog.Entry entry : entries) {
            appendRow(table, link(entry), severity(entry), entry.defaultState(), cell(entry.description()),
                    ruleWidth, severityWidth, defaultWidth, descriptionWidth);
        }
        table.append(END);
        return table.toString();
    }

    private static String link(RuleCatalog.Entry entry) {
        return "[`" + entry.id() + "`](#" + entry.id() + ")";
    }

    private static String severity(RuleCatalog.Entry entry) {
        return entry.severity().name().toLowerCase(Locale.ROOT);
    }

    private static String cell(String value) {
        return value.replace("|", "\\|");
    }

    private static int width(String header, List<String> values) {
        int width = header.length();
        for (String value : values) {
            width = Math.max(width, value.length());
        }
        return width;
    }

    private static void appendRow(StringBuilder table, String rule, String severity, String defaultState,
                                  String description, int ruleWidth, int severityWidth, int defaultWidth,
                                  int descriptionWidth) {
        table.append("| ").append(pad(rule, ruleWidth))
                .append(" | ").append(pad(severity, severityWidth))
                .append(" | ").append(pad(defaultState, defaultWidth))
                .append(" | ").append(pad(description, descriptionWidth))
                .append(" |\n");
    }

    private static void appendSeparator(StringBuilder table, int ruleWidth, int severityWidth,
                                        int defaultWidth, int descriptionWidth) {
        table.append('|').append("-".repeat(ruleWidth + 2))
                .append('|').append("-".repeat(severityWidth + 2))
                .append('|').append("-".repeat(defaultWidth + 2))
                .append('|').append("-".repeat(descriptionWidth + 2))
                .append("|\n");
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }
}
