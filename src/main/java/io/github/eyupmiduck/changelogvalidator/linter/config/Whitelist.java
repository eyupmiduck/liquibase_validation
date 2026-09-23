package io.github.eyupmiduck.changelogvalidator.linter.config;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The accepted linter findings, loaded from
 * {@code .liquibase-linter-whitelist.yml}.
 *
 * <p>An entry matches a finding field by field; a field it omits matches any
 * value, so an entry can be narrowed to the fields that identify it. Matching is
 * fail-closed: a finding no entry accepts is reported, an entry that matches no
 * finding is stale and reported, and a finding that matches several entries is
 * rejected as ambiguous. The latter two keep the whitelist from rotting or
 * becoming a catch-all.
 */
public final class Whitelist {

    private static final Set<String> KNOWN_KEYS = Set.of("rule", "file", "changeset", "statement", "reason");

    private final List<AllowedFinding> entries;

    private Whitelist(List<AllowedFinding> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Returns a whitelist that accepts nothing.
     *
     * @return the empty whitelist
     */
    public static Whitelist empty() {
        return new Whitelist(List.of());
    }

    /**
     * Loads a whitelist from a YAML file. A missing file yields the empty
     * whitelist, so a project without a whitelist still lints its findings.
     *
     * @param file the YAML file
     * @return the whitelist
     * @throws IOException if the file cannot be read or is malformed
     */
    public static Whitelist load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return empty();
        }
        try (InputStream input = Files.newInputStream(file)) {
            return load(input);
        }
    }

    /**
     * Loads a whitelist from a YAML document: a list of mappings with
     * {@code rule}, {@code file}, {@code changeset}, {@code statement} and
     * {@code reason} keys. The reason is required and non-empty; unknown keys,
     * an entry that sets none of the selector keys, and an ambiguous entry
     * fail fast.
     *
     * @param input the YAML document; an empty document yields the empty whitelist
     * @return the whitelist
     * @throws IOException if the document is malformed
     */
    public static Whitelist load(InputStream input) throws IOException {
        Object loaded;
        try {
            loaded = YamlDocuments.safe().load(input);
        } catch (YAMLException e) {
            // SnakeYAML reports malformed YAML as an unchecked YAMLException; the
            // documented contract here is IOException, so a caller that handles it
            // still sees malformed input.
            throw new IOException("linter whitelist is not valid YAML", e);
        }
        if (loaded == null) {
            return empty();
        }
        if (!(loaded instanceof List<?> items)) {
            throw new IOException("linter whitelist must be a YAML list");
        }
        List<AllowedFinding> entries = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!(items.get(i) instanceof Map<?, ?> map)) {
                throw new IOException("linter whitelist entry " + i + " must be a mapping");
            }
            entries.add(entry(i, map));
        }
        return new Whitelist(entries);
    }

    private static AllowedFinding entry(int index, Map<?, ?> map) throws IOException {
        for (Object key : map.keySet()) {
            if (!KNOWN_KEYS.contains(String.valueOf(key))) {
                throw new IOException("linter whitelist entry " + index + " has an unknown key: " + key);
            }
        }
        String reason = asString(index, "reason", map.get("reason"));
        if (reason == null || reason.isBlank()) {
            throw new IOException("linter whitelist entry " + index + " must set a non-empty reason");
        }
        AllowedFinding entry = new AllowedFinding(
                selector(index, map, "rule"),
                selector(index, map, "file"),
                selector(index, map, "changeset"),
                selector(index, map, "statement"),
                reason);
        if (entry.rule() == null && entry.file() == null && entry.changeset() == null && entry.statement() == null) {
            throw new IOException("linter whitelist entry " + index
                    + " must set at least one of rule, file, changeset or statement");
        }
        return entry;
    }

    private static String selector(int index, Map<?, ?> map, String key) throws IOException {
        String value = asString(index, key, map.get(key));
        if (value != null && value.isBlank()) {
            throw new IOException("linter whitelist entry " + index + " field " + key + " must not be blank");
        }
        return value;
    }

    private static String asString(int index, String key, Object value) throws IOException {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IOException("linter whitelist entry " + index + " field " + key
                    + " must be a string, got: " + value);
        }
        return text;
    }

    /**
     * Returns the accepted findings.
     *
     * @return the entries
     */
    public List<AllowedFinding> entries() {
        return entries;
    }

    /**
     * Splits {@code findings} into those no entry accepts and the entries that
     * matched no finding.
     *
     * @param findings the findings to check
     * @return the report
     * @throws IllegalArgumentException if a finding matches more than one entry
     */
    public Report apply(List<Finding> findings) {
        List<Finding> unmatched = new ArrayList<>();
        boolean[] matched = new boolean[entries.size()];
        for (Finding finding : findings) {
            int matches = 0;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).matches(finding)) {
                    matched[i] = true;
                    matches++;
                }
            }
            if (matches > 1) {
                throw new IllegalArgumentException("finding " + finding.ruleId() + " in " + finding.file()
                        + " matches " + matches + " whitelist entries; make the entries unambiguous");
            }
            if (matches == 0) {
                unmatched.add(finding);
            }
        }
        List<AllowedFinding> stale = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!matched[i]) {
                stale.add(entries.get(i));
            }
        }
        return new Report(unmatched, stale);
    }

    /**
     * One accepted finding. A {@code null} selector matches any value.
     *
     * @param rule      the rule id, or {@code null}
     * @param file      a path suffix of the finding's file, or {@code null}
     * @param changeset the changeset id, or {@code null}
     * @param statement the offending statement label, or {@code null}
     * @param reason    why the finding is accepted; required and non-empty
     */
    public record AllowedFinding(String rule, String file, String changeset, String statement, String reason) {

        private static boolean matches(String expected, String actual) {
            return expected == null || expected.equals(actual);
        }

        private static String orAny(String value) {
            return value == null ? "*" : value;
        }

        /**
         * Returns whether this entry accepts the given finding.
         *
         * @param finding the finding to test
         * @return {@code true} when every non-null selector matches
         */
        public boolean matches(Finding finding) {
            return matches(rule, finding.ruleId())
                    && matches(changeset, finding.changeSetId())
                    && matches(statement, finding.statement())
                    && matchesFile(finding.file());
        }

        private boolean matchesFile(Path actual) {
            if (file == null) {
                return true;
            }
            // Compare case-insensitively so a path that differs only in case (a
            // case-insensitive filesystem) is not reported as a stale entry.
            String candidate = actual.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            String expected = file.replace('\\', '/').toLowerCase(Locale.ROOT);
            return candidate.equals(expected) || candidate.endsWith("/" + expected);
        }

        /**
         * Formats the entry for a report.
         *
         * @return a human-readable description
         */
        public String describe() {
            return "rule=" + orAny(rule) + " file=" + orAny(file) + " changeset=" + orAny(changeset)
                    + " statement=" + orAny(statement) + ": " + reason;
        }
    }

    /**
     * The result of {@link #apply}: findings no entry accepted and entries that
     * matched nothing.
     *
     * @param unmatched findings no entry accepted
     * @param stale     entries that matched no finding
     */
    public record Report(List<Finding> unmatched, List<AllowedFinding> stale) {

        /**
         * Creates an immutable report.
         */
        public Report {
            unmatched = List.copyOf(unmatched);
            stale = List.copyOf(stale);
        }

        /**
         * Returns whether the run passed.
         *
         * @return {@code true} when no finding is unmatched and no entry is stale
         */
        public boolean isEmpty() {
            return unmatched.isEmpty() && stale.isEmpty();
        }
    }
}
