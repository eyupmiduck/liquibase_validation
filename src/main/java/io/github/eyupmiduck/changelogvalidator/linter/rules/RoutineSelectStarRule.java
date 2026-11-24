package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenType;
import io.github.eyupmiduck.changelogvalidator.linter.model.RoutineBodies;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports {@code SELECT *}, {@code alias.*} or {@code table.*} in a
 * stored-routine body.
 *
 * <p>An explicit select list keeps a routine's result and {@code INTO}
 * assignment stable when a table gains, drops or reorders a column. The rule
 * uses the body tokens, so a star in a comment or a string literal is ignored,
 * and it tracks parentheses so {@code count(*)} is not a violation. It also
 * catches a nested subquery's star.
 *
 * <p>The rule is an error and on by default. It checks forward and rollback
 * routine bodies.
 */
public final class RoutineSelectStarRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "routine-select-star";

    private static boolean isStar(Token token) {
        return token.type() == TokenType.OPERATOR && token.text().equals("*");
    }

    private static boolean isDot(Token token) {
        return isPunctuation(token, ".");
    }

    private static boolean isPunctuation(Token token, String text) {
        return token.type() == TokenType.PUNCTUATION && token.text().equals(text);
    }

    private static boolean isPunctuation(List<Token> tokens, int index, String text) {
        return index < tokens.size() && isPunctuation(tokens.get(index), text);
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

    private static boolean word(List<Token> tokens, int index, String keyword) {
        return index < tokens.size() && tokens.get(index).matchesKeyword(keyword);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public List<Violation> check(RuleContext context) {
        if (!RuleSupport.appliesToPostgres(context.changeSet())) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        collect(context.forward(), violations);
        collect(context.rollback(), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, List<Violation> violations) {
        for (SqlUnit unit : units) {
            if (unit.source().kind() != SqlSource.Kind.ROUTINE_BODY || !RuleSupport.appliesToPostgres(unit)) {
                continue;
            }
            for (RoutineBodies.Body body : RoutineBodies.find(List.of(unit))) {
                List<Token> tokens = body.tokens().stream().filter(token -> !token.isTrivia()).toList();
                for (int i = 0; i < tokens.size(); i++) {
                    if (tokens.get(i).matchesKeyword("SELECT")) {
                        collectSelect(tokens, i, unit, violations);
                    }
                }
            }
        }
    }

    private void collectSelect(List<Token> tokens, int select, SqlUnit unit, List<Violation> violations) {
        int cursor = select + 1;
        if (word(tokens, cursor, "DISTINCT")) {
            cursor++;
            if (word(tokens, cursor, "ON")) {
                cursor++;
                if (isPunctuation(tokens, cursor, "(")) {
                    int close = matchingParen(tokens, cursor);
                    if (close < 0) {
                        return;
                    }
                    cursor = close + 1;
                }
            }
        } else if (word(tokens, cursor, "ALL")) {
            cursor++;
        }

        int depth = 0;
        boolean itemStart = true;
        for (int i = cursor; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (isPunctuation(token, "(")) {
                // A nested subquery is scanned by its own SELECT iteration, so
                // skip it here to avoid reporting the same qualified star twice.
                if (word(tokens, i + 1, "SELECT")) {
                    int close = matchingParen(tokens, i);
                    if (close < 0) {
                        return;
                    }
                    i = close;
                    itemStart = false;
                    continue;
                }
                depth++;
                itemStart = false;
                continue;
            }
            if (isPunctuation(token, ")")) {
                if (depth == 0) {
                    // The paren was opened before this select list (an enclosing
                    // subquery or expression), so the select list ends here. The
                    // outer scan reports any star in it.
                    return;
                }
                depth--;
                itemStart = false;
                continue;
            }
            // A qualified star is a select-list star even inside a function call,
            // e.g. json_agg(t.*); count(*) has no preceding dot.
            if (isStar(token) && isDot(tokens.get(i - 1))) {
                violations.add(new Violation(
                        "SELECT *",
                        "routine selects every column; a table change silently changes its result",
                        "List the columns explicitly.",
                        unit.file(), token.line(), token.column()));
            }
            if (depth > 0) {
                continue;
            }
            if (token.matchesKeyword("FROM") || isPunctuation(token, ";")) {
                return;
            }
            if (isPunctuation(token, ",")) {
                itemStart = true;
                continue;
            }
            if (isStar(token) && itemStart) {
                violations.add(new Violation(
                        "SELECT *",
                        "routine selects every column; a table change silently changes its result",
                        "List the columns explicitly.",
                        unit.file(), token.line(), token.column()));
            }
            itemStart = false;
        }
    }

    @Override
    public String description() {
        return "`SELECT *`, `alias.*` or `table.*` in a routine body";
    }

    @Override
    public String help() {
        return "List the columns explicitly.";
    }
}
