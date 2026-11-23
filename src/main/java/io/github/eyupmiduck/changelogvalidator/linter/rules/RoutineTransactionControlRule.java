package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.model.RoutineBodies;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports {@code COMMIT} or {@code ROLLBACK} in a stored-function body.
 *
 * <p>PostgreSQL functions run inside the caller's transaction and cannot manage
 * it; a transaction-control statement fails at run time. A procedure may commit,
 * and the body rules deliberately leave procedure bodies alone.
 *
 * <p>The rule is an error and on by default. It uses the re-lexed body tokens, so
 * a {@code COMMIT} in a comment or string is not reported.
 */
public final class RoutineTransactionControlRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "routine-transaction-control";

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
                if (body.kind() != RoutineBodies.Kind.FUNCTION) {
                    continue;
                }
                for (Token token : body.tokens()) {
                    if (token.isTrivia()) {
                        continue;
                    }
                    if (token.matchesKeyword("COMMIT") || token.matchesKeyword("ROLLBACK")) {
                        violations.add(new Violation(
                                token.text().toUpperCase(java.util.Locale.ROOT),
                                "stored function body uses transaction control",
                                "A function cannot COMMIT or ROLLBACK; move the transaction boundary to the caller or use a procedure.",
                                unit.file(), token.line(), token.column()));
                    }
                }
            }
        }
    }

    @Override
    public String description() {
        return "`COMMIT` or `ROLLBACK` in a function body";
    }

    @Override
    public String help() {
        return "Remove transaction control from the function; let the caller own the transaction.";
    }
}
