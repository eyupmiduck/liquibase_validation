package io.github.eyupmiduck.changelogvalidator.linter.config;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link Whitelist}: loading, fail-closed parsing, field-by-field
 * matching (with path-suffix files), suppression, unmatched findings, stale
 * entries and ambiguous matches.
 */
class WhitelistTest {

    private static final Finding FINDING = new Finding(
            "changeset-single-statement",
            Severity.ERROR,
            "007-some-index",
            "me",
            Path.of("/repo/db/changelog/changes/sql_changes/007-some-index.sql"),
            3,
            1,
            "CREATE INDEX CONCURRENTLY",
            "CREATE INDEX CONCURRENTLY must be the only statement",
            null);

    private static Whitelist load(String yaml) throws IOException {
        return Whitelist.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * An entry parses every selector, and a matching entry suppresses the
     * finding.
     */
    @Test
    void suppressesAMatchingFinding() throws IOException {
        Whitelist whitelist = load("""
                - rule: changeset-single-statement
                  file: changes/sql_changes/007-some-index.sql
                  changeset: 007-some-index
                  statement: CREATE INDEX CONCURRENTLY
                  reason: intentional, the precondition comment is stripped
                """);

        Whitelist.Report report = whitelist.apply(List.of(FINDING));

        assertTrue(report.isEmpty());
    }

    /**
     * A field an entry omits matches any value.
     */
    @Test
    void omittedFieldsMatchAnyValue() throws IOException {
        Whitelist whitelist = load("""
                - rule: changeset-single-statement
                  changeset: 007-some-index
                  reason: accepted
                """);

        assertTrue(whitelist.apply(List.of(FINDING)).isEmpty());
    }

    /**
     * The file selector matches a path suffix of the finding's file.
     */
    @Test
    void matchesFileByPathSuffix() throws IOException {
        Whitelist whitelist = load("""
                - file: 007-some-index.sql
                  reason: accepted
                """);

        assertTrue(whitelist.apply(List.of(FINDING)).isEmpty());
    }

    /**
     * A finding no entry accepts is reported as unmatched.
     */
    @Test
    void reportsUnmatchedFindings() throws IOException {
        Whitelist whitelist = load("""
                - changeset: some-other-changeset
                  reason: accepted elsewhere
                """);

        Whitelist.Report report = whitelist.apply(List.of(FINDING));

        assertEquals(List.of(FINDING), report.unmatched());
        assertEquals(1, report.stale().size());
        assertFalse(report.isEmpty());
    }

    /**
     * An entry that matches no finding is stale.
     */
    @Test
    void reportsStaleEntries() throws IOException {
        Whitelist whitelist = load("""
                - rule: changeset-single-statement
                  changeset: 007-some-index
                  reason: accepted
                - rule: changeset-run-in-transaction-required
                  changeset: 999-gone
                  reason: this changeset no longer exists
                """);

        Whitelist.Report report = whitelist.apply(List.of(FINDING));

        assertTrue(report.unmatched().isEmpty());
        assertEquals(1, report.stale().size());
        assertEquals("999-gone", report.stale().get(0).changeset());
    }

    /**
     * A finding that matches two entries is rejected as ambiguous.
     */
    @Test
    void rejectsAmbiguousMatches() throws IOException {
        Whitelist whitelist = load("""
                - rule: changeset-single-statement
                  reason: first
                - changeset: 007-some-index
                  reason: second
                """);

        assertThrows(IllegalArgumentException.class, () -> whitelist.apply(List.of(FINDING)));
    }

    /**
     * An empty document and a missing file both yield a whitelist that accepts
     * nothing and has no stale entries.
     */
    @Test
    void emptyWhitelistAcceptsNothing() throws IOException {
        assertTrue(load("").apply(List.of()).isEmpty());
        assertTrue(Whitelist.empty().apply(List.of()).isEmpty());
        assertEquals(List.of(), load("# no entries\n").entries());
    }

    /**
     * A malformed whitelist fails fast: a non-list document, a non-mapping
     * entry, an unknown key, a missing or blank reason, and an entry with no
     * selector.
     */
    @Test
    void rejectsMalformedWhitelists() {
        assertThrows(IOException.class, () -> load("not: a list\n"));
        assertThrows(IOException.class, () -> load("- just a string\n"));
        assertThrows(IOException.class, () -> load("""
                - rule: changeset-single-statement
                  nonsense: true
                  reason: accepted
                """));
        assertThrows(IOException.class, () -> load("""
                - rule: changeset-single-statement
                """));
        assertThrows(IOException.class, () -> load("""
                - rule: changeset-single-statement
                  reason: "  "
                """));
        assertThrows(IOException.class, () -> load("""
                - reason: accepted
                """));
    }
}
