package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.ProjectRule;
import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The built-in rules, registered explicitly so the order findings are produced
 * in is deterministic.
 */
public final class Rules {

    private Rules() {
    }

    /**
     * Returns the built-in rules with no configuration options.
     *
     * @param pgVersion the PostgreSQL major version for version-gated statements,
     *                  or null when unknown
     * @return the rules
     */
    public static List<Rule> all(Integer pgVersion) {
        return build(pgVersion, LinterConfig.defaults());
    }

    /**
     * Returns the built-in rules configured from {@code config}. The
     * version-gated rules use {@link LinterConfig#postgresMajorVersion()}, and
     * rules with options (for example the dynamic-SQL allow-list) read them here.
     *
     * @param config the configuration
     * @return the rules
     */
    public static List<Rule> all(LinterConfig config) {
        return build(config.postgresMajorVersion(), config);
    }

    private static List<Rule> build(Integer pgVersion, LinterConfig config) {
        return List.of(
                new RunInTransactionRequiredRule(pgVersion),
                new SingleStatementRule(pgVersion),
                new PreferSingleStatementRule(pgVersion),
                new RequireConcurrentIndexCreationRule(),
                new RequireConcurrentIndexDeletionRule(),
                new RequireRollbackRule(),
                new RequireRollbackParityRule(),
                new DynamicSqlRule(allowedRoutines(config)),
                new RoutineSelectStarRule(),
                new RoutineTransactionControlRule(),
                new RoutineArgumentNamingRule(),
                new BlockRawAlterTableRule(),
                new RequireDbmsPostgresqlRule(),
                new RoutineRunOnChangeRule(),
                new TriggerCreateOrReplaceRule(pgVersion),
                new RequireNotValidConstraintRule(),
                new RequireIfExistsOnDropRule(),
                new ChangesetAuthorRequiredRule(),
                new ChangesetFileNameMatchesIdRule());
    }

    /**
     * Returns the built-in rules that need the whole changelog graph, run after
     * the per-changeset rules.
     *
     * @return the project rules
     */
    public static List<ProjectRule> projectRules() {
        return List.of(new ChangesetIdUniqueRule());
    }

    /**
     * Returns the built-in project-analysis rules, run after the changeset-only
     * project rules. They are opt-in and read their options from {@code config}.
     *
     * @param config the configuration
     * @return the project-analysis rules
     */
    public static List<ProjectAnalysisRule> projectAnalysisRules(LinterConfig config) {
        return List.of(
                new DuplicateObjectRule(),
                new ForeignKeyTargetRule(knownTables(config, ForeignKeyTargetRule.ID)),
                new UnvalidatedConstraintRule(),
                new OrderingDependencyRule(knownTables(config, OrderingDependencyRule.ID)),
                new ConflictingCommentRule(),
                new UnregisteredRoutineFileRule());
    }

    /**
     * Returns the built-in project-analysis rules with no options.
     *
     * @return the project-analysis rules
     */
    public static List<ProjectAnalysisRule> projectAnalysisRules() {
        return projectAnalysisRules(LinterConfig.defaults());
    }

    /**
     * Reads a rule's {@code knownTables} allow-list: tables installed outside
     * the changelog that the rule should treat as existing.
     *
     * @param config the configuration
     * @param ruleId the rule id
     * @return the known tables, or an empty set
     * @throws IllegalArgumentException when the option is not a list of strings
     */
    private static Set<String> knownTables(LinterConfig config, String ruleId) {
        LinterConfig.RuleSettings settings = config.rules().get(ruleId);
        if (settings == null) {
            return Set.of();
        }
        Object value = settings.options().get("knownTables");
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("knownTables for " + ruleId + " must be a list");
        }
        List<String> tables = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String table) || table.isBlank()) {
                throw new IllegalArgumentException(
                        "knownTables for " + ruleId + " must contain non-blank strings");
            }
            tables.add(table.strip());
        }
        return Set.copyOf(tables);
    }

    /**
     * Reads the {@code routine-dynamic-sql} allow-list: a list of
     * {@code schema.name} routines whose dynamic SQL is a known boundary and is
     * not reported. Entries are stripped; an unquoted routine name is folded to
     * lower case by the lexer, so use the lower-case spelling for it.
     *
     * @param config the configuration
     * @return the allowed routine names, or an empty set
     * @throws IllegalArgumentException when the option is not a list of strings
     */
    private static Set<String> allowedRoutines(LinterConfig config) {
        LinterConfig.RuleSettings settings = config.rules().get(DynamicSqlRule.ID);
        if (settings == null) {
            return Set.of();
        }
        Object value = settings.options().get("allowedRoutines");
        if (value == null) {
            return Set.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("allowedRoutines for " + DynamicSqlRule.ID + " must be a list");
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException(
                        "allowedRoutines for " + DynamicSqlRule.ID + " must contain non-blank strings");
            }
            names.add(name.strip());
        }
        return Set.copyOf(names);
    }
}
