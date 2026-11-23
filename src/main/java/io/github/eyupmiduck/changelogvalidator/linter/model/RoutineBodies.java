package io.github.eyupmiduck.changelogvalidator.linter.model;

import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;

import java.util.*;

/**
 * Extracts the stored-routine bodies from SQL units and re-lexes each body with
 * absolute offsets, lines and columns.
 *
 * <p>The outer {@code CREATE FUNCTION}/{@code CREATE PROCEDURE} statement exposes
 * a dollar-quoted body as one {@link TokenType#DOLLAR_STRING} token. Body rules
 * need the tokens inside that string, and a finding must point at the original
 * file. This helper finds the {@code AS $$...$$} or {@code AS $tag$...$tag$}
 * token, re-lexes its content with the original offset base, and returns the
 * tokens with absolute positions.
 *
 * <p>A unit with no routine declaration is treated as a bare body and its own
 * tokens are returned, so a hand-written body fragment can still be checked. A
 * declaration whose body is a single-quoted SQL string is not extracted: the
 * supported forms are the dollar-quoted PL/pgSQL bodies.
 */
public final class RoutineBodies {

    /**
     * First words of multi-word type names that would otherwise be mistaken for
     * an unnamed parameter.
     */
    private static final Set<String> MULTI_WORD_TYPE_STARTS = Set.of(
            "double", "character", "char", "bit", "national", "nchar", "timestamp", "time", "interval");

    /**
     * Continuations of the multi-word type names in {@link #MULTI_WORD_TYPE_STARTS}.
     */
    private static final Set<String> TYPE_CONTINUATIONS = Set.of(
            "precision", "varying", "with", "without",
            "year", "month", "day", "hour", "minute", "second", "to", "character");

    private RoutineBodies() {
    }

    /**
     * Finds every routine body in {@code units}.
     *
     * @param units the SQL units
     * @return the bodies, in unit then declaration order
     */
    public static List<Body> find(List<SqlUnit> units) {
        List<Body> bodies = new ArrayList<>();
        for (SqlUnit unit : units) {
            bodies.addAll(find(unit));
        }
        return List.copyOf(bodies);
    }

