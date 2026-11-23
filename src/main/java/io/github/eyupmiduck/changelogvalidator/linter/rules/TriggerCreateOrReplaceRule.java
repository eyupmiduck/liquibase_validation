package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * Reports a {@code CREATE TRIGGER} that does not use
 * {@code CREATE OR REPLACE TRIGGER}.
 *
 * <p>{@code CREATE OR REPLACE TRIGGER} (PostgreSQL 14+) makes the changeset
 * re-runnable in place. A plain {@code CREATE TRIGGER} fails when the changeset
 * is re-applied after a body change, and a body change then cannot be delivered
 * without a manual drop.
 *
 * <p>The rule is an error and on by default on PostgreSQL 14+; when
 * {@code pgVersion} is known to be below 14 the rule is opt-in and reports
 * nothing, because the syntax is unavailable.
 */
public final class TriggerCreateOrReplaceRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "trigger-create-or-replace";

    private final Integer pgVersion;

    /**
     * Creates a rule that does not know the PostgreSQL version.
     */
    public TriggerCreateOrReplaceRule() {
        this(null);
    }

    /**
     * Creates a version-gated rule.
     *
     * @param pgVersion the PostgreSQL major version, or null when unknown
     */
    public TriggerCreateOrReplaceRule(Integer pgVersion) {
        this.pgVersion = pgVersion;
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
    public boolean enabledByDefault() {
        return supported();
    }

    private boolean supported() {
        return pgVersion == null || pgVersion >= 14;
    }

    @Override
    public List<Violation> check(RuleContext context) {
        if (!supported() || !RuleSupport.appliesToPostgres(context.changeSet())) {
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
                    if (!tokens.get(i).matchesKeyword("CREATE")) {
                        continue;
                    }
                    int cursor = i + 1;
                    boolean orReplace = word(tokens, cursor, "OR") && word(tokens, cursor + 1, "REPLACE");
                    if (orReplace) {
                        cursor += 2;
                    }
                    if (word(tokens, cursor, "CONSTRAINT")) {
                        cursor++;
                    }
                    if (!word(tokens, cursor, "TRIGGER")) {
                        continue;
                    }
                    if (!orReplace) {
                        Token token = tokens.get(i);
                        violations.add(new Violation(
                                "CREATE TRIGGER",
                                "CREATE TRIGGER without OR REPLACE cannot be re-applied after a body change",
                                "Use CREATE OR REPLACE TRIGGER (PostgreSQL 14+).",
                                unit.file(), token.line(), token.column()));
                    }
                    break;
                }
            }
        }
    }
}
