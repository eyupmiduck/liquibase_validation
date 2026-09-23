package io.github.eyupmiduck.changelogvalidator.linter.sql;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits changeset SQL into the statements Liquibase would execute, using the
 * {@link SqlLexer} to respect SQL structure.
 *
 * <p>Delimiters are only recognised at the top level: a {@code ;} inside a
 * string, a dollar-quoted routine body, a quoted identifier or a comment does
 * not end a statement. This is stricter than Liquibase's textual splitter and
 * matches the practical case of a single statement per changeset.
 *
 * <p>Defaults follow Liquibase: {@code splitStatements} is on and the
 * {@code endDelimiter} is {@code ;}. Comments and directives are removed from
 * the returned text by default so a rule cannot match a keyword that only
 * appears in a comment. Liquibase also splits on a {@code GO} at the end of a
 * line; that is a SQL Server idiom and is not implemented here.
 *
 * <p>Limitations: a custom delimiter must correspond to a single non-operator
 * token (for example {@code GO}); an operator delimiter such as {@code /} is not
 * supported because it would also split a division expression. A statement begins
 * at its first non-trivia token; a delimited statement ends at the delimiter and
 * a final statement at its last non-trivia token, so leading trivia is never part
 * of the text and the statement offsets are those of the first and last
 * non-trivia tokens, not of the returned text. {@code splitStatements=false}
 * returns the whole input as one statement.
 */
public final class SqlStatementSplitter {

    /**
     * The default statement delimiter, matching Liquibase.
     */
    public static final String DEFAULT_END_DELIMITER = ";";

    private SqlStatementSplitter() {
    }

    /**
     * Splits {@code sql} using Liquibase's defaults: split statements on
     * {@code ;} and strip comments.
     *
     * @param sql the SQL to split
     * @return the statements, in input order, without empty fragments
     */
    public static List<SqlStatement> split(String sql) {
        return split(sql, true, DEFAULT_END_DELIMITER, true);
    }

    /**
     * Splits {@code sql} into statements.
     *
     * @param sql             the SQL to split
     * @param splitStatements whether to split on the delimiter; when false the
     *                        whole input is one statement
     * @param endDelimiter    the delimiter, or {@code null} for {@code ;}; an
     *                        empty string disables splitting
     * @param stripComments   whether comments and directives inside the statement
     *                        (between its first and last non-trivia tokens) are
     *                        removed from the statement text
     * @return the statements, in input order, without empty fragments
     */
    public static List<SqlStatement> split(String sql, boolean splitStatements, String endDelimiter,
                                           boolean stripComments) {
        String delimiter = endDelimiter == null ? DEFAULT_END_DELIMITER : endDelimiter.strip();
        List<SqlStatement> statements = new ArrayList<>();
        List<Token> current = new ArrayList<>();
        int start = -1;
        int lastEnd = -1;
        for (Token token : SqlLexer.tokenize(sql)) {
            if (splitStatements && isDelimiter(token, delimiter)) {
                addIfNotEmpty(statements, sql, current, start, token.startOffset(), stripComments);
                current = new ArrayList<>();
                start = -1;
                lastEnd = -1;
                continue;
            }
            if (token.isTrivia()) {
                if (start >= 0) {
                    current.add(token);
                }
                continue;
            }
            if (start < 0) {
                start = token.startOffset();
            }
            current.add(token);
            lastEnd = token.endOffset();
        }
        addIfNotEmpty(statements, sql, current, start, lastEnd, stripComments);
        return List.copyOf(statements);
    }

    private static boolean isDelimiter(Token token, String delimiter) {
        return !delimiter.isEmpty()
                && !token.isTrivia()
                && !token.isStringLiteral()
                && token.type() != TokenType.QUOTED_IDENTIFIER
                && token.type() != TokenType.OPERATOR
                && token.type() != TokenType.ERROR
                && token.text().equalsIgnoreCase(delimiter);
    }

    private static void addIfNotEmpty(List<SqlStatement> statements, String sql, List<Token> tokens,
                                      int start, int end, boolean stripComments) {
        if (start < 0) {
            return;
        }
        String text = statementText(sql, tokens, start, end, stripComments);
        if (!text.isEmpty()) {
            statements.add(new SqlStatement(text, start, end));
        }
    }

    private static String statementText(String sql, List<Token> tokens, int start, int end, boolean stripComments) {
        if (!stripComments) {
            return sql.substring(start, end).strip();
        }
        // Slice the source and replace each removed comment with a single space,
        // so dropping a comment cannot fuse the tokens on either side (for
        // example SELECT/*c*/1 must not become SELECT1).
        StringBuilder text = new StringBuilder();
        int cursor = start;
        for (Token token : tokens) {
            if (!isComment(token) || token.startOffset() < cursor || token.endOffset() > end) {
                continue;
            }
            text.append(sql, cursor, token.startOffset());
            text.append(' ');
            cursor = token.endOffset();
        }
        text.append(sql, cursor, end);
        return text.toString().strip();
    }

    private static boolean isComment(Token token) {
        return token.type() == TokenType.LINE_COMMENT || token.type() == TokenType.BLOCK_COMMENT
                || token.type() == TokenType.DIRECTIVE;
    }
}
