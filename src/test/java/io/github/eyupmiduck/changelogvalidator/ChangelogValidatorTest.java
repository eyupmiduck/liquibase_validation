package io.github.eyupmiduck.changelogvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the behaviour of {@link ChangelogValidator} against temporary
 * changelog directory layouts.
 */
class ChangelogValidatorTest {

    @TempDir
    Path tempDir;

    /**
     * A file whose name does not start with a three-digit, zero-padded prefix
     * is reported as invalid, while correctly prefixed files (using either
     * separator) and non-changelog files are ignored.
     */
    @Test
    void flagsFilesWithoutThreeDigitPrefix() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Files.writeString(changes.resolve("001-create.xml"), "");
        Files.writeString(changes.resolve("002_create.sql"), "");
        Files.writeString(changes.resolve("create.sql"), "");
        Files.writeString(changes.resolve("readme.txt"), "");

        List<Path> invalid = ChangelogValidator.findInvalidlyNamedFiles(changes);

        assertEquals(List.of(Path.of("create.sql")), invalid);
    }

    /**
     * A SQL file that no changelog XML references is reported as orphaned,
     * while a referenced one is not.
     */
    @Test
    void reportsSqlFileAsOrphanedWhenNotReferenced() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path sqlChanges = Files.createDirectories(changes.resolve("sql_changes"));
        Files.writeString(sqlChanges.resolve("001-referenced.sql"), "");
        Files.writeString(sqlChanges.resolve("002-orphan.sql"), "");
        Files.writeString(changes.resolve("001-create.xml"), master("""
                <changeSet id="1" author="test">
                    <sqlFile path="sql_changes/001-referenced.sql" relativeToChangelogFile="true"/>
                </changeSet>
                """));

        List<Path> orphaned = ChangelogValidator.findOrphanedSqlFiles(changes);

        assertEquals(List.of(Path.of("sql_changes/002-orphan.sql")), orphaned);
    }

    /**
     * When {@code relativeToChangelogFile} is false the referenced SQL file is
     * resolved against the changelog root rather than the XML file's directory.
     */
    @Test
    void resolvesSqlFileRelativeToChangelogRoot() throws IOException {
        Path changes = Files.createDirectories(tempDir.resolve("changes"));
        Path sqlChanges = Files.createDirectories(changes.resolve("sql_changes"));
        Files.writeString(sqlChanges.resolve("001-referenced.sql"), "");
        Files.writeString(changes.resolve("001-create.xml"), master("""
                <changeSet id="1" author="test">
                    <sqlFile path="sql_changes/001-referenced.sql" relativeToChangelogFile="false"/>
                </changeSet>
                """));

        List<Path> orphaned = ChangelogValidator.findOrphanedSqlFiles(changes);

        assertEquals(List.of(), orphaned);
    }

    private static String master(String changeSets) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog \
                https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                %s
                </databaseChangeLog>
                """.formatted(changeSets);
    }
}
