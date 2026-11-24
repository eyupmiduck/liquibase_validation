package io.github.eyupmiduck.changelogvalidator.linter.sql;

import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
 * appears in a comment.
 *
 * <p>Delimiter handling:
 * <ul>
 *   <li>{@code ;} (and other punctuation) splits anywhere at the top level;</li>
 *   <li>a word delimiter such as {@code GO} splits only when it is the last
 *       non-trivia token on its line;</li>
 *   <li>an operator delimiter such as {@code /} splits only when it is the only
 *       non-trivia token on its line, so a division expression is not split;</li>
 *   <li>a dollar-quote delimiter such as {@code $$} or {@code $body$} splits on
 *       a line that contains only that delimiter. The lexer still sees a
 *       dollar-quoted body as one opaque token, so the line scan is used
 *       instead. A line inside a multi-line string, quoted identifier or
 *       comment is never a delimiter.</li>
 * </ul>
 *
 * <p>A statement begins at its first non-trivia token and ends at its last
 * non-trivia token (before the delimiter), so leading and trailing trivia is
 * never part of the offsets. The text is the span between those first and last
 * non-trivia tokens. {@code splitStatements=false} returns the whole input as
 * one statement.
 *
 * <p>Limitation: with a dollar-quote delimiter, a routine body whose closing
 * delimiter is alone on a line is indistinguishable from a statement delimiter.
 * Put the trailing {@code LANGUAGE ...} clause on the same line as the body's
 * closing delimiter, or use a different {@code endDelimiter}. A delimiter the
 * splitter cannot represent (for example a quoted string) throws
 * {@link IllegalArgumentException} rather than being ignored.
 */
public final class SqlStatementSplitter {

    /**
     * The default statement delimiter, matching Liquibase.
     */
    public static final String DEFAULT_END_DELIMITER = ";";

