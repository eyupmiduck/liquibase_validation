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
 * Verifies {@link RoutineSelectStarRule}: a star in a routine body's select list
 * is reported, while a count, a string or a comment is not.
 */
class RoutineSelectStarRuleTest {

    private static final Path FILE = RuleTestSupport.FILE;

    private static RuleContext context(SqlSource.Kind kind, String sql) {
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, true,
                null, null, null, List.of(), true, List.of());
        return new RuleContext(changeSet, List.of(RuleTestSupport.unit(kind, sql)), List.of());
    }

    private static List<Rule.Violation> check(String body) {
        return new RoutineSelectStarRule().check(context(SqlSource.Kind.ROUTINE_BODY,
                "CREATE FUNCTION app.f() RETURNS void AS $$ " + body + " $$ LANGUAGE plpgsql;"));
    }

    @Test
    void reportsSelectStarForms() {
        assertEquals(1, check("BEGIN RETURN QUERY SELECT * FROM app.t; END;").size());
        assertEquals(1, check("BEGIN RETURN QUERY SELECT t.* FROM app.t t; END;").size());
        assertEquals(1, check("BEGIN RETURN QUERY SELECT DISTINCT * FROM app.t; END;").size());
        assertEquals(1, check("BEGIN RETURN QUERY SELECT a, t.* FROM app.t t; END;").size());
    }

    @Test
    void reportsAStarInANestedSubquery() {
        assertEquals(1, check("BEGIN RETURN QUERY SELECT a FROM (SELECT * FROM app.t) s; END;").size());
    }

    @Test
    void reportsQualifiedStarsInsideFunctionCalls() {
        assertEquals(1, check("BEGIN RETURN QUERY SELECT json_agg(t.*) FROM app.t t; END;").size());
        assertEquals(1, check("BEGIN RETURN QUERY SELECT array_agg(t.*) FROM app.t t; END;").size());
        assertEquals(1, check("BEGIN RETURN QUERY SELECT (t.*) FROM app.t t; END;").size());
    }

    @Test
    void stopsAFramlessSelectAtTheStatementEnd() {
        assertEquals(1, check("BEGIN SELECT current_setting('x') INTO l_v;"
                + " SELECT t.* FROM app.t t; END;").size());
    }

    @Test
    void ignoresStarsThatAreNotASelectListStar() {
        assertTrue(check("BEGIN RETURN QUERY SELECT count(*) FROM app.t; END;").isEmpty());
        assertTrue(check("BEGIN RETURN QUERY SELECT 'a * b' AS x; END;").isEmpty());
        assertTrue(check("BEGIN -- SELECT * FROM app.t\n RETURN 1; END;").isEmpty());
        assertTrue(check("BEGIN RETURN QUERY SELECT a * b FROM app.t; END;").isEmpty());
    }

    @Test
    void ignoresNonRoutineSources() {
        assertTrue(new RoutineSelectStarRule()
                .check(context(SqlSource.Kind.INLINE_SQL, "SELECT * FROM app.t;")).isEmpty());
        assertTrue(new RoutineSelectStarRule()
                .check(context(SqlSource.Kind.SQL_FILE, "SELECT * FROM app.t;")).isEmpty());
    }

    @Test
    void ignoresAnotherDatabase() {
        SqlUnit unit = RuleTestSupport.unit(
                "CREATE FUNCTION app.f() RETURNS void AS $$ BEGIN RETURN QUERY SELECT * FROM app.t; END; $$"
                        + " LANGUAGE plpgsql;", "oracle");
        RuleContext context = new RuleContext(RuleTestSupport.changeSet(true, true, null, false),
                List.of(unit), List.of());
        assertTrue(new RoutineSelectStarRule().check(context).isEmpty());
    }

    @Test
    void checksTheRollbackDirection() {
        RuleContext context = new RuleContext(RuleTestSupport.changeSet(true, true, null, false), List.of(),
                List.of(RuleTestSupport.unit(SqlSource.Kind.ROUTINE_BODY,
                        "CREATE FUNCTION app.f() RETURNS void AS $$ BEGIN RETURN QUERY SELECT * FROM app.t; END; $$"
                                + " LANGUAGE plpgsql;")));
        assertEquals(1, new RoutineSelectStarRule().check(context).size());
    }

    @Test
    void pointsAtTheStarToken() {
        Rule.Violation violation = new RoutineSelectStarRule().check(context(SqlSource.Kind.ROUTINE_BODY, """
                CREATE FUNCTION app.f() RETURNS void AS $$
                BEGIN
                  RETURN QUERY SELECT * FROM app.t;
                END;
                $$ LANGUAGE plpgsql;
                """)).getFirst();

        assertEquals(3, violation.line());
        assertEquals(23, violation.column());
        assertEquals(FILE, violation.file());
        assertEquals("SELECT *", violation.statement());
    }

    @Test
    void isAnErrorAndOnByDefault() {
        assertEquals(Severity.ERROR, new RoutineSelectStarRule().defaultSeverity());
        assertTrue(new RoutineSelectStarRule().enabledByDefault());
    }
}
