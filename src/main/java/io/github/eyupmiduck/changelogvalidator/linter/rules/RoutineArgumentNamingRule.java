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
 * Enforces the routine naming convention: input parameters start with
 * {@code i_}, output parameters with {@code o_}, input/output parameters with
 * {@code io_}, and local variables with {@code l_}.
 *
 * <p>A prefix makes a routine body readable without the declaration in view, so
 * a reader can tell a parameter from a column or another routine's local. The
 * rule checks the declaration's named parameters and the local variables a
 * PL/pgSQL body declares, both in a {@code DECLARE} block and as a {@code FOR}
 * loop target.
 *
 * <p>It uses the extracted body tokens, so a name in a comment or a string is not
 * checked. Unnamed parameters and SQL-language bodies (which have no
 * declarations) contribute nothing. {@code RETURNS TABLE} result columns are
 * not declared parameters; they are left to the project's result-shape
 * convention.
 *
 * <p>The rule is an error and on by default; it checks forward and rollback
 * routine bodies.
 */
public final class RoutineArgumentNamingRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "routine-argument-naming";

    private static final String HELP = "Prefix input parameters with i_, output parameters with o_, "
            + "INOUT parameters with io_, and local variables with l_.";

    /**
     * Returns the tokens that declare a local variable: the first identifier of
     * each top-level {@code DECLARE} declaration and every {@code FOR} loop
     * target.
     */
    private static List<Token> locals(List<Token> tokens) {
        List<Token> triviaFree = tokens.stream().filter(token -> !token.isTrivia()).toList();
        List<Token> locals = new ArrayList<>();
        for (int i = 0; i < triviaFree.size(); i++) {
            if (triviaFree.get(i).matchesKeyword("DECLARE")) {
                collectDeclarations(triviaFree, i + 1, locals);
            } else if (triviaFree.get(i).matchesKeyword("FOR")
                    || triviaFree.get(i).matchesKeyword("FOREACH")) {
                Token target = loopTarget(triviaFree, i);
                if (target != null) {
                    locals.add(target);
                }
            }
        }
        return locals;
    }

    private static void collectDeclarations(List<Token> tokens, int start, List<Token> locals) {
        List<Token> segment = new ArrayList<>();
        int depth = 0;
        for (int i = start; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.matchesKeyword("BEGIN") && depth == 0) {
                addDeclaration(segment, locals);
                return;
            }
            if (isPunctuation(token, "(")) {
                depth++;
            } else if (isPunctuation(token, ")")) {
                depth--;
            }
            if (depth == 0 && isPunctuation(token, ";")) {
                addDeclaration(segment, locals);
                segment = new ArrayList<>();
            } else {
                segment.add(token);
            }
        }
    }

    private static void addDeclaration(List<Token> segment, List<Token> locals) {
        if (segment.isEmpty()) {
            return;
        }
        Token first = segment.getFirst();
        if (first.type() == TokenType.WORD || first.type() == TokenType.QUOTED_IDENTIFIER) {
            locals.add(first);
        }
    }

    /**
     * Returns an identifier token's name, without the quotes of a quoted
     * identifier.
     */
    private static String name(Token token) {
        if (token.type() == TokenType.QUOTED_IDENTIFIER) {
            return token.text().substring(1, token.text().length() - 1).replace("\"\"", "\"");
        }
        return token.text();
    }

    /**
     * Returns the loop variable of a {@code FOR var IN ...} or {@code FOR var IN
     * REVERSE ...} loop, or null when the {@code FOR} is not a loop (for example
     * {@code SELECT ... FOR UPDATE}).
     */
    private static Token loopTarget(List<Token> tokens, int forIndex) {
        int name = forIndex + 1;
        if (name >= tokens.size()) {
            return null;
        }
        Token target = tokens.get(name);
        if (target.type() != TokenType.WORD && target.type() != TokenType.QUOTED_IDENTIFIER) {
            return null;
        }
        int next = name + 1;
        if (next < tokens.size() && tokens.get(next).matchesKeyword("IN")) {
            // Covers both `FOR var IN ...` and `FOR var IN REVERSE ...`.
            return target;
        }
        return null;
    }

    private static boolean isPunctuation(Token token, String text) {
        return token.type() == TokenType.PUNCTUATION && token.text().equals(text);
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
                for (RoutineBodies.Parameter parameter : body.parameters()) {
                    String prefix = switch (parameter.mode()) {
                        case IN, VARIADIC -> "i_";
                        case OUT -> "o_";
                        case INOUT -> "io_";
                    };
                    if (!parameter.name().startsWith(prefix)) {
                        violations.add(new Violation(
                                "parameter",
                                "routine parameter '" + parameter.name() + "' must start with " + prefix,
                                HELP,
                                unit.file(), parameter.token().line(), parameter.token().column()));
                    }
                }
                for (Token local : locals(body.tokens())) {
                    String name = name(local);
                    if (!name.startsWith("l_")) {
                        violations.add(new Violation(
                                "local variable",
                                "routine local variable '" + name + "' must start with l_",
                                HELP,
                                unit.file(), local.line(), local.column()));
                    }
                }
            }
        }
    }
}
