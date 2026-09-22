package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Linter;
import io.github.eyupmiduck.changelogvalidator.linter.Rule;
import io.github.eyupmiduck.changelogvalidator.linter.RuleContext;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.SqlUnit;
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
 * Verifies {@link BlockRawAlterTableRule}: a raw {@code ALTER TABLE} is reported
 * with the table and a suggested wrapper, non-ALTER statements and routine bodies
 * are accepted, and the rule is an error by default.
 */
class BlockRawAlterTableRuleTest {

    private static final Path FILE = Path.of("/db/sql.sql");

    private static SqlUnit unit(SqlSource.Kind kind, String sql) {
        return new SqlUnit(new SqlSource(kind, kind == SqlSource.Kind.ROUTINE_BODY ? FILE : null,
                kind == SqlSource.Kind.ROUTINE_BODY ? null : sql, true, ";", true, null),
                FILE, sql, SqlLexer.tokenize(sql), SqlStatementSplitter.split(sql));
    }

    private static RuleContext context(SqlUnit unit) {
        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null, List.of(), true, List.of());
        return new RuleContext(changeSet, List.of(unit), List.of());
    }

    private static List<Rule.Violation> check(String sql) {
        return new BlockRawAlterTableRule().check(context(unit(SqlSource.Kind.INLINE_SQL, sql)));
    }

    /**
     * A raw {@code ALTER TABLE ... ADD COLUMN} names the table and points at the
     * add-column wrapper.
     */
    @Test
    void reportsAddColumn() {
        List<Rule.Violation> violations = check("ALTER TABLE ddl_utils.example ADD COLUMN c int;");

        assertEquals(1, violations.size());
        Rule.Violation violation = violations.get(0);
        assertEquals("ALTER TABLE", violation.statement());
        assertTrue(violation.message().contains("raw ALTER TABLE on ddl_utils.example"));
        assertTrue(violation.message().contains("ddl_utils.add_column"));
        assertEquals(FILE, violation.file());
        assertEquals(1, violation.line());
        assertEquals(1, violation.column());
    }

    /**
     * The suggested wrapper tracks the clause: DROP NOT NULL, SET NOT NULL,
     * foreign key, check, drop column/constraint, rename, and a generic fallback.
     */
    @Test
    void suggestsTheMatchingWrapper() {
        assertTrue(check("ALTER TABLE t ALTER COLUMN c DROP NOT NULL;").get(0).message().contains("ddl_utils.drop_not_null"));
        assertTrue(check("ALTER TABLE t ALTER COLUMN c SET NOT NULL;").get(0).message().contains("ddl_utils.ensure_not_null"));
        assertTrue(check("ALTER TABLE t ADD CONSTRAINT fk FOREIGN KEY (c) REFERENCES u (id);")
                .get(0).message().contains("ddl_utils.add_foreign_key"));
        assertTrue(check("ALTER TABLE t ADD CONSTRAINT ck CHECK (c > 0);").get(0).message().contains("ddl_utils.add_check_constraint"));
        assertTrue(check("ALTER TABLE t DROP COLUMN c;").get(0).message().contains("ddl_utils.drop_column"));
        assertTrue(check("ALTER TABLE t DROP CONSTRAINT ck;").get(0).message().contains("ddl_utils.drop_constraint"));
        assertTrue(check("ALTER TABLE t RENAME COLUMN a TO b;").get(0).message().contains("ddl_utils.rename_column"));
        assertTrue(check("ALTER TABLE t RENAME TO t2;").get(0).message().contains("ddl_utils.rename_table"));
        assertTrue(check("ALTER TABLE t SET (fillfactor = 70);").get(0).message().contains("matching ddl_utils"));
    }

    /**
     * A schema-qualified and a quoted table name are described by their
     * normalized parts.
     */
    @Test
    void normalizesTheTableName() {
        assertTrue(check("ALTER TABLE Public.Example ADD COLUMN c int;")
                .get(0).message().contains("on public.example"));
        assertTrue(check("ALTER TABLE \"Example\" ADD COLUMN c int;")
                .get(0).message().contains("on Example"));
    }

    /**
     * A statement that is not an {@code ALTER TABLE}, and {@code ALTER} of
     * something else, are accepted.
     */
    @Test
    void ignoresNonAlterTableStatements() {
        assertTrue(check("CREATE TABLE t (c int);").isEmpty());
        assertTrue(check("ALTER INDEX idx RENAME TO idx2;").isEmpty());
    }

    /**
     * A routine body that itself runs {@code ALTER TABLE} is the wrapper's
     * implementation, not a caller's raw statement, so it is exempt.
     */
    @Test
    void ignoresRoutineBodies() {
        List<Rule.Violation> forward = new BlockRawAlterTableRule().check(context(unit(
                SqlSource.Kind.ROUTINE_BODY,
                "CREATE FUNCTION foo() RETURNS void AS $$ BEGIN ALTER TABLE t ADD COLUMN c int; END; $$ LANGUAGE plpgsql;")));

        assertTrue(forward.isEmpty());
    }

    /**
     * Every {@code ALTER TABLE} in a multi-statement changeset is reported, and
     * a raw rollback {@code ALTER TABLE} is reported too.
     */
    @Test
    void reportsEachStatementAndRollback() {
        assertEquals(2, check("ALTER TABLE t ADD COLUMN a int; ALTER TABLE t ADD COLUMN b int;").size());

        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false,
                null, null, null, List.of(), true, List.of());
        RuleContext context = new RuleContext(changeSet, List.of(),
                List.of(unit(SqlSource.Kind.INLINE_SQL, "ALTER TABLE t DROP COLUMN c;")));
        assertEquals(1, new BlockRawAlterTableRule().check(context).size());
    }

    /**
     * The rule is an error by default and reports through the engine.
     */
    @Test
    void isAnErrorByDefault() throws Exception {
        assertEquals(Severity.ERROR, new BlockRawAlterTableRule().defaultSeverity());

        ChangeSet changeSet = new ChangeSet("cs-1", "me", Path.of("/changelog.xml"), true, false, null, null, null,
                List.of(new SqlSource(SqlSource.Kind.INLINE_SQL, null, "ALTER TABLE t ADD COLUMN c int;", true, ";", true, null)),
                true, List.of());
        List<Finding> findings = Linter.withRules(List.of(new BlockRawAlterTableRule())).lint(List.of(changeSet));

        assertEquals(1, findings.size());
        assertEquals(BlockRawAlterTableRule.ID, findings.get(0).ruleId());
        assertEquals(Severity.ERROR, findings.get(0).severity());
    }
}
