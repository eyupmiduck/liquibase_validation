package io.github.eyupmiduck.changelogvalidator.linter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link LinterCli}: usage and argument errors, running a clean and a
 * failing changelog, the reporter and failOn options, configuration loading, and
 * the PostgreSQL version gate.
 */
class LinterCliTest {

    private static final String CLEAN = """
            <changeSet id="001-ok" author="a">
                <sql>SELECT 1;</sql>
            </changeSet>
            """;
    private static final String FORBIDDEN = """
            <changeSet id="001-bad" author="a" dbms="postgresql">
                <sql>CREATE INDEX CONCURRENTLY idx ON t (c);</sql>
            </changeSet>
            """;
    private static final String ALTER_TYPE = """
            <changeSet id="001-type" author="a">
                <sql>ALTER TYPE mood ADD VALUE 'happy'; SELECT 1;</sql>
            </changeSet>
            """;

    @TempDir
    Path tempDir;

    private static String xml(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog \
                https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                %s
                </databaseChangeLog>
                """.formatted(body);
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = LinterCli.run(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /**
     * {@code --help} prints the usage and succeeds.
     */
    @Test
    void printsHelp() {
        Result result = run("--help");

        assertEquals(0, result.code());
        assertTrue(result.out().contains("Usage: liquibase-linter"));
    }

    /**
     * A missing changelog root, an unknown option, a missing value and an unknown
     * reporter are usage errors.
     */
    @Test
    void rejectsInvalidArguments() {
        assertEquals(2, run().code());
        assertEquals(2, run("--nope").code());
        assertEquals(2, run("--changelog-root").code());

        Result reporter = run("--changelog-root", tempDir.toString(), "--reporter", "xml");
        assertEquals(2, reporter.code());
        assertTrue(reporter.err().contains("unknown reporter"));
    }

    /**
     * A clean changelog passes and the JSON reporter emits an empty array.
     */
    @Test
    void passesCleanChangelog() throws IOException {
        Path root = changelog("clean", CLEAN);

        Result result = run("--changelog-root", root.toString(), "--reporter", "json");

        assertEquals(0, result.code());
        assertEquals("[]", result.out());
    }

    /**
     * A forbidden statement fails the run and is rendered.
     */
    @Test
    void failsOnForbiddenStatement() throws IOException {
        Path root = changelog("bad", FORBIDDEN);

        Result tty = run("--changelog-root", root.toString());
        assertEquals(1, tty.code());
        assertTrue(tty.out().contains("changeset-run-in-transaction-required"));

        Result sarif = run("--changelog-root", root.toString(), "--reporter", "sarif");
        assertEquals(1, sarif.code());
        assertTrue(sarif.out().contains("\"version\":\"2.1.0\""));
    }

    /**
     * {@code --fail-on none} never fails, and a lower threshold fails on warnings
     * too.
     */
    @Test
    void honoursFailOn() throws IOException {
        Path root = changelog("bad", FORBIDDEN);

        assertEquals(0, run("--changelog-root", root.toString(), "--fail-on", "none").code());
        assertEquals(1, run("--changelog-root", root.toString(), "--fail-on", "info").code());
        assertEquals(1, run("--changelog-root", root.toString(), "--fail-on", "warning").code());
    }

    /**
     * A value beginning with a dash is accepted unless it is a known option;
     * the previous blanket rejection made such paths impossible.
     */
    @Test
    void acceptsAValueBeginningWithDash() throws IOException {
        Path root = changelog("clean", CLEAN);

        Result result = run("--changelog-root", root.toString(), "--config", "-config.yml");

        assertEquals(0, result.code());
    }

    /**
     * A configuration file can set {@code failOn: none}, matching the documented
     * threshold even though it is not a finding severity.
     */
    @Test
    void honoursFailOnNoneFromConfiguration() throws IOException {
        Path root = changelog("bad", FORBIDDEN);
        Path config = config("failOn: none");

        Result result = run("--changelog-root", root.toString(), "--config", config.toString());

        assertEquals(0, result.code());
    }

    /**
     * A configuration file can exclude a rule.
     */
    @Test
    void loadsConfiguration() throws IOException {
        Path root = changelog("bad", FORBIDDEN);
        Path config = config("""
                exclude:
                  - changeset-run-in-transaction-required
                """);

        Result result = run("--changelog-root", root.toString(), "--config", config.toString());

        assertEquals(0, result.code());
    }

    /**
     * The PostgreSQL version from the configuration gates {@code ALTER TYPE ...
     * ADD VALUE}.
     */
    @Test
    void derivesThePgVersionFromConfiguration() throws IOException {
        Path root = changelog("type", ALTER_TYPE);
        Path old = config("pgVersion: '11'");
        Path current = config("pgVersion: '17'");
        Path invalid = config("pgVersion: 'latest'");

        assertEquals(1, run("--changelog-root", root.toString(), "--config", old.toString()).code());
        assertEquals(0, run("--changelog-root", root.toString(), "--config", current.toString()).code());

        Result invalidResult = run("--changelog-root", root.toString(), "--config", invalid.toString());
        assertEquals(2, invalidResult.code());
        assertTrue(invalidResult.err().contains("pgVersion"));
    }

    /**
     * A repeated option and a value that looks like another option are usage
     * errors, so a forgotten value is not silently bound as a path.
     */
    @Test
    void rejectsDuplicateOptionsAndMissingValues() throws IOException {
        Path root = changelog("clean", CLEAN);

        assertEquals(2,
                run("--changelog-root", root.toString(), "--changelog-root", root.toString()).code());
        assertEquals(2, run("--changelog-root", root.toString(), "--reporter", "--config").code());
    }

    /**
     * {@code --fail-on none} also suppresses the stale-whitelist failure.
     */
    @Test
    void failOnNoneSuppressesStaleWhitelist() throws IOException {
        Path root = changelog("clean", CLEAN);
        Path whitelist = whitelist("""
                - rule: changeset-run-in-transaction-required
                  changeset: 999-gone
                  reason: the changeset was removed
                """);

        Result result = run("--changelog-root", root.toString(), "--whitelist", whitelist.toString(),
                "--fail-on", "none");

        assertEquals(0, result.code());

        Result configured = run("--changelog-root", root.toString(), "--whitelist", whitelist.toString(),
                "--config", config("failOn: none").toString());

        assertEquals(0, configured.code());
    }

    /**
     * An explicit {@code --master} is used instead of the default.
     */
    @Test
    void acceptsAnExplicitMaster() throws IOException {
        Path root = changelog("master", CLEAN);

        Result result = run("--changelog-root", root.toString(),
                "--master", root.resolve("db.changelog-master.xml").toString());

        assertEquals(0, result.code());
    }

    /**
     * {@code --version} prints the artifact version and does not need a
     * changelog root.
     */
    @Test
    void printsVersion() {
        Result result = run("--version");

        assertEquals(0, result.code());
        assertTrue(result.out().startsWith("liquibase-linter "), result.out());
        assertFalse(result.out().contains("${"), result.out());
    }

    /**
     * {@code --list-rules} prints the rule metadata as a table and does not need a
     * changelog root.
     */
    @Test
    void listsRules() {
        Result result = run("--list-rules");

        assertEquals(0, result.code());
        assertTrue(result.out().contains("ID"));
        assertTrue(result.out().contains("KIND"));
        assertTrue(result.out().contains("changeset-author-required"));
        assertTrue(result.out().contains("project-analysis"));
    }

    /**
     * {@code --list-rules --reporter json} is machine-readable.
     */
    @Test
    void listsRulesAsJson() {
        Result result = run("--list-rules", "--reporter", "json");

        assertEquals(0, result.code());
        assertTrue(result.out().startsWith("["), result.out());
        assertTrue(result.out().contains("\"id\":\"changeset-author-required\""), result.out());
        assertTrue(result.out().contains("\"severity\":\"error\""), result.out());
        assertTrue(result.out().contains("\"default\":\"on\""), result.out());
    }

    /**
     * The SARIF reporter is for findings, not the rule catalogue.
     */
    @Test
    void rejectsSarifRuleList() {
        Result result = run("--list-rules", "--reporter", "sarif");

        assertEquals(2, result.code());
        assertTrue(result.err().contains("sarif"), result.err());
    }

    /**
     * A normal run prints severity counts to stderr, keeping stdout for the
     * reporter.
     */
    @Test
    void printsSeveritySummary() throws IOException {
        Path root = changelog("bad", FORBIDDEN);

        Result result = run("--changelog-root", root.toString(), "--reporter", "json");

        assertEquals(1, result.code());
        assertTrue(result.out().startsWith("["), result.out());
        assertTrue(result.err().contains("1 finding: 1 error, 0 warnings, 0 info"), result.err());
    }

    /**
     * A warning is counted separately from an error.
     */
    @Test
    void printsWarningSummary() throws IOException {
        Path root = changelog("clean", CLEAN);
        Path config = config("""
                include:
                  - changeset-rollback-required
                """);

        Result result = run("--changelog-root", root.toString(), "--config", config.toString());

        assertEquals(0, result.code());
        assertTrue(result.err().contains("1 finding: 0 errors, 1 warning, 0 info"), result.err());
    }

    /**
     * {@code --update-whitelist} writes a reviewed baseline and prints the added
     * entries and the findings it now accepts.
     */
    @Test
    void updatesWhitelist() throws IOException {
        Path root = changelog("bad", FORBIDDEN);
        Path whitelist = tempDir.resolve("baseline.yml");

        Result result = run("--changelog-root", root.toString(), "--whitelist", whitelist.toString(),
                "--update-whitelist");

        assertEquals(0, result.code());
        assertTrue(result.err().contains("whitelist updated"), result.err());
        assertTrue(result.err().contains("+ rule=changeset-run-in-transaction-required"), result.err());
        assertTrue(result.out().contains("changeset-run-in-transaction-required"), result.out());
        String yaml = Files.readString(whitelist);
        assertTrue(yaml.contains("rule: changeset-run-in-transaction-required"), yaml);
        assertTrue(yaml.contains("reason:"), yaml);

        Result second = run("--changelog-root", root.toString(), "--whitelist", whitelist.toString());
        assertEquals(0, second.code());
        assertTrue(second.err().contains("no findings"), second.err());
    }

    /**
     * A stale entry is removed by an update and reported.
     */
    @Test
    void removesStaleEntriesOnUpdate() throws IOException {
        Path root = changelog("clean", CLEAN);
        Path whitelist = whitelist("""
                - rule: changeset-run-in-transaction-required
                  changeset: 999-gone
                  reason: the changeset was removed
                """);

        Result result = run("--changelog-root", root.toString(), "--whitelist", whitelist.toString(),
                "--update-whitelist");

        assertEquals(0, result.code());
        assertTrue(result.err().contains("1 entry removed"), result.err());
        assertFalse(Files.readString(whitelist).contains("999-gone"));
    }

    /**
     * An inline suppression with a reason hides the finding for that changeset.
     */
    @Test
    void suppressesFindingsWithInlineComment() throws IOException {
        Path root = changelog("suppressed", """
                <changeSet id="001-bad" author="a" dbms="postgresql">
                    <sql>
                        -- liquibase-linter:ignore reason=accepted for this test
                        CREATE INDEX CONCURRENTLY idx ON t (c);
                    </sql>
                </changeSet>
                """);

        Result result = run("--changelog-root", root.toString());

        assertEquals(0, result.code());
        assertTrue(result.err().contains("no findings"), result.err());
    }

    /**
     * A suppression without a reason fails closed instead of hiding findings.
     */
    @Test
    void rejectsInlineSuppressionWithoutReason() throws IOException {
        Path root = changelog("bad-suppression", """
                <changeSet id="001-bad" author="a" dbms="postgresql">
                    <sql>
                        -- liquibase-linter:ignore
                        CREATE INDEX CONCURRENTLY idx ON t (c);
                    </sql>
                </changeSet>
                """);

        Result result = run("--changelog-root", root.toString());

        assertEquals(2, result.code());
        assertTrue(result.err().contains("reason"), result.err());
    }

    /**
     * A suppression naming an unknown rule fails closed.
     */
    @Test
    void rejectsUnknownRuleInInlineSuppression() throws IOException {
        Path root = changelog("bad-rule", """
                <changeSet id="001-bad" author="a" dbms="postgresql">
                    <sql>
                        -- liquibase-linter:ignore no-such-rule reason=typo
                        CREATE INDEX CONCURRENTLY idx ON t (c);
                    </sql>
                </changeSet>
                """);

        Result result = run("--changelog-root", root.toString());

        assertEquals(2, result.code());
        assertTrue(result.err().contains("unknown rule id"), result.err());
    }

    /**
     * A graph with no changesets is an error, not a vacuous pass.
     */
    @Test
    void rejectsAnEmptyChangelog() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("empty"));
        Files.writeString(root.resolve("db.changelog-master.xml"), xml(""));

        Result result = run("--changelog-root", root.toString());

        assertEquals(2, result.code());
        assertTrue(result.err().contains("no changesets found"));
    }

    /**
     * An unreadable changelog is a runtime error.
     */
    @Test
    void reportsRuntimeErrors() {
        Result result = run("--changelog-root", tempDir.resolve("missing").toString());

        assertEquals(2, result.code());
        assertTrue(result.err().startsWith("error:"));
    }

    /**
     * A whitelisted finding is suppressed and the run passes.
     */
    @Test
    void suppressesWhitelistedFindings() throws IOException {
        Path root = changelog("bad", FORBIDDEN);
        Path whitelist = whitelist("""
                - rule: changeset-run-in-transaction-required
                  changeset: 001-bad
                  statement: CREATE INDEX CONCURRENTLY
                  reason: accepted in this test
                """);

        Result result = run("--changelog-root", root.toString(), "--whitelist", whitelist.toString());

        assertEquals(0, result.code());
        assertTrue(result.err().contains("no findings"), result.err());
    }

    /**
     * A whitelist entry that matches no finding fails the run (stale entry).
     */
    @Test
    void failsOnStaleWhitelistEntry() throws IOException {
        Path root = changelog("clean", CLEAN);
        Path whitelist = whitelist("""
                - rule: changeset-run-in-transaction-required
                  changeset: 999-gone
                  reason: the changeset was removed
                """);

        Result result = run("--changelog-root", root.toString(), "--whitelist", whitelist.toString());

        assertEquals(1, result.code());
        assertTrue(result.err().contains("stale whitelist entry"));
    }

    /**
     * A finding that matches two entries is a runtime error.
     */
    @Test
    void rejectsAmbiguousWhitelistEntries() throws IOException {
        Path root = changelog("bad", FORBIDDEN);
        Path whitelist = whitelist("""
                - rule: changeset-run-in-transaction-required
                  reason: first
                - changeset: 001-bad
                  reason: second
                """);

        Result result = run("--changelog-root", root.toString(), "--whitelist", whitelist.toString());

        assertEquals(2, result.code());
        assertTrue(result.err().contains("unambiguous"));
    }

    private Path changelog(String name, String changesBody) throws IOException {
        Path root = Files.createDirectories(tempDir.resolve(name));
        Files.writeString(root.resolve("db.changelog-master.xml"), xml(
                "<include file=\"changes.xml\" relativeToChangelogFile=\"true\"/>"));
        Files.writeString(root.resolve("changes.xml"), xml(changesBody));
        return root;
    }

    private Path config(String yaml) throws IOException {
        Path file = tempDir.resolve("config-" + Math.abs(yaml.hashCode()) + ".yml");
        Files.writeString(file, yaml);
        return file;
    }

    private Path whitelist(String yaml) throws IOException {
        Path file = tempDir.resolve("whitelist-" + Math.abs(yaml.hashCode()) + ".yml");
        Files.writeString(file, yaml);
        return file;
    }

    private record Result(int code, String out, String err) {
    }
}
