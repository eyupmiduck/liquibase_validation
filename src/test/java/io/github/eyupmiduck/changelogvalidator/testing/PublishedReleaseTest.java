package io.github.eyupmiduck.changelogvalidator.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link PublishedRelease} against local HTTP servers: version
 * selection, download and cache, redirect handling without credential leakage,
 * and the failure modes.
 */
class PublishedReleaseTest {

    private static final byte[] JAR = "dml_utils-1.2.0.jar".getBytes(StandardCharsets.UTF_8);

    private HttpServer repository;
    private HttpServer blob;
    private final List<String> repositoryPaths = new CopyOnWriteArrayList<>();
    private final List<String> blobPaths = new CopyOnWriteArrayList<>();
    private final Map<String, String> repositoryAuthorization = new ConcurrentHashMap<>();
    private final Map<String, String> blobAuthorization = new ConcurrentHashMap<>();
    private HttpHandler repositoryHandler;
    private HttpHandler blobHandler;

    @TempDir
    private Path cacheDirectory;

    /**
     * Starts a repository server and a second "blob storage" server.
     */
    @BeforeEach
    void startServers() throws IOException {
        repository = start(repositoryPaths, repositoryAuthorization, () -> repositoryHandler);
        blob = start(blobPaths, blobAuthorization, () -> blobHandler);
    }

    /**
     * Stops both servers.
     */
    @AfterEach
    void stopServers() {
        if (repository != null) {
            repository.stop(0);
        }
        if (blob != null) {
            blob.stop(0);
        }
    }

    /**
     * The highest release below the current snapshot is selected, snapshots are
     * skipped, and the jar is downloaded.
     */
    @Test
    void selectsTheHighestReleaseBelowTheCurrentVersion() throws IOException {
        repositoryHandler = exchange -> {
            if (isMetadata(exchange)) {
                respond(exchange, 200, metadata("1.0.0", "1.1.0", "1.2.0", "1.2.1-SNAPSHOT"));
            } else {
                respond(exchange, 200, JAR);
            }
        };

        Optional<PublishedRelease.Resolved> resolved = PublishedRelease.resolve(
                coordinates("1.2.1-SNAPSHOT"), cacheDirectory, RegistryCredentials.none());

        assertTrue(resolved.isPresent());
        assertEquals("1.2.0", resolved.get().version());
        assertEquals(JAR.length, Files.size(resolved.get().jar()));
        assertTrue(repositoryPaths.stream().anyMatch(path -> path.endsWith("dml_utils-1.2.0.jar")));
    }

    /**
     * No release is selected when every candidate is at or above the current
     * version.
     */
    @Test
    void returnsEmptyWhenNoReleaseIsBelowTheCurrentVersion() {
        repositoryHandler = exchange -> respond(exchange, 200, metadata("1.2.1-SNAPSHOT", "1.3.0"));

        Optional<PublishedRelease.Resolved> resolved = PublishedRelease.resolve(
                coordinates("1.2.1-SNAPSHOT"), cacheDirectory, RegistryCredentials.none());

        assertTrue(resolved.isEmpty());
    }

    /**
     * A missing metadata document means nothing has ever been published, so the
     * result is empty rather than an error.
     */
    @Test
    void returnsEmptyWhenMetadataIsMissing() {
        repositoryHandler = exchange -> respond(exchange, 404, new byte[0]);

        Optional<PublishedRelease.Resolved> resolved = PublishedRelease.resolve(
                coordinates("1.2.1-SNAPSHOT"), cacheDirectory, RegistryCredentials.none());

        assertTrue(resolved.isEmpty());
    }

    /**
     * Only snapshots are published, so there is no release to upgrade from.
     */
    @Test
    void selectsNoReleaseWhenOnlySnapshotsExist() {
        repositoryHandler = exchange -> respond(exchange, 200, metadata("1.0.0-SNAPSHOT"));

        Optional<PublishedRelease.Resolved> resolved = PublishedRelease.resolve(
                coordinates("1.0.0-SNAPSHOT"), cacheDirectory, RegistryCredentials.none());

        assertTrue(resolved.isEmpty());
    }

    /**
     * A second resolution reuses the cached jar without downloading it again.
     */
    @Test
    void reusesACachedJar() {
        repositoryHandler = exchange -> {
            if (isMetadata(exchange)) {
                respond(exchange, 200, metadata("1.2.0"));
            } else {
                respond(exchange, 200, JAR);
            }
        };

        PublishedRelease.resolve(coordinates("1.2.1-SNAPSHOT"), cacheDirectory, RegistryCredentials.none());
        PublishedRelease.resolve(coordinates("1.2.1-SNAPSHOT"), cacheDirectory, RegistryCredentials.none());

        long downloads = repositoryPaths.stream().filter(path -> path.endsWith(".jar")).count();
        assertEquals(1, downloads, "the jar should be downloaded once and then cached");
    }

