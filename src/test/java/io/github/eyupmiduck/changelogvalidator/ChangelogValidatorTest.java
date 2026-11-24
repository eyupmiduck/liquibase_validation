package io.github.eyupmiduck.changelogvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the behaviour of {@link ChangelogValidator} against temporary
 * changelog directory layouts.
 */
class ChangelogValidatorTest {

    @TempDir
    Path tempDir;

    /**
     * Wraps {@code body} in a databaseChangeLog document that declares the
     * Liquibase namespace under the {@code db} prefix.
     */
    private static String prefixedChangelog(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <db:databaseChangeLog xmlns:db="http://www.liquibase.org/xml/ns/dbchangelog">
                %s
                </db:databaseChangeLog>
                """.formatted(body);
    }

    /**
     * A SQL file whose name does not start with a three-digit, zero-padded
     * prefix is reported as invalid, while correctly prefixed files (using
     * either separator), nested files, and non-SQL files are ignored.
     */
    @Test
    void flagsSqlFilesWithoutThreeDigitPrefix() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Files.writeString(changes.resolve("001-create.sql"), "");
        Files.writeString(changes.resolve("002_create.sql"), "");
        Files.writeString(changes.resolve("create.sql"), "");
        Files.writeString(changes.resolve("readme.txt"), "");
        Files.createDirectories(changes.resolve("directory.sql"));
        Path nested = Files.createDirectories(changes.resolve("sql_changes"));
        Files.writeString(nested.resolve("orphan.sql"), "");

        List<Path> invalid = ChangelogValidator.findInvalidlyNamedSqlFiles(changes);

        assertEquals(List.of(Path.of("create.sql"), Path.of("sql_changes/orphan.sql")), invalid);
    }

    /**
     * SQL files under an exempt directory ({@code functions}, {@code procedures},
     * {@code triggers}, or their {@code -rollback} variants, at any depth) are
     * exempt from the naming rule, while other SQL files are still checked.
     */
    @Test
    void exemptsRoutineAndTriggerDirectoriesFromNaming() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path functions = Files.createDirectories(changes.resolve("functions"));
        Files.writeString(functions.resolve("alter_table.sql"), "");
        Path routineRollback = Files.createDirectories(functions.resolve("rollback"));
        Files.writeString(routineRollback.resolve("alter_table-rollback.sql"), "");
        Path functionsRollback = Files.createDirectories(changes.resolve("functions-rollback"));
        Files.writeString(functionsRollback.resolve("alter_table-rollback.sql"), "");
        Path procedures = Files.createDirectories(changes.resolve("procedures"));
        Files.writeString(procedures.resolve("do_thing.sql"), "");
        Path proceduresRollback = Files.createDirectories(changes.resolve("procedures-rollback"));
        Files.writeString(proceduresRollback.resolve("do_thing-rollback.sql"), "");
        Path triggers = Files.createDirectories(changes.resolve("triggers"));
        Files.writeString(triggers.resolve("set_updated_at.sql"), "");
        Path triggersRollback = Files.createDirectories(changes.resolve("triggers-rollback"));
        Files.writeString(triggersRollback.resolve("set_updated_at-rollback.sql"), "");
        Path sqlChanges = Files.createDirectories(changes.resolve("sql_changes"));
        Files.writeString(sqlChanges.resolve("bad.sql"), "");

        List<Path> invalid = ChangelogValidator.findInvalidlyNamedSqlFiles(changes);

        assertEquals(List.of(Path.of("sql_changes/bad.sql")), invalid);
    }

    /**
     * The default patterns reject whitespace and empty name segments that a
     * loose {@code .+} suffix would have accepted.
     */
    @Test
    void rejectsLooseNaming() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("changes.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="001-good" author="test"/>
                <changeSet id="002-with space" author="test"/>
                <changeSet id="003-" author="test"/>
                """));
        Files.writeString(changes.resolve("001-good.sql"), "");
        Files.writeString(changes.resolve("002-with space.sql"), "");
        Files.writeString(changes.resolve("003-.sql"), "");

        assertEquals(List.of("002-with space", "003-"),
                ChangelogValidator.findInvalidlyNamedChangeSets(changes, master).stream()
                        .map(ChangelogValidator.InvalidChangeSet::id).toList());
        assertEquals(List.of(Path.of("002-with space.sql"), Path.of("003-.sql")),
                ChangelogValidator.findInvalidlyNamedSqlFiles(changes));
    }

    /**
     * Changesets reachable through an include are checked by id; ids without a
     * three-digit prefix and changesets missing an id are reported, and
     * correctly named ones are not.
     */
    @Test
    void flagsChangeSetsWithoutThreeDigitPrefix() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("changes.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="001-create-example" author="test"/>
                <changeSet id="create-example" author="test"/>
                <changeSet author="test"/>
                """));

        List<ChangelogValidator.InvalidChangeSet> invalid =
                ChangelogValidator.findInvalidlyNamedChangeSets(changes, master);

        Path changesXml = changes.resolve("changes.xml");
        assertEquals(List.of(
                new ChangelogValidator.InvalidChangeSet(changesXml, ""),
                new ChangelogValidator.InvalidChangeSet(changesXml, "create-example")), invalid);
    }

    /**
     * A caller-supplied id pattern can accept stored-routine changesets named
     * after the routine while still flagging ids that match neither rule.
     */
    @Test
    void acceptsCustomChangeSetIdPattern() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="functions.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("functions.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="function-ddl_utils.set_not_null" author="test"/>
                <changeSet id="function-ddl_utils_lib.set_not_null" author="test"/>
                <changeSet id="procedure-ddl_utils.ensure_not_null" author="test"/>
                <changeSet id="006-set_database_lock_settings" author="test"/>
                <changeSet id="not-named" author="test"/>
                """));
        Pattern pattern = Pattern.compile(
                "\\d{3}[-_].+|(function|procedure)-[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*");

        List<ChangelogValidator.InvalidChangeSet> invalid =
                ChangelogValidator.findInvalidlyNamedChangeSets(changes, master, pattern);

        assertEquals(List.of(new ChangelogValidator.InvalidChangeSet(
                changes.resolve("functions.xml"), "not-named")), invalid);
    }

    /**
     * A SQL file that no changelog XML reachable from the master references is
     * reported as orphaned, while forward and rollback references are not.
     */
    @Test
    void reportsSqlFileAsOrphanedWhenNotReferenced() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path sqlChanges = Files.createDirectories(changes.resolve("sql_changes"));
        Files.writeString(sqlChanges.resolve("001-referenced.sql"), "");
        Files.writeString(sqlChanges.resolve("002-rollback.sql"), "");
        Files.writeString(sqlChanges.resolve("003-orphan.sql"), "");
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("changes.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="sql_changes/001-referenced.sql" relativeToChangelogFile="true"/>
                    <rollback>
                        <sqlFile path="sql_changes/002-rollback.sql" relativeToChangelogFile="true"/>
                    </rollback>
                </changeSet>
                """));

        List<Path> orphaned = ChangelogValidator.findOrphanedSqlFiles(changes, master);

        assertEquals(List.of(Path.of("sql_changes/003-orphan.sql")), orphaned);
    }

    /**
     * A SQL reference that points to a missing file is rejected rather than
     * silently passing the orphan check.
     */
    @Test
    void rejectsMissingSqlReference() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("changes.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="sql_changes/missing.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogValidator.findOrphanedSqlFiles(changes, master));
        assertTrue(failure.getMessage().contains("SQL file reference not found"));
        assertTrue(failure.getMessage().contains("changes.xml"),
                () -> "the failure should name the containing changelog: " + failure.getMessage());
    }

    /**
     * A reference whose case differs from the file on disk still counts as a
     * reference on a case-insensitive filesystem. A symlinked directory spells
     * the case variant on a case-sensitive filesystem too.
     */
    @Test
    void treatsACaseVariantSqlReferenceAsReferenced() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path scripts = Files.createDirectories(changes.resolve("scripts"));
        Files.writeString(scripts.resolve("foo.sql"), "");
        Files.createSymbolicLink(changes.resolve("Scripts"), scripts);
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="Scripts/foo.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """));

        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(changes, master));
    }

    /**
     * A case-variant include of the same physical changelog is visited once, so
     * its changesets are not reported twice.
     */
    @Test
    void deduplicatesCaseVariantIncludes() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path real = Files.createDirectories(changes.resolve("real"));
        Files.writeString(real.resolve("changes.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="create-example" author="test"/>
                """));
        Files.createSymbolicLink(changes.resolve("Real"), real);
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="real/changes.xml" relativeToChangelogFile="true"/>
                <include file="Real/changes.xml" relativeToChangelogFile="true"/>
                """));

        assertEquals(2, ChangelogValidator.findReachableChangelogFiles(changes, master).size());
        assertEquals(List.of(new ChangelogValidator.InvalidChangeSet(
                        real.resolve("changes.xml"), "create-example")),
                ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
    }

    /**
     * A raw SQL file reached through {@code <include>} is rejected at the include
     * site, and the diagnostic names the including changelog.
     */
    @Test
    void rejectsAnIncludeOfUnformattedSqlNamingTheIncluder() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Files.writeString(changes.resolve("raw.sql"), "SELECT 1;\n");
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="raw.sql" relativeToChangelogFile="true"/>
                """));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogValidator.findReachableChangelogFiles(changes, master));
        assertTrue(failure.getMessage().contains("formatted sql"), failure.getMessage());
        assertTrue(failure.getMessage().contains("master.xml"), failure.getMessage());
    }

    /**
     * findSqlFiles lists every {@code .sql} file under the root, relative to
     * it and sorted, ignoring non-SQL files.
     */
    @Test
    void listsEverySqlFileRelativeToRoot() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path sqlChanges = Files.createDirectories(changes.resolve("sql_changes"));
        Files.writeString(changes.resolve("002-b.sql"), "");
        Files.writeString(changes.resolve("001-a.sql"), "");
        Files.writeString(sqlChanges.resolve("003-c.sql"), "");
        Files.writeString(changes.resolve("readme.txt"), "");

        List<Path> sqlFiles = ChangelogValidator.findSqlFiles(changes);

        assertEquals(List.of(
                Path.of("001-a.sql"),
                Path.of("002-b.sql"),
                Path.of("sql_changes/003-c.sql")), sqlFiles);
    }

    /**
     * findReferencedSqlFiles lists only the SQL files the changelog graph
     * rooted at the master references.
     */
    @Test
    void listsOnlyReferencedSqlFiles() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path sqlChanges = Files.createDirectories(changes.resolve("sql_changes"));
        Files.writeString(sqlChanges.resolve("001-referenced.sql"), "");
        Files.writeString(sqlChanges.resolve("002-orphan.sql"), "");
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("changes.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="sql_changes/001-referenced.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """));

        List<Path> referenced = ChangelogValidator.findReferencedSqlFiles(changes, master);

        assertEquals(List.of(Path.of("sql_changes/001-referenced.sql")), referenced);
    }

    /**
     * Includes and SQL references with {@code relativeToChangelogFile="false"}
     * are resolved against the changelog root rather than the including file.
     */
    @Test
    void resolvesPathsRelativeToChangelogRoot() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("changelog"));
        Path nested = Files.createDirectories(root.resolve("sub"));
        Path master = root.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="sub/changes.xml" relativeToChangelogFile="false"/>
                """));
        Path sqlChanges = Files.createDirectories(root.resolve("sql_changes"));
        Files.writeString(sqlChanges.resolve("001-create.sql"), "");
        Files.writeString(nested.resolve("changes.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="sql_changes/001-create.sql" relativeToChangelogFile="false"/>
                </changeSet>
                """));

        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(root, master));
        assertEquals(List.of(), ChangelogValidator.findInvalidlyNamedChangeSets(root, master));
    }

    /**
     * A SQL file referenced as the body of a {@code createProcedure} element is
     * not orphaned, while an unreferenced routine body is.
     */
    @Test
    void referencesRoutineBodiesViaCreateProcedurePath() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path functions = Files.createDirectories(changes.resolve("functions"));
        Files.writeString(functions.resolve("alter_table.sql"), "");
        Files.writeString(functions.resolve("orphan_function.sql"), "");
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="functions.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("functions.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="005-create-functions" author="test">
                    <createProcedure path="functions/alter_table.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """));

        List<Path> orphaned = ChangelogValidator.findOrphanedSqlFiles(changes, master);

        assertEquals(List.of(Path.of("functions/orphan_function.sql")), orphaned);
    }

    /**
     * A {@code createFunction} body is followed too, and a path with
     * {@code relativeToChangelogFile="false"} resolves against the changelog
     * root rather than the changelog file.
     */
    @Test
    void followsCreateFunctionPathRelativeToChangelogRoot() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("changelog"));
        Path functions = Files.createDirectories(root.resolve("functions"));
        Files.writeString(functions.resolve("alter_table.sql"), "");
        Path master = root.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="functions.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(root.resolve("functions.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="005-create-functions" author="test">
                    <createFunction path="functions/alter_table.sql" relativeToChangelogFile="false"/>
                </changeSet>
                """));

        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(root, master));
    }

    /**
     * {@code <includeAll>} follows every XML changelog in the directory and its
     * subdirectories.
     */
    @Test
    void followsIncludeAllXmlChangelogs() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path included = Files.createDirectories(changes.resolve("included"));
        Files.writeString(included.resolve("001-first.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="001-first" author="test"><sql>SELECT 1;</sql></changeSet>
                """));
        Files.writeString(included.resolve("002-second.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="002-second" author="test"><sql>SELECT 2;</sql></changeSet>
                """));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"/>
                """));

        assertEquals(List.of(), ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
        assertEquals(3, ChangelogValidator.findReachableChangelogFiles(changes, master).size());
    }

    /**
     * A SQL file found by {@code <includeAll>} is a referenced change, not an
     * orphan, and still follows the SQL naming rule.
     */
    @Test
    void includeAllSqlFilesAreReferencedAndNotOrphaned() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path included = Files.createDirectories(changes.resolve("included"));
        Files.writeString(included.resolve("001-raw.sql"), "SELECT 1;\n");
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"/>
                """));

        assertEquals(List.of(Path.of("included/001-raw.sql")),
                ChangelogValidator.findReferencedSqlFiles(changes, master));
        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(changes, master));
        assertEquals(List.of(), ChangelogValidator.findInvalidlyNamedSqlFiles(changes));
    }

    /**
     * A formatted SQL changelog found by {@code <includeAll>} is parsed by
     * content: its changeset ids are checked and its file is referenced.
     */
    @Test
    void formattedSqlChangelogIncludedThroughIncludeAll() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path included = Files.createDirectories(changes.resolve("included"));
        Path changelog = included.resolve("changelog.sql");
        Files.writeString(changelog, """
                --liquibase formatted sql
                --changeset alice:001-create
                CREATE TABLE t (id int);
                --changeset alice:002-index
                CREATE INDEX idx ON t (id);
                """);
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"/>
                """));

        assertEquals(List.of(), ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
        assertEquals(List.of(Path.of("included/changelog.sql")),
                ChangelogValidator.findReferencedSqlFiles(changes, master));
        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(changes, master));
        assertEquals(List.of(), ChangelogValidator.findInvalidlyNamedSqlFiles(changes));
    }

    /**
     * A formatted SQL changelog found by {@code <includeAll>} has its changeset
     * ids checked like any other changelog.
     */
    @Test
    void formattedSqlChangelogNamingIsCheckedThroughIncludeAll() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path included = Files.createDirectories(changes.resolve("included"));
        Path changelog = included.resolve("changelog.sql");
        Files.writeString(changelog, """
                --liquibase formatted sql
                --changeset alice:create-table
                CREATE TABLE t (id int);
                """);
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"/>
                """));

        assertEquals(List.of(new ChangelogValidator.InvalidChangeSet(changelog, "create-table")),
                ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
    }

    /**
     * A formatted SQL changelog included through {@code <include>} is referenced,
     * not orphaned, exempt from the SQL naming rule, and its changeset ids are
     * checked.
     */
    @Test
    void formattedSqlChangelogIncludedThroughInclude() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path changelog = changes.resolve("db.changelog.sql");
        Files.writeString(changelog, """
                --liquibase formatted sql
                --changeset alice:001-create
                CREATE TABLE t (id int);
                --rollback DROP TABLE t;
                --changeset alice:002-index
                CREATE INDEX idx ON t (id);
                """);
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="db.changelog.sql" relativeToChangelogFile="true"/>
                """));

        assertEquals(List.of(), ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
        assertEquals(List.of(Path.of("db.changelog.sql")),
                ChangelogValidator.findReferencedSqlFiles(changes, master));
        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(changes, master));
        assertEquals(List.of(), ChangelogValidator.findInvalidlyNamedSqlFiles(changes));
    }

    /**
     * A formatted SQL changelog's changeset ids are checked like XML ids.
     */
    @Test
    void formattedSqlChangelogNamingIsChecked() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path changelog = changes.resolve("db.changelog.sql");
        Files.writeString(changelog, """
                --liquibase formatted sql
                --changeset alice:create-table
                CREATE TABLE t (id int);
                """);
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="db.changelog.sql" relativeToChangelogFile="true"/>
                """));

        assertEquals(List.of(new ChangelogValidator.InvalidChangeSet(changelog, "create-table")),
                ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
    }

    /**
     * A missing includeAll directory fails instead of silently producing an
     * incomplete graph.
     */
    @Test
    void rejectsMissingIncludeAllDirectory() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <includeAll path="missing/" relativeToChangelogFile="true"/>
                """));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogValidator.findReachableChangelogFiles(changes, master));
        assertTrue(failure.getMessage().contains("includeAll directory not found"), failure.getMessage());
    }

    /**
     * An includeAll attribute that needs a caller-supplied class fails closed.
     */
    @Test
    void rejectsUnsupportedIncludeAllAttribute() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <includeAll path="included/" relativeToChangelogFile="true"
                            resourceComparator="com.example.Comparator"/>
                """));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogValidator.findReachableChangelogFiles(changes, master));
        assertTrue(failure.getMessage().contains("resourceComparator"), failure.getMessage());
    }

    /**
     * An includeAll path that escapes the changelog root is rejected.
     */
    @Test
    void rejectsIncludeAllEscapingTheRoot() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <includeAll path="../outside/" relativeToChangelogFile="true"/>
                """));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogValidator.findReachableChangelogFiles(changes, master));
        assertTrue(failure.getMessage().contains("escapes the changelog root"), failure.getMessage());
    }

    /**
     * A symlink under the changelog root that points outside it is rejected, as
     * the lexical containment check alone would allow it.
     */
    @Test
    void rejectsSymlinkEscape() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("outside.xml"), ChangelogTestSupport.changelog(""));
        Files.createSymbolicLink(changes.resolve("linked"), outside);
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="linked/outside.xml" relativeToChangelogFile="true"/>
                """));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
        assertTrue(failure.getMessage().contains("symlink"));
    }

    /**
     * A cycle of includes is traversed once and does not loop forever.
     */
    @Test
    void guardsAgainstIncludeCycles() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("a.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="b.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("b.xml"), ChangelogTestSupport.changelog("""
                <include file="a.xml" relativeToChangelogFile="true"/>
                """));

        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(changes, master));
    }

    /**
     * An {@code <include>} or {@code <sqlFile>} without its required path
     * attribute is rejected instead of being dropped from the graph, so an
     * attribute-name typo cannot turn into silent under-validation.
     */
    @Test
    void rejectsIncludeAndSqlFileWithoutPath() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include/>
                """));

        IOException includeFailure = assertThrows(IOException.class,
                () -> ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
        assertTrue(includeFailure.getMessage().contains("include without a file attribute"));

        Files.writeString(master, ChangelogTestSupport.changelog("""
                <changeSet id="001-create" author="test">
                    <sqlFile/>
                </changeSet>
                """));

        IOException sqlFailure = assertThrows(IOException.class,
                () -> ChangelogValidator.findReferencedSqlFiles(changes, master));
        assertTrue(sqlFailure.getMessage().contains("sqlFile without a path attribute"));
    }

    /**
     * A routine body given as the element's inline text (or an empty
     * {@code <createProcedure/>}) carries no path and is not a broken reference.
     */
    @Test
    void ignoresRoutineBodiesWithoutAPath() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path sqlChanges = Files.createDirectories(changes.resolve("sql_changes"));
        Files.writeString(sqlChanges.resolve("001-create.sql"), "");
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("changes.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="sql_changes/001-create.sql" relativeToChangelogFile="true"/>
                    <createProcedure>CREATE FUNCTION f() RETURNS integer LANGUAGE sql RETURN 1</createProcedure>
                    <createProcedure/>
                </changeSet>
                """));

        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(changes, master));
        assertEquals(List.of(), ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
    }

    /**
     * An unparseable changelog file in the graph is reported as an I/O error,
     * matching the declared contract.
     */
    @Test
    void rejectsMalformedChangelogFile() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="broken.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("broken.xml"), "<databaseChangeLog>");

        assertThrows(IOException.class, () -> ChangelogValidator.findOrphanedSqlFiles(changes, master));
    }

    /**
     * A DOCTYPE that declares an external entity is rejected instead of being
     * resolved, so a crafted changelog cannot read a local file.
     */
    @Test
    void rejectsDoctypeWithExternalEntity() throws IOException {
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "TOP SECRET");
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE databaseChangeLog [
                  <!ENTITY xxe SYSTEM "%s">
                ]>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
                  <changeSet id="001-probe" author="test">
                    <sql>&xxe;</sql>
                  </changeSet>
                </databaseChangeLog>
                """.formatted(secret.toUri()));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogValidator.findReferencedSqlFiles(changes, master));
        assertTrue(failure.getMessage().contains("master.xml"),
                () -> "the failure should name the changelog: " + failure.getMessage());
    }

    /**
     * An include that escapes the changelog root is rejected instead of being
     * parsed, so a traversal cannot reach outside the changelog.
     */
    @Test
    void rejectsIncludeEscapingTheChangelogRoot() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="../outside.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(tempDir.resolve("outside.xml"), ChangelogTestSupport.changelog(""));

        assertThrows(IOException.class, () -> ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
    }

    /**
     * An absolute include path is rejected too.
     */
    @Test
    void rejectsAbsoluteIncludePath() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="/etc/hosts" relativeToChangelogFile="true"/>
                """));

        assertThrows(IOException.class, () -> ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
    }

    /**
     * A SQL file reference that escapes the changelog root is rejected, so a
     * {@code ../} reference is not silently reported as orphaned (or mishandled
     * on a different filesystem root).
     */
    @Test
    void rejectsSqlReferenceEscapingTheChangelogRoot() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, ChangelogTestSupport.changelog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("changes.xml"), ChangelogTestSupport.changelog("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="../outside.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """));

        assertThrows(IOException.class, () -> ChangelogValidator.findOrphanedSqlFiles(changes, master));
    }

    /**
     * The SQL extension and the exempt-directory names are matched
     * case-insensitively, so a {@code .SQL} file and a {@code Functions}
     * directory behave like their lower-case forms.
     */
    @Test
    void matchesSqlExtensionsAndExemptDirectoriesCaseInsensitively() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Files.writeString(changes.resolve("001-create.SQL"), "");
        Files.writeString(changes.resolve("create.SQL"), "");
        Path functions = Files.createDirectories(changes.resolve("Functions"));
        Files.writeString(functions.resolve("alter_table.SQL"), "");

        assertEquals(List.of(Path.of("create.SQL")), ChangelogValidator.findInvalidlyNamedSqlFiles(changes));
        assertEquals(List.of(
                        Path.of("001-create.SQL"),
                        Path.of("Functions/alter_table.SQL"),
                        Path.of("create.SQL")),
                ChangelogValidator.findSqlFiles(changes));
    }

    /**
     * A changelog that uses a prefixed Liquibase namespace is traversed exactly
     * like the default namespace: includes are followed, and changeSet and
     * sqlFile elements are found.
     */
    @Test
    void traversesAPrefixedLiquibaseNamespace() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path sqlChanges = Files.createDirectories(changes.resolve("sql_changes"));
        Files.writeString(sqlChanges.resolve("001-create.sql"), "");
        Path master = changes.resolve("master.xml");
        Files.writeString(master, prefixedChangelog("""
                <db:include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("changes.xml"), prefixedChangelog("""
                <db:changeSet id="create-example" author="test">
                    <db:sqlFile path="sql_changes/001-create.sql" relativeToChangelogFile="true"/>
                </db:changeSet>
                """));

        assertEquals(List.of(new ChangelogValidator.InvalidChangeSet(
                        changes.resolve("changes.xml"), "create-example")),
                ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(changes, master));
    }
}
