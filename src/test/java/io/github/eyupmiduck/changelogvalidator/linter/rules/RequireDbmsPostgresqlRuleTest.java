package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.*;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RequireDbmsPostgresqlRule}: PostgreSQL-only syntax without a
 * {@code dbms="postgresql"} gate is reported, a gate (changeset- or
 * source-level) accepts it, portable SQL is ignored, and the rule is an error by
 * default.
 */
class RequireDbmsPostgresqlRuleTest {

    private static final Path FILE = Path.of("/db/sql.sql");

    private static RuleContext context(String dbms, String forwardSql) {
        SqlUnit unit = new SqlUnit(new SqlSource(SqlSource.Kind.INLINE_SQL, null, forwardSql, true, ";", true, null),
                FILE, forwardSql, SqlLexer.tokenize(forwardSql), SqlStatementSplitter.split(forwardSql));
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                dbms, null, null, List.of(), true, List.of());
        return new RuleContext(changeSet, List.of(unit), List.of());
    }

    private static List<Rule.Violation> check(String dbms, String sql) {
        return new RequireDbmsPostgresqlRule().check(context(dbms, sql));
    }

    /**
     * A {@code CONCURRENTLY} statement without a dbms gate is reported.
     */
    @Test
    void reportsConcurrentlyWithoutAGate() {
        List<Rule.Violation> violations = check(null, "CREATE INDEX CONCURRENTLY idx ON t (c);");

        assertEquals(1, violations.size());
        assertEquals("CONCURRENTLY", violations.get(0).statement());
        assertTrue(violations.get(0).message().contains("dbms=\"postgresql\""));
    }

    /**
     * Each PostgreSQL-only construct is reported when ungated and accepted when
     * the changeset sets {@code dbms="postgresql"} (case-insensitive or in a
     * list).
     */
    @Test
    void reportsEachConstructAndAcceptsTheGate() {
        assertTrue(!check(null, "CREATE DOMAIN positive_int AS int CHECK (VALUE > 0);").isEmpty());
        assertTrue(!check(null, "CREATE EXTENSION pg_trgm;").isEmpty());
        assertTrue(!check(null, "CREATE FUNCTION f() RETURNS void AS $$ BEGIN END; $$ LANGUAGE plpgsql;").isEmpty());
        assertTrue(!check(null, "CREATE INDEX idx ON t USING gin (tags);").isEmpty());

        for (String sql : List.of(
                "CREATE INDEX CONCURRENTLY idx ON t (c);",
                "CREATE DOMAIN positive_int AS int CHECK (VALUE > 0);",
                "CREATE EXTENSION pg_trgm;",
                "CREATE FUNCTION f() RETURNS void AS $$ BEGIN END; $$ LANGUAGE plpgsql;")) {
            assertTrue(check("postgresql", sql).isEmpty(), () -> "gated but reported: " + sql);
            assertTrue(check("PostgreSQL", sql).isEmpty());
            assertTrue(check("mysql, postgresql", sql).isEmpty());
        }
    }

    /**
     * A PostgreSQL-only cast or operator is reported, but portable SQL is not.
     */
    @Test
    void reportsPostgresOperators() {
        assertEquals(1, check(null, "SELECT '1'::int;").size());
        assertEquals(1, check(null, "SELECT data -> 'k' FROM t;").size());
        assertEquals(1, check(null, "SELECT tags @> ARRAY['a'] FROM t;").size());

        assertTrue(check(null, "CREATE TABLE t (c int);").isEmpty());
        assertTrue(check(null, "SELECT 1;").isEmpty());
        assertTrue(check("oracle", "CREATE TABLE t (c int);").isEmpty());
    }

    /**
     * A keyword inside a string literal or comment does not trigger the rule.
     */
    @Test
    void ignoresMentionsInStringsAndComments() {
        assertTrue(check(null, "-- CREATE INDEX CONCURRENTLY idx ON t (c);\nSELECT 1;").isEmpty());
        assertTrue(check(null, "SELECT 'CONCURRENTLY';").isEmpty());
    }

    /**
     * A source-level dbms gate accepts a PostgreSQL-only source even when the
     * changeset does not set dbms.
     */
    @Test
    void acceptsASourceLevelGate() {
        String sql = "CREATE INDEX CONCURRENTLY idx ON t (c);";
        SqlUnit unit = new SqlUnit(
                new SqlSource(SqlSource.Kind.SQL_FILE, FILE, null, true, ";", true, "postgresql"),
                FILE, sql, SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql));
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null, List.of(), true, List.of());

        assertTrue(new RequireDbmsPostgresqlRule().check(new RuleContext(changeSet, List.of(unit), List.of())).isEmpty());
    }

    /**
     * An ordinary identifier named {@code concurrently} is not PostgreSQL-only
     * syntax, while a real concurrent index build and the lone {@code ?} and
     * {@code #>>} operators are recognised.
     */
    @Test
    void requiresPostgresContextForConcurrently() {
        assertTrue(check(null, "SELECT concurrently FROM t;").isEmpty());
        assertTrue(check(null, "CREATE INDEX idx ON t (concurrently);").isEmpty());

        assertEquals(1, check(null, "SELECT data ? 'k' FROM t;").size());
        assertTrue(check(null, "SELECT data #>> '{a}' FROM t;").get(0).message().contains("#>>"));
    }

    /**
     * The rule is an error by default and reports through the engine.
     */
    @Test
    void isAnErrorByDefault() throws Exception {
        assertEquals(Severity.ERROR, new RequireDbmsPostgresqlRule().defaultSeverity());

        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false, null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, "CREATE INDEX CONCURRENTLY idx ON t (c);", true, ";", true, null)),
                true, List.of());
        List<Finding> findings = Linter.withRules(List.of(new RequireDbmsPostgresqlRule())).lint(List.of(changeSet));

        assertEquals(1, findings.size());
        assertEquals(RequireDbmsPostgresqlRule.ID, findings.get(0).ruleId());
        assertEquals(Severity.ERROR, findings.get(0).severity());
    }
}
