package io.github.eyupmiduck.changelogvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RoutineFileCheck}: every routine or trigger file declares the
 * object its path names, and its rollback file drops that object.
 */
class RoutineFileCheckTest {

    @TempDir
    Path changesRoot;

    private void write(String relative, String content) throws IOException {
        Path file = changesRoot.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private List<RoutineFileCheck.Violation> violations() throws IOException {
        return RoutineFileCheck.findViolations(changesRoot);
    }

    private void writeCompliantFunction() throws IOException {
        write("functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");
        write("functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");
    }

    private void writeCompliantProcedure() throws IOException {
        write("procedures/app/bar.sql",
                "CREATE OR REPLACE PROCEDURE app.bar() LANGUAGE plpgsql AS $$ BEGIN NULL; END; $$;\n");
        write("procedures-rollback/app/bar-rollback.sql", "DROP PROCEDURE IF EXISTS app.bar();\n");
    }

    private void writeCompliantTrigger() throws IOException {
        write("triggers/app/foo_trg.sql", """
                CREATE OR REPLACE TRIGGER foo_trg
                    BEFORE UPDATE ON app.foo
                    FOR EACH ROW
                EXECUTE FUNCTION app.set_updated_at();
                """);
        write("triggers-rollback/app/foo_trg-rollback.sql", """
                DO
                $$
                    BEGIN
                        IF pg_catalog.to_regclass('app.foo') IS NOT NULL THEN
                            DROP TRIGGER IF EXISTS foo_trg ON app.foo;
                        END IF;
                    END;
                $$;
                """);
    }

    /**
     * A function, a procedure and a trigger in the conventional layout produce
     * no violations; a directory README and a {@code .gitkeep} are ignored.
     */
    @Test
    void acceptsTheConventionalTree() throws IOException {
        writeCompliantFunction();
        writeCompliantProcedure();
        writeCompliantTrigger();
        write("functions/README.md", "Documentation, not SQL.\n");
        write("functions/.gitkeep", "");
        write("functions/app/.gitkeep", "");

        assertEquals(List.of(), violations());
    }

    /**
     * A quoted routine name is accepted.
     */
    @Test
    void acceptsAQuotedRoutineName() throws IOException {
        write("functions/app/quoted.sql",
                "CREATE OR REPLACE FUNCTION \"app\".\"quoted\"() RETURNS integer"
                        + " LANGUAGE sql AS 'SELECT 1';\n");
        write("functions-rollback/app/quoted-rollback.sql", "DROP FUNCTION IF EXISTS app.quoted();\n");

        assertEquals(List.of(), violations());
    }

    /**
     * A rollback file may drop several overloads of the same routine, as long as
     * every drop names the object the path names.
     */
    @Test
    void acceptsSeveralDropsOfOneOverloadedRoutine() throws IOException {
        writeCompliantFunction();
        write("functions-rollback/app/foo-rollback.sql", """
                DROP FUNCTION IF EXISTS app.foo();
                DROP FUNCTION IF EXISTS app.foo(integer);
                """);

        assertEquals(List.of(), violations());
    }

    /**
     * A file that declares no stored object is reported.
     */
    @Test
    void reportsAFileThatDeclaresNothing() throws IOException {
        writeCompliantFunction();
        write("functions/app/foo.sql", "SELECT 1;\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("does not declare a function"),
                violations.getFirst().message());
    }

    /**
     * A file that declares the wrong routine name is reported.
     */
    @Test
    void reportsAFileWithTheWrongRoutineName() throws IOException {
        writeCompliantFunction();
        write("functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION app.bar() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("declares app.bar but the path expects app.foo"),
                violations.getFirst().message());
    }

    /**
     * A file that declares the wrong routine schema is reported.
     */
    @Test
    void reportsAFileWithTheWrongRoutineSchema() throws IOException {
        writeCompliantFunction();
        write("functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION other.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("declares other.foo but the path expects app.foo"),
                violations.getFirst().message());
    }

    /**
     * A function file that declares a procedure is reported.
     */
    @Test
    void reportsAFileWithTheWrongObjectKind() throws IOException {
        writeCompliantFunction();
        write("functions/app/foo.sql",
                "CREATE OR REPLACE PROCEDURE app.foo() LANGUAGE plpgsql AS $$ BEGIN NULL; END; $$;\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("declares a procedure"),
                violations.getFirst().message());
    }

    /**
     * A file that declares more than one stored object is reported.
     */
    @Test
    void reportsAFileThatDeclaresTwoObjects() throws IOException {
        writeCompliantFunction();
        write("functions/app/foo.sql", """
                CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';
                CREATE OR REPLACE FUNCTION app.foo(i_value integer) RETURNS integer
                    LANGUAGE sql AS 'SELECT i_value';
                """);

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("declares 2 stored objects"),
                violations.getFirst().message());
    }

    /**
     * A forward file without a rollback file is reported.
     */
    @Test
    void reportsAMissingRollbackFile() throws IOException {
        write("functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("has no matching rollback file"),
                violations.getFirst().message());
    }

    /**
     * A rollback file that drops nothing is reported.
     */
    @Test
    void reportsARollbackThatDropsNothing() throws IOException {
        writeCompliantFunction();
        write("functions-rollback/app/foo-rollback.sql", "SELECT 1;\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("does not drop a function"),
                violations.getFirst().message());
    }

