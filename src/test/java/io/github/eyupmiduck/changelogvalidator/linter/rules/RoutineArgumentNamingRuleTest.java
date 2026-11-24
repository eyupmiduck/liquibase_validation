package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RoutineArgumentNamingRule}: parameters use {@code i_}/{@code o_}
 * and locals use {@code l_}, while unnamed types, comments, strings and
 * {@code FOR UPDATE} are not mistaken for declarations.
 */
class RoutineArgumentNamingRuleTest {

    private static final Path FILE = RuleTestSupport.FILE;

    private static RuleContext context(SqlSource.Kind kind, String sql) {
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, true,
                null, null, null, List.of(), true, List.of());
        return new RuleContext(changeSet, List.of(RuleTestSupport.unit(kind, sql)), List.of());
    }

    private static List<Rule.Violation> check(String parameters, String body) {
        return new RoutineArgumentNamingRule().check(context(SqlSource.Kind.ROUTINE_BODY,
                "CREATE FUNCTION app.f(" + parameters + ") RETURNS void AS $$ " + body + " $$ LANGUAGE plpgsql;"));
    }

    @Test
    void acceptsTheConventionalParameterPrefixes() {
        assertTrue(check("i_x integer, OUT o_y integer, INOUT io_z integer, VARIADIC i_values integer[]",
                "BEGIN NULL; END;").isEmpty());
    }

    @Test
    void reportsEachParameterWithoutItsModePrefix() {
        assertEquals(1, check("x integer", "BEGIN NULL; END;").size());
        assertEquals(1, check("OUT y integer", "BEGIN NULL; END;").size());
        assertEquals(1, check("INOUT z integer", "BEGIN NULL; END;").size());
        assertEquals(1, check("VARIADIC values integer[]", "BEGIN NULL; END;").size());
        assertEquals(1, check("i_x integer, y text", "BEGIN NULL; END;").size());
    }

    @Test
    void ignoresUnnamedParameterTypes() {
        assertTrue(check("integer, text", "BEGIN NULL; END;").isEmpty());
        assertTrue(check("double precision, character varying, numeric(10, 2)", "BEGIN NULL; END;").isEmpty());
        assertTrue(check("integer[], app.custom_type", "BEGIN NULL; END;").isEmpty());
    }

    @Test
    void acceptsAndReportsLocalVariables() {
        assertTrue(check("", "DECLARE l_x integer; l_y text; BEGIN NULL; END;").isEmpty());
        assertEquals(1, check("", "DECLARE x integer; BEGIN NULL; END;").size());
        assertEquals(2, check("", "DECLARE x integer; y text; BEGIN NULL; END;").size());
    }

    @Test
    void foldsUnquotedLocalNamesAndKeepsQuotedCase() {
        assertTrue(check("", "DECLARE L_amount integer; BEGIN NULL; END;").isEmpty());
        assertEquals(1, check("", "DECLARE \"L_amount\" integer; BEGIN NULL; END;").size());
    }

    @Test
    void acceptsTheTwoWordInOutMode() {
        assertTrue(check("IN OUT io_x integer", "BEGIN NULL; END;").isEmpty());
        assertEquals(1, check("IN OUT x integer", "BEGIN NULL; END;").size());
    }

    @Test
    void toleratesAnUnmatchedParenOrMissingBeginInDeclare() {
        assertEquals(2, check("", "DECLARE x integer); y text; BEGIN NULL; END;").size());
        assertEquals(2, check("", "DECLARE x integer; y text;").size());
    }

    @Test
    void acceptsAndReportsForLoopVariables() {
        assertTrue(check("", "BEGIN FOR l_i IN 1..10 LOOP NULL; END LOOP; END;").isEmpty());
        assertEquals(1, check("", "BEGIN FOR i IN 1..10 LOOP NULL; END LOOP; END;").size());
        assertEquals(1, check("", "BEGIN FOR i IN REVERSE 10..1 LOOP NULL; END LOOP; END;").size());
        assertEquals(1, check("", "BEGIN FOR r IN SELECT 1 LOOP NULL; END LOOP; END;").size());
        assertTrue(check("", "BEGIN FOREACH l_x IN ARRAY ARRAY[1] LOOP NULL; END LOOP; END;").isEmpty());
        assertEquals(1, check("", "BEGIN FOREACH x IN ARRAY ARRAY[1] LOOP NULL; END LOOP; END;").size());
    }

    @Test
    void ignoresForUpdateAndCommentsAndStrings() {
        assertTrue(check("", "DECLARE l_x integer; BEGIN -- FOR x IN\n PERFORM 'DECLARE y integer';"
                + " RETURN QUERY SELECT 1 FROM app.t FOR UPDATE; END;").isEmpty());
    }

    @Test
    void ignoresSqlLanguageBodies() {
        assertTrue(new RoutineArgumentNamingRule().check(context(SqlSource.Kind.ROUTINE_BODY,
                "CREATE FUNCTION app.f(i_x integer) RETURNS integer AS $$ SELECT i_x; $$ LANGUAGE sql;")).isEmpty());
    }

    @Test
    void ignoresNonRoutineSourcesAndAnotherDatabase() {
        assertTrue(new RoutineArgumentNamingRule()
                .check(context(SqlSource.Kind.INLINE_SQL, "SELECT 1;")).isEmpty());

        SqlUnit unit = RuleTestSupport.unit(
                "CREATE FUNCTION app.f(x integer) RETURNS void AS $$ BEGIN NULL; END; $$ LANGUAGE plpgsql;",
                "oracle");
        RuleContext context = new RuleContext(RuleTestSupport.changeSet(true, true, null, false),
                List.of(unit), List.of());
        assertTrue(new RoutineArgumentNamingRule().check(context).isEmpty());
    }

    @Test
    void checksTheRollbackDirection() {
        RuleContext context = new RuleContext(RuleTestSupport.changeSet(true, true, null, false), List.of(),
                List.of(RuleTestSupport.unit(SqlSource.Kind.ROUTINE_BODY,
                        "CREATE FUNCTION app.f(x integer) RETURNS void AS $$ BEGIN NULL; END; $$ LANGUAGE plpgsql;")));
        assertEquals(1, new RoutineArgumentNamingRule().check(context).size());
    }

    @Test
    void pointsAtTheNameToken() {
        Rule.Violation parameter = new RoutineArgumentNamingRule().check(context(SqlSource.Kind.ROUTINE_BODY,
                "CREATE FUNCTION app.f(x integer) RETURNS void AS $$ DECLARE y integer; BEGIN NULL; END; $$"
                        + " LANGUAGE plpgsql;")).getFirst();

        assertEquals(1, parameter.line());
        assertEquals(23, parameter.column());
        assertEquals(FILE, parameter.file());
        assertEquals("parameter", parameter.statement());
        assertTrue(parameter.message().contains("'x'"));
    }

    @Test
    void isAnErrorAndOnByDefault() {
        assertEquals(Severity.ERROR, new RoutineArgumentNamingRule().defaultSeverity());
        assertTrue(new RoutineArgumentNamingRule().enabledByDefault());
    }
}
