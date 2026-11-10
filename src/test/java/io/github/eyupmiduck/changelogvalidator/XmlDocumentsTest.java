package io.github.eyupmiduck.changelogvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link XmlDocuments}: the shared parser is namespace-aware and
 * hardened, and the start-tag line map keys prefixed elements by qualified name.
 */
class XmlDocumentsTest {

    @TempDir
    Path temporaryDirectory;

    private Path write(String content) throws IOException {
        Path file = temporaryDirectory.resolve("doc.xml");
        Files.writeString(file, content);
        return file;
    }

    /**
     * The line map uses the qualified name for a prefixed element, matching
     * {@code Element#getTagName()}, and reports document-order line numbers.
     */
    @Test
    void mapsPrefixedNamesToTheirStartLines() throws IOException {
        Path file = write("""
                <?xml version="1.0"?>
                <lb:databaseChangeLog xmlns:lb="urn:example">
                  <lb:include file="a.xml"/>
                  <lb:changeSet>
                    <lb:sql>SELECT 1;</lb:sql>
                  </lb:changeSet>
                </lb:databaseChangeLog>
                """);

        Map<String, Deque<Integer>> lines = XmlDocuments.startTagLines(file);

        assertEquals(2, lines.get("lb:databaseChangeLog").getFirst());
        assertEquals(3, lines.get("lb:include").getFirst());
        assertEquals(4, lines.get("lb:changeSet").getFirst());
        assertEquals(5, lines.get("lb:sql").getFirst());
    }

    /**
     * Repeated elements keep each occurrence's line in document order.
     */
    @Test
    void recordsRepeatedElementLinesInOrder() throws IOException {
        Path file = write("""
                <root>
                  <changeSet id="1"/>
                  <changeSet id="2"/>
                </root>
                """);

        Deque<Integer> lines = XmlDocuments.startTagLines(file).get("changeSet");

        assertEquals(2, lines.pollFirst());
        assertEquals(3, lines.pollFirst());
        assertTrue(lines.isEmpty());
    }

    /**
     * The DOM parse is namespace-aware, so a prefixed changelog exposes its local
     * names.
     */
    @Test
    void parsesNamespaceAwareDocuments() throws IOException {
        Path file = write("""
                <lb:databaseChangeLog xmlns:lb="urn:example">
                  <lb:changeSet id="1"/>
                </lb:databaseChangeLog>
                """);

        Document document = XmlDocuments.parse(file);

        assertEquals("databaseChangeLog", document.getDocumentElement().getLocalName());
    }

    /**
     * Both entry points reject a DOCTYPE, so an external entity cannot be
     * resolved.
     */
    @Test
    void rejectsDoctypeAndExternalEntities() throws IOException {
        Path file = write("""
                <?xml version="1.0"?>
                <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <root>&xxe;</root>
                """);

        assertThrows(IOException.class, () -> XmlDocuments.parse(file));
        assertThrows(IOException.class, () -> XmlDocuments.startTagLines(file));
    }
}
