package io.github.eyupmiduck.changelogvalidator.testing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the most recent published release of a Maven artifact from a Maven
 * repository, so an upgrade test can apply the release's Liquibase changelog and
 * then the current changelog.
 *
 * <p>GitHub Packages serves {@code <artifactId>/maven-metadata.xml} with a
 * {@code <versions>} listing, and the release jar at
 * {@code <version>/<artifactId>-<version>.jar}. The resolver selects the highest
 * non-{@code SNAPSHOT} version strictly below the current version, then
 * downloads (and caches) that jar.
 *
 * <p>Credentials come from {@link RegistryCredentials} and are sent only to the
 * repository's own origin: GitHub Packages answers the jar request with a
 * {@code 302} to blob storage, and the {@code Authorization} header must not
 * travel to that other host. Redirects are therefore followed manually.
 *
 * <p>This class performs network I/O, so it is used only by tests that opt in to
 * the upstream upgrade; the rest of the suite does not touch the network.
 */
public final class PublishedRelease {

    /**
     * The default directory the release jar is cached in, relative to the
     * module's working directory.
     */
    public static final Path DEFAULT_CACHE_DIRECTORY = Path.of("target", "previous-release");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_REDIRECTS = 5;
    private static final Pattern VERSION_ELEMENT =
            Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");

    private PublishedRelease() {
    }

    /**
     * Resolves the most recent published release using the default cache
     * directory and detected credentials.
     *
     * @param coordinates the artifact coordinates
     * @return the resolved release, or empty when no release is below the
     * current version
     */
    public static Optional<Resolved> resolve(Coordinates coordinates) {
        return resolve(coordinates, DEFAULT_CACHE_DIRECTORY, RegistryCredentials.detect());
    }

    /**
     * Resolves the most recent published release using detected credentials.
     *
     * @param coordinates    the artifact coordinates
     * @param cacheDirectory the directory the jar is cached in
     * @return the resolved release, or empty when no release is below the
     * current version
     */
    public static Optional<Resolved> resolve(Coordinates coordinates, Path cacheDirectory) {
        return resolve(coordinates, cacheDirectory, RegistryCredentials.detect());
    }

    /**
     * Resolves the most recent published release with explicit credentials.
     *
     * @param coordinates    the artifact coordinates
     * @param cacheDirectory the directory the jar is cached in
     * @param credentials    the registry credentials
     * @return the resolved release, or empty when no release is below the
     * current version
     */
    static Optional<Resolved> resolve(Coordinates coordinates, Path cacheDirectory,
                                      RegistryCredentials credentials) {
        Objects.requireNonNull(coordinates, "coordinates");
        Objects.requireNonNull(cacheDirectory, "cacheDirectory");
        Objects.requireNonNull(credentials, "credentials");

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        URI repository = URI.create(coordinates.baseUrl());
        String artifactPath = coordinates.groupId().replace('.', '/') + "/" + coordinates.artifactId();

        URI metadataUri = URI.create(repository + "/" + artifactPath + "/maven-metadata.xml");
        HttpResponse<byte[]> metadataResponse = send(client, metadataUri, credentials, repository);
        if (metadataResponse.statusCode() == 404) {
            // No release has ever been published under this coordinate.
            return Optional.empty();
        }
        requireSuccess(metadataResponse, metadataUri, credentials, "Maven metadata");
        List<String> versions =
                parseVersions(new String(metadataResponse.body(), StandardCharsets.UTF_8));
        Optional<String> selected = selectPreviousRelease(versions, coordinates.currentVersion());
        if (selected.isEmpty()) {
            return Optional.empty();
        }

        String version = selected.get();
        Path jar = cacheDirectory.resolve(coordinates.artifactId() + "-" + version + ".jar");
        if (isUsable(jar)) {
            return Optional.of(new Resolved(version, jar));
        }

        URI jarUri = URI.create(repository + "/" + artifactPath + "/" + version + "/"
                + coordinates.artifactId() + "-" + version + ".jar");
        HttpResponse<byte[]> jarResponse = send(client, jarUri, credentials, repository);
        requireSuccess(jarResponse, jarUri, credentials, "release jar");
        writeAtomically(jar, jarResponse.body());
        return Optional.of(new Resolved(version, jar));
    }

