package io.github.eyupmiduck.changelogvalidator.linter.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link FormattedSqlChangelog}: the marker, changesets, attributes,
 * rollbacks, supported and unsupported directives, and line handling.
 */
class FormattedSqlChangelogTest {

    @TempDir
    Path directory;

    private List<ChangeSet> parse(String sql) throws IOException {
        Path file = directory.resolve("changelog.sql");
        Files.writeString(file, sql);
        return FormattedSqlChangelog.parse(file, Map.of("schema", "app"));
    }

    /**
     * Changesets, their attributes, forward SQL and rollbacks are read in file
     * order.
     */
    @Test
    void parsesChangesetsAndRollbacks() throws IOException {
        List<ChangeSet> changesets = parse("""
                --liquibase formatted sql
                --changeset alice:001-create
                CREATE TABLE t (id int);
                --rollback DROP TABLE t;
                --changeset bob:002-index runInTransaction:false runOnChange:true dbms:postgresql context:dev labels:core
                CREATE INDEX idx ON t (id);
                --rollback DROP INDEX idx;
                """);

        assertEquals(2, changesets.size());
        ChangeSet first = changesets.get(0);
        assertEquals("001-create", first.id());
        assertEquals("alice", first.author());
        assertTrue(first.runInTransaction());
        assertFalse(first.runOnChange());
        assertTrue(first.sqlSources().getFirst().text().contains("CREATE TABLE t"));
        assertTrue(first.rollbackDefined());
        assertTrue(first.rollbackSources().getFirst().text().contains("DROP TABLE t"));

        ChangeSet second = changesets.get(1);
        assertEquals("002-index", second.id());
        assertEquals("bob", second.author());
        assertFalse(second.runInTransaction());
        assertTrue(second.runOnChange());
        assertEquals("postgresql", second.dbms());
        assertEquals("dev", second.context());
        assertEquals("core", second.labels());
    }

    /**
     * Quoted attribute values may contain spaces and delimiters.
     */
    @Test
    void acceptsQuotedAttributeValues() throws IOException {
        ChangeSet changeSet = parse("""
                --liquibase formatted sql
                --changeset a:1 context:"a or b" labels:'core,dev' endDelimiter:"$$" stripComments:true
                SELECT 1
                $$
                """).getFirst();

        assertEquals("a or b", changeSet.context());
        assertEquals("core,dev", changeSet.labels());
        assertEquals("$$", changeSet.sqlSources().getFirst().endDelimiter());
        assertTrue(changeSet.sqlSources().getFirst().stripComments());
    }

    /**
     * The forward SQL's line is the first line after the changeset directive, and
     * a metadata directive becomes a blank line so later lines stay aligned.
     */
    @Test
    void keepsLineNumbersAligned() throws IOException {
        ChangeSet changeSet = parse("""
                --liquibase formatted sql
                --changeset a:1
                --comment: note
                SELECT 1;
                """).getFirst();

        SqlSource source = changeSet.sqlSources().getFirst();
        assertEquals(3, source.line());
        assertTrue(source.text().startsWith("\nSELECT 1;"), source.text());
    }

    /**
     * Metadata directives are ignored rather than becoming SQL.
     */
    @Test
    void ignoresMetadataDirectives() throws IOException {
        ChangeSet changeSet = parse("""
                --liquibase formatted sql
                --comment: a comment
                --property name:value
                --changeset a:1
                --comment: inside
                --preconditions onFail:HALT onError:HALT
                --precondition-sql-check expectedResult:0 SELECT 1
                --validCheckSum: 3:abc
                SELECT 1;
                """).getFirst();

        String sql = changeSet.sqlSources().getFirst().text();
        assertTrue(sql.contains("SELECT 1;"));
        assertFalse(sql.contains("comment"));
        assertFalse(sql.contains("preconditions"));
    }

    /**
     * A SQL file without the formatted-SQL marker is rejected.
     */
    @Test
    void rejectsMissingMarker() {
        IOException failure = assertThrows(IOException.class, () -> parse("SELECT 1;\n"));
        assertTrue(failure.getMessage().contains("not a Liquibase formatted SQL changelog"),
                failure.getMessage());
    }

