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
 * Verifies {@link RoutineTransactionControlRule}: a function body that uses
 * {@code COMMIT} or {@code ROLLBACK} is reported, while a procedure body, a
 * comment or a string is not.
 */
class RoutineTransactionControlRuleTest {

    private static final Path FILE = RuleTestSupport.FILE;

    private static RuleContext context(SqlSource.Kind kind, String sql) {
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, true,
                null, null, null, List.of(), true, List.of());
        return new RuleContext(changeSet, List.of(RuleTestSupport.unit(kind, sql)), List.of());
    }

    private static List<Rule.Violation> checkFunction(String body) {
        return new RoutineTransactionControlRule().check(context(SqlSource.Kind.ROUTINE_BODY,
                "CREATE FUNCTION app.f() RETURNS void AS $$ " + body + " $$ LANGUAGE plpgsql;"));
    }

    @Test
    void reportsCommitAndRollbackInAFunction() {
        assertEquals(1, checkFunction("BEGIN COMMIT; END;").size());
        assertEquals(1, checkFunction("BEGIN ROLLBACK; END;").size());
        assertEquals(2, checkFunction("BEGIN COMMIT; ROLLBACK; END;").size());
    }

    @Test
    void ignoresAProcedureBody() {
        assertTrue(new RoutineTransactionControlRule().check(context(SqlSource.Kind.ROUTINE_BODY,
                "CREATE PROCEDURE app.p() LANGUAGE plpgsql AS $$ BEGIN COMMIT; END; $$;")).isEmpty());
    }

    @Test
    void ignoresCommentsAndStrings() {
        assertTrue(checkFunction("BEGIN -- COMMIT\n PERFORM 'ROLLBACK'; END;").isEmpty());
    }

    @Test
    void ignoresNonRoutineSourcesAndAnotherDatabase() {
        assertTrue(new RoutineTransactionControlRule()
                .check(context(SqlSource.Kind.INLINE_SQL, "COMMIT;")).isEmpty());

        SqlUnit unit = RuleTestSupport.unit(
                "CREATE FUNCTION app.f() RETURNS void AS $$ BEGIN COMMIT; END; $$ LANGUAGE plpgsql;", "oracle");
        RuleContext context = new RuleContext(RuleTestSupport.changeSet(true, true, null, false),
                List.of(unit), List.of());
        assertTrue(new RoutineTransactionControlRule().check(context).isEmpty());
    }

    @Test
    void checksTheRollbackDirection() {
        RuleContext context = new RuleContext(RuleTestSupport.changeSet(true, true, null, false), List.of(),
                List.of(RuleTestSupport.unit(SqlSource.Kind.ROUTINE_BODY,
                        "CREATE FUNCTION app.f() RETURNS void AS $$ BEGIN ROLLBACK; END; $$ LANGUAGE plpgsql;")));
        assertEquals(1, new RoutineTransactionControlRule().check(context).size());
    }

    @Test
    void pointsAtTheTransactionStatement() {
        Rule.Violation violation = new RoutineTransactionControlRule().check(context(SqlSource.Kind.ROUTINE_BODY, """
                CREATE FUNCTION app.f() RETURNS void AS $$
                BEGIN
                  COMMIT;
                END;
                $$ LANGUAGE plpgsql;
                """)).getFirst();

        assertEquals(3, violation.line());
        assertEquals(3, violation.column());
        assertEquals(FILE, violation.file());
        assertEquals("COMMIT", violation.statement());
    }

    @Test
    void isAnErrorAndOnByDefault() {
        assertEquals(Severity.ERROR, new RoutineTransactionControlRule().defaultSeverity());
        assertTrue(new RoutineTransactionControlRule().enabledByDefault());
    }
}