    private static List<Body> find(SqlUnit unit) {
        List<Token> tokens = unit.tokens().stream().filter(token -> !token.isTrivia()).toList();
        List<Body> bodies = new ArrayList<>();
        boolean declaration = false;
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).matchesKeyword("CREATE")) {
                continue;
            }
            int cursor = i + 1;
            if (word(tokens, cursor, "OR") && word(tokens, cursor + 1, "REPLACE")) {
                cursor += 2;
            }
            Kind kind = word(tokens, cursor, "FUNCTION") ? Kind.FUNCTION
                    : word(tokens, cursor, "PROCEDURE") ? Kind.PROCEDURE : null;
            if (kind == null) {
                continue;
            }
            declaration = true;
            String name = qualifiedName(tokens, cursor + 1);
            int open = indexOfPunctuation(tokens, "(", cursor + 1);
            if (open < 0) {
                continue;
            }
            int close = matchingParen(tokens, open);
            if (close < 0) {
                continue;
            }
            int as = indexOf(tokens, "AS", close + 1);
            if (as < 0 || as + 1 >= tokens.size()) {
                continue;
            }
            Token body = tokens.get(as + 1);
            if (body.type() != TokenType.DOLLAR_STRING) {
                continue;
            }
            Body parsed = body(unit, body, name, kind, parameters(tokens, open, close));
            if (parsed != null) {
                bodies.add(parsed);
                i = as + 1;
            }
        }
        if (!declaration) {
            bodies.add(new Body(unit, null, Kind.UNKNOWN, List.of(), unit.tokens()));
        }
        return List.copyOf(bodies);
    }

    private static Body body(SqlUnit unit, Token body, String name, Kind kind, List<Parameter> parameters) {
        String text = body.text();
        int secondDollar = text.indexOf('$', 1);
        if (secondDollar < 0) {
            return null;
        }
        int delimiterLength = secondDollar + 1;
        if (text.length() < delimiterLength * 2) {
            return null;
        }
        String content = text.substring(delimiterLength, text.length() - delimiterLength);
        List<Token> tokens = SqlLexer.tokenize(content,
                body.startOffset() + delimiterLength,
                body.line(),
                body.column() + delimiterLength);
        return new Body(unit, name, kind, parameters, tokens);
    }

    /**
     * Parses the named parameters of a declaration. A segment whose first token is
     * followed by a dot, bracket or opening parenthesis is a type, not a named
     * parameter, and a multi-word type's first word is filtered by a small type
     * vocabulary.
     */
    private static List<Parameter> parameters(List<Token> tokens, int open, int close) {
        List<Parameter> parameters = new ArrayList<>();
        List<Token> segment = new ArrayList<>();
        int depth = 0;
        for (int i = open + 1; i < close; i++) {
            Token token = tokens.get(i);
            if (isPunctuation(token, "(")) {
                depth++;
            } else if (isPunctuation(token, ")")) {
                depth--;
            }
            if (depth == 0 && isPunctuation(token, ",")) {
                addParameter(segment, parameters);
                segment = new ArrayList<>();
            } else {
                segment.add(token);
            }
        }
        addParameter(segment, parameters);
        return List.copyOf(parameters);
    }

    private static void addParameter(List<Token> segment, List<Parameter> parameters) {
        if (segment.isEmpty()) {
            return;
        }
        int cursor = 0;
        ParameterMode mode = ParameterMode.IN;
        if (segment.get(cursor).matchesKeyword("IN")) {
            cursor++;
        } else if (segment.get(cursor).matchesKeyword("OUT")) {
            mode = ParameterMode.OUT;
            cursor++;
        } else if (segment.get(cursor).matchesKeyword("INOUT")) {
            mode = ParameterMode.INOUT;
            cursor++;
        } else if (segment.get(cursor).matchesKeyword("VARIADIC")) {
            mode = ParameterMode.VARIADIC;
            cursor++;
        }
        if (cursor + 1 >= segment.size()) {
            return;
        }
        Token name = segment.get(cursor);
        String identifier = identifier(name);
        if (identifier == null || isUnnamedType(segment, cursor)) {
            return;
        }
        parameters.add(new Parameter(identifier, mode, name));
    }

    /**
     * Returns whether the tokens at {@code cursor} are an unnamed parameter's type
     * rather than a parameter name.
     */
    private static boolean isUnnamedType(List<Token> segment, int cursor) {
        Token second = segment.get(cursor + 1);
        if (second.type() == TokenType.PUNCTUATION
                && (second.text().equals(".") || second.text().equals("[") || second.text().equals("("))) {
            return true;
        }
        String first = segment.get(cursor).text().toLowerCase(Locale.ROOT);
        String next = second.text().toLowerCase(Locale.ROOT);
        return MULTI_WORD_TYPE_STARTS.contains(first) && TYPE_CONTINUATIONS.contains(next);
    }

    private static String qualifiedName(List<Token> tokens, int start) {
        if (start < 0 || start >= tokens.size()) {
            return null;
        }
        String first = identifier(tokens.get(start));
        if (first == null) {
            return null;
        }
        StringBuilder name = new StringBuilder(first);
        int i = start + 1;
        while (i + 1 < tokens.size() && isPunctuation(tokens.get(i), ".")) {
            String next = identifier(tokens.get(i + 1));
            if (next == null) {
                break;
            }
            name.append('.').append(next);
            i += 2;
        }
        return name.toString();
    }

    private static String identifier(Token token) {
        return switch (token.type()) {
            case WORD -> token.text().toLowerCase(Locale.ROOT);
            case QUOTED_IDENTIFIER -> token.text().substring(1, token.text().length() - 1)
                    .replace("\"\"", "\"");
            default -> null;
        };
    }

    private static int matchingParen(List<Token> tokens, int open) {
        int depth = 0;
        for (int i = open; i < tokens.size(); i++) {
            if (isPunctuation(tokens.get(i), "(")) {
                depth++;
            } else if (isPunctuation(tokens.get(i), ")")) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int indexOf(List<Token> tokens, String keyword, int from) {
        for (int i = from; i < tokens.size(); i++) {
            if (tokens.get(i).matchesKeyword(keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfPunctuation(List<Token> tokens, String punctuation, int from) {
        for (int i = from; i < tokens.size(); i++) {
            if (isPunctuation(tokens.get(i), punctuation)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean word(List<Token> tokens, int index, String keyword) {
        return index < tokens.size() && tokens.get(index).matchesKeyword(keyword);
    }

    private static boolean isPunctuation(Token token, String punctuation) {
        return token.type() == TokenType.PUNCTUATION && punctuation.equals(token.text());
    }

    /**
     * The routine declaration kind.
     */
    public enum Kind {

        /**
         * A {@code CREATE FUNCTION} body.
         */
        FUNCTION,

        /**
         * A {@code CREATE PROCEDURE} body.
         */
        PROCEDURE,

        /**
         * A bare body with no declaration.
         */
        UNKNOWN
    }

    /**
     * The parameter mode.
     */
    public enum ParameterMode {

        /**
         * An input parameter (the default).
         */
        IN,

        /**
         * An output parameter.
         */
        OUT,

        /**
         * An input/output parameter.
         */
        INOUT,

        /**
         * A variadic input parameter.
         */
        VARIADIC
    }

    /**
     * One extracted routine body.
     *
     * @param unit       the SQL unit the body came from
     * @param name       the schema-qualified routine name when the declaration
     *                   names one, otherwise {@code null} (a bare body)
     * @param kind       the declaration kind, or {@link Kind#UNKNOWN} for a bare body
     * @param parameters the declared parameters, in declaration order; empty for a
     *                   bare body
     * @param tokens     the body tokens, with absolute offsets, lines and columns
     */
    public record Body(SqlUnit unit, String name, Kind kind, List<Parameter> parameters, List<Token> tokens) {

        /**
         * Validates the required components.
         */
        public Body {
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(parameters, "parameters");
            Objects.requireNonNull(tokens, "tokens");
            parameters = List.copyOf(parameters);
            tokens = List.copyOf(tokens);
        }
    }

    /**
     * A declared routine parameter.
     *
     * @param name  the parameter name as written (not case-folded)
     * @param mode  the parameter mode
     * @param token the token the name came from
     */
    public record Parameter(String name, ParameterMode mode, Token token) {

        /**
         * Validates the required components.
         */
        public Parameter {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(token, "token");
        }
    }
}
