package io.github.eyupmiduck.changelogvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ChangelogLayoutCheck}: the changeset id, the forward and
 * rollback file names, and the routine or trigger declaration inside the file
 * must agree with the convention.
 */
class ChangelogLayoutCheckTest {

    private static final String XML_HEADER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
            """;

    private static final String MASTER = "db.changelog-master.xml";
    private static final String CHANGES = "changes/changes.xml";

    @TempDir
    Path root;

    private static String sqlChangeSet(String id, String forward, String rollback) {
        return """
                <changeSet id="%s" author="test">
                    <sqlFile path="%s" relativeToChangelogFile="true"/>
                    <rollback>
                        <sqlFile path="%s" relativeToChangelogFile="true"/>
                    </rollback>
                </changeSet>
                """.formatted(id, forward, rollback);
    }

    private static String procedureChangeSet(String id, String forward, String rollback) {
        return """
                <changeSet id="%s" author="test" runOnChange="true">
                    <createProcedure path="%s" relativeToChangelogFile="true"/>
                    <rollback>
                        <sqlFile path="%s" relativeToChangelogFile="true"/>
                    </rollback>
                </changeSet>
                """.formatted(id, forward, rollback);
    }

    private static String triggerChangeSet(String id, String forward, String rollback) {
        return """
                <changeSet id="%s" author="test" runOnChange="true">
                    <sqlFile path="%s" relativeToChangelogFile="true"/>
                    <rollback>
                        <sqlFile path="%s" relativeToChangelogFile="true"/>
                    </rollback>
                </changeSet>
                """.formatted(id, forward, rollback);
    }

    private void project(String changeSets) throws IOException {
        write(MASTER, XML_HEADER + """
                <include file="changes/changes.xml" relativeToChangelogFile="true"/>
                </databaseChangeLog>
                """);
        write(CHANGES, XML_HEADER + changeSets + "</databaseChangeLog>\n");
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<ChangelogLayoutCheck.Violation> violations(boolean rollbackRequired) throws IOException {
        return ChangelogLayoutCheck.findViolations(root, root.resolve(MASTER), rollbackRequired);
    }

    private List<ChangelogLayoutCheck.Violation> violations() throws IOException {
        return violations(true);
    }

    /**
     * A numbered changeset, a function, a procedure and a trigger in the
     * conventional layout produce no violations; quoted routine identifiers are
     * accepted too.
     */
    @Test
    void acceptsTheConventionalLayout() throws IOException {
        project(
                sqlChangeSet("001-create", "sql_changes/001-create.sql", "rollback/001-create-rollback.sql")
                        + procedureChangeSet("function-app.foo",
                        "functions/app/foo.sql", "functions-rollback/app/foo-rollback.sql")
                        + procedureChangeSet("function-app.quoted",
                        "functions/app/quoted.sql", "functions-rollback/app/quoted-rollback.sql")
                        + procedureChangeSet("procedure-app.bar",
                        "procedures/app/bar.sql", "procedures-rollback/app/bar-rollback.sql")
                        + triggerChangeSet("trigger-app.foo_trg",
                        "triggers/app/foo_trg.sql", "triggers-rollback/app/foo_trg-rollback.sql"));
        write("changes/sql_changes/001-create.sql", "SELECT 1;\n");
        write("changes/rollback/001-create-rollback.sql", "SELECT 1;\n");
        write("changes/functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");
        write("changes/functions/app/quoted.sql",
                "CREATE OR REPLACE FUNCTION \"app\".\"quoted\"() RETURNS integer"
                        + " LANGUAGE sql AS 'SELECT 1';\n");
        write("changes/functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");
        write("changes/functions-rollback/app/quoted-rollback.sql", "DROP FUNCTION IF EXISTS app.quoted();\n");
        write("changes/procedures/app/bar.sql", "CREATE OR REPLACE PROCEDURE app.bar()"
                + " LANGUAGE plpgsql AS $$ BEGIN NULL; END; $$;\n");
        write("changes/procedures-rollback/app/bar-rollback.sql", "DROP PROCEDURE IF EXISTS app.bar();\n");
        write("changes/triggers/app/foo_trg.sql", """
                CREATE OR REPLACE TRIGGER foo_trg
                    BEFORE UPDATE ON app.foo
                    FOR EACH ROW
                EXECUTE FUNCTION app.set_updated_at();
                """);
        write("changes/triggers-rollback/app/foo_trg-rollback.sql",
                "DROP TRIGGER IF EXISTS foo_trg ON app.foo;\n");

        assertEquals(List.of(), violations());
    }

    /**
     * A numbered changeset's forward file must be named after the changeset.
     */
    @Test
    void reportsANumberedForwardFileNameMismatch() throws IOException {
        project(sqlChangeSet("001-create", "sql_changes/001-other.sql", "rollback/001-create-rollback.sql"));
        write("changes/sql_changes/001-other.sql", "SELECT 1;\n");
        write("changes/rollback/001-create-rollback.sql", "SELECT 1;\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("sql_changes/001-create.sql"),
                violations.getFirst().message());
    }

    /**
     * A sibling directory whose name merely ends with the conventional one is
     * not the conventional directory.
     */
    @Test
    void reportsASiblingDirectoryThatOnlyEndsWithTheConventionalName() throws IOException {
        project(sqlChangeSet("001-create", "legacy_sql_changes/001-create.sql",
                "rollback/001-create-rollback.sql"));
        write("changes/legacy_sql_changes/001-create.sql", "SELECT 1;\n");
        write("changes/rollback/001-create-rollback.sql", "SELECT 1;\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("does not match sql_changes/001-create.sql"),
                violations.getFirst().message());
    }

    /**
     * A numbered changeset's rollback file must be named after the changeset.
     */
    @Test
    void reportsANumberedRollbackFileNameMismatch() throws IOException {
        project(sqlChangeSet("001-create", "sql_changes/001-create.sql", "rollback/001-create-undo.sql"));
        write("changes/sql_changes/001-create.sql", "SELECT 1;\n");
        write("changes/rollback/001-create-undo.sql", "SELECT 1;\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("rollback/001-create-rollback.sql"),
                violations.getFirst().message());
    }

    /**
     * A file-based changeset without a rollback file fails when rollbacks are
     * required.
     */
    @Test
    void reportsAMissingRollbackFileWhenRequired() throws IOException {
        project("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="sql_changes/001-create.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """);
        write("changes/sql_changes/001-create.sql", "SELECT 1;\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("no matching rollback"),
                violations.getFirst().message());
    }

    /**
     * The same changeset is accepted when the project does not require a
     * rollback file.
     */
    @Test
    void acceptsAMissingRollbackFileWhenNotRequired() throws IOException {
        project("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="sql_changes/001-create.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """);
        write("changes/sql_changes/001-create.sql", "SELECT 1;\n");

        assertEquals(List.of(), violations(false));
    }

    /**
     * A rollback file with no forward file is reported.
     */
    @Test
    void reportsARollbackFileWithoutAForwardFile() throws IOException {
        project("""
                <changeSet id="001-create" author="test">
                    <rollback>
                        <sqlFile path="rollback/001-create-rollback.sql" relativeToChangelogFile="true"/>
                    </rollback>
                </changeSet>
                """);
        write("changes/rollback/001-create-rollback.sql", "SELECT 1;\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("no forward SQL file"),
                violations.getFirst().message());
    }

    /**
     * A routine changeset's path must put the file in the schema/name directory
     * its id names.
     */
    @Test
    void reportsARoutineForwardPathMismatch() throws IOException {
        project(procedureChangeSet("function-app.foo",
                "functions/app/bar.sql", "functions-rollback/app/foo-rollback.sql"));
        write("changes/functions/app/bar.sql",
                "CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");
        write("changes/functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("functions/app/foo.sql"),
                violations.getFirst().message());
    }

    /**
     * A routine rollback path must match the id too.
     */
    @Test
    void reportsARoutineRollbackPathMismatch() throws IOException {
        project(procedureChangeSet("function-app.foo",
                "functions/app/foo.sql", "functions-rollback/app/bar-rollback.sql"));
        write("changes/functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");
        write("changes/functions-rollback/app/bar-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("functions-rollback/app/foo-rollback.sql"),
                violations.getFirst().message());
    }

    /**
     * The routine file must declare the object the path names.
     */
    @Test
    void reportsARoutineDeclarationMismatch() throws IOException {
        project(procedureChangeSet("function-app.foo",
                "functions/app/foo.sql", "functions-rollback/app/foo-rollback.sql"));
        write("changes/functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION app.bar() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");
        write("changes/functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("declares app.bar"),
                violations.getFirst().message());
    }

    /**
     * An unqualified routine declaration is reported.
     */
    @Test
    void reportsAnUnqualifiedRoutineDeclaration() throws IOException {
        project(procedureChangeSet("function-app.foo",
                "functions/app/foo.sql", "functions-rollback/app/foo-rollback.sql"));
        write("changes/functions/app/foo.sql",
                "CREATE FUNCTION foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");
        write("changes/functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("not schema-qualified"),
                violations.getFirst().message());
    }

    /**
     * A routine file with the wrong stored-object kind is reported.
     */
    @Test
    void reportsAWrongRoutineKind() throws IOException {
        project(procedureChangeSet("function-app.foo",
                "functions/app/foo.sql", "functions-rollback/app/foo-rollback.sql"));
        write("changes/functions/app/foo.sql",
                "CREATE OR REPLACE PROCEDURE app.foo() LANGUAGE plpgsql AS $$ BEGIN NULL; END; $$;\n");
        write("changes/functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("declares a procedure"),
                violations.getFirst().message());
    }

    /**
     * A routine file with more than one declaration is reported.
     */
    @Test
    void reportsMoreThanOneRoutineDeclaration() throws IOException {
        project(procedureChangeSet("function-app.foo",
                "functions/app/foo.sql", "functions-rollback/app/foo-rollback.sql"));
        write("changes/functions/app/foo.sql", """
                CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';
                CREATE OR REPLACE FUNCTION app.bar() RETURNS integer LANGUAGE sql AS 'SELECT 2';
                """);
        write("changes/functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("declares 2 stored objects"),
                violations.getFirst().message());
    }

    /**
     * A constraint trigger is a valid trigger form and declares the path's
     * trigger.
     */
    @Test
    void acceptsAConstraintTriggerDeclaration() throws IOException {
        project(triggerChangeSet("trigger-app.foo_trg",
                "triggers/app/foo_trg.sql", "triggers-rollback/app/foo_trg-rollback.sql"));
        write("changes/triggers/app/foo_trg.sql", """
                CREATE CONSTRAINT TRIGGER foo_trg
                    AFTER UPDATE ON app.foo
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW
                EXECUTE FUNCTION app.set_updated_at();
                """);
        write("changes/triggers-rollback/app/foo_trg-rollback.sql",
                "DROP TRIGGER IF EXISTS foo_trg ON app.foo;\n");

        assertEquals(List.of(), violations());
    }

    /**
     * A trigger on a table in another schema than the path is reported.
     */
    @Test
    void reportsATriggerOnTheWrongSchema() throws IOException {
        project(triggerChangeSet("trigger-app.foo_trg",
                "triggers/app/foo_trg.sql", "triggers-rollback/app/foo_trg-rollback.sql"));
        write("changes/triggers/app/foo_trg.sql", """
                CREATE OR REPLACE TRIGGER foo_trg
                    BEFORE UPDATE ON other.foo
                    FOR EACH ROW
                EXECUTE FUNCTION app.set_updated_at();
                """);
        write("changes/triggers-rollback/app/foo_trg-rollback.sql", "DROP TRIGGER IF EXISTS foo_trg ON app.foo;\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("trigger is on schema other"),
                violations.getFirst().message());
    }

    /**
     * A trigger whose table is unqualified is reported.
     */
    @Test
    void reportsATriggerWithoutASchemaQualifiedTable() throws IOException {
        project(triggerChangeSet("trigger-app.foo_trg",
                "triggers/app/foo_trg.sql", "triggers-rollback/app/foo_trg-rollback.sql"));
        write("changes/triggers/app/foo_trg.sql", """
                CREATE OR REPLACE TRIGGER foo_trg
                    BEFORE UPDATE ON foo
                    FOR EACH ROW
                EXECUTE FUNCTION app.set_updated_at();
                """);
        write("changes/triggers-rollback/app/foo_trg-rollback.sql", "DROP TRIGGER IF EXISTS foo_trg ON app.foo;\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("not schema-qualified"),
                violations.getFirst().message());
    }

    /**
     * A trigger file with no ON clause is reported as an unqualified table.
     */
    @Test
    void reportsATriggerWithoutAnOnClause() throws IOException {
        project(triggerChangeSet("trigger-app.foo_trg",
                "triggers/app/foo_trg.sql", "triggers-rollback/app/foo_trg-rollback.sql"));
        write("changes/triggers/app/foo_trg.sql", "CREATE OR REPLACE TRIGGER foo_trg\n");
        write("changes/triggers-rollback/app/foo_trg-rollback.sql", "DROP TRIGGER IF EXISTS foo_trg ON app.foo;\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("not schema-qualified"),
                violations.getFirst().message());
    }

    /**
     * The ON-table lookup is bounded to the trigger's own statement, so an ON
     * in a later GRANT/COMMENT/INDEX statement does not bind to the trigger.
     */
    @Test
    void doesNotBindATriggersOnToALaterStatement() throws IOException {
        project(triggerChangeSet("trigger-app.foo_trg",
                "triggers/app/foo_trg.sql", "triggers-rollback/app/foo_trg-rollback.sql"));
        write("changes/triggers/app/foo_trg.sql", """
                CREATE OR REPLACE TRIGGER foo_trg
                    BEFORE UPDATE
                    FOR EACH ROW
                EXECUTE FUNCTION app.set_updated_at();
                GRANT EXECUTE ON app.foo TO app_writer;
                """);
        write("changes/triggers-rollback/app/foo_trg-rollback.sql", "DROP TRIGGER IF EXISTS foo_trg ON app.foo;\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("not schema-qualified"),
                violations.getFirst().message());
    }

    /**
     * A quoted identifier keeps its case in PostgreSQL, so a quoted declaration
     * that differs only in case from the path is reported.
     */
    @Test
    void reportsAQuotedDeclarationThatDiffersOnlyInCase() throws IOException {
        project(procedureChangeSet("function-app.foo",
                "functions/app/foo.sql", "functions-rollback/app/foo-rollback.sql"));
        write("changes/functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION app.\"Foo\"() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");
        write("changes/functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("declares app.Foo"),
                violations.getFirst().message());
    }

    /**
     * An unquoted identifier folds to lower case in PostgreSQL, so a declaration
     * that differs only in case still names the path's object.
     */
    @Test
    void acceptsAnUnquotedDeclarationWithDifferentCase() throws IOException {
        project(procedureChangeSet("function-app.foo",
                "functions/app/foo.sql", "functions-rollback/app/foo-rollback.sql"));
        write("changes/functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION APP.FOO() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");
        write("changes/functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        assertEquals(List.of(), violations());
    }

    /**
     * The path comparison is deliberately case-insensitive for checkouts on a
     * case-insensitive filesystem; this pins that documented leniency.
     */
    @Test
    void acceptsAPathCaseVariant() throws IOException {
        project(sqlChangeSet("001-create", "Sql_Changes/001-Create.sql", "Rollback/001-Create-rollback.sql"));
        write("changes/Sql_Changes/001-Create.sql", "SELECT 1;\n");
        write("changes/Rollback/001-Create-rollback.sql", "SELECT 1;\n");

        assertEquals(List.of(), violations());
    }

    /**
     * A file that is declared twice is reported.
     */
    @Test
    void reportsADuplicateFileReference() throws IOException {
        project(sqlChangeSet("001-create", "sql_changes/001-create.sql", "rollback/001-create-rollback.sql")
                + """
                <changeSet id="001-create" author="test">
                    <sqlFile path="sql_changes/001-create.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """);
        write("changes/sql_changes/001-create.sql", "SELECT 1;\n");
        write("changes/rollback/001-create-rollback.sql", "SELECT 1;\n");

        List<ChangelogLayoutCheck.Violation> violations = violations(false);
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("referenced more than once"),
                violations.getFirst().message());
    }

    /**
     * An id that matches neither shape is left to the naming check.
     */
    @Test
    void skipsAnIdTheNamingCheckOwns() throws IOException {
        project(sqlChangeSet("not-a-conventional-id", "sql_changes/one.sql", "rollback/one-rollback.sql"));
        write("changes/sql_changes/one.sql", "SELECT 1;\n");
        write("changes/rollback/one-rollback.sql", "SELECT 1;\n");

        assertEquals(List.of(), violations());
    }

    /**
     * Inline SQL has no file to join, so it is not a layout violation.
     */
    @Test
    void skipsInlineSql() throws IOException {
        project("""
                <changeSet id="001-create" author="test">
                    <sql>SELECT 1;</sql>
                </changeSet>
                """);

        assertEquals(List.of(), violations());
    }

    /**
     * A SQL file the lexer cannot classify is reported through the declaration
     * check rather than being silently accepted.
     */
    @Test
    void reportsAnUnlexableRoutineFile() throws IOException {
        project(procedureChangeSet("function-app.foo",
                "functions/app/foo.sql", "functions-rollback/app/foo-rollback.sql"));
        write("changes/functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'unterminated;\n");
        write("changes/functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        List<ChangelogLayoutCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("cannot classify"),
                violations.getFirst().message());
        assertTrue(violations.getFirst().message().contains("line 1"),
                violations.getFirst().message());
    }

    /**
     * A violation describes its file, line, changeset and message, including the
     * no-SQL-file shape.
     */
    @Test
    void describesViolationsAndValidatesTheirFields() {
        ChangelogLayoutCheck.Violation withFile = new ChangelogLayoutCheck.Violation(
                Path.of("changes/changes.xml"), "001-create", Path.of("changes/sql_changes/001-create.sql"),
                4, "message");
        assertEquals("changes/sql_changes/001-create.sql:4 [001-create]: message", withFile.describe());

        ChangelogLayoutCheck.Violation withoutFile = new ChangelogLayoutCheck.Violation(
                Path.of("changes/changes.xml"), "001-create", null, 4, "message");
        assertEquals("changes/changes.xml:4 [001-create]: message", withoutFile.describe());

        assertThrows(IllegalArgumentException.class, () -> new ChangelogLayoutCheck.Violation(
                Path.of("changes/changes.xml"), "001-create", null, 0, "message"));
    }
}
