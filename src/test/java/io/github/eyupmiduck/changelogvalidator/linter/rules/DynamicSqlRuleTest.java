package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.*;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.lexer.SqlLexer;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangeSet;
import io.github.eyupmiduck.changelogvalidator.linter.model.SqlSource;
import io.github.eyupmiduck.changelogvalidator.linter.sql.SqlStatementSplitter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link DynamicSqlRule}: a routine body that uses {@code EXECUTE} is
 * reported, a body without dynamic SQL is accepted, and the rule is opt-in.
 */
class DynamicSqlRuleTest {

    private static final Path FILE = Path.of("/db/routines/foo.sql");

    @TempDir
    Path tempDir;

    private static SqlUnit routine(String sql) {
        return new SqlUnit(new SqlSource(SqlSource.Kind.ROUTINE_BODY, FILE, null, true, ";", false, null),
                FILE, sql, SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql));
    }

    private static RuleContext context(SqlUnit... units) {
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, true,
                null, null, null, List.of(), true, List.of());
        return new RuleContext(changeSet, List.of(units), List.of());
    }

    /**
     * A routine body that uses {@code EXECUTE} is reported at the EXECUTE token.
     */
    @Test
    void reportsDynamicSql() {
        List<Rule.Violation> violations = new DynamicSqlRule().check(context(routine(
                "CREATE FUNCTION foo() RETURNS void AS $$ BEGIN EXECUTE format('DROP TABLE %I', 't'); END; $$ LANGUAGE plpgsql;")));

        assertEquals(1, violations.size());
        Rule.Violation violation = violations.get(0);
        assertEquals("EXECUTE", violation.statement());
        assertTrue(violation.message().contains("static rules cannot see"));
        assertEquals(FILE, violation.file());
    }

    /**
     * A body without {@code EXECUTE} is accepted, and inline/file SQL is not
     * this rule's concern.
     */
    @Test
    void ignoresBodiesWithoutDynamicSql() {
        assertTrue(new DynamicSqlRule().check(context(routine(
                "CREATE FUNCTION foo() RETURNS int AS $$ BEGIN RETURN 1; END; $$ LANGUAGE plpgsql;"))).isEmpty());

        SqlUnit inline = new SqlUnit(new SqlSource(SqlSource.Kind.INLINE_SQL, null, "SELECT 'EXECUTE';", true, ";", false, null),
                Path.of("/db/changes.xml"), "SELECT 'EXECUTE';", SqlLexer.tokenize("SELECT 'EXECUTE';"),
                SqlStatementSplitter.split("SELECT 'EXECUTE';"));
        assertTrue(new DynamicSqlRule().check(context(inline)).isEmpty());
    }

    /**
     * The rule is opt-in, informational, and reports through the engine when
     * included.
     */
    @Test
    void isOptInAndInformational() throws IOException {
        assertEquals(Severity.INFO, new DynamicSqlRule().defaultSeverity());

        Path body = tempDir.resolve("foo.sql");
        Files.writeString(body, "CREATE FUNCTION foo() RETURNS void AS $$ BEGIN EXECUTE 'DROP TABLE t'; END; $$ LANGUAGE plpgsql;");
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, true, null, null, null,
                List.of(new SqlSource(SqlSource.Kind.ROUTINE_BODY, body, null, true, ";", false, null)),
                true, List.of());
        assertTrue(Linter.withRules(List.of(new DynamicSqlRule())).lint(List.of(changeSet)).isEmpty());

        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(), List.of(DynamicSqlRule.ID), Map.of());
        Linter linter = new Linter(List.of(new DynamicSqlRule()), config);
        assertEquals(1, linter.lint(List.of(changeSet)).size());
    }
}
