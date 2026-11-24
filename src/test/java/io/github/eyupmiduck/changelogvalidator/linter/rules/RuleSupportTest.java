package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.Token;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.TokenWords;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the shared rule helpers, especially the bounds and fallbacks that
 * keep a malformed input from throwing inside a rule.
 */
class RuleSupportTest {

    private static List<Token> words(String sql) {
        return TokenWords.within(io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer.tokenize(sql), 0, sql.length());
    }

    /**
     * A negative start or a start past the end yields the documented null rather
     * than reaching an invalid list index.
     */
    @Test
    void qualifiedNameRejectsOutOfRangeStarts() {
        List<Token> words = words("schema.table");

        assertEquals("schema.table", RuleSupport.qualifiedName(words, 0));
        assertNull(RuleSupport.qualifiedName(words, -1));
        assertNull(RuleSupport.qualifiedName(words, words.size()));
    }

    /**
     * A statement with no token in its span falls back to the unit's first
     * non-trivia token, not to leading whitespace.
     */
    @Test
    void firstTokenFallsBackToTheFirstMeaningfulToken() {
        SqlUnit unit = RuleTestSupport.unit("  SELECT 1");

        Token token = RuleSupport.firstToken(unit, new SqlStatement("", 0, 0));

        assertTrue(token.matchesKeyword("SELECT"), () -> "got " + token);
    }

    /**
     * A unit with no non-trivia token cannot supply a location, so the helper
     * fails clearly instead of throwing an index error.
     */
    @Test
    void firstTokenRejectsAnEmptyUnit() {
        SqlUnit unit = RuleTestSupport.unit("");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RuleSupport.firstToken(unit, new SqlStatement("", 0, 0)));
        assertTrue(error.getMessage().contains("no token"));
    }

    /**
     * A leading comment in a statement's span is not a location; the first
     * meaningful token is reported instead.
     */
    @Test
    void firstTokenSkipsLeadingCommentTrivia() {
        SqlUnit unit = RuleTestSupport.unit("-- note\nSELECT 1");

        Token token = RuleSupport.firstToken(unit, new SqlStatement("", 0, unit.sql().length()));

        assertTrue(token.matchesKeyword("SELECT"), () -> "got " + token);
    }

    /**
     * The simple-name fallback only matches a qualified name with an unqualified
     * one; two different schemas never match. Quoted identifiers keep their case.
     */
    @Test
    void containsTableOnlyFallsBackWhenOneNameIsUnqualified() {
        assertFalse(RuleSupport.containsTable(java.util.Set.of("analytics.events"), "app.events"));
        assertFalse(RuleSupport.containsTable(java.util.Set.of("app.events"), "analytics.events"));
        assertTrue(RuleSupport.containsTable(java.util.Set.of("app.events"), "events"));
        assertTrue(RuleSupport.containsTable(java.util.Set.of("events"), "app.events"));
        assertFalse(RuleSupport.containsTable(java.util.Set.of("Foo"), "foo"));
        assertTrue(RuleSupport.containsTable(java.util.Set.of("Foo"), "Foo"));
    }

    /**
     * A {@code CREATE TABLE} modifier is recognised, so a temporary or unlogged
     * table is not missed.
     */
    @Test
    void createdTableAtAcceptsCreateModifiers() {
        assertEquals("t", RuleSupport.createdTableAt(words("CREATE UNLOGGED TABLE t (id int)"), 0));
        assertEquals("t", RuleSupport.createdTableAt(words("CREATE GLOBAL TEMPORARY TABLE t (id int)"), 0));
        assertEquals("t", RuleSupport.createdTableAt(words("CREATE LOCAL TEMP TABLE t (id int)"), 0));
        assertEquals("t", RuleSupport.createdTableAt(words("CREATE TEMPORARY TABLE IF NOT EXISTS t (id int)"), 0));
        assertNull(RuleSupport.createdTableAt(words("CREATE INDEX i ON t (id)"), 0));
    }
}
