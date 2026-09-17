package io.github.eyupmiduck.changelogvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the behaviour of {@link ChangelogValidator} against temporary
 * changelog directory layouts.
 */
class ChangelogValidatorTest {

    @TempDir
    Path tempDir;

    private static String databaseChangeLog(String body) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog \
                https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                %s
                </databaseChangeLog>
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
     * Changesets reachable through an include are checked by id; ids without a
     * three-digit prefix and changesets missing an id are reported, and
     * correctly named ones are not.
     */
    @Test
    void flagsChangeSetsWithoutThreeDigitPrefix() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, databaseChangeLog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("changes.xml"), databaseChangeLog("""
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
        Files.writeString(master, databaseChangeLog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("changes.xml"), databaseChangeLog("""
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
     * Includes and SQL references with {@code relativeToChangelogFile="false"}
     * are resolved against the changelog root rather than the including file.
     */
    @Test
    void resolvesPathsRelativeToChangelogRoot() throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("changelog"));
        Path nested = Files.createDirectories(root.resolve("sub"));
        Path master = root.resolve("master.xml");
        Files.writeString(master, databaseChangeLog("""
                <include file="sub/changes.xml" relativeToChangelogFile="false"/>
                """));
        Path sqlChanges = Files.createDirectories(root.resolve("sql_changes"));
        Files.writeString(sqlChanges.resolve("001-create.sql"), "");
        Files.writeString(nested.resolve("changes.xml"), databaseChangeLog("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="sql_changes/001-create.sql" relativeToChangelogFile="false"/>
                </changeSet>
                """));

        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(root, master));
        assertEquals(List.of(), ChangelogValidator.findInvalidlyNamedChangeSets(root, master));
    }

    /**
     * A cycle of includes is traversed once and does not loop forever.
     */
    @Test
    void guardsAgainstIncludeCycles() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("a.xml");
        Files.writeString(master, databaseChangeLog("""
                <include file="b.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("b.xml"), databaseChangeLog("""
                <include file="a.xml" relativeToChangelogFile="true"/>
                """));

        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(changes, master));
    }

    /**
     * Include and SQL reference elements without a path are ignored.
     */
    @Test
    void ignoresIncludesAndSqlFilesWithoutPath() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, databaseChangeLog("""
                <include file="changes.xml" relativeToChangelogFile="true"/>
                <include/>
                """));
        Path sqlChanges = Files.createDirectories(changes.resolve("sql_changes"));
        Files.writeString(sqlChanges.resolve("001-create.sql"), "");
        Files.writeString(changes.resolve("changes.xml"), databaseChangeLog("""
                <changeSet id="001-create" author="test">
                    <sqlFile path="sql_changes/001-create.sql" relativeToChangelogFile="true"/>
                    <sqlFile/>
                </changeSet>
                """));

        assertEquals(List.of(), ChangelogValidator.findOrphanedSqlFiles(changes, master));
        assertEquals(List.of(), ChangelogValidator.findInvalidlyNamedChangeSets(changes, master));
    }

    /**
     * An unparseable changelog file in the graph is reported as an illegal
     * state.
     */
    @Test
    void rejectsMalformedChangelogFile() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path master = changes.resolve("master.xml");
        Files.writeString(master, databaseChangeLog("""
                <include file="broken.xml" relativeToChangelogFile="true"/>
                """));
        Files.writeString(changes.resolve("broken.xml"), "<databaseChangeLog>");

        assertThrows(IllegalStateException.class, () -> ChangelogValidator.findOrphanedSqlFiles(changes, master));
    }
}
