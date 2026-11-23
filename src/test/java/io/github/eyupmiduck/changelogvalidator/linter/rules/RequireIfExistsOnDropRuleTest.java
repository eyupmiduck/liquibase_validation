package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RequireIfExistsOnDropRule}: each supported DROP without
 * IF EXISTS is reported, IF EXISTS is accepted, and the rollback direction is
 * checked too.
 */
class RequireIfExistsOnDropRuleTest {

    private static List<Rule.Violation> check(String sql) {
        return new RequireIfExistsOnDropRule().check(RuleTestSupport.forwardOnly(sql));
    }

    /**
     * Every supported object kind is reported without IF EXISTS and accepted with
     * it; DROP INDEX also handles its CONCURRENTLY position.
     */
    @Test
    void reportsEachDropKindWithoutIfExists() {
        for (String sql : List.of(
                "DROP TABLE t",
                "DROP FUNCTION f()",
                "DROP PROCEDURE p()",
                "DROP TRIGGER tr ON t",
                "DROP INDEX i",
                "DROP INDEX CONCURRENTLY i")) {
            assertEquals(1, check(sql).size(), sql);
        }
        for (String sql : List.of(
                "DROP TABLE IF EXISTS t",
                "DROP FUNCTION IF EXISTS f()",
                "DROP PROCEDURE IF EXISTS p()",
                "DROP TRIGGER IF EXISTS tr ON t",
                "DROP INDEX IF EXISTS i",
                "DROP INDEX CONCURRENTLY IF EXISTS i")) {
            assertTrue(check(sql).isEmpty(), sql);
        }
        assertEquals(Severity.WARNING, new RequireIfExistsOnDropRule().defaultSeverity());
        assertTrue(!new RequireIfExistsOnDropRule().enabledByDefault());
    }

    /**
     * A different DROP variant is not this rule's concern.
     */
    @Test
    void ignoresUnsupportedDropForms() {
        assertTrue(check("DROP OWNED BY someone").isEmpty());
        assertTrue(check("DROP SCHEMA s").isEmpty());
    }

    /**
     * The rollback direction is checked.
     */
    @Test
    void checksRollback() {
        RuleContext context = RuleTestSupport.context(null, "DROP TABLE t");
        assertEquals(1, new RequireIfExistsOnDropRule().check(context).size());
    }

    /**
     * A source scoped to another database is ignored.
     */
    @Test
    void ignoresAnotherDatabase() {
        RuleContext context = new RuleContext(
                RuleTestSupport.changeSet(true, false, null, false),
                List.of(RuleTestSupport.unit("DROP TABLE t", "oracle")),
                List.of());
        assertTrue(new RequireIfExistsOnDropRule().check(context).isEmpty());
    }
}
