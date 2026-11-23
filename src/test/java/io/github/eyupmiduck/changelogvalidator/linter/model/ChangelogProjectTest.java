package io.github.eyupmiduck.changelogvalidator.linter.model;

import io.github.eyupmiduck.changelogvalidator.ChangelogTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies the {@link ChangelogProject} model: the visited files and includes,
 * the effective properties, the changeset order, and the minimal project used
 * when only a changeset list is available.
 */
class ChangelogProjectTest {

    @TempDir
    Path root;

    private Path write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    @Test
    void capturesFilesIncludesPropertiesAndOrder() throws IOException {
        Path master = write("master.xml", ChangelogTestSupport.changelog("""
                <include file="a.xml" relativeToChangelogFile="true"/>
                <include file="b.xml" relativeToChangelogFile="true"/>
                """));
        Path a = write("a.xml", ChangelogTestSupport.changelog("""
                <property name="p" value="a"/>
                <changeSet id="a" author="me"><sql>SELECT '${p}';</sql></changeSet>
                """));
        Path b = write("b.xml", ChangelogTestSupport.changelog("""
                <property name="q" value="b"/>
                <changeSet id="b" author="me"><sql>SELECT '${q}';</sql></changeSet>
                """));

        ChangelogProject project = ChangelogModel.project(root, master);

        assertEquals(root.toAbsolutePath().normalize(), project.root());
        assertEquals(master.toAbsolutePath().normalize(), project.master());
        assertEquals(List.of("a", "b"), project.changesets().stream().map(ChangeSet::id).toList());
        assertEquals(3, project.files().size());
        assertEquals(List.of(a.toAbsolutePath().normalize(), b.toAbsolutePath().normalize()),
                project.files().getFirst().includes());
        assertEquals("a", project.files().get(1).properties().get("p"));
        assertEquals("b", project.files().get(2).properties().get("q"));
        assertEquals("a", project.properties().get("p"));
        assertEquals("b", project.properties().get("q"));
    }

    @Test
    void createsAMinimalProjectFromChangesets() {
        ChangeSet changeSet = new ChangeSet("001", "me", Path.of("/db/changelog.xml"), true, false,
                null, null, null, List.of(), false, List.of(), Normalisation.none(), 1);

        ChangelogProject project = ChangelogProject.of(List.of(changeSet));

        assertNull(project.root());
        assertNull(project.master());
        assertEquals(List.of(), project.files());
        assertEquals(List.of(changeSet), project.changesets());
        assertEquals(Map.of(), project.properties());
    }
}