    /**
     * Parses the {@code <version>} elements from a {@code maven-metadata.xml}
     * document.
     *
     * @param metadata the metadata document
     * @return the versions in document order
     */
    static List<String> parseVersions(String metadata) {
        List<String> versions = new ArrayList<>();
        Matcher matcher = VERSION_ELEMENT.matcher(metadata);
        while (matcher.find()) {
            versions.add(matcher.group(1));
        }
        return versions;
    }

    /**
     * Selects the highest non-{@code SNAPSHOT} version strictly below the
     * current version.
     *
     * @param versions       the candidate versions
     * @param currentVersion the version being built
     * @return the selected version, or empty when there is none
     */
    static Optional<String> selectPreviousRelease(List<String> versions, String currentVersion) {
        return versions.stream()
                .filter(version -> !MavenVersion.isSnapshot(version))
                .filter(version -> MavenVersion.compare(version, currentVersion) < 0)
                .max(MavenVersion::compare);
    }

    private static HttpResponse<byte[]> send(HttpClient client, URI uri,
                                             RegistryCredentials credentials, URI authOrigin) {
        URI current = uri;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(current)
                    .timeout(REQUEST_TIMEOUT)
                    .GET();
            if (credentials.present() && sameOrigin(current, authOrigin)) {
                request.header("Authorization", basicAuth(credentials));
            }
            HttpResponse<byte[]> response = execute(client, request.build());
            int status = response.statusCode();
            if (status < 300 || status >= 400) {
                return response;
            }
            Optional<String> location = response.headers().firstValue("Location");
            if (location.isEmpty()) {
                throw new IllegalStateException(
                        "HTTP " + status + " from " + current + " without a Location header");
            }
            current = current.resolve(location.get());
        }
        throw new IllegalStateException("too many redirects fetching " + uri);
    }

    private static HttpResponse<byte[]> execute(HttpClient client, HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("could not reach " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while fetching " + request.uri(), e);
        }
    }

    private static void requireSuccess(HttpResponse<byte[]> response, URI uri,
                                       RegistryCredentials credentials, String what) {
        int status = response.statusCode();
        if (status == 200) {
            return;
        }
        if (status == 401 || status == 403) {
            String hint = credentials.present()
                    ? "the supplied credentials were rejected; check the token has read:packages "
                    + "and the repository has access to the package"
                    : "no credentials were found; set GITHUB_ACTOR/GITHUB_TOKEN or add a '"
                    + RegistryCredentials.DEFAULT_SERVER_ID + "' server to ~/.m2/settings.xml";
            throw new IllegalStateException(
                    what + " at " + uri + " returned HTTP " + status + ": " + hint);
        }
        throw new IllegalStateException(what + " at " + uri + " returned HTTP " + status);
    }

    private static boolean sameOrigin(URI left, URI right) {
        return origin(left).equals(origin(right));
    }

    private static String origin(URI uri) {
        int port = uri.getPort();
        if (port < 0) {
            port = switch (uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT)) {
                case "https" -> 443;
                case "http" -> 80;
                default -> -1;
            };
        }
        return uri.getScheme() + "://" + uri.getHost() + ":" + port;
    }

    private static String basicAuth(RegistryCredentials credentials) {
        String raw = credentials.username() + ":" + credentials.password();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isUsable(Path jar) {
        try {
            return Files.isRegularFile(jar) && Files.size(jar) > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeAtomically(Path target, byte[] body) {
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());
            Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(),
                    target.getFileName().toString(), ".part");
            try {
                Files.write(temporary, body);
                try {
                    Files.move(temporary, target,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("could not write " + target, e);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * The coordinates of the artifact to resolve.
     *
     * @param baseUrl        the repository base URL, for example
     *                       {@code https://maven.pkg.github.com/eyupmiduck/dml_utils}
     * @param groupId        the Maven group id
     * @param artifactId     the Maven artifact id
     * @param currentVersion the version being built; the release must be below it
     */
    public record Coordinates(String baseUrl, String groupId, String artifactId, String currentVersion) {

        /**
         * Validates the coordinates.
         */
        public Coordinates {
            requireNonBlank(baseUrl, "baseUrl");
            requireNonBlank(groupId, "groupId");
            requireNonBlank(artifactId, "artifactId");
            requireNonBlank(currentVersion, "currentVersion");
        }
    }

    /**
     * A resolved release and the local path of its jar.
     *
     * @param version the release version
     * @param jar     the cached jar
     */
    public record Resolved(String version, Path jar) {
    }
}