    /**
     * SQL before the first changeset is rejected.
     */
    @Test
    void rejectsSqlBeforeFirstChangeset() {
        IOException failure = assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                SELECT 1;
                --changeset a:1
                SELECT 2;
                """));
        assertTrue(failure.getMessage().contains("SQL before the first --changeset"),
                failure.getMessage());
    }

    /**
     * A rollback directive before the first changeset is rejected.
     */
    @Test
    void rejectsRollbackBeforeFirstChangeset() {
        IOException failure = assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --rollback DROP TABLE t;
                --changeset a:1
                SELECT 1;
                """));
        assertTrue(failure.getMessage().contains("directive before the first --changeset"),
                failure.getMessage());
    }

    /**
     * A graph-changing directive before the first changeset is rejected.
     */
    @Test
    void rejectsIncludeAllBeforeFirstChangeset() {
        IOException failure = assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --includeAll path:included/
                --changeset a:1
                SELECT 1;
                """));
        assertTrue(failure.getMessage().contains("before the first --changeset"),
                failure.getMessage());
    }

    /**
     * A bare {@code --rollback} is an ordinary comment, matching Liquibase: plain
     * SQL after it is forward SQL and each rollback line must carry its own
     * {@code --rollback} prefix.
     */
    @Test
    void treatsPlainSqlAfterABareRollbackAsForwardSql() throws IOException {
        ChangeSet changeSet = parse("""
                --liquibase formatted sql
                --changeset a:1
                CREATE TABLE t (id int);
                --rollback
                DROP TABLE t;
                """).getFirst();

        String forward = changeSet.sqlSources().getFirst().text();
        assertTrue(forward.contains("--rollback"), forward);
        assertTrue(forward.contains("DROP TABLE t"), forward);
        assertFalse(changeSet.rollbackDefined());
        assertEquals(List.of(), changeSet.rollbackSources());
    }

    /**
     * The block-comment rollback form is rejected with a diagnostic rather than
     * being treated as forward SQL.
     */
    @Test
    void rejectsTheBlockCommentRollbackForm() {
        IOException failure = assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --changeset a:1
                CREATE TABLE t (id int);
                /* liquibase rollback
                DROP TABLE t;
                */
                """));
        assertTrue(failure.getMessage().contains("block form is not supported"),
                failure.getMessage());
    }

    /**
     * Every spelling of the block-comment rollback form is rejected, including a
     * closed comment and a closed comment followed by SQL on the same line.
     */
    @Test
    void rejectsEveryBlockCommentRollbackVariant() {
        for (String rollback : List.of(
                "/* liquibase rollback */",
                "/* liquibase rollback */ DROP TABLE t;",
                "/*liquibase  rollback*/")) {
            IOException failure = assertThrows(IOException.class, () -> parse("""
                    --liquibase formatted sql
                    --changeset a:1
                    CREATE TABLE t (id int);
                    %s
                    """.formatted(rollback)), rollback);
            assertTrue(failure.getMessage().contains("block form is not supported"),
                    rollback + ": " + failure.getMessage());
        }
    }

    /**
     * Liquibase's header check tolerates flexible whitespace between the marker
     * words, so a file it accepts is not rejected here.
     */
    @Test
    void acceptsAFlexibleMarkerLine() throws IOException {
        assertEquals(1, parse("""
                --liquibase  formatted  sql
                --changeset a:1
                SELECT 1;
                """).size());
    }

    /**
     * Liquibase-style boolean spellings are honoured for the changeset
     * attributes, rather than every non-"true" value silently meaning false.
     */
    @Test
    void parsesLiquibaseBooleanSpellings() throws IOException {
        ChangeSet changeSet = parse("""
                --liquibase formatted sql
                --changeset a:1 runInTransaction:yes splitStatements:1 stripComments:on
                SELECT 1;
                """).getFirst();

        assertTrue(changeSet.runInTransaction());
        assertTrue(changeSet.sqlSources().getFirst().splitStatements());
        assertTrue(changeSet.sqlSources().getFirst().stripComments());
    }

    /**
     * A malformed boolean attribute fails closed instead of silently becoming
     * false and changing what SQL the linter analyses.
     */
    @Test
    void rejectsAMalformedBooleanAttribute() {
        IOException failure = assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --changeset a:1 splitStatements:flase
                SELECT 1;
                """));
        assertTrue(failure.getMessage().contains("malformed boolean changeset attribute 'splitStatements:flase'"),
                failure.getMessage());
    }

    /**
     * A changeset without a usable author:id is rejected.
     */
    @Test
    void rejectsMalformedChangeset() {
        assertTrue(assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --changeset
                SELECT 1;
                """)).getMessage().contains("without author:id"));
        assertTrue(assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --changeset alice
                SELECT 1;
                """)).getMessage().contains("author:id"));
        assertTrue(assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --changeset :1
                SELECT 1;
                """)).getMessage().contains("author:id"));
    }

    /**
     * Directives that change the SQL text, the graph or the rollback file set are
     * rejected instead of being ignored.
     */
    @Test
    void rejectsUnsupportedDirectives() {
        for (String directive : List.of(
                "--ignoreLines:2",
                "--include file:other.xml",
                "--includeAll path:other/",
                "--rollbackSqlFile path:rollback.sql")) {
            IOException failure = assertThrows(IOException.class, () -> parse("""
                    --liquibase formatted sql
                    --changeset a:1
                    SELECT 1;
                    %s
                    """.formatted(directive)), directive);
            assertTrue(failure.getMessage().contains("not supported")
                            || failure.getMessage().contains("before the first --changeset"),
                    directive + ": " + failure.getMessage());
        }
    }

    /**
     * An unknown changeset attribute is rejected.
     */
    @Test
    void rejectsUnknownAttribute() {
        IOException failure = assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --changeset a:1 unknown:value
                SELECT 1;
                """));
        assertTrue(failure.getMessage().contains("unsupported changeset attribute 'unknown'"),
                failure.getMessage());
    }

    /**
     * A native executor changes splitting semantics, so it is rejected; jdbc is
     * accepted.
     */
    @Test
    void acceptsJdbcAndRejectsNativeRunWith() throws IOException {
        assertEquals(1, parse("""
                --liquibase formatted sql
                --changeset a:1 runWith:jdbc
                SELECT 1;
                """).size());

        IOException failure = assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --changeset a:1 runWith:sqlplus
                SELECT 1;
                """));
        assertTrue(failure.getMessage().contains("runWith:sqlplus"), failure.getMessage());
    }

    /**
     * An ignored changeset is omitted; a file whose changesets are all ignored is
     * empty rather than an error.
     */
    @Test
    void skipsIgnoredChangeset() throws IOException {
        List<ChangeSet> changesets = parse("""
                --liquibase formatted sql
                --changeset a:1 ignore:true
                SELECT 1;
                --changeset a:2
                SELECT 2;
                """);
        assertEquals(List.of("2"), changesets.stream().map(ChangeSet::id).toList());

        assertEquals(List.of(), parse("""
                --liquibase formatted sql
                --changeset a:1 ignore:true
                SELECT 1;
                """));
    }

    /**
     * A changeset with only a rollback has no forward source.
     */
    @Test
    void keepsEmptyForwardSourceOut() throws IOException {
        ChangeSet changeSet = parse("""
                --liquibase formatted sql
                --changeset a:1
                --rollback DROP TABLE t;
                """).getFirst();

        assertEquals(List.of(), changeSet.sqlSources());
        assertTrue(changeSet.rollbackDefined());
        assertEquals(1, changeSet.rollbackSources().size());
    }

    /**
     * Duplicate attributes are rejected.
     */
    @Test
    void rejectsDuplicateAttribute() {
        IOException failure = assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --changeset a:1 dbms:postgresql dbms:oracle
                SELECT 1;
                """));
        assertTrue(failure.getMessage().contains("duplicate changeset attribute 'dbms'"),
                failure.getMessage());
    }

    /**
     * An unterminated quoted attribute value is rejected.
     */
    @Test
    void rejectsUnterminatedQuote() {
        IOException failure = assertThrows(IOException.class, () -> parse("""
                --liquibase formatted sql
                --changeset a:1 context:"a or b
                SELECT 1;
                """));
        assertTrue(failure.getMessage().contains("unterminated quote"), failure.getMessage());
    }

    /**
     * The rollback attributes are applied to the rollback source, independently of
     * the forward source.
     */
    @Test
    void appliesRollbackAttributes() throws IOException {
        ChangeSet changeSet = parse("""
                --liquibase formatted sql
                --changeset a:1 splitStatements:false endDelimiter:$$ stripComments:true rollbackSplitStatements:false rollbackEndDelimiter:@@ rollbackStripComments:true
                SELECT 1;
                --rollback DROP TABLE t;
                """).getFirst();

        SqlSource forward = changeSet.sqlSources().getFirst();
        assertFalse(forward.splitStatements());
        assertEquals("$$", forward.endDelimiter());
        assertTrue(forward.stripComments());

        SqlSource rollback = changeSet.rollbackSources().getFirst();
        assertFalse(rollback.splitStatements());
        assertEquals("@@", rollback.endDelimiter());
        assertTrue(rollback.stripComments());
    }
}
