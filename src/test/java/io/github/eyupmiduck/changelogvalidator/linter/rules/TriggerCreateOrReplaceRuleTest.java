package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link TriggerCreateOrReplaceRule}: plain trigger creation is
 * reported, {@code OR REPLACE} is accepted, and the rule is version-gated to
 * PostgreSQL 14+.
 */
class TriggerCreateOrReplaceRuleTest {

    private static List<Rule.Violation> check(String sql) {
        return new TriggerCreateOrReplaceRule().check(RuleTestSupport.forwardOnly(sql));
    }

    /**
     * A plain CREATE TRIGGER is reported, while OR REPLACE is accepted, including
     * the CONSTRAINT trigger form.
     */
    @Test
    void reportsPlainCreateAndAcceptsOrReplace() {
        assertEquals(1, check("CREATE TRIGGER t BEFORE UPDATE ON x FOR EACH ROW EXECUTE FUNCTION f()").size());
        assertTrue(check("CREATE OR REPLACE TRIGGER t BEFORE UPDATE ON x FOR EACH ROW"
                + " EXECUTE FUNCTION f()").isEmpty());
        assertEquals(1, check("CREATE CONSTRAINT TRIGGER t AFTER INSERT ON x"
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f()").size());
        assertTrue(check("CREATE OR REPLACE CONSTRAINT TRIGGER t AFTER INSERT ON x"
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION f()").isEmpty());
        assertEquals(Severity.ERROR, new TriggerCreateOrReplaceRule().defaultSeverity());
    }

    /**
     * The rollback direction is checked too; a DROP TRIGGER is not a create.
     */
    @Test
    void checksRollbackAndIgnoresDrop() {
        assertEquals(1, new TriggerCreateOrReplaceRule()
                .check(RuleTestSupport.context(null,
                        "CREATE TRIGGER t BEFORE UPDATE ON x FOR EACH ROW EXECUTE FUNCTION f()")).size());
        assertTrue(new TriggerCreateOrReplaceRule()
                .check(RuleTestSupport.context("DROP TRIGGER IF EXISTS t ON x")).isEmpty());
    }

    /**
     * A body string that mentions CREATE TRIGGER is not a statement.
     */
    @Test
    void ignoresTriggerTextInAString() {
        assertTrue(check("COMMENT ON TABLE t IS 'CREATE TRIGGER t'").isEmpty());
    }

    /**
     * On PostgreSQL 13 the syntax is unavailable, so the rule is opt-in and
     * reports nothing.
     */
    @Test
    void isGatedToPostgres14() {
        TriggerCreateOrReplaceRule rule = new TriggerCreateOrReplaceRule(13);
        assertTrue(!rule.enabledByDefault());
        assertTrue(rule.check(RuleTestSupport.forwardOnly(
                "CREATE TRIGGER t BEFORE UPDATE ON x FOR EACH ROW EXECUTE FUNCTION f()")).isEmpty());
        assertTrue(new TriggerCreateOrReplaceRule(17).enabledByDefault());
    }
}
