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
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notes that a stored-routine body builds SQL dynamically, so the static rules
 * cannot see the statements it runs.
 *
 * <p>A PL/pgSQL body that uses {@code EXECUTE} assembles DDL at runtime. The token
 * rules inspect the body's own tokens only, so a {@code CREATE INDEX
 * CONCURRENTLY} or an {@code ALTER TABLE} inside the dynamic string is invisible
 * to them. This rule reports the body so the linter does not imply the changeset
 * is clean; it complements {@code plpgsql_check}, which analyses the static body
 * but not the runtime string.
 *
 * <p>{@link RoutineBodies} extracts and re-lexes the dollar-quoted body, so an
 * {@code EXECUTE} inside a comment or a string literal is not reported. A routine
 * in {@code allowedRoutines()} (a {@code schema.name} allow-list) is skipped for
 * a known dynamic-SQL boundary such as {@code ddl_utils_lib.alter_table}.
 *
 * <p>The rule is informational and opt-in. A bare body with no declaration has
 * no name, so it cannot be allow-listed.
 */
public final class DynamicSqlRule implements Rule {

    /**
     * The rule id.
     */
    public static final String ID = "routine-dynamic-sql";

    // EXECUTE as a whole word, for legacy single-quoted bodies that the
    // dollar-quote extractor does not re-lex.
    private static final Pattern EXECUTE = Pattern.compile("(?i)(?<!\\w)execute(?!\\w)");

    private final Set<String> allowedRoutines;

    /**
     * Creates a rule with no allowed routines.
     */
    public DynamicSqlRule() {
        this(Set.of());
    }

    /**
     * Creates a rule with an allow-list.
     *
     * @param allowedRoutines the {@code schema.name} routines allowed to use
     *                        dynamic SQL
     */
    public DynamicSqlRule(Set<String> allowedRoutines) {
        Objects.requireNonNull(allowedRoutines, "allowedRoutines");
        this.allowedRoutines = Set.copyOf(allowedRoutines);
    }

    /**
     * Returns the one-based line and column of {@code offset} in the unit's SQL.
     * The walk uses the lexer's own rules: a bare CR is a line break, a CRLF is
     * one break, and a surrogate pair counts as one column.
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
        Token anchor = unit.tokens().stream().filter(token -> !token.isTrivia()).findFirst()
                .orElseThrow(() -> new IllegalStateException("routine body has no token to locate"));
        return new int[]{anchor.line(), anchor.column()};
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
            for (RoutineBodies.Body body : RoutineBodies.find(List.of(unit))) {
                if (body.name() != null && allowedRoutines.contains(body.name())) {
                    continue;
                }
                for (Token token : body.tokens()) {
                    if (!token.isTrivia() && token.matchesKeyword("EXECUTE")) {
                        violations.add(new Violation(
                                "EXECUTE",
                                "routine body builds SQL dynamically; static rules cannot see the statements it runs",
                                "Inspect the dynamic SQL by hand (or with plpgsql_check) for the rules the linter cannot apply.",
                                unit.file(), token.line(), token.column()));
                    }
                }
            }
            collectSingleQuotedBodies(unit, violations);
        }
    }

    /**
     * Keeps the rule's original coverage for a legacy single-quoted routine body.
     * {@link RoutineBodies} deliberately extracts only dollar-quoted bodies; a
     * body written as {@code AS 'BEGIN ... EXECUTE ...; END'} is a single string
     * token, so this fallback scans its text for the keyword. It is
     * informational and quote-unaware, which is acceptable for the reminder.
     */
    private void collectSingleQuotedBodies(SqlUnit unit, List<Violation> violations) {
        List<Token> tokens = unit.tokens().stream().filter(token -> !token.isTrivia()).toList();
        boolean declaration = tokens.stream()
                .anyMatch(token -> token.matchesKeyword("FUNCTION") || token.matchesKeyword("PROCEDURE"));
        if (!declaration) {
            return;
        }
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token body = tokens.get(i + 1);
            if (!tokens.get(i).matchesKeyword("AS")
                    || body.type() == TokenType.DOLLAR_STRING
                    || !body.isStringLiteral()) {
                continue;
            }
            Matcher matcher = EXECUTE.matcher(body.text());
            while (matcher.find()) {
                int[] position = position(unit, body.startOffset() + matcher.start());
                violations.add(new Violation(
                        "EXECUTE",
                        "routine body builds SQL dynamically; static rules cannot see the statements it runs",
                        "Inspect the dynamic SQL by hand (or with plpgsql_check) for the rules the linter cannot apply.",
                        unit.file(), position[0], position[1]));
            }
        }
    }

    @Override
    public String description() {
        return "a routine body that uses `EXECUTE`";
    }

    @Override
    public String help() {
        return "Review the dynamic SQL manually, or add the routine to `allowedRoutines`.";
    }
}