    /**
     * A rollback file that drops a different routine is reported.
     */
    @Test
    void reportsARollbackThatDropsTheWrongRoutine() throws IOException {
        writeCompliantFunction();
        write("functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.bar();\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("drops app.bar but the path expects app.foo"),
                violations.getFirst().message());
    }

    /**
     * A rollback file that drops the wrong object kind is reported.
     */
    @Test
    void reportsARollbackThatDropsTheWrongKind() throws IOException {
        writeCompliantFunction();
        write("functions-rollback/app/foo-rollback.sql", "DROP PROCEDURE IF EXISTS app.foo();\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("drops a procedure"),
                violations.getFirst().message());
    }

    /**
     * A rollback file without a forward file is reported.
     */
    @Test
    void reportsARollbackWithoutAForwardFile() throws IOException {
        write("functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("has no matching forward file"),
                violations.getFirst().message());
    }

    /**
     * A rollback file that omits the {@code -rollback} suffix is reported, both
     * as an orphan and because the forward file has no matching rollback.
     */
    @Test
    void reportsARollbackFileWithoutTheSuffix() throws IOException {
        writeCompliantFunction();
        Files.delete(changesRoot.resolve("functions-rollback/app/foo-rollback.sql"));
        write("functions-rollback/app/foo.sql", "DROP FUNCTION IF EXISTS app.foo();\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(2, violations.size(), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("does not end with -rollback.sql")),
                violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.message().contains("has no matching rollback file")),
                violations.toString());
    }

    /**
     * A trigger on a table in a different schema is reported.
     */
    @Test
    void reportsATriggerOnTheWrongTableSchema() throws IOException {
        writeCompliantTrigger();
        write("triggers/app/foo_trg.sql", """
                CREATE OR REPLACE TRIGGER foo_trg
                    BEFORE UPDATE ON other.foo
                    FOR EACH ROW
                EXECUTE FUNCTION app.set_updated_at();
                """);

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("trigger is on schema other"),
                violations.getFirst().message());
    }

    /**
     * A trigger rollback that drops the trigger from a table in a different
     * schema is reported.
     */
    @Test
    void reportsATriggerRollbackForTheWrongTable() throws IOException {
        writeCompliantTrigger();
        write("triggers-rollback/app/foo_trg-rollback.sql",
                "DROP TRIGGER IF EXISTS foo_trg ON other.foo;\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("drops trigger on schema other"),
                violations.getFirst().message());
    }

    /**
     * A file that is not directly under a schema directory is reported.
     */
    @Test
    void reportsAFileThatIsNotUnderASchemaDirectory() throws IOException {
        write("functions/foo.sql",
                "CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("directly under a schema directory"),
                violations.getFirst().message());
    }

    /**
     * A file nested more than one directory below the kind root is reported.
     */
    @Test
    void reportsAFileNestedBelowTheSchemaDirectory() throws IOException {
        write("functions/app/sub/foo.sql",
                "CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("directly under a schema directory"),
                violations.getFirst().message());
    }

    /**
     * An unqualified routine declaration is reported.
     */
    @Test
    void reportsAnUnqualifiedRoutineDeclaration() throws IOException {
        writeCompliantFunction();
        write("functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("routine is not schema-qualified"),
                violations.getFirst().message());
    }

    /**
     * A trigger on an unqualified table is reported.
     */
    @Test
    void reportsAnUnqualifiedTriggerTable() throws IOException {
        writeCompliantTrigger();
        write("triggers/app/foo_trg.sql", """
                CREATE OR REPLACE TRIGGER foo_trg
                    BEFORE UPDATE ON foo
                    FOR EACH ROW
                EXECUTE FUNCTION app.set_updated_at();
                """);

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("trigger's table is not schema-qualified"),
                violations.getFirst().message());
    }

    /**
     * An unqualified rollback drop is reported.
     */
    @Test
    void reportsAnUnqualifiedRollbackDrop() throws IOException {
        writeCompliantFunction();
        write("functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS foo();\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("DROP function is not schema-qualified"),
                violations.getFirst().message());
    }

    /**
     * A trigger rollback with an unqualified table is reported.
     */
    @Test
    void reportsATriggerRollbackWithoutAQualifiedTable() throws IOException {
        writeCompliantTrigger();
        write("triggers-rollback/app/foo_trg-rollback.sql",
                "DROP TRIGGER IF EXISTS foo_trg ON foo;\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("DROP TRIGGER's table is not schema-qualified"),
                violations.getFirst().message());
    }

    /**
     * A {@code DROP} with no object name is reported.
     */
    @Test
    void reportsADropWithoutAName() throws IOException {
        writeCompliantFunction();
        write("functions-rollback/app/foo-rollback.sql", "DROP FUNCTION;\n");

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("does not name an object"),
                violations.getFirst().message());
    }

    /**
     * A {@code DROP TRIGGER} inside a comment or a string is not a rollback
     * statement.
     */
    @Test
    void ignoresADropInsideACommentOrString() throws IOException {
        writeCompliantTrigger();
        write("triggers-rollback/app/foo_trg-rollback.sql", """
                -- DROP TRIGGER IF EXISTS foo_trg ON app.foo;
                SELECT 'DROP TRIGGER IF EXISTS foo_trg ON app.foo';
                """);

        List<RoutineFileCheck.Violation> violations = violations();
        assertEquals(1, violations.size(), violations.toString());
        assertTrue(violations.getFirst().message().contains("does not drop a trigger"),
                violations.getFirst().message());
    }
}
