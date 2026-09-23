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
    private final LinterConfig config;

    /**
     * Creates a linter.
     *
     * @param rules  the rules to run
     * @param config the configuration
     * @throws IllegalArgumentException if two rules share an id, or the
     *                                  configuration references an unknown rule id
     */
    public Linter(List<Rule> rules, LinterConfig config) {
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(config, "config");
        Set<String> known = new HashSet<>();
        for (Rule rule : rules) {
            if (!known.add(rule.id())) {
                throw new IllegalArgumentException("duplicate rule id: " + rule.id());
            }
        }
        for (String id : config.referencedRuleIds()) {
            if (!known.contains(id)) {
                throw new IllegalArgumentException("unknown rule id in configuration: " + id);
            }
        }
        this.rules = List.copyOf(rules);
        this.config = config;
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
     * Runs every enabled rule over {@code changeSets}.
     *
     * @param changeSets the changesets
     * @return the findings, in changeset then rule order
     * @throws IOException if a SQL file cannot be read
     */
    public List<Finding> lint(List<ChangeSet> changeSets) throws IOException {
        List<Finding> findings = new ArrayList<>();
        for (ChangeSet changeSet : changeSets) {
            RuleContext context = context(changeSet);
            for (Rule rule : rules) {
                if (!config.isEnabled(rule)) {
                    continue;
                }
                Severity severity = config.severityFor(rule);
                for (Rule.Violation violation : rule.check(context)) {
                    findings.add(new Finding(rule.id(), severity, changeSet.id(), changeSet.author(),
                            violation.file(), violation.line(), violation.column(),
                            violation.statement(), violation.message(), violation.help()));
                }
            }
        }
        return List.copyOf(findings);
    }

    /**
     * Returns whether {@code findings} should fail the run under the configured
     * {@code failOn} threshold.
     *
     * @param findings the findings
     * @return {@code true} when at least one finding is at or above the threshold
     */
    public boolean fails(List<Finding> findings) {
        return findings.stream().anyMatch(finding -> finding.severity().atLeast(config.failOn()));
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
            List<Token> tokens = SqlLexer.tokenize(sql);
            List<SqlStatement> statements = SqlStatementSplitter.split(sql, source.splitStatements(),
                    source.endDelimiter(), source.stripComments());
            units.add(new SqlUnit(source, file, sql, tokens, statements));
        }
        return units;
    }
}
