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
 * <p>Limitations: a custom delimiter must correspond to a single token (for
 * example {@code GO} or {@code /}), and {@code splitStatements=false} returns
 * the whole input as one statement.
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
     * @param stripComments   whether comments and directives are removed from the
     *                        statement text
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
        StringBuilder text = new StringBuilder();
        for (Token token : tokens) {
            if (token.type() == TokenType.LINE_COMMENT || token.type() == TokenType.BLOCK_COMMENT
                    || token.type() == TokenType.DIRECTIVE) {
                continue;
            }
            text.append(token.text());
        }
        return text.toString().strip();
    }
}
