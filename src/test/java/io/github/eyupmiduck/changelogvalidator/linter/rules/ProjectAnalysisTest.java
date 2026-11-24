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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the project-analysis rules over a multi-file changelog graph: each
 * changeset lives in a separate included file, so a finding proves the rule
 * crosses changeset and file boundaries.
 */
class ProjectAnalysisTest {

    @TempDir
    Path root;

    private static String changeSet(String id, String sql) {
        return "<changeSet id=\"" + id + "\" author=\"me\"><sql>" + sql + "</sql></changeSet>";
    }

    private static String unsplitChangeSet(String id, String sql) {
        return "<changeSet id=\"" + id + "\" author=\"me\"><sql splitStatements=\"false\">"
                + sql + "</sql></changeSet>";
    }

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
    void toleratesTruncatedAlterTableActions() {
        assertDoesNotThrow(() -> lint(new DuplicateObjectRule(),
                changeSet("a", "ALTER TABLE t ADD COLUMN;"),
                changeSet("b", "ALTER TABLE t DROP COLUMN;"),
                changeSet("c", "ALTER TABLE t ADD CONSTRAINT;"),
                changeSet("d", "ALTER TABLE t DROP CONSTRAINT;")));
    }

    @Test
    void processesAlterTableActionsInTextualOrder() throws IOException {
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE t (b int);"),
                changeSet("b", "ALTER TABLE t DROP COLUMN b, ADD COLUMN b int;")).isEmpty());
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE t (b int);"),
                changeSet("b", "ALTER TABLE t DROP b;"),
                changeSet("c", "ALTER TABLE t ADD b int;")).isEmpty());
    }

    @Test
    void supportsOmittedColumnKeywordAndConstraintKinds() throws IOException {
        assertEquals(1, lint(new DuplicateObjectRule(),
                changeSet("a", "ALTER TABLE t ADD b int;"),
                changeSet("b", "ALTER TABLE t ADD COLUMN b int;")).size());
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "ALTER TABLE t ADD PRIMARY KEY (id);"),
                changeSet("b", "ALTER TABLE t ADD COLUMN primary int;")).isEmpty());
    }

    @Test
    void skipsUnnamedIndexes() throws IOException {
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE INDEX ON t (x);"),
                changeSet("b", "CREATE INDEX ON t (y);")).isEmpty());
    }

    @Test
    void tracksActionsAfterTheInheritanceMarker() throws IOException {
        assertEquals(1, lint(new DuplicateObjectRule(),
                changeSet("a", "ALTER TABLE t * ADD COLUMN b int;"),
                changeSet("b", "ALTER TABLE t ADD COLUMN b int;")).size());
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "ALTER TABLE t * ADD COLUMN b int;"),
                changeSet("b", "ALTER TABLE t * DROP COLUMN b;"),
                changeSet("c", "ALTER TABLE t ADD COLUMN b int;")).isEmpty());
    }

    @Test
    void recognizesUniqueNullsNotDistinct() throws IOException {
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "ALTER TABLE t ADD UNIQUE NULLS NOT DISTINCT (a);"),
                changeSet("b", "ALTER TABLE t ADD UNIQUE NULLS DISTINCT (a);")).isEmpty());
    }

    @Test
    void clearsIndexesOnDropTableAndCommaSeparatedLists() throws IOException {
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE INDEX i ON t (x);"),
                changeSet("b", "DROP TABLE t;"),
                changeSet("c", "CREATE INDEX i ON t (x);")).isEmpty());
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE a (id int); CREATE TABLE b (id int);"),
                changeSet("b", "DROP TABLE a, b;"),
                changeSet("c", "CREATE TABLE b (id int);")).isEmpty());
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE INDEX i1 ON t (x); CREATE INDEX i2 ON t (x);"),
                changeSet("b", "DROP INDEX i1, i2;"),
                changeSet("c", "CREATE INDEX i2 ON t (x);")).isEmpty());
    }

    @Test
    void ignoresTheSelectParenOfCreateTableAs() throws IOException {
        assertTrue(lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE t AS SELECT (a + b) FROM x;"),
                changeSet("b", "ALTER TABLE t ADD COLUMN a int;")).isEmpty());
    }

    @Test
    void tracksUnquotedNonReservedColumnNames() throws IOException {
        assertEquals(1, lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE t (exclude int);"),
                changeSet("b", "ALTER TABLE t ADD COLUMN exclude int;")).size());
        assertEquals(1, lint(new DuplicateObjectRule(),
                changeSet("a", "CREATE TABLE t (constraint int);"),
                changeSet("b", "ALTER TABLE t ADD COLUMN constraint int;")).size());
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
    void handlesCommaSeparatedDropTableAndSchemaQualifiedKeys() throws IOException {
        assertTrue(lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE b ADD CONSTRAINT c CHECK (id > 0) NOT VALID;"),
                changeSet("b", "DROP TABLE a, b;")).isEmpty());
        assertTrue(lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE public.t ADD CONSTRAINT c CHECK (id > 0) NOT VALID;"),
                changeSet("b", "ALTER TABLE t VALIDATE CONSTRAINT c;")).isEmpty());
    }

    @Test
    void boundsConstraintClausesToTheAlterTableCommand() throws IOException {
        assertEquals(1, lint(new UnvalidatedConstraintRule(),
                unsplitChangeSet("a", "ALTER TABLE t ADD CONSTRAINT c CHECK (id > 0) NOT VALID;"
                        + " ALTER TABLE u VALIDATE CONSTRAINT c;")).size());
    }

    @Test
    void keepsSchemaQualifiedConstraintKeysDistinct() throws IOException {
        assertEquals(1, lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE audit.t ADD CONSTRAINT c CHECK (id > 0) NOT VALID;"),
                changeSet("b", "DROP TABLE public.t;")).size());
        assertTrue(lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE public.t ADD CONSTRAINT c CHECK (id > 0) NOT VALID;"),
                changeSet("b", "ALTER TABLE t VALIDATE CONSTRAINT c;")).isEmpty());
    }

    @Test
    void supportsTheOptionalConstraintKeyword() throws IOException {
        assertEquals(1, lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE t ADD c CHECK (id > 0) NOT VALID;")).size());
        assertTrue(lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE t ADD c CHECK (id > 0) NOT VALID;"),
                changeSet("b", "ALTER TABLE t VALIDATE CONSTRAINT c;")).isEmpty());
    }

    @Test
    void findsMidCommandAlterTableInAnUnsplitSource() throws IOException {
        assertEquals(1, lint(new ForeignKeyTargetRule(),
                unsplitChangeSet("a", "CREATE TABLE child (pid int);"
                        + " ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (pid) REFERENCES missing (id);"))
                .size());
        assertEquals(1, lint(new OrderingDependencyRule(),
                unsplitChangeSet("a", "CREATE TABLE child (pid int);"
                        + " ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (pid) REFERENCES later (id);"),
                changeSet("b", "CREATE TABLE later (id int);")).size());
    }

    @Test
    void findsCreateTableMidCommandInAnUnsplitSource() throws IOException {
        assertTrue(lint(new ForeignKeyTargetRule(),
                unsplitChangeSet("a", "SELECT 1; CREATE TABLE parent (id int);"),
                changeSet("b", "ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (pid) REFERENCES parent (id);"))
                .isEmpty());
        assertEquals(1, lint(new ForeignKeyTargetRule(),
                unsplitChangeSet("a", "SELECT 1; CREATE TABLE child (pid int,"
                        + " FOREIGN KEY (pid) REFERENCES missing (id));")).size());
    }

    @Test
    void doesNotEquateDifferentQualifiedTables() throws IOException {
        assertTrue(lint(new OrderingDependencyRule(),
                changeSet("a", "ALTER TABLE app.events ADD COLUMN c int;"),
                changeSet("b", "CREATE TABLE analytics.events (id int);")).isEmpty());
    }

    @Test
    void tracksEveryAlterTableCommandInAnUnsplitSource() throws IOException {
        assertEquals(2, lint(new UnvalidatedConstraintRule(),
                unsplitChangeSet("a", "ALTER TABLE t ADD CONSTRAINT c CHECK (id > 0) NOT VALID;"
                        + " ALTER TABLE u ADD CONSTRAINT d CHECK (id > 0) NOT VALID;")).size());
    }

    @Test
    void handlesMidStatementDropTable() throws IOException {
        assertTrue(lint(new UnvalidatedConstraintRule(),
                unsplitChangeSet("a", "ALTER TABLE t ADD CONSTRAINT c CHECK (id > 0) NOT VALID;"
                        + " DROP TABLE t;")).isEmpty());
    }

    @Test
    void ignoresAddColumnWithAConstraintKindName() throws IOException {
        assertTrue(lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE t ADD COLUMN exclude int CHECK (exclude > 0) NOT VALID;"))
                .isEmpty());
    }

    @Test
    void handlesPerItemOnlyAndInheritanceStarsInTruncate() throws IOException {
        assertEquals(1, lint(new OrderingDependencyRule(),
                changeSet("a", "TRUNCATE ONLY t1, ONLY t2;"),
                changeSet("b", "CREATE TABLE t2 (id int);")).size());
        assertEquals(1, lint(new OrderingDependencyRule(),
                changeSet("a", "TRUNCATE t1*, t2;"),
                changeSet("b", "CREATE TABLE t2 (id int);")).size());
    }

    @Test
    void dropsBeforeAddsWithinOneAlterTable() throws IOException {
        assertEquals(1, lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE t DROP CONSTRAINT c,"
                        + " ADD CONSTRAINT c CHECK (id > 0) NOT VALID;")).size());
        assertTrue(lint(new UnvalidatedConstraintRule(),
                changeSet("a", "ALTER TABLE t ADD CONSTRAINT c CHECK (id > 0) NOT VALID,"
                        + " VALIDATE CONSTRAINT c;")).isEmpty());
    }

    @Test
    void normalizesCommentKeysAndFindsTheValueIs() throws IOException {
        assertEquals(1, lint(new ConflictingCommentRule(),
                changeSet("a", "COMMENT ON TABLE Foo IS 'one';"),
                changeSet("b", "COMMENT ON TABLE foo IS 'two';")).size());
        assertTrue(lint(new ConflictingCommentRule(),
                changeSet("a", "COMMENT ON TABLE \"Foo\" IS 'one';"),
                changeSet("b", "COMMENT ON TABLE foo IS 'two';")).isEmpty());
        assertEquals(1, lint(new ConflictingCommentRule(),
                changeSet("a", "COMMENT ON COLUMN tab.is IS 'one';"),
                changeSet("b", "COMMENT ON COLUMN tab.is IS 'two';")).size());
        assertEquals(1, lint(new ConflictingCommentRule(),
                changeSet("a", "COMMENT ON FUNCTION foo(a INT) IS 'one';"),
                changeSet("b", "COMMENT ON FUNCTION foo(a int) IS 'two';")).size());
    }

    @Test
    void recognizesModifiedCreateTableStatements() throws IOException {
        assertTrue(lint(new ForeignKeyTargetRule(),
                changeSet("a", "CREATE UNLOGGED TABLE parent (id int);"),
                changeSet("b", "ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (pid) REFERENCES parent (id);"))
                .isEmpty());
        assertTrue(lint(new ForeignKeyTargetRule(),
                changeSet("a", "CREATE TEMPORARY TABLE parent (id int);"),
                changeSet("b", "ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (pid) REFERENCES parent (id);"))
                .isEmpty());
        assertEquals(1, lint(new OrderingDependencyRule(),
                changeSet("a", "ALTER TABLE t ADD COLUMN c int;"),
                changeSet("b", "CREATE UNLOGGED TABLE t (id int);")).size());
    }

    @Test
    void handlesOnlyAndCommaSeparatedTruncateAndDelete() throws IOException {
        assertEquals(1, lint(new OrderingDependencyRule(),
                changeSet("a", "TRUNCATE ONLY t;"),
                changeSet("b", "CREATE TABLE t (id int);")).size());
        assertEquals(1, lint(new OrderingDependencyRule(),
                changeSet("a", "DELETE FROM ONLY t;"),
                changeSet("b", "CREATE TABLE t (id int);")).size());
        assertEquals(1, lint(new OrderingDependencyRule(),
                changeSet("a", "CREATE TABLE a (id int); TRUNCATE a, b;"),
                changeSet("b", "CREATE TABLE b (id int);")).size());
    }

    @Test
    void ignoresReferencesAsANonKeywordIdentifier() throws IOException {
        assertTrue(lint(new OrderingDependencyRule(),
                changeSet("a", "SELECT u.references later FROM u;"),
                changeSet("b", "CREATE TABLE later (id int);")).isEmpty());
        assertTrue(lint(new ForeignKeyTargetRule(),
                changeSet("a", "SELECT u.references later FROM u;")).isEmpty());
        assertEquals(1, lint(new OrderingDependencyRule(),
                changeSet("a", "CREATE TABLE child (pid int REFERENCES parent (id));"),
                changeSet("b", "CREATE TABLE parent (id int);")).size());
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

    @Test
    void recognizesAnUppercaseSqlExtension() throws IOException {
        write("functions/app/foo.SQL", "CREATE FUNCTION app.foo() RETURNS int LANGUAGE sql AS 'SELECT 1';\n");

        List<Finding> findings = lint(new UnregisteredRoutineFileRule());

        assertEquals(1, findings.size());
        assertTrue(findings.getFirst().file().endsWith("foo.SQL"));
    }

    @Test
    void relativizesAgainstAnAbsoluteRoot() {
        Path relativeRoot = Path.of("src");
        Path absoluteFile = Path.of("src/functions/app/foo.sql").toAbsolutePath();

        assertTrue(UnregisteredRoutineFileRule.isRoutineFile(relativeRoot, absoluteFile));
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
