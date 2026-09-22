package io.github.eyupmiduck.changelogvalidator.linter.config;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The linter configuration, loaded from {@code .liquibase-linter.yml}.
 *
 * <p>Rules run in one of three ways: excluded always off, included always on (for
 * opt-in rules), or on by default. A per-rule severity overrides the rule's
 * default. {@code failOn} is the least severity that fails the run.
 *
 * @param pgVersion the PostgreSQL version for version-gated rules, or null
 * @param failOn    the least severity that fails the run
 * @param exclude   rule ids to disable
 * @param include   rule ids to enable even when they are opt-in
 * @param rules     per-rule settings
 */
public record LinterConfig(String pgVersion, Severity failOn, List<String> exclude, List<String> include,
                           Map<String, RuleSettings> rules) {

    /**
     * Validates the configuration and copies its collections.
     */
    public LinterConfig {
        Objects.requireNonNull(failOn, "failOn");
        exclude = List.copyOf(exclude);
        include = List.copyOf(include);
        rules = Map.copyOf(rules);
    }

    /**
     * Returns the configuration with no overrides: fail on error, every
     * default-enabled rule on.
     *
     * @return the default configuration
     */
    public static LinterConfig defaults() {
        return new LinterConfig(null, Severity.ERROR, List.of(), List.of(), Map.of());
    }

    /**
     * Returns whether {@code rule} runs under this configuration.
     *
     * @param rule the rule
     * @return {@code true} when the rule is enabled
     */
    public boolean isEnabled(Rule rule) {
        if (exclude.contains(rule.id())) {
            return false;
        }
        if (include.contains(rule.id())) {
            return true;
        }
        return rule.enabledByDefault();
    }

    /**
     * Returns the effective severity for {@code rule}.
     *
     * @param rule the rule
     * @return the configured severity, or the rule's default
     */
    public Severity severityFor(Rule rule) {
        RuleSettings settings = rules.get(rule.id());
        return settings != null && settings.severity() != null ? settings.severity() : rule.defaultSeverity();
    }

    /**
     * Returns every rule id referenced by this configuration, so the engine can
     * reject typos.
     *
     * @return the referenced rule ids, sorted
     */
    public Set<String> referencedRuleIds() {
        Set<String> ids = new TreeSet<>(exclude);
        ids.addAll(include);
        ids.addAll(rules.keySet());
        return ids;
    }

    /**
     * Per-rule settings.
     *
     * @param severity the severity override, or null to use the rule default
     * @param options  free-form rule options
     */
    public record RuleSettings(Severity severity, Map<String, Object> options) {

        /**
         * Validates the settings and copies the options.
         */
        public RuleSettings {
            Objects.requireNonNull(options, "options");
            options = Map.copyOf(options);
        }
    }
}
