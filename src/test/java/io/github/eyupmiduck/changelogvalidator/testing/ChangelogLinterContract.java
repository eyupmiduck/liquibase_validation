package io.github.eyupmiduck.changelogvalidator.testing;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Linter;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfigLoader;
import io.github.eyupmiduck.changelogvalidator.linter.config.Whitelist;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangelogModel;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.rules.Rules;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract for a consuming project's changelog-linter test.
 *
 * <p>It mirrors the {@code liquibase-linter} verify gate: it lints the module's
 * changelog with the rules and severities from the module's
 * {@code .liquibase-linter.yml}, applies the accepted findings from
 * {@code .liquibase-linter-whitelist.yml}, and fails on a finding at or above
 * the configured {@code failOn} severity or on a stale whitelist entry. A
 * missing config or whitelist file falls back to the library defaults, so a
 * project without one still lints.
 *
 * <p>It also proves the gate can fail: a transaction-forbidden statement in a
 * transactional changeset is reported and fails, a whitelist entry suppresses
 * that finding, and an entry that matches nothing is reported as stale.
 *
 * <p>A consumer extends this class, normally without overriding anything, and
 * keeps the Javadoc describing the module's changelog. Override
 * {@link #changelogRoot()}, {@link #master()}, {@link #configFile()} or
 * {@link #whitelistFile()} only for a non-default layout. The config and
 * whitelist paths are relative to the module directory, which is the surefire
 * working directory.
 *
 * <p>The linter rules combine the SQL tokens with Liquibase changeset semantics
 * (for example {@code runInTransaction}), which SQLFluff and plpgsql_check
 * cannot see.
 */
public abstract class ChangelogLinterContract {

    /**
     * The linter config file the verify gate passes with {@code --config},
     * relative to the module directory.
     */
    public static final String CONFIG_FILE = ".liquibase-linter.yml";

    /**
     * The accepted findings the verify gate passes with {@code --whitelist},
     * relative to the module directory.
     */
    public static final String WHITELIST_FILE = ".liquibase-linter-whitelist.yml";

    private static Whitelist whitelist(String yaml) throws IOException {
        return Whitelist.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static String describe(List<String> lines) {
        return String.join("\n", lines);
    }

    /**
     * The changelog root directory.
     *
     * @return the changelog root
     */
    protected Path changelogRoot() {
        return ChangelogTestSupport.changelogRoot();
    }

    /**
     * The master changelog file.
     *
     * @return the master changelog
     */
    protected Path master() {
        return ChangelogTestSupport.master();
    }

    /**
     * The linter config file, relative to the module directory.
     *
     * @return the config file
     */
    protected Path configFile() {
        return Path.of(CONFIG_FILE);
    }

    /**
     * The accepted-findings whitelist, relative to the module directory.
     *
     * @return the whitelist file
     */
    protected Path whitelistFile() {
        return Path.of(WHITELIST_FILE);
    }

    /**
     * The SQL of the deliberately bad changeset: a statement PostgreSQL forbids
     * in a transaction, so a transactional changeset must be reported.
     *
     * @return the deliberately transaction-forbidden SQL
     */
    protected String transactionForbiddenSql() {
        return "CREATE INDEX CONCURRENTLY example_probe_idx ON public.example_probe (example_id);";
    }

    /**
     * The changelog lints clean under the verify gate's configuration, with no
     * stale whitelist entry and no finding at or above {@code failOn}.
     *
     * @throws IOException if the changelog, config or whitelist cannot be read
     */
    @Test
    void changelogPassesTheVerifyGate() throws IOException {
        LinterConfig config = LinterConfigLoader.load(configFile());
        Linter linter = new Linter(Rules.all(config.postgresMajorVersion()), config);

        List<ChangeSet> changeSets = ChangelogModel.changesets(changelogRoot(), master());
        List<Finding> findings = linter.lint(changeSets);
        Whitelist.Report report = Whitelist.load(whitelistFile()).apply(findings);

        assertEquals(List.of(), report.stale(),
                () -> "stale whitelist entries:\n" + describe(report.stale().stream()
                        .map(Whitelist.AllowedFinding::describe)
                        .toList()));
        assertFalse(linter.fails(report.unmatched()),
                () -> "linter findings fail the verify gate:\n" + describe(report.unmatched().stream()
                        .map(finding -> finding.ruleId() + ": " + finding.message())
                        .toList()));
    }

    /**
     * A transaction-forbidden statement in a transactional changeset is reported
     * and fails the run, so the verify gate can fail a deliberately bad
     * changeset.
     */
    @Test
    void transactionForbiddenStatementInATransactionalChangesetFails() throws IOException {
        Linter linter = new Linter(Rules.all(null), LinterConfig.defaults());
        List<Finding> findings = linter.lint(List.of(deliberatelyBadChangeSet()));

        assertEquals(List.of("changeset-run-in-transaction-required"),
                findings.stream().map(Finding::ruleId).toList());
        assertTrue(linter.fails(findings), "an error finding must fail the run");
    }

    /**
     * A finding accepted by a whitelist entry is suppressed, so an intentional
     * violation can be carried without failing the gate.
     */
    @Test
    void whitelistedFindingPasses() throws IOException {
        List<Finding> findings = deliberatelyBadFindings();

        Whitelist.Report report = whitelist("""
                - rule: changeset-run-in-transaction-required
                  changeset: 999-deliberate
                  statement: CREATE INDEX CONCURRENTLY
                  reason: deliberate change in a test
                """).apply(findings);

        assertTrue(report.isEmpty(), () -> "unexpected report: " + report);
    }

    /**
     * A whitelist entry that matches no finding is stale and fails the run, so
     * the whitelist cannot rot.
     */
    @Test
    void staleWhitelistEntryIsReported() throws IOException {
        List<Finding> findings = deliberatelyBadFindings();

        Whitelist.Report report = whitelist("""
                - rule: changeset-run-in-transaction-required
                  changeset: 999-gone
                  reason: the changeset was removed
                """).apply(findings);

        assertEquals(1, report.unmatched().size(), "the real finding is not accepted");
        Finding unmatched = report.unmatched().getFirst();
        assertEquals("changeset-run-in-transaction-required", unmatched.ruleId());
        assertEquals("999-deliberate", unmatched.changeSetId());

        assertEquals(1, report.stale().size(), "the entry matches nothing and is stale");
        Whitelist.AllowedFinding stale = report.stale().getFirst();
        assertEquals("changeset-run-in-transaction-required", stale.rule());
        assertEquals("999-gone", stale.changeset());
    }

    private List<Finding> deliberatelyBadFindings() throws IOException {
        Linter linter = new Linter(Rules.all(null), LinterConfig.defaults());
        return linter.lint(List.of(deliberatelyBadChangeSet()));
    }

    private ChangeSet deliberatelyBadChangeSet() {
        SqlSource forward = new SqlSource(
                SqlSource.Kind.INLINE_SQL,
                null,
                transactionForbiddenSql(),
                true,
                ";",
                false,
                null);
        return new ChangeSet(
                "999-deliberate",
                "test",
                Path.of("deliberate-changelog.xml"),
                true,
                false,
                "postgresql",
                null,
                null,
                List.of(forward),
                false,
                List.of());
    }
}