    /**
     * The characters PostgreSQL allows in a dollar-quote tag.
     */
    private static final String DOLLAR_QUOTE_TAG = "[A-Za-z0-9_]*";

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
        return split(sql, SqlLexer.tokenize(sql), splitStatements, endDelimiter, stripComments);
    }

    /**
     * Splits {@code sql} into statements using an already-lexed token list, so a
     * caller that also needs the tokens does not lex the text twice.
     *
     * @param sql             the SQL the tokens were lexed from
     * @param tokens          the tokens of {@code sql}, in order
     * @param splitStatements whether to split on the delimiter; when false the
     *                        whole input is one statement
     * @param endDelimiter    the delimiter, or {@code null} for {@code ;}; an
     *                        empty string disables splitting
     * @param stripComments   whether comments and directives inside the statement
     *                        (between its first and last non-trivia tokens) are
     *                        removed from the statement text
     * @return the statements, in input order, without empty fragments
     */
    public static List<SqlStatement> split(String sql, List<Token> tokens, boolean splitStatements,
                                           String endDelimiter, boolean stripComments) {
        String delimiter = endDelimiter == null ? DEFAULT_END_DELIMITER : endDelimiter.strip();
        if (splitStatements) {
            validateDelimiter(delimiter);
        }
        if (splitStatements && isDollarQuoteDelimiter(delimiter)) {
            return splitOnDelimiterLines(sql, tokens, delimiter, stripComments);
        }
        List<SqlStatement> statements = new ArrayList<>();
        int start = -1;
        int lastEnd = -1;
        for (int index = 0; index < tokens.size(); index++) {
            Token token = tokens.get(index);
            if (splitStatements && isDelimiter(tokens, index, delimiter)) {
                addIfNotEmpty(statements, sql, tokens, start, lastEnd, stripComments);
                start = -1;
                lastEnd = -1;
                continue;
            }
            if (token.isTrivia()) {
                continue;
            }
            if (start < 0) {
                start = token.startOffset();
            }
            lastEnd = token.endOffset();
        }
        addIfNotEmpty(statements, sql, tokens, start, lastEnd, stripComments);
        return List.copyOf(statements);
    }

    private static boolean isDollarQuoteDelimiter(String delimiter) {
        return delimiter.length() >= 2
                && delimiter.startsWith("$")
                && delimiter.endsWith("$")
                && delimiter.substring(1, delimiter.length() - 1).matches(DOLLAR_QUOTE_TAG);
    }

    /**
     * Rejects a delimiter the splitter cannot represent, so an unusual
     * {@code endDelimiter} cannot be ignored silently. A supported delimiter is a
     * single word, punctuation or operator token, a dollar-quote delimiter, or
     * the empty string (no splitting).
     */
    private static void validateDelimiter(String delimiter) {
        if (delimiter.isEmpty() || isDollarQuoteDelimiter(delimiter)) {
            return;
        }
        List<Token> tokens = SqlLexer.tokenize(delimiter).stream()
                .filter(token -> !token.isTrivia())
                .toList();
        if (tokens.size() != 1) {
            throw new IllegalArgumentException("unsupported endDelimiter: " + delimiter);
        }
        TokenType type = tokens.getFirst().type();
        if (type != TokenType.WORD && type != TokenType.PUNCTUATION && type != TokenType.OPERATOR) {
            throw new IllegalArgumentException("unsupported endDelimiter: " + delimiter);
        }
    }

    private static List<SqlStatement> splitOnDelimiterLines(String sql, List<Token> tokens, String delimiter,
                                                            boolean stripComments) {
        Set<Integer> opaqueLines = opaqueInteriorLines(tokens);
        List<SqlStatement> statements = new ArrayList<>();
        int statementStart = -1;
        int statementEnd = -1;
        int statementLine = -1;
        int statementColumn = -1;
        int lineStart = 0;
        int lineNumber = 1;
        while (lineStart <= sql.length()) {
            int lineEnd = endOfLine(sql, lineStart);
            int first = firstNonWhitespace(sql, lineStart, lineEnd);
            int last = lastNonWhitespace(sql, lineStart, lineEnd);
            boolean delimiterLine = first >= 0
                    && !opaqueLines.contains(lineNumber)
                    && sql.substring(first, last + 1).equalsIgnoreCase(delimiter);
            if (delimiterLine) {
                addLineStatement(statements, sql, statementStart, statementEnd,
                        statementLine, statementColumn, stripComments);
                statementStart = -1;
                statementEnd = -1;
                statementLine = -1;
                statementColumn = -1;
            } else if (first >= 0) {
                if (statementStart < 0) {
                    statementStart = first;
                    statementLine = lineNumber;
                    statementColumn = first - lineStart + 1;
                }
                statementEnd = last + 1;
            }
            if (lineEnd >= sql.length()) {
                break;
            }
            lineStart = nextLineStart(sql, lineEnd);
            lineNumber++;
        }
        addLineStatement(statements, sql, statementStart, statementEnd,
                statementLine, statementColumn, stripComments);
        return List.copyOf(statements);
    }

    /**
     * Adds a line-scanned segment unless it is empty or contains only comments.
     * The segment text is re-lexed with absolute offsets because the full token
     * stream can classify a delimiter-bounded comment region as a dollar-quoted
     * string; the fragment's own tokens then pair its dollar quotes correctly and
     * its real comment tokens are stripped. The span is trimmed to the fragment's
     * first and last non-trivia token, matching the token-scan path.
     */
    private static void addLineStatement(List<SqlStatement> statements, String sql,
                                         int start, int end, int line, int column,
                                         boolean stripComments) {
        if (start < 0) {
            return;
        }
        List<Token> fragmentTokens = SqlLexer.tokenize(sql.substring(start, end), start, line, column);
        int first = -1;
        int last = -1;
        for (Token token : fragmentTokens) {
            if (token.isTrivia()) {
                continue;
            }
            if (first < 0) {
                first = token.startOffset();
            }
            last = token.endOffset();
        }
        if (first < 0) {
            return;
        }
        addIfNotEmpty(statements, sql, fragmentTokens, first, last, stripComments);
    }

    private static boolean isDelimiter(List<Token> tokens, int index, String delimiter) {
        Token token = tokens.get(index);
        if (delimiter.isEmpty()
                || token.isTrivia()
                || token.type() == TokenType.ERROR
                || !token.text().equalsIgnoreCase(delimiter)) {
            return false;
        }
        if (token.type() == TokenType.PUNCTUATION) {
            return true;
        }
        if (token.type() == TokenType.STRING
                || token.type() == TokenType.E_STRING
                || token.type() == TokenType.DOLLAR_STRING
                || token.type() == TokenType.QUOTED_IDENTIFIER) {
            // An opaque token is never a delimiter; a dollar-quote delimiter is
            // handled by the line scan instead.
            return false;
        }
        if (token.type() == TokenType.OPERATOR) {
            return isAloneOnLine(tokens, index);
        }
        return endsLine(tokens, index);
    }

    private static boolean endsLine(List<Token> tokens, int index) {
        Token token = tokens.get(index);
        for (int next = index + 1; next < tokens.size(); next++) {
            Token candidate = tokens.get(next);
            if (!candidate.isTrivia()) {
                return candidate.line() > token.line();
            }
        }
        return true;
    }

    private static boolean isAloneOnLine(List<Token> tokens, int index) {
        Token token = tokens.get(index);
        for (int previous = index - 1; previous >= 0; previous--) {
            Token candidate = tokens.get(previous);
            if (!candidate.isTrivia()) {
                if (candidate.line() == token.line()) {
                    return false;
                }
                break;
            }
        }
        return endsLine(tokens, index);
    }

    private static Set<Integer> opaqueInteriorLines(List<Token> tokens) {
        Set<Integer> lines = new HashSet<>();
        for (Token token : tokens) {
            if (!isMultilineOpaque(token)) {
                continue;
            }
            int newlines = countNewlines(token.text());
            for (int line = token.line() + 1; line < token.line() + newlines; line++) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static boolean isMultilineOpaque(Token token) {
        return switch (token.type()) {
            case STRING, E_STRING, DOLLAR_STRING, QUOTED_IDENTIFIER, BLOCK_COMMENT -> true;
            default -> false;
        };
    }

    private static int countNewlines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '\n') {
                count++;
            } else if (current == '\r') {
                count++;
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
            }
        }
        return count;
    }

    private static int endOfLine(String sql, int start) {
        int end = start;
        while (end < sql.length() && sql.charAt(end) != '\n' && sql.charAt(end) != '\r') {
            end++;
        }
        return end;
    }

    private static int nextLineStart(String sql, int lineEnd) {
        int next = lineEnd;
        if (next < sql.length() && sql.charAt(next) == '\r') {
            next++;
        }
        if (next < sql.length() && sql.charAt(next) == '\n') {
            next++;
        }
        return next;
    }

    private static int firstNonWhitespace(String sql, int start, int end) {
        for (int index = start; index < end; index++) {
            if (!isWhitespace(sql.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static int lastNonWhitespace(String sql, int start, int end) {
        for (int index = end - 1; index >= start; index--) {
            if (!isWhitespace(sql.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\n'
                || character == '\r' || character == '\f' || character == '\u000B';
    }

    private static void addIfNotEmpty(List<SqlStatement> statements, String sql, List<Token> tokens,
                                      int start, int end, boolean stripComments) {
        if (start < 0 || hasOnlyTrivia(tokens, start, end)) {
            return;
        }
        String text = statementText(sql, tokens, start, end, stripComments);
        if (!text.isEmpty()) {
            statements.add(new SqlStatement(text, start, end));
        }
    }

    /**
     * Returns whether the token range contains no non-trivia token. The token
     * scan never creates such a range, but the line scan can when a
     * dollar-quote-delimited segment holds only comments.
     */
    private static boolean hasOnlyTrivia(List<Token> tokens, int start, int end) {
        for (Token token : tokens) {
            if (token.endOffset() <= start) {
                continue;
            }
            if (token.startOffset() >= end) {
                break;
            }
            if (!token.isTrivia()) {
                return false;
            }
        }
        return true;
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
