package io.github.eyupmiduck.changelogvalidator.testing;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Classpath access to the test resources a changelog project keeps beside its
 * tests: a single file's contents and every {@code .sql} file under a directory.
 *
 * <p>A directory is read from an exploded classpath and from a jar alike, so a
 * consumer can read its changelog files whether the tests run from the module
 * directory or from a packaged test classpath.
 */
public final class TestResources {

    private TestResources() {
    }

    /**
     * Reads a classpath resource as UTF-8 text.
     *
     * @param resource the resource path, relative to the classpath root
     * @return the file's contents
     * @throws IOException if the resource cannot be read
     */
    public static String read(String resource) throws IOException {
        try (InputStream in = TestResources.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(in, () -> "resource not found: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Reads the contents of every {@code .sql} file under a classpath directory,
     * in file-name order, so a caller's diagnostics are deterministic.
     *
     * @param directory the classpath directory, relative to the classpath root
     * @return the files' contents
     * @throws IOException if the directory cannot be read
     */
    public static List<String> readSqlFiles(String directory) throws IOException {
        URL url = TestResources.class.getClassLoader().getResource(directory);
        if (url == null) {
            throw new IOException(directory + " must be on the test classpath");
        }
        try {
            if ("file".equals(url.getProtocol())) {
                return readFromDirectory(Path.of(url.toURI()));
            }
            try (FileSystem fileSystem = FileSystems.newFileSystem(url.toURI(), Map.of())) {
                return readFromDirectory(fileSystem.getPath(directory));
            }
        } catch (URISyntaxException e) {
            throw new IOException("invalid classpath URL for " + directory, e);
        }
    }

    private static List<String> readFromDirectory(Path directory) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(directory)) {
            files = walk.filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        List<String> contents = new ArrayList<>();
        for (Path file : files) {
            contents.add(Files.readString(file));
        }
        return contents;
    }
}
