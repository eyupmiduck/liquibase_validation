package io.github.eyupmiduck.changelogvalidator.linter.sql;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
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
     * Splitting with an already-lexed token list is equivalent to the
     * string overload, so a caller that also needs the tokens can pass them in.
     */
    @Test
    void reusesAnAlreadyLexedTokenList() {
        String sql = "CREATE INDEX CONCURRENTLY i ON t (c); SELECT 1;";

        List<SqlStatement> statements = SqlStatementSplitter.split(sql,
                SqlLexer.tokenize(sql), true, ";", true);

        assertEquals(SqlStatementSplitter.split(sql), statements);
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
     * An explicit delimiter replaces the default. A word delimiter is honoured
     * only at the end of its line, matching Liquibase.
     */
    @Test
    void honoursCustomEndDelimiter() {
        List<SqlStatement> statements = SqlStatementSplitter.split("SELECT 1 GO\nSELECT 2 go", true, "GO", true);

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
     * A delimited statement's end offset is its last non-trivia token, not the
     * delimiter or any trailing comment.
     */
    @Test
    void usesTheLastTokenForDelimitedOffsets() {
        SqlStatement statement = SqlStatementSplitter.split("SELECT 1 /*c*/;", true, ";", false).get(0);

        assertEquals("SELECT 1", statement.text());
        assertEquals(8, statement.endOffset());
    }

    /**
     * A word delimiter only splits when it ends its line; an identifier named GO
     * inside a statement is not a delimiter.
     */
    @Test
    void requiresWordDelimitersAtEndOfLine() {
        assertEquals(1, SqlStatementSplitter.split("SELECT go FROM t", true, "GO", true).size());
        assertEquals(2, SqlStatementSplitter.split("SELECT 1 GO\nSELECT 2", true, "GO", true).size());
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
     * An operator delimiter splits when it is the only non-trivia token on its
     * line.
     */
    @Test
    void splitsOnOperatorDelimiterAloneOnALine() {
        List<SqlStatement> statements = SqlStatementSplitter.split("SELECT 1\n/\nSELECT 2\n/", true, "/", true);

        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(0).text());
        assertEquals("SELECT 2", statements.get(1).text());
    }

    /**
     * A division operator is not a delimiter, so {@code /} does not split an
     * expression or a trailing operator.
     */
    @Test
    void doesNotSplitOnDivisionOperator() {
        assertEquals(1, SqlStatementSplitter.split("SELECT a/b", true, "/", true).size());
        assertEquals(1, SqlStatementSplitter.split("SELECT 1 /\nSELECT 2", true, "/", true).size());
    }

    /**
     * A dollar-quote delimiter splits on a line that contains only the
     * delimiter, even though the lexer sees a dollar-quoted body as one token.
     */
    @Test
    void splitsOnDollarQuoteDelimiter() {
        List<SqlStatement> statements = SqlStatementSplitter.split(
                "SELECT 1\n$$\nSELECT 2\n$$", true, "$$", true);

        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(0).text());
        assertEquals("SELECT 2", statements.get(1).text());
    }

    /**
     * A tagged dollar-quote delimiter is supported too.
     */
    @Test
    void splitsOnTaggedDollarQuoteDelimiter() {
        List<SqlStatement> statements = SqlStatementSplitter.split(
                "SELECT 1\n$body$\nSELECT 2\n$body$", true, "$body$", true);

        assertEquals(2, statements.size());
        assertEquals("SELECT 1", statements.get(0).text());
        assertEquals("SELECT 2", statements.get(1).text());
    }

    /**
     * A routine body is kept as one statement when its closing delimiter shares
     * a line with the trailing clause, and the final delimiter line splits.
     */
    @Test
    void keepsARoutineBodyWithADollarQuoteDelimiter() {
        String sql = """
                CREATE FUNCTION f() RETURNS void AS $$
                BEGIN
                    PERFORM 1;
                END;
                $$ LANGUAGE plpgsql;
                $$
                SELECT 1
                $$
                """;

        List<SqlStatement> statements = SqlStatementSplitter.split(sql, true, "$$", true);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).text().startsWith("CREATE FUNCTION f()"));
        assertTrue(statements.get(0).text().contains("PERFORM 1;"));
        assertEquals("SELECT 1", statements.get(1).text());
    }

    /**
     * A delimiter line inside a multi-line string or block comment is not a
     * statement delimiter.
     */
    @Test
    void ignoresDollarQuoteDelimiterInsideOpaqueRegions() {
        String inString = """
                SELECT 'a
                $$
                b'
                $$
                SELECT 2
                $$""";
        List<SqlStatement> stringStatements = SqlStatementSplitter.split(inString, true, "$$", true);
        assertEquals(2, stringStatements.size(), stringStatements.toString());
        assertTrue(stringStatements.get(0).text().contains("$$"));
        assertEquals("SELECT 2", stringStatements.get(1).text());

        String inComment = """
                /*
                $$
                */
                $$
                SELECT 1
                $$""";
        List<SqlStatement> commentStatements = SqlStatementSplitter.split(inComment, true, "$$", true);
        assertEquals(1, commentStatements.size(), commentStatements.toString());
        assertEquals("SELECT 1", commentStatements.get(0).text());
    }

    /**
     * A comment-only segment between dollar-quote delimiters is not a statement,
     * even when comments are kept.
     */
    @Test
    void skipsACommentOnlySegmentWithADollarQuoteDelimiter() {
        String sql = "SELECT 1\n$$\n-- done\n$$\nSELECT 2\n$$";

        List<SqlStatement> statements = SqlStatementSplitter.split(sql, true, "$$", false);

        assertEquals(2, statements.size(), statements.toString());
        assertEquals("SELECT 1", statements.get(0).text());
        assertEquals("SELECT 2", statements.get(1).text());
    }

    /**
     * A comment inside a dollar-quote-delimited segment is stripped from the
     * statement text, because the segment is re-lexed rather than read through
     * the full token stream's phantom dollar-quoted string.
     */
    @Test
    void stripsCommentsInsideADollarQuoteDelimitedSegment() {
        String sql = "SELECT 1\n$$\n-- DROP TABLE x\nSELECT 2\n$$";

        List<SqlStatement> statements = SqlStatementSplitter.split(sql, true, "$$", true);

        assertEquals(2, statements.size(), statements.toString());
        assertEquals("SELECT 2", statements.get(1).text());
        assertFalse(statements.get(1).text().contains("DROP TABLE"));
    }

    /**
     * A dollar-quote-delimited statement's offsets start at its first non-trivia
     * token and end at its last, not at a leading or trailing comment.
     */
    @Test
    void trimsDollarQuoteDelimitedOffsetsToTheTokens() {
        String sql = "$$\n-- note\nSELECT 2 -- trailing\n$$";

        SqlStatement statement = SqlStatementSplitter.split(sql, true, "$$", false).get(0);

        assertEquals(sql.indexOf("SELECT 2"), statement.startOffset());
        assertEquals(sql.indexOf("SELECT 2") + "SELECT 2".length(), statement.endOffset());
        assertFalse(statement.text().startsWith("--"));
        assertFalse(statement.text().endsWith("-- trailing"));
    }

    /**
     * A delimiter the splitter cannot represent is rejected instead of being
     * ignored silently.
     */
    @Test
    void rejectsAnUnsupportedDelimiter() {
        assertThrows(IllegalArgumentException.class,
                () -> SqlStatementSplitter.split("SELECT 1", true, "'x'", true));
        assertThrows(IllegalArgumentException.class,
                () -> SqlStatementSplitter.split("SELECT 1", true, "--", true));
    }

    /**
     * A null statement text is rejected.
     */
    @Test
    void rejectsNullStatementText() {
        assertThrows(NullPointerException.class, () -> new SqlStatement(null, 0, 0));
    }
}
