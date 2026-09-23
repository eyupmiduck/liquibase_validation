package io.github.eyupmiduck.changelogvalidator.linter.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link SqlStatementSplitter}: it splits on the delimiter only at the
 * top level, respects strings, dollar-quoted bodies, quoted identifiers and
 * comments, honours {@code splitStatements}/{@code endDelimiter}, strips
 * comments, and reports offsets.
 */
class SqlStatementSplitterTest {

    private static Stream<Arguments> opaqueDelimiterInputs() {
        return Stream.of(
                Arguments.of("SELECT 'a;b'"),
                Arguments.of("SELECT $$x;y$$"),
                Arguments.of("SELECT $tag$x;y$tag$"),
                Arguments.of("SELECT \"a;b\""),
                Arguments.of("SELECT /* a;b */ 1"),
                Arguments.of("-- a;b\nSELECT 1"));
    }

    /**
     * Statements separated by {@code ;} are returned without the delimiter.
     */
    @Test
    void splitsOnSemicolons() {
        List<SqlStatement> statements = SqlStatementSplitter.split("SELECT 1; SELECT 2;");

        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(0).text());
        assertEquals("SELECT 2", statements.get(1).text());
    }

    /**
     * A delimiter inside a string, dollar-quoted body, quoted identifier or
     * comment does not end a statement.
     *
     * @param sql a single SQL statement containing a non-top-level delimiter
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("opaqueDelimiterInputs")
    void doesNotSplitInsideOpaqueRegions(String sql) {
        assertEquals(1, SqlStatementSplitter.split(sql).size());
    }

    /**
     * A dollar-quoted routine body with semicolons is a single statement.
     */
    @Test
    void treatsRoutineBodyAsOneStatement() {
        String sql = """
                CREATE FUNCTION f() RETURNS void AS $$
                BEGIN
                    PERFORM 1; PERFORM 2;
                END;
                $$ LANGUAGE plpgsql;
                """;

        List<SqlStatement> statements = SqlStatementSplitter.split(sql);

        assertEquals(1, statements.size());
        assertTrue(statements.get(0).text().contains("PERFORM 1; PERFORM 2;"));
    }

    /**
     * A final statement without a trailing delimiter is still returned.
     */
    @Test
    void handlesTrailingStatementWithoutDelimiter() {
        List<SqlStatement> statements = SqlStatementSplitter.split("SELECT 1");

        assertEquals(1, statements.size());
        assertEquals("SELECT 1", statements.get(0).text());
    }

    /**
     * Empty fragments, whitespace and comment-only fragments are not statements.
     */
    @Test
    void skipsEmptyStatements() {
        assertEquals(0, SqlStatementSplitter.split("").size());
        assertEquals(0, SqlStatementSplitter.split("   ").size());
        assertEquals(0, SqlStatementSplitter.split("-- just a comment").size());
        assertEquals(1, SqlStatementSplitter.split(";; SELECT 1 ;;").size());
    }

    /**
     * With {@code splitStatements=false} the whole input is one statement.
     */
    @Test
    void doesNotSplitWhenSplitStatementsIsFalse() {
        List<SqlStatement> statements = SqlStatementSplitter.split("SELECT 1; SELECT 2;", false, ";", true);

        assertEquals(1, statements.size());
        assertEquals("SELECT 1; SELECT 2;", statements.get(0).text());
    }

    /**
     * An explicit delimiter replaces the default.
     */
    @Test
    void honoursCustomEndDelimiter() {
        List<SqlStatement> statements = SqlStatementSplitter.split("SELECT 1 GO SELECT 2 go", true, "GO", true);

        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(0).text());
        assertEquals("SELECT 2", statements.get(1).text());
    }

    /**
     * An empty delimiter disables splitting, like {@code splitStatements=false}.
     */
    @Test
    void disablesSplittingForEmptyDelimiter() {
        List<SqlStatement> statements = SqlStatementSplitter.split("SELECT 1; SELECT 2;", true, "", true);

        assertEquals(1, statements.size());
        assertEquals("SELECT 1; SELECT 2;", statements.get(0).text());
    }

    /**
     * Comments are stripped from the statement text by default and kept when
     * requested.
     */
    @Test
    void stripsCommentsWhenRequested() {
        String sql = "SELECT -- inner\n 1;";

        String stripped = SqlStatementSplitter.split(sql, true, ";", true).get(0).text();
        String kept = SqlStatementSplitter.split(sql, true, ";", false).get(0).text();

        assertFalse(stripped.contains("-- inner"));
        assertTrue(kept.contains("-- inner"));
    }

    /**
     * Liquibase directives are trivia and never become statements.
     */
    @Test
    void ignoresLiquibaseDirectives() {
        String sql = """
                --liquibase formatted sql
                --changeset me:1 runInTransaction:false
                --comment adding an index concurrently
                CREATE INDEX CONCURRENTLY idx ON t (c);
                --rollback DROP INDEX CONCURRENTLY idx;
                """;

        List<SqlStatement> statements = SqlStatementSplitter.split(sql);

        assertEquals(1, statements.size());
        assertEquals("CREATE INDEX CONCURRENTLY idx ON t (c)", statements.get(0).text());
    }

    /**
     * Statements carry the offsets of their first and last non-trivia tokens.
     */
    @Test
    void tracksOffsets() {
        List<SqlStatement> statements = SqlStatementSplitter.split("SELECT 1; SELECT 2");

        assertEquals(0, statements.get(0).startOffset());
        assertEquals(8, statements.get(0).endOffset());
        assertEquals(10, statements.get(1).startOffset());
        assertEquals(18, statements.get(1).endOffset());
    }

    /**
     * Stripping a comment must not fuse the tokens on either side.
     */
    @Test
    void strippingCommentsDoesNotFuseTokens() {
        assertEquals("SELECT 1", SqlStatementSplitter.split("SELECT/*c*/1").get(0).text());
        assertEquals("SELECT a b", SqlStatementSplitter.split("SELECT a/*c*/b").get(0).text());
        assertEquals("'a' 'b'", SqlStatementSplitter.split("'a'/*c*/'b'").get(0).text());
    }

    /**
     * An operator delimiter is not recognised, so {@code /} does not split a
     * division expression.
     */
    @Test
    void doesNotSplitOnOperatorDelimiter() {
        List<SqlStatement> statements = SqlStatementSplitter.split("SELECT a/b", true, "/", true);

        assertEquals(1, statements.size());
        assertEquals("SELECT a/b", statements.get(0).text());
    }

    /**
     * A null statement text is rejected.
     */
    @Test
    void rejectsNullStatementText() {
        assertThrows(NullPointerException.class, () -> new SqlStatement(null, 0, 0));
    }
}
