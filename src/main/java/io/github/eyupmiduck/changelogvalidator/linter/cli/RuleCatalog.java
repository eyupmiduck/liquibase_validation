package io.github.eyupmiduck.changelogvalidator.linter.cli;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.ProjectRule;
import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.report.Json;
import io.github.eyupmiduck.changelogvalidator.linter.rules.Rules;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The registered rules and their metadata, for {@code --list-rules}.
 *
 * <p>The id, kind, default severity and default state come from the rule
 * implementations, so the listing cannot drift from the rule set the linter
 * runs. The table form is for a terminal; the JSON form is for tooling.
 */
public final class RuleCatalog {

    private RuleCatalog() {
    }

    /**
     * Returns every registered rule, in registration order: per-changeset rules,
     * then changeset-only project rules, then project-analysis rules.
     *
     * @param config the configuration, used to build the same rule instances the
     *               linter uses
     * @return the rule metadata
     */
    public static List<Entry> entries(LinterConfig config) {
        List<Entry> entries = new ArrayList<>();
        for (Rule rule : Rules.all(config)) {
            entries.add(new Entry(rule.id(), Kind.CHANGESET, rule.defaultSeverity(), rule.enabledByDefault()));
        }
        for (ProjectRule rule : Rules.projectRules()) {
            entries.add(new Entry(rule.id(), Kind.PROJECT, rule.defaultSeverity(), rule.enabledByDefault()));
        }
        for (ProjectAnalysisRule rule : Rules.projectAnalysisRules(config)) {
            entries.add(new Entry(rule.id(), Kind.PROJECT_ANALYSIS, rule.defaultSeverity(),
                    rule.enabledByDefault()));
        }
        return List.copyOf(entries);
    }

    /**
     * Writes a fixed-width table with a header.
     *
     * @param entries the rule metadata
     * @param out     the destination
     * @throws IOException if writing fails
     */
    public static void writeTable(List<Entry> entries, Appendable out) throws IOException {
        out.append(String.format("%-40s %-16s %-8s %s%n", "ID", "KIND", "SEVERITY", "DEFAULT"));
        for (Entry entry : entries) {
            out.append(String.format("%-40s %-16s %-8s %s%n",
                    entry.id(),
                    entry.kind().label(),
                    entry.severity().name().toLowerCase(Locale.ROOT),
                    entry.enabledByDefault() ? "on" : "opt-in"));
        }
    }

    /**
     * Writes a JSON array of {@code {id, kind, severity, default}} objects.
     *
     * @param entries the rule metadata
     * @param out     the destination
     * @throws IOException if writing fails
     */
    public static void writeJson(List<Entry> entries, Appendable out) throws IOException {
        out.append('[');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            Entry entry = entries.get(i);
            out.append('{')
                    .append("\"id\":").append(Json.quote(entry.id())).append(',')
                    .append("\"kind\":").append(Json.quote(entry.kind().label())).append(',')
                    .append("\"severity\":")
                    .append(Json.quote(entry.severity().name().toLowerCase(Locale.ROOT))).append(',')
                    .append("\"default\":").append(Json.quote(entry.enabledByDefault() ? "on" : "opt-in"))
                    .append('}');
        }
        out.append(']');
    }

    /**
     * The kind of rule.
     */
    public enum Kind {

        /**
         * A per-changeset rule.
         */
        CHANGESET("changeset"),

        /**
         * A whole-graph rule over the changeset list.
         */
        PROJECT("project"),

        /**
         * A whole-graph rule over the project model.
         */
        PROJECT_ANALYSIS("project-analysis");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /**
         * The label used in the table and JSON output.
         *
         * @return the label
         */
        public String label() {
            return label;
        }
    }

    /**
     * One rule's metadata.
     *
     * @param id               the stable rule id
     * @param kind             the rule kind
     * @param severity         the default severity
     * @param enabledByDefault whether the rule runs without being listed under
     *                         {@code include}
     */
    public record Entry(String id, Kind kind, Severity severity, boolean enabledByDefault) {

        /**
         * Validates the required components.
         */
        public Entry {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("id must not be blank");
            }
            if (kind == null) {
                throw new IllegalArgumentException("kind must not be null");
            }
            if (severity == null) {
                throw new IllegalArgumentException("severity must not be null");
            }
        }
    }
}
