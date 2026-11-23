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
import java.util.Objects;
import java.util.Set;

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
        }
    }
}
