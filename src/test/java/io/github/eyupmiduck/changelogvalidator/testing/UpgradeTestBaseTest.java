package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link UpgradeTestBase} against a real PostgreSQL, with a fixture
 * jar standing in for the previous release.
 *
 * <p>The fixture jar holds the same classpath changelog, so the previous run
 * applies its changesets and the current run applies none. That is the strongest
 * assertion that the jar accessor and the classpath accessor record the same
 * {@code FILENAME}: a mismatch would make the second run attempt (and fail) to
 * re-apply the shipped changesets.
 */
class UpgradeTestBaseTest extends UpgradeTestBase {

    private static Path buildFixtureJar() throws IOException {
        Path db = ChangelogTestSupport.changelogRoot().getParent();
        Path fixture = Path.of("target", "upgrade-test-fixture");
        Files.createDirectories(fixture);
        Path jar = fixture.resolve("previous.jar");
        try (OutputStream output = Files.newOutputStream(jar);
             JarOutputStream jarOutput = new JarOutputStream(output);
             Stream<Path> paths = Files.walk(db)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = "db/" + db.relativize(path).toString().replace(File.separatorChar, '/');
                jarOutput.putNextEntry(new JarEntry(name));
                Files.copy(path, jarOutput);
                jarOutput.closeEntry();
            }
        }
        return jar;
    }

    /**
     * The coordinates are unused because the fixture jar is supplied directly.
     */
    @Override
    protected PublishedRelease.Coordinates releaseCoordinates() {
        return new PublishedRelease.Coordinates(
                "https://example.invalid/repo", "io.github.eyupmiduck", "liquibase-validation", "9999.0.0");
    }

    /**
     * Supplies a jar built from the classpath changelog fixture.
     */
    @Override
    protected Optional<Path> previousReleaseJar() {
        try {
            return Optional.of(buildFixtureJar());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The previous release's changesets are recorded, the current changelog adds
     * nothing, and the upgraded database is usable as the test role.
     */
    @Test
    void appliesThePreviousReleaseAndSkipsTheAlreadyAppliedChangesets() {
        assertEquals(List.of("001-placeholder"), report().previousRelease());
        assertTrue(report().currentRelease().isEmpty(),
                () -> "no changeset should run on top of the shipped release, but got "
                        + report().currentRelease());
        assertEquals(1, upgradeDsl().fetchOne("SELECT 1").get(0, Integer.class));
        assertTrue(upgradeDatabaseName().startsWith("upgrade_"));
    }
}
