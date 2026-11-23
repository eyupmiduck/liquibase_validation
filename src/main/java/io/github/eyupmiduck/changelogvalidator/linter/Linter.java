package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlNormalizer;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Runs a set of {@link Rule}s over changesets from a changelog model.
 *
 * <p>Rules are registered explicitly and run in that order within each changeset,
 * so the configuration decides which rules run and at what severity. A
 * configuration that references a rule id the engine does not know is rejected,
 * so a typo cannot silently disable a rule, and a duplicate rule id is rejected
 * so a rule cannot report twice.
 */
public final class Linter {

    private final List<Rule> rules;
    private final List<ProjectRule> projectRules;
    private final LinterConfig config;

    /**
     * Creates a linter with no project rules.
     *
     * @param rules  the per-changeset rules to run
     * @param config the configuration
     * @throws IllegalArgumentException if two rules share an id, or the
     *                                  configuration references an unknown rule id
     */
    public Linter(List<Rule> rules, LinterConfig config) {
        this(rules, List.of(), config);
    }

    /**
     * Creates a linter.
     *
     * @param rules        the per-changeset rules to run
     * @param projectRules the whole-graph rules to run after them
     * @param config       the configuration
     * @throws IllegalArgumentException if two rules share an id, or the
     *                                  configuration references an unknown rule id
     */
    public Linter(List<Rule> rules, List<ProjectRule> projectRules, LinterConfig config) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(projectRules, "projectRules");
        Objects.requireNonNull(config, "config");
        Set<String> known = new HashSet<>();
        for (Rule rule : rules) {
            Objects.requireNonNull(rule, "rule");
            register(known, rule.id());
        }
        for (ProjectRule rule : projectRules) {
            Objects.requireNonNull(rule, "project rule");
            register(known, rule.id());
        }
        for (String id : config.referencedRuleIds()) {
            if (!known.contains(id)) {
                throw new IllegalArgumentException("unknown rule id in configuration: " + id);
            }
        }
        this.rules = List.copyOf(rules);
        this.projectRules = List.copyOf(projectRules);
        this.config = config;
    }

    private static void register(Set<String> known, String id) {
        Objects.requireNonNull(id, "rule id");
        if (!id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("rule id must be lower-case kebab-case: " + id);
        }
        if (!known.add(id)) {
            throw new IllegalArgumentException("duplicate rule id: " + id);
        }
    }

    /**
     * Creates a linter with the default configuration.
     *
     * @param rules the rules to run
     * @return the linter
     */
    public static Linter withRules(List<Rule> rules) {
        return new Linter(rules, LinterConfig.defaults());
    }

    /**
     * Creates a linter with the default configuration and project rules.
     *
     * @param rules        the per-changeset rules to run
     * @param projectRules the whole-graph rules to run after them
     * @return the linter
     */
    public static Linter withRules(List<Rule> rules, List<ProjectRule> projectRules) {
        return new Linter(rules, projectRules, LinterConfig.defaults());
    }

    /**
     * Runs every enabled rule over {@code changeSets}.
     *
     * @param changeSets the changesets
     * @return the findings, in changeset then rule order
     * @throws IOException           if a SQL file cannot be read
     * @throws IllegalStateException if property substitution in a changeset's SQL
     *                               cycles, does not converge, or exceeds the
     *                               safety bound
     */
    public List<Finding> lint(List<ChangeSet> changeSets) throws IOException {
        boolean anyRule = rules.stream().anyMatch(config::isEnabled)
                || projectRules.stream().anyMatch(rule -> config.isEnabled(rule.id(), rule.enabledByDefault()));
        if (!anyRule) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (ChangeSet changeSet : changeSets) {
            RuleContext context = context(changeSet);
            for (Rule rule : rules) {
                if (!config.isEnabled(rule)) {
                    continue;
                }
                Severity severity = config.severityFor(rule);
                List<Rule.Violation> violations = Objects.requireNonNull(rule.check(context),
                        () -> "rule " + rule.id() + " returned null from check()");
                for (Rule.Violation violation : violations) {
                    findings.add(new Finding(rule.id(), severity, changeSet.id(), changeSet.author(),
                            violation.file(), violation.line(), violation.column(),
                            violation.statement(), violation.message(), violation.help()));
                }
            }
        }
        for (ProjectRule rule : projectRules) {
            if (!config.isEnabled(rule.id(), rule.enabledByDefault())) {
                continue;
            }
            Severity severity = config.severityFor(rule.id(), rule.defaultSeverity());
            List<ProjectRule.Violation> violations = Objects.requireNonNull(rule.check(changeSets),
                    () -> "project rule " + rule.id() + " returned null from check()");
            for (ProjectRule.Violation violation : violations) {
                ChangeSet changeSet = violation.changeSet();
                findings.add(new Finding(rule.id(), severity, changeSet.id(), changeSet.author(),
                        changeSet.changelogFile(), violation.line(), violation.column(),
                        violation.statement(), violation.message(), violation.help()));
            }
        }
        return List.copyOf(findings);
    }

    /**
     * Returns whether {@code findings} should fail the run under the configured
     * {@code failOn} threshold. A null threshold ({@code failOn: none}) never
     * fails.
     *
     * @param findings the findings
     * @return {@code true} when at least one finding is at or above the threshold
     */
    public boolean fails(List<Finding> findings) {
        return config.failOn() != null
                && findings.stream().anyMatch(finding -> finding.severity().atLeast(config.failOn()));
    }

    private RuleContext context(ChangeSet changeSet) throws IOException {
        return new RuleContext(changeSet,
                units(changeSet, changeSet.sqlSources(), false),
                units(changeSet, changeSet.rollbackSources(), true));
    }

    private List<SqlUnit> units(ChangeSet changeSet, List<SqlSource> sources, boolean rollback) throws IOException {
        List<SqlUnit> units = new ArrayList<>();
        for (SqlSource source : sources) {
            Path file = source.isInline() ? changeSet.changelogFile() : source.path();
            String raw = source.isInline() ? source.text() : Files.readString(file);
            String sql = SqlNormalizer.normalise(raw, changeSet.normalisation(), rollback);
            if (source.isInline() && source.line() > 1) {
                // Inline SQL is reported against the changelog file; shift token
                // lines to the declaring XML element instead of starting at 1.
                sql = "\n".repeat(source.line() - 1) + sql;
            }
            List<Token> tokens = SqlLexer.tokenize(sql);
            List<SqlStatement> statements = SqlStatementSplitter.split(sql, tokens, source.splitStatements(),
                    source.endDelimiter(), source.stripComments());
            units.add(new SqlUnit(source, file, sql, tokens, statements));
        }
        return units;
    }
}
