package io.github.eyupmiduck.changelogvalidator.testing;

import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link LiquibaseUpgrade} against a real PostgreSQL: the previous
 * changelog runs, the current changelog applies only the delta, a released
 * changeset edited in place fails validation, and a previous release read from a
 * jar behaves the same as one read from a directory.
 *
 * <p>The tests drive their own scratch database (created as the container
 * superuser) because the shared template database is already at the current
 * schema and has nothing to upgrade.
 */
class LiquibaseUpgradeTest extends PostgresTestBase {

    private static final String MASTER = ChangelogTestSupport.MASTER_RESOURCE;

    private static final LiquibaseUpgrade.TrackingSettings TRACKING =
            new LiquibaseUpgrade.TrackingSettings("liquibase", "databasechangelog", "databasechangeloglock");

    private static final String CREATE_WIDGET = """
            <changeSet id="001-create-widget" author="lee.horner" dbms="postgresql">
                <sql>CREATE TABLE upgrade_widget (id integer);</sql>
            </changeSet>
            """;

    private static final String MODIFIED_CREATE_WIDGET = """
            <changeSet id="001-create-widget" author="lee.horner" dbms="postgresql">
                <sql>CREATE TABLE upgrade_widget (id bigint);</sql>
            </changeSet>
            """;

    private static final String ADD_WIDGET_NAME = """
            <changeSet id="002-add-widget-name" author="lee.horner" dbms="postgresql">
                <sql>ALTER TABLE upgrade_widget ADD COLUMN name text;</sql>
            </changeSet>
            """;

    private Connection scratch;
    private String scratchDatabase;

    private static Path writeChangelog(Path root, String... changeSets) throws IOException {
        Path changelog = root.resolve("db/changelog");
        Path changes = changelog.resolve("changes");
        Files.createDirectories(changes);
        Files.writeString(changelog.resolve("db.changelog-master.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                            https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                    <include file="changes/changes.xml" relativeToChangelogFile="true"/>
                </databaseChangeLog>
                """);
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog
                        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                        xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                        xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                            https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                """);
        for (String changeSet : changeSets) {
            xml.append(changeSet);
        }
        Files.writeString(changes.resolve("changes.xml"), xml.append("</databaseChangeLog>\n").toString());
        return root;
    }

    private static Path jar(Path root, Path jarFile) throws IOException {
        try (OutputStream output = Files.newOutputStream(jarFile);
             JarOutputStream jar = new JarOutputStream(output);
             Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = root.relativize(path).toString().replace(File.separatorChar, '/');
                jar.putNextEntry(new JarEntry(name));
                Files.copy(path, jar);
                jar.closeEntry();
            }
        }
        return jarFile;
    }

    /**
     * Creates a scratch database with the Liquibase tracking schema.
     */
    @BeforeEach
    void createScratchDatabase() throws SQLException {
        scratchDatabase = "upgrade_" + Integer.toUnsignedString(UUID.randomUUID().hashCode(), 16);
        try (Connection admin = openAdminConnection(databaseName());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE \"" + scratchDatabase + "\"");
        }
        scratch = openAdminConnection(scratchDatabase);
        try (Statement statement = scratch.createStatement()) {
            statement.execute("CREATE SCHEMA liquibase");
        }
    }

    /**
     * Drops the scratch database, even after a failed test.
     */
    @AfterEach
    void dropScratchDatabase() throws SQLException {
        if (scratch != null) {
            scratch.close();
        }
        if (scratchDatabase != null) {
            try (Connection admin = openAdminConnection(databaseName());
                 Statement statement = admin.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS \"" + scratchDatabase + "\" WITH (FORCE)");
            }
        }
    }

    /**
     * The previous release's changesets run first, and the current changelog
     * applies only the changeset it added.
     */
    @Test
    void appliesOnlyTheDeltaOnTopOfThePreviousRelease(@TempDir Path root) throws Exception {
        Path previous = writeChangelog(root.resolve("previous"), CREATE_WIDGET);
        Path current = writeChangelog(root.resolve("current"), CREATE_WIDGET, ADD_WIDGET_NAME);

        LiquibaseUpgrade.Report report = LiquibaseUpgrade.apply(scratch, TRACKING,
                new DirectoryResourceAccessor(previous), new DirectoryResourceAccessor(current), MASTER);

        assertEquals(List.of("001-create-widget"), report.previousRelease());
        assertEquals(List.of("002-add-widget-name"), report.currentRelease());
        assertEquals(1, columnCount("upgrade_widget", "name"));
    }

    /**
     * Editing a released changeset makes the upgrade fail Liquibase's checksum
     * validation instead of silently re-running.
     */
    @Test
    void failsWhenAReleasedChangesetWasModified(@TempDir Path root) throws Exception {
        Path previous = writeChangelog(root.resolve("previous"), CREATE_WIDGET);
        Path current = writeChangelog(root.resolve("current"), MODIFIED_CREATE_WIDGET, ADD_WIDGET_NAME);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> LiquibaseUpgrade.apply(scratch, TRACKING,
                        new DirectoryResourceAccessor(previous),
                        new DirectoryResourceAccessor(current), MASTER));

        assertTrue(failure.getMessage().contains("Validation Failed")
                        || failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("check sum"),
                failure.getMessage());
    }

    /**
     * A previous release read from a jar is treated as the same changelog as the
     * current one, so only the delta runs.
     */
    @Test
    void appliesAPreviousReleaseReadFromAJar(@TempDir Path root) throws Exception {
        Path previous = writeChangelog(root.resolve("previous"), CREATE_WIDGET);
        Path jar = jar(previous, root.resolve("previous.jar"));
        Path current = writeChangelog(root.resolve("current"), CREATE_WIDGET, ADD_WIDGET_NAME);

        LiquibaseUpgrade.Report report = LiquibaseUpgrade.apply(scratch, TRACKING, jar,
                new DirectoryResourceAccessor(current), MASTER);

        assertEquals(List.of("001-create-widget"), report.previousRelease());
        assertEquals(List.of("002-add-widget-name"), report.currentRelease());
        assertEquals(1, columnCount("upgrade_widget", "name"));
    }

    private int columnCount(String table, String column) throws SQLException {
        try (PreparedStatement statement = scratch.prepareStatement(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
            statement.setString(1, "public");
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
