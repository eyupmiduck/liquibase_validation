package io.github.eyupmiduck.changelogvalidator.linter.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(0, run("--changelog-root", root.toString(), "--config", invalid.toString()).code());
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
        assertEquals("", result.err());
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
