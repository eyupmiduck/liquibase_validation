package io.github.eyupmiduck.changelogvalidator.linter;

import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangelogProject;
import io.github.eyupmiduck.changelogvalidator.linter.model.RoutineBodies;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlNormalizer;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * The suppression marker: {@code -- liquibase-linter:ignore [rule[,rule]]
     * reason=...}. A line comment only; a block comment is not a suppression.
     */
    private static final Pattern SUPPRESSION =
            Pattern.compile("(?i)^--\\s*liquibase-linter:ignore\\b(.*)$");

    /**
     * The rule set that means "suppress every rule for this changeset".
     */
    private static final String SUPPRESS_ALL = "*";

    private final List<Rule> rules;
    private final List<ProjectRule> projectRules;
    private final List<ProjectAnalysisRule> projectAnalysisRules;
    private final LinterConfig config;
    private final Set<String> knownRuleIds;

    /**
     * Creates a linter with no project rules.
     *
     * @param rules  the per-changeset rules to run
     * @param config the configuration
     * @throws IllegalArgumentException if two rules share an id, or the
     *                                  configuration references an unknown rule id
     */
    public Linter(List<Rule> rules, LinterConfig config) {
        this(rules, List.of(), List.of(), config);
    }

    /**
     * Creates a linter with changeset-only project rules.
     *
     * @param rules        the per-changeset rules to run
     * @param projectRules the whole-graph rules to run after them
     * @param config       the configuration
     * @throws IllegalArgumentException if two rules share an id, or the
     *                                  configuration references an unknown rule id
     */
    public Linter(List<Rule> rules, List<ProjectRule> projectRules, LinterConfig config) {
        this(rules, projectRules, List.of(), config);
    }

    /**
     * Creates a linter.
     *
     * @param rules                the per-changeset rules to run
     * @param projectRules         the changeset-only whole-graph rules
     * @param projectAnalysisRules the project-model rules, run after the others
     * @param config               the configuration
     * @throws IllegalArgumentException if two rules share an id, or the
     *                                  configuration references an unknown rule id
     */
    public Linter(List<Rule> rules, List<ProjectRule> projectRules,
                  List<ProjectAnalysisRule> projectAnalysisRules, LinterConfig config) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(projectRules, "projectRules");
        Objects.requireNonNull(projectAnalysisRules, "projectAnalysisRules");
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
        for (ProjectAnalysisRule rule : projectAnalysisRules) {
            Objects.requireNonNull(rule, "project analysis rule");
            register(known, rule.id());
        }
        for (String id : config.referencedRuleIds()) {
            if (!known.contains(id)) {
                throw new IllegalArgumentException("unknown rule id in configuration: " + id);
            }
        }
        this.rules = List.copyOf(rules);
        this.projectRules = List.copyOf(projectRules);
        this.projectAnalysisRules = List.copyOf(projectAnalysisRules);
        this.config = config;
        this.knownRuleIds = Set.copyOf(known);
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
        return new Linter(rules, List.of(), List.of(), LinterConfig.defaults());
    }

    /**
     * Creates a linter with the default configuration and project rules.
     *
     * @param rules        the per-changeset rules to run
     * @param projectRules the whole-graph rules to run after them
     * @return the linter
     */
    public static Linter withRules(List<Rule> rules, List<ProjectRule> projectRules) {
        return new Linter(rules, projectRules, List.of(), LinterConfig.defaults());
    }

    /**
     * Creates a linter with the default configuration and project-analysis rules.
     *
     * @param rules                the per-changeset rules to run
     * @param projectRules         the changeset-only whole-graph rules
     * @param projectAnalysisRules the project-model rules
     * @return the linter
     */
    public static Linter withRules(List<Rule> rules, List<ProjectRule> projectRules,
                                   List<ProjectAnalysisRule> projectAnalysisRules) {
        return new Linter(rules, projectRules, projectAnalysisRules, LinterConfig.defaults());
    }

    private static boolean isSuppressed(Set<String> suppressed, String ruleId) {
        return suppressed.contains(SUPPRESS_ALL) || suppressed.contains(ruleId);
    }

    private static int indexOfIgnoreCase(String text, String target) {
        return text.toLowerCase(Locale.ROOT).indexOf(target.toLowerCase(Locale.ROOT));
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0)) {
            return value.substring(1, value.length() - 1).strip();
        }
        return value;
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
        return lint(ChangelogProject.of(changeSets));
    }

    /**
     * Runs every enabled rule over a whole changelog project.
     *
     * @param project the changelog graph and its changesets
     * @return the findings, in per-changeset, project, then analysis order
     * @throws IOException           if a SQL file cannot be read
     * @throws IllegalStateException if property substitution in a changeset's SQL
     *                               cycles, does not converge, or exceeds the
     *                               safety bound
     */
    public List<Finding> lint(ChangelogProject project) throws IOException {
        Objects.requireNonNull(project, "project");
        boolean anyChangesetRule = rules.stream().anyMatch(config::isEnabled);
        boolean anyProjectRule = projectRules.stream()
                .anyMatch(rule -> config.isEnabled(rule.id(), rule.enabledByDefault()));
        boolean anyAnalysisRule = projectAnalysisRules.stream()
                .anyMatch(rule -> config.isEnabled(rule.id(), rule.enabledByDefault()));
        if (!anyChangesetRule && !anyProjectRule && !anyAnalysisRule) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        List<ProjectAnalysisRule.AnalyzedChangeSet> analyzed = new ArrayList<>();
        Map<ChangeSet, Set<String>> suppressedByChangeSet = new IdentityHashMap<>();
        if (anyChangesetRule || anyAnalysisRule) {
            for (ChangeSet changeSet : project.changesets()) {
                RuleContext context = context(changeSet);
                analyzed.add(new ProjectAnalysisRule.AnalyzedChangeSet(changeSet, context));
                Set<String> suppressed = suppressions(context);
                suppressedByChangeSet.put(changeSet, suppressed);
                if (anyChangesetRule) {
                    for (Rule rule : rules) {
                        if (!config.isEnabled(rule) || isSuppressed(suppressed, rule.id())) {
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
            }
        }
        for (ProjectRule rule : projectRules) {
            if (!config.isEnabled(rule.id(), rule.enabledByDefault())) {
                continue;
            }
            Severity severity = config.severityFor(rule.id(), rule.defaultSeverity());
            List<ProjectRule.Violation> violations = Objects.requireNonNull(rule.check(project.changesets()),
                    () -> "project rule " + rule.id() + " returned null from check()");
            for (ProjectRule.Violation violation : violations) {
                ChangeSet changeSet = violation.changeSet();
                if (isSuppressed(suppressedByChangeSet.getOrDefault(changeSet, Set.of()), rule.id())) {
                    continue;
                }
                findings.add(new Finding(rule.id(), severity, changeSet.id(), changeSet.author(),
                        changeSet.changelogFile(), violation.line(), violation.column(),
                        violation.statement(), violation.message(), violation.help()));
            }
        }
        if (anyAnalysisRule) {
            ProjectAnalysisRule.ProjectContext context = new ProjectAnalysisRule.ProjectContext(project, analyzed);
            for (ProjectAnalysisRule rule : projectAnalysisRules) {
                if (!config.isEnabled(rule.id(), rule.enabledByDefault())) {
                    continue;
                }
                Severity severity = config.severityFor(rule.id(), rule.defaultSeverity());
                List<ProjectAnalysisRule.Violation> violations = Objects.requireNonNull(rule.check(context),
                        () -> "project analysis rule " + rule.id() + " returned null from check()");
                for (ProjectAnalysisRule.Violation violation : violations) {
                    ChangeSet changeSet = violation.changeSet();
                    if (changeSet != null
                            && isSuppressed(suppressedByChangeSet.getOrDefault(changeSet, Set.of()),
                            rule.id())) {
                        continue;
                    }
                    findings.add(new Finding(rule.id(), severity,
                            changeSet == null ? "" : changeSet.id(),
                            changeSet == null ? "" : changeSet.author(),
                            violation.file(), violation.line(), violation.column(),
                            violation.statement(), violation.message(), violation.help()));
                }
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

    /**
     * Returns the rule ids suppressed for a changeset by
     * {@code -- liquibase-linter:ignore} comments in its forward or rollback SQL,
     * or in a routine body. The special value {@link #SUPPRESS_ALL} suppresses
     * every rule. Suppressions are only scanned when the run loads SQL, so a
     * project-rules-only run still does not read SQL files.
     */
    private Set<String> suppressions(RuleContext context) {
        Set<String> rules = new LinkedHashSet<>();
        for (SqlUnit unit : context.forward()) {
            scanSuppressions(unit.tokens(), unit.file(), rules);
        }
        for (SqlUnit unit : context.rollback()) {
            scanSuppressions(unit.tokens(), unit.file(), rules);
        }
        for (RoutineBodies.Body body : RoutineBodies.find(context.forward())) {
            scanSuppressions(body.tokens(), body.unit().file(), rules);
        }
        for (RoutineBodies.Body body : RoutineBodies.find(context.rollback())) {
            scanSuppressions(body.tokens(), body.unit().file(), rules);
        }
        return Set.copyOf(rules);
    }

    private void scanSuppressions(List<Token> tokens, Path file, Set<String> rules) {
        for (Token token : tokens) {
            if (token.type() != TokenType.LINE_COMMENT && token.type() != TokenType.DIRECTIVE) {
                continue;
            }
            Matcher matcher = SUPPRESSION.matcher(token.text());
            if (matcher.matches()) {
                parseSuppression(matcher.group(1), file, token.line(), rules);
            }
        }
    }

    private void parseSuppression(String body, Path file, int line, Set<String> rules) {
        String rest = body.strip();
        int reasonIndex = indexOfIgnoreCase(rest, "reason=");
        if (reasonIndex < 0) {
            throw new IllegalArgumentException("suppression comment requires reason=... at "
                    + file + ":" + line);
        }
        String rulesPart = rest.substring(0, reasonIndex).strip();
        String reason = unquote(rest.substring(reasonIndex + "reason=".length()).strip());
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("suppression comment requires a non-empty reason=... at "
                    + file + ":" + line);
        }
        if (rulesPart.isEmpty()) {
            rules.add(SUPPRESS_ALL);
            return;
        }
        for (String id : rulesPart.split("[,\\s]+")) {
            if (id.isBlank()) {
                continue;
            }
            if (!knownRuleIds.contains(id)) {
                throw new IllegalArgumentException("unknown rule id in suppression: " + id
                        + " at " + file + ":" + line);
            }
            rules.add(id);
        }
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
