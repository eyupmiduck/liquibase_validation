package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notes that a stored-routine body builds SQL dynamically, so the static rules
 * cannot see the statements it runs.
 *
 * <p>A PL/pgSQL body that uses {@code EXECUTE} (or {@code format(...)} fed to
 * {@code EXECUTE}) assembles DDL at runtime. The token rules inspect the body's
 * own tokens only, so a {@code CREATE INDEX CONCURRENTLY} or an
 * {@code ALTER TABLE} inside the dynamic string is invisible to them. This rule
 * reports that blind spot rather than implying the changeset is clean, and
 * complements {@code plpgsql_check} (which analyses the static body but not the
 * runtime string).
 *
 * <p>A routine body may be dollar-quoted, so the lexer can expose it as one
 * string token rather than as statements. The rule therefore scans the body text
 * for the {@code EXECUTE} keyword with a regular expression. The scan is
 * deliberately not quote- or comment-aware: a mention of the word inside a
 * literal also triggers it, which is acceptable for an informational reminder.
 *
 * <p>The rule is opt-in (informational): dynamic SQL in a routine is normal, so
 * a project enables the rule when it wants the reminder. It only fires on
 * routine bodies; inline or file SQL has no dynamic string to hide statements
 * in.
 */
public final class DynamicSqlRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "routine-dynamic-sql";

    // EXECUTE as a whole word. It is deliberately not quote-aware: a mention of
    // the word inside a literal also triggers it, which is acceptable for an
    // informational "look at the dynamic SQL" reminder.
    private static final Pattern EXECUTE = Pattern.compile("(?i)(?<![\\w])execute(?![\\w])");

    /**
     * Returns the one-based line and column of {@code offset} in the unit's SQL,
     * relative to the body token that contains it, so the finding points at the
     * {@code EXECUTE} rather than the top of the file. The walk uses the lexer's
     * own rules: a bare {@code \r} is a line break, a CRLF is one break, and a
     * surrogate pair counts as one column.
     */
    private static int[] position(SqlUnit unit, int offset) {
        for (Token token : unit.tokens()) {
            if (token.startOffset() <= offset && offset < token.endOffset()) {
                String sql = unit.sql();
                int line = token.line();
                int column = token.column();
                for (int i = token.startOffset(); i < offset; i++) {
                    char consumed = sql.charAt(i);
                    if (consumed == '\n') {
                        line++;
                        column = 1;
                    } else if (consumed == '\r' && (i + 1 >= sql.length() || sql.charAt(i + 1) != '\n')) {
                        line++;
                        column = 1;
                    } else {
                        column++;
                        if (Character.isHighSurrogate(consumed) && i + 1 < offset
                                && Character.isLowSurrogate(sql.charAt(i + 1))) {
                            i++;
                        }
                    }
                }
                return new int[]{line, column};
            }
        }
        // The offset is inconsistent with the lexed tokens; report at the first
        // meaningful token instead of the top of the file.
        Token anchor = unit.tokens().stream().filter(token -> !token.isTrivia()).findFirst()
                .orElseThrow(() -> new IllegalStateException("routine body has no token to locate"));
        return new int[]{anchor.line(), anchor.column()};
    }

    /**
     * Returns every body to scan and each body's offset in the unit.
     *
     * <p>For a {@code CREATE FUNCTION}/{@code CREATE PROCEDURE} declaration a
     * body is any string literal introduced by {@code AS}, so a routine or
     * parameter named {@code execute} in the header is not mistaken for dynamic
     * SQL, and a file declaring several routines contributes every body. A bare
     * routine body has no declaration and is scanned whole.
     */
    private static List<Body> bodies(SqlUnit unit) {
        List<Body> bodies = new ArrayList<>();
        List<Token> tokens = unit.tokens().stream().filter(token -> !token.isTrivia()).toList();
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).matchesKeyword("AS") && tokens.get(i + 1).isStringLiteral()) {
                Token body = tokens.get(i + 1);
                bodies.add(new Body(body.text(), body.startOffset()));
            }
        }
        return bodies.isEmpty() ? List.of(new Body(unit.sql(), 0)) : bodies;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.INFO;
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
        collect(context.forward(), violations);
        collect(context.rollback(), violations);
        return List.copyOf(violations);
    }

    private void collect(List<SqlUnit> units, List<Violation> violations) {
        for (SqlUnit unit : units) {
            if (unit.source().kind() != SqlSource.Kind.ROUTINE_BODY || !RuleSupport.appliesToPostgres(unit)) {
                continue;
            }
            for (Body body : bodies(unit)) {
                Matcher matcher = EXECUTE.matcher(body.text());
                while (matcher.find()) {
                    int[] position = position(unit, body.sourceOffset() + matcher.start());
                    violations.add(new Violation(
                            "EXECUTE",
                            "routine body builds SQL dynamically; static rules cannot see the statements it runs",
                            "Inspect the dynamic SQL by hand (or with plpgsql_check) for the rules the linter cannot apply.",
                            unit.file(), position[0], position[1]));
                }
            }
        }
    }

    /**
     * A body to scan and its offset in the unit's SQL.
     */
    private record Body(String text, int sourceOffset) {
    }
}
