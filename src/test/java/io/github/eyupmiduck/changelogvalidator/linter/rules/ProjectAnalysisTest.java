package io.github.eyupmiduck.changelogvalidator.linter.rules;

import io.github.eyupmiduck.changelogvalidator.ChangelogTestSupport;
import io.github.eyupmiduck.changelogvalidator.linter.Finding;
import io.github.eyupmiduck.changelogvalidator.linter.Linter;
import io.github.eyupmiduck.changelogvalidator.linter.ProjectAnalysisRule;
import io.github.eyupmiduck.changelogvalidator.linter.Severity;
import io.github.eyupmiduck.changelogvalidator.linter.config.LinterConfig;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangelogModel;
import io.github.eyupmiduck.changelogvalidator.linter.model.ChangelogProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the project-analysis rules over a multi-file changelog graph: each
 * changeset lives in a separate included file, so a finding proves the rule
 * crosses changeset and file boundaries.
 */
class ProjectAnalysisTest {

    @TempDir
    Path root;

    private List<Finding> lint(ProjectAnalysisRule rule, String... files) throws IOException {
        Path master = root.resolve("master.xml");
        StringBuilder includes = new StringBuilder();
        for (int i = 0; i < files.length; i++) {
            String name = "changes" + (i + 1) + ".xml";
            Files.writeString(root.resolve(name), ChangelogTestSupport.changelog(files[i]));
            includes.append("<include file=\"").append(name).append("\" relativeToChangelogFile=\"true\"/>");
        }
        Files.writeString(master, ChangelogTestSupport.changelog(includes.toString()));
        ChangelogProject project = ChangelogModel.project(root, master);
        LinterConfig config = new LinterConfig(null, Severity.ERROR, List.of(), List.of(rule.id()), Map.of());
        return new Linter(List.of(), List.of(), List.of(rule), config).lint(project);
    }

    private static String changeSet(String id, String sql) {
        return "<changeSet id=\"" + id + "\" author=\"me\"><sql>" + sql + "</sql></changeSet>";
    }

    @Test
    void reportsDuplicateObjectsAcrossFiles() throws IOException {
        assertEquals(1, lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE t (id int);"),
                changeSet("b", "CREATE TABLE t (id int);")).size());
        assertEquals(1, lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE t (id int);"),
                changeSet("b", "ALTER TABLE t ADD COLUMN id text;")).size());
        assertEquals(1, lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE INDEX i ON t (id);"),
                changeSet("b", "CREATE INDEX i ON t (id);")).size());
        assertEquals(1, lint(new DuplicateObjectRule(),
                changeSet("a", "ALTER TABLE t ADD CONSTRAINT c CHECK (id > 0);"),
                changeSet("b", "ALTER TABLE t ADD CONSTRAINT c CHECK (id > 0);")).size());
    }

    @Test
    void acceptsDropRecreateAndIfNotExists() throws IOException {
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE t (id int);"),
                changeSet("b", "DROP TABLE t;"),
                changeSet("c", "CREATE TABLE t (id int);")).isEmpty());
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE IF NOT EXISTS t (id int);"),
                changeSet("b", "CREATE TABLE IF NOT EXISTS t (id int);")).isEmpty());
    }

    @Test
    void ignoresChangesetsScopedToAnotherDatabase() throws IOException {
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE t (id int);"),
                "<changeSet id=\"b\" author=\"me\" dbms=\"mysql\">"
                        + "<sql>CREATE TABLE t (id int);</sql></changeSet>").isEmpty());
    }

    @Test
    void reportsAForeignKeyToATableNeverCreated() throws IOException {
        assertTrue(lint(new ForeignKeyTargetRule(),
                changeSet("a", "CREATE TABLE parent (id int);"),
                changeSet("b", "ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (pid) REFERENCES parent (id);"))
                .isEmpty());
        assertEquals(1, lint(new ForeignKeyTargetRule(),
                changeSet("a", "CREATE TABLE child (pid int);"),
                changeSet("b", "ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (pid) REFERENCES missing (id);"))
                .size());
        assertTrue(lint(new ForeignKeyTargetRule(Set.of("missing")),
                changeSet("a", "CREATE TABLE child (pid int);"),
                changeSet("b", "ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (pid) REFERENCES missing (id);"))
                .isEmpty());
    }

    @Test
    void reportsAConstraintThatIsNeverValidated() throws IOException {
        assertEquals(1, lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE t ADD CONSTRAINT c CHECK (id > 0) NOT VALID;")).size());
        assertTrue(lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE t ADD CONSTRAINT c CHECK (id > 0) NOT VALID;"),
                changeSet("b", "ALTER TABLE t VALIDATE CONSTRAINT c;")).isEmpty());
        assertTrue(lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE t ADD CONSTRAINT c CHECK (id > 0) NOT VALID;"),
                changeSet("b", "ALTER TABLE t DROP CONSTRAINT c;")).isEmpty());
        assertTrue(lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE t ADD CONSTRAINT c CHECK (NOT valid);")).isEmpty());
    }

    @Test
    void reportsATableUsedBeforeItIsCreated() throws IOException {
        assertEquals(1, lint(new OrderingDependencyRule(),
                changeSet("a", "ALTER TABLE t ADD COLUMN c int;"),
                changeSet("b", "CREATE TABLE t (id int);")).size());
        assertTrue(lint(new OrderingDependencyRule(),
                changeSet("a", "CREATE TABLE t (id int);"),
                changeSet("b", "ALTER TABLE t ADD COLUMN c int;")).isEmpty());
        assertTrue(lint(new OrderingDependencyRule(),
                changeSet("a", "ALTER TABLE external ADD COLUMN c int;")).isEmpty());
        assertTrue(lint(new OrderingDependencyRule(Set.of("t")),
                changeSet("a", "ALTER TABLE t ADD COLUMN c int;"),
                changeSet("b", "CREATE TABLE t (id int);")).isEmpty());
    }

    @Test
    void reportsConflictingAndDuplicateComments() throws IOException {
        assertEquals(1, lint(new ConflictingCommentRule(),
                changeSet("a", "COMMENT ON TABLE t IS 'one';"),
                changeSet("b", "COMMENT ON TABLE t IS 'two';")).size());
        assertEquals(1, lint(new ConflictingCommentRule(),
                changeSet("a", "COMMENT ON TABLE t IS 'one';"),
                changeSet("b", "COMMENT ON TABLE t IS 'one';")).size());
        assertTrue(lint(new ConflictingCommentRule(),
                changeSet("a", "COMMENT ON TABLE t IS 'one';"),
                changeSet("b", "COMMENT ON TABLE t IS NULL;")).isEmpty());
    }

    @Test
    void reportsAnUnregisteredRoutineFile() throws IOException {
        write("functions/app/foo.sql", "CREATE FUNCTION app.foo() RETURNS int LANGUAGE sql AS 'SELECT 1';\n");
        write("functions/app/bar.sql", "CREATE FUNCTION app.bar() RETURNS int LANGUAGE sql AS 'SELECT 1';\n");

        List<Finding> findings = lint(new UnregisteredRoutineFileRule(),
                "<changeSet id=\"a\" author=\"me\">"
                        + "<createProcedure path=\"functions/app/foo.sql\" relativeToChangelogFile=\"true\"/>"
                        + "</changeSet>");

        assertEquals(1, findings.size());
        assertTrue(findings.getFirst().file().endsWith("bar.sql"));
        assertEquals("", findings.getFirst().changeSetId());
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
