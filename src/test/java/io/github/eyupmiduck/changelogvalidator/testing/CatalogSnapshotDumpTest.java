package io.github.eyupmiduck.changelogvalidator.testing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link CatalogSnapshot#dump} keeps file names within the filesystem
 * limit for long routine signatures, without losing the object's document.
 */
class CatalogSnapshotDumpTest {

    /**
     * A short name is dumped verbatim.
     */
    @Test
    void keepsShortNamesReadable(@TempDir Path root) throws IOException {
        CatalogSnapshot.dump(Map.of("function/fixture.bump(integer)", "{}"), root);

        assertTrue(Files.isRegularFile(root.resolve("function/fixture.bump/(integer).json")));
    }

    /**
     * A qualified name far longer than the file-name limit is truncated with a
     * content hash, so it still dumps one file with the right document.
     */
    @Test
    void shortensALongObjectName(@TempDir Path root) throws IOException {
        String longName = "function/fixture.run("
                + "dml_utils_data.non_null_text, ".repeat(20) + "dml_utils_data.positive_integer)";

        CatalogSnapshot.dump(Map.of(longName, "{\"documented\": true}"), root);

        List<Path> files;
        try (Stream<Path> walked = Files.walk(root)) {
            files = walked.filter(Files::isRegularFile).toList();
        }
        assertEquals(1, files.size());
        String fileName = files.get(0).getFileName().toString();
        assertTrue(fileName.endsWith(".json"), fileName);
        assertTrue(fileName.length() <= 21, () -> "a long signature should fall back to a hash: " + fileName);
        assertEquals("{\"documented\": true}", Files.readString(files.get(0)).strip());
    }

    /**
     * Two long names that share a prefix get distinct files.
     */
    @Test
    void keepsLongNamesDistinct(@TempDir Path root) throws IOException {
        String prefix = "function/fixture.run(" + "text, ".repeat(60);
        CatalogSnapshot.dump(Map.of(prefix + "integer)", "{\"a\": 1}", prefix + "bigint)", "{\"b\": 2}"), root);

        try (Stream<Path> walked = Files.walk(root)) {
            assertEquals(2, walked.filter(Files::isRegularFile).count());
        }
    }

    /**
     * A second dump replaces the first, so a stale object cannot remain.
     */
    @Test
    void replacesAPreviousDump(@TempDir Path root) throws IOException {
        CatalogSnapshot.dump(Map.of("relation/fixture.old", "{}"), root);
        CatalogSnapshot.dump(Map.of("relation/fixture.new", "{}"), root);

        assertFalse(Files.exists(root.resolve("relation/fixture.old.json")));
        assertTrue(Files.isRegularFile(root.resolve("relation/fixture.new.json")));
    }
}