    /**
     * The jar redirect to blob storage is followed, and the Authorization
     * header is sent to the repository origin but not to the blob origin.
     */
    @Test
    void followsARedirectWithoutSendingCredentialsToTheBlobHost() throws IOException {
        String jarPath = "/io/github/eyupmiduck/dml_utils/1.2.0/dml_utils-1.2.0.jar";
        String blobPath = "/blob/dml_utils-1.2.0.jar";
        repositoryHandler = exchange -> {
            if (isMetadata(exchange)) {
                respond(exchange, 200, metadata("1.2.0"));
            } else {
                exchange.getResponseHeaders().add("Location", blobBase() + blobPath);
                respond(exchange, 302, new byte[0]);
            }
        };
        blobHandler = exchange -> respond(exchange, 200, JAR);

        Optional<PublishedRelease.Resolved> resolved = PublishedRelease.resolve(
                coordinates("1.2.1-SNAPSHOT"), cacheDirectory, new RegistryCredentials("octocat", "token"));

        assertTrue(resolved.isPresent());
        assertEquals(JAR.length, Files.size(resolved.get().jar()));
        assertTrue(repositoryAuthorization.containsKey(jarPath),
                "the repository should have received the Authorization header");
        assertTrue(blobPaths.contains(blobPath), "the blob host should have been asked for the jar");
        assertTrue(blobAuthorization.isEmpty(),
                "credentials must not be forwarded to the blob host");
    }

    /**
     * A rejected anonymous request fails with a message that names the
     * credentials to supply.
     */
    @Test
    void failsWithAnActionableMessageWhenCredentialsAreMissing() {
        repositoryHandler = exchange -> respond(exchange, 401, new byte[0]);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PublishedRelease.resolve(
                        coordinates("1.2.1-SNAPSHOT"), cacheDirectory, RegistryCredentials.none()));

        assertTrue(failure.getMessage().contains("401"), failure.getMessage());
        assertTrue(failure.getMessage().contains("GITHUB_ACTOR"), failure.getMessage());
        assertTrue(failure.getMessage().contains("settings.xml"), failure.getMessage());
    }

    /**
     * A rejected authenticated request says the credentials were rejected.
     */
    @Test
    void failsWithAnActionableMessageWhenCredentialsAreRejected() {
        repositoryHandler = exchange -> respond(exchange, 403, new byte[0]);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> PublishedRelease.resolve(coordinates("1.2.1-SNAPSHOT"), cacheDirectory,
                        new RegistryCredentials("octocat", "token")));

        assertTrue(failure.getMessage().contains("credentials were rejected"), failure.getMessage());
    }

    /**
     * Only the release versions below the current one are candidates.
     */
    @Test
    void selectsTheHighestReleaseFromAList() {
        assertEquals(Optional.of("1.5.1"),
                PublishedRelease.selectPreviousRelease(
                        List.of("1.0.0", "1.5.1", "1.6.0-SNAPSHOT"), "1.6.0-SNAPSHOT"));
        assertEquals(Optional.of("1.5.1"),
                PublishedRelease.selectPreviousRelease(
                        List.of("1.0.0", "1.5.1", "2.0.0"), "1.5.2"));
        assertTrue(PublishedRelease.selectPreviousRelease(List.of(), "1.0.0").isEmpty());
    }

    private HttpServer start(List<String> paths, Map<String, String> authorization,
                             Supplier<HttpHandler> handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (header != null) {
                authorization.put(exchange.getRequestURI().getPath(), header);
            }
            handler.get().handle(exchange);
        });
        server.start();
        return server;
    }

    private PublishedRelease.Coordinates coordinates(String currentVersion) {
        return new PublishedRelease.Coordinates(
                "http://127.0.0.1:" + repository.getAddress().getPort(),
                "io.github.eyupmiduck", "dml_utils", currentVersion);
    }

    private String blobBase() {
        return "http://127.0.0.1:" + blob.getAddress().getPort();
    }

    private static boolean isMetadata(HttpExchange exchange) {
        return exchange.getRequestURI().getPath().endsWith("maven-metadata.xml");
    }

    private static byte[] metadata(String... versions) {
        StringBuilder xml = new StringBuilder("<metadata><versioning><versions>");
        for (String version : versions) {
            xml.append("<version>").append(version).append("</version>");
        }
        return xml.append("</versions></versioning></metadata>")
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        if (body.length == 0) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
