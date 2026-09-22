package io.github.eyupmiduck.changelogvalidator.linter.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A hand-written PostgreSQL tokenizer for the SQL found in Liquibase changelogs.
 *
 * <p>The token stream is lossless: concatenating {@link Token#text()} over the
 * result reproduces the input exactly. Whitespace, comments and Liquibase
 * formatted-SQL directives are emitted as trivia. Input the lexer cannot
 * classify is emitted as {@link TokenType#ERROR} rather than skipped, so rules
 * fail closed.
 *
 * <p>Supported lexical forms: whitespace; {@code --} line comments and nested
 * block comments; standard string literals with doubled quotes; {@code E'...'}
 * strings with backslash escapes; the {@code B'...'} and {@code X'...'} string
 * forms; dollar-quoted strings ({@code $$} and {@code $tag$$...$tag$}); quoted
 * identifiers with doubled double quotes; numeric literals, including the
 * {@code 0x}, {@code 0o} and {@code 0b} integer forms; positional parameters;
 * bare words; operators; and structural punctuation.
 *
 * <p>Keyword classification is deliberately left to rules: a bare word is a
 * {@link TokenType#WORD} and rules match it case-insensitively with
 * {@link Token#matchesKeyword(String)}. Unicode-escape strings ({@code U&...})
 * are not supported and are reported as {@link TokenType#ERROR}.
 */
public final class SqlLexer {

    /**
     * Liquibase formatted-SQL directives, recognised when they immediately
     * follow the {@code --} marker.
     */
    private static final Set<String> DIRECTIVES = Set.of(
            "liquibase", "changeset", "rollback", "preconditions", "property", "comment");

    /**
     * Characters PostgreSQL allows in an operator.
     */
    private static final String OPERATOR_CHARACTERS = "+-*/<>=~!@#%^&|?:";

    /**
     * Characters treated as structural punctuation rather than operators.
     */
    private static final String PUNCTUATION_CHARACTERS = "()[],;.";

    private final String sql;
    private final int length;
    private int position;
    private int line = 1;
    private int column = 1;

    private SqlLexer(String sql) {
        this.sql = sql;
        this.length = sql.length();
    }

    /**
     * Tokenizes {@code sql} into a lossless token stream.
     *
     * @param sql the SQL to tokenize
     * @return the tokens, in input order
     */
    public static List<Token> tokenize(String sql) {
        return new SqlLexer(sql).scan();
    }

    private List<Token> scan() {
        List<Token> tokens = new ArrayList<>();
        while (position < length) {
            tokens.add(nextToken());
        }
        return List.copyOf(tokens);
    }

    private Token nextToken() {
        int start = position;
        int startLine = line;
        int startColumn = column;
        char current = sql.charAt(position);
        TokenType type;
        int end;
        if (isWhitespace(current)) {
            end = endOfWhitespace(position);
            type = TokenType.WHITESPACE;
        } else if (current == '-' && peek(1) == '-') {
            end = endOfLine(position);
            String word = wordAt(start + 2, end);
            type = DIRECTIVES.contains(word) ? TokenType.DIRECTIVE : TokenType.LINE_COMMENT;
        } else if (current == '/' && peek(1) == '*') {
            int blockEnd = endOfBlockComment(position);
            type = blockEnd < 0 ? TokenType.ERROR : TokenType.BLOCK_COMMENT;
            end = blockEnd < 0 ? length : blockEnd;
        } else if (current == '\'') {
            int stringEnd = endOfString(position, false);
            type = stringEnd < 0 ? TokenType.ERROR : TokenType.STRING;
            end = stringEnd < 0 ? length : stringEnd;
        } else if ((current == 'E' || current == 'e') && peek(1) == '\'') {
            int stringEnd = endOfString(position + 1, true);
            type = stringEnd < 0 ? TokenType.ERROR : TokenType.E_STRING;
            end = stringEnd < 0 ? length : stringEnd;
        } else if ((current == 'B' || current == 'b' || current == 'X' || current == 'x') && peek(1) == '\'') {
            int stringEnd = endOfString(position + 1, false);
            type = stringEnd < 0 ? TokenType.ERROR : TokenType.STRING;
            end = stringEnd < 0 ? length : stringEnd;
        } else if (current == '"') {
            int identifierEnd = endOfQuotedIdentifier(position);
            type = identifierEnd < 0 ? TokenType.ERROR : TokenType.QUOTED_IDENTIFIER;
            end = identifierEnd < 0 ? length : identifierEnd;
        } else if (current == '$') {
            int dollarEnd = endOfDollar(position);
            if (dollarEnd < 0) {
                type = TokenType.ERROR;
                end = length;
            } else {
                end = dollarEnd;
                type = dollarType(position, end);
            }
        } else if (isDigit(current) || (current == '.' && isDigit(peek(1)))) {
            end = endOfNumber(position);
            type = TokenType.NUMBER;
        } else if (isWordStart(current)) {
            end = endOfWord(position);
            type = TokenType.WORD;
        } else if (isPunctuation(current)) {
            end = position + 1;
            type = TokenType.PUNCTUATION;
        } else if (isOperatorCharacter(current)) {
            end = endOfOperator(position);
            type = TokenType.OPERATOR;
        } else {
            end = position + 1;
            type = TokenType.ERROR;
        }
        consumeTo(end);
        return new Token(type, sql.substring(start, end), start, end, startLine, startColumn);
    }

    private char peek(int ahead) {
        int index = position + ahead;
        return index < length ? sql.charAt(index) : '\0';
    }

    private void consumeTo(int target) {
        while (position < target) {
            char consumed = sql.charAt(position++);
            if (consumed == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
    }

    private int endOfWhitespace(int index) {
        int end = index;
        while (end < length && isWhitespace(sql.charAt(end))) {
            end++;
        }
        return end;
    }

    private int endOfLine(int index) {
        int end = index;
        while (end < length && sql.charAt(end) != '\n') {
            end++;
        }
        return end;
    }

    /**
     * Returns the exclusive end of a block comment starting at {@code index}, or
     * {@code -1} when it is unterminated. Block comments nest in PostgreSQL.
     */
    private int endOfBlockComment(int index) {
        int end = index + 2;
        int depth = 1;
        while (end < length) {
            if (sql.charAt(end) == '/' && end + 1 < length && sql.charAt(end + 1) == '*') {
                depth++;
                end += 2;
            } else if (sql.charAt(end) == '*' && end + 1 < length && sql.charAt(end + 1) == '/') {
                depth--;
                end += 2;
                if (depth == 0) {
                    return end;
                }
            } else {
                end++;
            }
        }
        return -1;
    }

    /**
     * Returns the exclusive end of a single-quoted string starting at
     * {@code index}, or {@code -1} when it is unterminated. Doubled quotes are
     * escapes; backslash escapes are honoured only for {@code E'...'} strings.
     */
    private int endOfString(int index, boolean backslashEscapes) {
        int end = index + 1;
        while (end < length) {
            char current = sql.charAt(end);
            if (backslashEscapes && current == '\\' && end + 1 < length) {
                end += 2;
            } else if (current == '\'') {
                if (end + 1 < length && sql.charAt(end + 1) == '\'') {
                    end += 2;
                } else {
                    return end + 1;
                }
            } else {
                end++;
            }
        }
        return -1;
    }

    private int endOfQuotedIdentifier(int index) {
        int end = index + 1;
        while (end < length) {
            if (sql.charAt(end) == '"') {
                if (end + 1 < length && sql.charAt(end + 1) == '"') {
                    end += 2;
                } else {
                    return end + 1;
                }
            } else {
                end++;
            }
        }
        return -1;
    }

    /**
     * Returns the exclusive end of a parameter or dollar-quoted string starting
     * at {@code index}. A lone {@code $} ends after one character.
     */
    private int endOfDollar(int index) {
        if (index + 1 < length && sql.charAt(index + 1) == '$') {
            return endOfDollarString(index, "$$");
        }
        if (isDigit(peekFrom(index, 1))) {
            int end = index + 1;
            while (end < length && isDigit(sql.charAt(end))) {
                end++;
            }
            return end;
        }
        if (isWordStart(peekFrom(index, 1))) {
            int tagEnd = index + 1;
            while (tagEnd < length && isDollarTagPart(sql.charAt(tagEnd))) {
                tagEnd++;
            }
            if (tagEnd < length && sql.charAt(tagEnd) == '$') {
                return endOfDollarString(index, sql.substring(index, tagEnd + 1));
            }
        }
        return index + 1;
    }

    private TokenType dollarType(int index, int end) {
        if (end == index + 1) {
            return TokenType.ERROR;
        }
        if (isDigit(peekFrom(index, 1))) {
            return TokenType.PARAMETER;
        }
        return TokenType.DOLLAR_STRING;
    }

    private int endOfDollarString(int index, String delimiter) {
        int close = sql.indexOf(delimiter, index + delimiter.length());
        if (close < 0) {
            return -1;
        }
        return close + delimiter.length();
    }

    private int endOfNumber(int index) {
        int end = index;
        if (sql.charAt(index) == '0' && index + 1 < length) {
            char radix = Character.toLowerCase(sql.charAt(index + 1));
            if (radix == 'x' || radix == 'o' || radix == 'b') {
                end = index + 2;
                while (end < length && isWordPart(sql.charAt(end))) {
                    end++;
                }
                return end;
            }
        }
        while (end < length && isDigit(sql.charAt(end))) {
            end++;
        }
        if (end < length && sql.charAt(end) == '.') {
            end++;
            while (end < length && isDigit(sql.charAt(end))) {
                end++;
            }
        }
        if (end < length && (sql.charAt(end) == 'e' || sql.charAt(end) == 'E')) {
            int exponent = end + 1;
            if (exponent < length && (sql.charAt(exponent) == '+' || sql.charAt(exponent) == '-')) {
                exponent++;
            }
            if (exponent < length && isDigit(sql.charAt(exponent))) {
                while (exponent < length && isDigit(sql.charAt(exponent))) {
                    exponent++;
                }
                end = exponent;
            }
        }
        return end;
    }

    private int endOfWord(int index) {
        int end = index;
        while (end < length && isWordPart(sql.charAt(end))) {
            end++;
        }
        return end;
    }

    private int endOfOperator(int index) {
        int end = index;
        while (end < length && isOperatorCharacter(sql.charAt(end))) {
            end++;
        }
        return end;
    }

    private String wordAt(int start, int end) {
        int wordEnd = start;
        while (wordEnd < end && isWordPart(sql.charAt(wordEnd))) {
            wordEnd++;
        }
        return sql.substring(start, wordEnd).toLowerCase(Locale.ROOT);
    }

    private char peekFrom(int index, int ahead) {
        int target = index + ahead;
        return target < length ? sql.charAt(target) : '\0';
    }

    private static boolean isWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\n'
                || character == '\r' || character == '\f' || character == '\u000B';
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isWordStart(char character) {
        return Character.isLetter(character) || character == '_';
    }

    private static boolean isWordPart(char character) {
        return Character.isLetterOrDigit(character) || character == '_' || character == '$';
    }

    private static boolean isDollarTagPart(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private static boolean isOperatorCharacter(char character) {
        return OPERATOR_CHARACTERS.indexOf(character) >= 0;
    }

    private static boolean isPunctuation(char character) {
        return PUNCTUATION_CHARACTERS.indexOf(character) >= 0;
    }
}
