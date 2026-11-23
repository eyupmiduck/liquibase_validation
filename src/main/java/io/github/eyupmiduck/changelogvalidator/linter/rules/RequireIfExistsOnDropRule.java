package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Advises using {@code IF EXISTS} on a {@code DROP TABLE}, {@code DROP
 * FUNCTION}, {@code DROP PROCEDURE}, {@code DROP TRIGGER} or {@code DROP
 * INDEX}.
 *
 * <p>Without {@code IF EXISTS}, a re-applied or partially rolled-back changeset
 * fails when the object is already gone. {@code DROP INDEX CONCURRENTLY} is
 * recognised in its {@code [CONCURRENTLY] [IF EXISTS]} position.
 *
 * <p>The rule is a warning and opt-in. It checks forward and rollback SQL.
 */
public final class RequireIfExistsOnDropRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "require-if-exists-on-drop";

    private static final Set<String> OBJECT_KINDS = Set.of(
            "TABLE", "FUNCTION", "PROCEDURE", "TRIGGER", "INDEX");

    private static boolean isObjectKind(Token token) {
        for (String kind : OBJECT_KINDS) {
            if (token.matchesKeyword(kind)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARNING;
    }

    @Override
    public boolean enabledByDefault() {
        return false;
    }

    @Override
    public List<Violation> check(RuleContext context) {
        if (!RuleSupport.appliesToPostgres(context.changeSet())) {
            return List.of();
        }
        List<Violation> violations = new ArrayList<>();
        collect(RuleSupport.applicableToPostgres(context.forward()), violations);
        collect(RuleSupport.applicableToPostgres(context.rollback()), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, List<Violation> violations) {
        for (SqlUnit unit : units) {
            for (SqlStatement statement : unit.statements()) {
                List<Token> tokens = RuleSupport.nonTriviaWithin(unit.tokens(), statement);
                for (int i = 0; i < tokens.size(); i++) {
                    if (!tokens.get(i).matchesKeyword("DROP")) {
                        continue;
                    }
                    int cursor = i + 1;
                    if (cursor >= tokens.size() || !isObjectKind(tokens.get(cursor))) {
                        continue;
                    }
                    String kind = tokens.get(cursor).text().toUpperCase(Locale.ROOT);
                    cursor++;
                    if (kind.equals("INDEX") && TokenWords.at(tokens, cursor, "CONCURRENTLY")) {
                        cursor++;
                    }
                    if (TokenWords.at(tokens, cursor, "IF") && TokenWords.at(tokens, cursor + 1, "EXISTS")) {
                        continue;
                    }
                    Token token = tokens.get(i);
                    violations.add(new Violation(
                            "DROP " + kind,
                            "DROP " + kind + " without IF EXISTS fails when the object is already gone",
                            "Add IF EXISTS so the changeset is safely re-runnable.",
                            unit.file(), token.line(), token.column()));
                }
            }
        }
    }
}
