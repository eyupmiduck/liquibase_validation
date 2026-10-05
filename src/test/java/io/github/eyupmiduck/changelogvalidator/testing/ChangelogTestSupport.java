package io.github.eyupmiduck.changelogvalidator.testing;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Classpath access to a Liquibase changelog, shared by the static
 * (non-container) changelog tests and the container test base.
 *
 * <p>The conventional layout is a {@code db/changelog} directory on the test
 * classpath with a {@code db.changelog-master.xml} master and a
 * {@code changes/} subdirectory holding the changeset XML and SQL files.
 */
public final class ChangelogTestSupport {

    /**
     * The master changelog resource path, relative to the classpath root.
     */
    public static final String MASTER_RESOURCE = "db/changelog/db.changelog-master.xml";

    private ChangelogTestSupport() {
    }

    /**
     * Returns the default changelog root directory ({@code db/changelog}).
     *
     * @return the changelog root
     */
    public static Path changelogRoot() {
        return changelogRoot("db/changelog");
    }

    /**
     * Returns a changelog root directory by classpath resource name, for
     * changelogs that do not use the default {@code db/changelog} location.
     *
     * @param resource the classpath resource directory
     * @return the changelog root
     * @throws NullPointerException  when the resource is absent from the classpath
     * @throws IllegalStateException when the resource is not an exploded directory
     */
    public static Path changelogRoot(String resource) {
        return classpathDir(resource);
    }

    /**
     * Returns the changes directory ({@code db/changelog/changes}) holding the
     * changelog XML and the SQL files.
     *
     * @return the changes directory
     */
    public static Path changesRoot() {
        return classpathDir("db/changelog/changes");
    }

    /**
     * Returns the master changelog file.
     *
     * @return the master changelog path
     */
    public static Path master() {
        return changelogRoot().resolve(Path.of(MASTER_RESOURCE).getFileName());
    }

    private static Path classpathDir(String resource) {
        URL url = ChangelogTestSupport.class.getClassLoader().getResource(resource);
        Objects.requireNonNull(url, resource + " must be on the test classpath");
        // These tests walk the changelog as files, so they require an exploded
        // classpath. Fail with a clear message rather than a FileSystemNotFound
        // when run from a packaged (jar:) test classpath.
        if (!"file".equals(url.getProtocol())) {
            throw new IllegalStateException(resource
                    + " must be an exploded directory on the test classpath, but was " + url);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("invalid classpath URL for " + resource, e);
        }
    }
}
