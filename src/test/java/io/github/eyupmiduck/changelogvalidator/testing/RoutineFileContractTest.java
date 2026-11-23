package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs the shared {@link RoutineFileContract} against a compliant fixture tree,
 * so the inherited test and its changes-root plumbing are exercised here too.
 */
class RoutineFileContractTest extends RoutineFileContract {

    @TempDir
    Path fixture;

    /**
     * Creates one function, one procedure and one trigger, each with a matching
     * rollback file.
     *
     * @throws IOException if the fixture cannot be written
     */
    @BeforeEach
    void createFixture() throws IOException {
        write("functions/app/foo.sql",
                "CREATE OR REPLACE FUNCTION app.foo() RETURNS integer LANGUAGE sql AS 'SELECT 1';\n");
        write("functions-rollback/app/foo-rollback.sql", "DROP FUNCTION IF EXISTS app.foo();\n");
        write("procedures/app/bar.sql",
                "CREATE OR REPLACE PROCEDURE app.bar() LANGUAGE plpgsql AS $$ BEGIN NULL; END; $$;\n");
        write("procedures-rollback/app/bar-rollback.sql", "DROP PROCEDURE IF EXISTS app.bar();\n");
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

    private void write(String relative, String content) throws IOException {
        Path file = fixture.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * The fixture tree is the changes root.
     *
     * @return the fixture directory
     */
    @Override
    protected Path changesRoot() {
        return fixture;
    }
}
