package io.github.eyupmiduck.changelogvalidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ChangelogIncludes}: includeAll selection, ordering, depth,
 * filters, containment and the supported attribute subset.
 */
class ChangelogIncludesTest {

    private static final String XML_HEADER = """
            <?xml version="1.0" encoding="UTF-8"?>
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog">
            """;

    @TempDir
    Path root;

    private Element includeAll(String attributes) throws IOException {
        Path master = root.resolve("master.xml");
        Files.writeString(master, XML_HEADER + "<includeAll " + attributes + "/></databaseChangeLog>");
        Document document = XmlDocuments.parse(master);
        return (Element) document.getElementsByTagNameNS("*", "includeAll").item(0);
    }

    private List<Path> include(String attributes) throws IOException {
        Path master = root.resolve("master.xml");
        return ChangelogIncludes.includeAll(root.toAbsolutePath().normalize(), master, includeAll(attributes));
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * Files are sorted by their path under the include directory and non-changelog
     * files are ignored.
     */
    @Test
    void sortsFilesAndIgnoresNonChangelogs() throws IOException {
        write("included/002-second.xml", "");
        write("included/001-first.xml", "");
        write("included/README.md", "not a changelog");

        assertEquals(List.of(
                        root.resolve("included/001-first.xml"),
                        root.resolve("included/002-second.xml")),
                include("path=\"included\""));
    }

    /**
     * minDepth and maxDepth are inclusive and relative to the include directory.
     */
    @Test
    void honoursMinAndMaxDepth() throws IOException {
        write("included/001-top.xml", "");
        write("included/sub/002-nested.xml", "");
        write("included/sub/deep/003-deep.xml", "");

        assertEquals(List.of(root.resolve("included/sub/002-nested.xml")),
                include("path=\"included\" minDepth=\"2\" maxDepth=\"2\""));
    }

    /**
     * Liquibase's internal default minDepth is 0; an explicit 0 selects the same
     * files as the default here, where a direct child is depth 1.
     */
    @Test
    void acceptsZeroMinDepthAsTheDefault() throws IOException {
        write("included/001-top.xml", "");
        write("included/sub/002-nested.xml", "");

        assertEquals(List.of(root.resolve("included/001-top.xml")),
                include("path=\"included\" minDepth=\"0\" maxDepth=\"1\""));
        assertTrue(assertThrows(IOException.class, () -> include("path=\"included\" minDepth=\"-1\""))
                .getMessage().contains("must not be negative"));
    }

    /**
     * maxDepth 0 means unlimited, symmetrically with minDepth 0 meaning the
     * default, so an explicit 0/0 pair is accepted and selects every depth.
     */
    @Test
    void acceptsZeroMaxDepthAsUnlimited() throws IOException {
        write("included/001-top.xml", "");
        write("included/sub/002-nested.xml", "");
        write("included/sub/deep/003-deep.xml", "");

        List<Path> all = List.of(
                root.resolve("included/001-top.xml"),
                root.resolve("included/sub/002-nested.xml"),
                root.resolve("included/sub/deep/003-deep.xml"));
        assertEquals(all, include("path=\"included\" minDepth=\"0\" maxDepth=\"0\""));
        assertEquals(all, include("path=\"included\" maxDepth=\"0\""));
        assertTrue(assertThrows(IOException.class, () -> include("path=\"included\" maxDepth=\"-1\""))
                .getMessage().contains("must not be negative"));
    }

    /**
     * A malformed boolean attribute fails closed instead of being read as false,
     * which would silently change which directory or file set is validated.
     */
    @Test
    void rejectsMalformedBooleanAttributes() throws IOException {
        Files.createDirectories(root.resolve("included"));
        assertTrue(assertThrows(IOException.class,
                () -> include("path=\"included\" relativeToChangelogFile=\"ture\""))
                .getMessage().contains("not a boolean"));
        assertTrue(assertThrows(IOException.class,
                () -> include("path=\"included\" errorIfMissingOrEmpty=\"yes\""))
                .getMessage().contains("not a boolean"));
    }

    /**
     * endsWithFilter is case-insensitive and trims whitespace.
     */
    @Test
    void honoursEndsWithFilterCaseInsensitively() throws IOException {
        write("included/001-first.XML", "");
        write("included/002-second.sql", "");

        assertEquals(List.of(root.resolve("included/001-first.XML")),
                include("path=\"included\" endsWithFilter=\" .xml \""));
    }

    /**
     * A root-relative path resolves against the changelog root, not the master's
     * directory.
     */
    @Test
    void resolvesAgainstTheRootByDefault() throws IOException {
        write("config/master.xml", "");
        write("included/001-first.xml", "");
        Path master = root.resolve("config/master.xml");
        Files.writeString(master, XML_HEADER + """
                <includeAll path="included"/>
                </databaseChangeLog>
                """);

        assertEquals(List.of(root.resolve("included/001-first.xml")),
                ChangelogIncludes.includeAll(root.toAbsolutePath().normalize(), master,
                        (Element) XmlDocuments.parse(master)
                                .getElementsByTagNameNS("*", "includeAll").item(0)));
    }

    /**
     * A missing or empty directory fails when required and is empty otherwise.
     */
    @Test
    void honoursErrorIfMissingOrEmpty() throws IOException {
        assertTrue(assertThrows(IOException.class, () -> include("path=\"missing\""))
                .getMessage().contains("not found"));

        Files.createDirectories(root.resolve("included"));
        assertTrue(assertThrows(IOException.class, () -> include("path=\"included\""))
                .getMessage().contains("empty"));

        assertEquals(List.of(), include("path=\"included\" errorIfMissingOrEmpty=\"false\""));
    }

    /**
     * A symlink inside the include directory that points outside the root is
     * rejected.
     */
    @Test
    void rejectsSymlinkEscape() throws IOException {
        write("included/001-first.xml", "");
        Path outside = Files.createDirectories(root.getParent().resolve("outside"));
        Files.writeString(outside.resolve("secret.xml"), "");
        Files.createSymbolicLink(root.resolve("included/linked.xml"), outside.resolve("secret.xml"));

        IOException failure = assertThrows(IOException.class, () -> include("path=\"included\""));
        assertTrue(failure.getMessage().contains("symlink"), failure.getMessage());
    }

    /**
     * A dangling symlink is inspected through the link itself and fails closed:
     * following it would make it look absent and skip the containment check.
     */
    @Test
    void rejectsDanglingSymlinkEscape() throws IOException {
        Path rootPath = root.toAbsolutePath().normalize();
        Files.createSymbolicLink(root.resolve("dangling.xml"),
                root.getParent().resolve("missing-secret.xml"));

        IOException failure = assertThrows(IOException.class,
                () -> ChangelogIncludes.resolveWithin(rootPath, rootPath, "dangling.xml", "include"));
        assertTrue(failure.getMessage().contains("symlink"), failure.getMessage());
    }

    /**
     * Invalid depth attributes and unsupported attributes fail closed.
     */
    @Test
    void rejectsInvalidAttributes() throws IOException {
        assertTrue(assertThrows(IOException.class, () -> include("path=\"included\" minDepth=\"x\""))
                .getMessage().contains("not an integer"));
        assertTrue(assertThrows(IOException.class,
                () -> include("path=\"included\" minDepth=\"2\" maxDepth=\"1\""))
                .getMessage().contains("maxDepth"));
        assertTrue(assertThrows(IOException.class, () -> include("path=\"\""))
                .getMessage().contains("without a path"));
        for (String attribute : List.of(
                "filter=\"com.example.Filter\"",
                "resourceComparator=\"com.example.Comparator\"",
                "logicalFilePath=\"logical.xml\"",
                "ignore=\"true\"")) {
            assertTrue(assertThrows(IOException.class, () -> include("path=\"included\" " + attribute))
                    .getMessage().contains("not supported"), attribute);
        }
    }

    /**
     * A YAML or JSON changelog found by includeAll fails closed instead of being
     * skipped.
     */
    @Test
    void rejectsUnsupportedChangelogFormats() throws IOException {
        write("included/001-first.yaml", "");
        assertTrue(assertThrows(IOException.class, () -> include("path=\"included\""))
                .getMessage().contains("unsupported changelog format"));
    }

    /**
     * A formatted SQL changelog is detected by its first non-blank line, with a
     * BOM tolerated; an ordinary SQL file is not.
     */
    @Test
    void detectsFormattedSqlChangelogs() throws IOException {
        write("formatted.sql", "\n\n--liquibase formatted sql\n--changeset a:1\nSELECT 1;\n");
        write("bom.sql", "\uFEFF--liquibase formatted sql\n--changeset a:1\nSELECT 1;\n");
        write("plain.sql", "SELECT 1;\n");
        write("empty.sql", "");

        assertTrue(ChangelogIncludes.isFormattedSql(root.resolve("formatted.sql")));
        assertTrue(ChangelogIncludes.isFormattedSql(root.resolve("bom.sql")));
        assertFalse(ChangelogIncludes.isFormattedSql(root.resolve("plain.sql")));
        assertFalse(ChangelogIncludes.isFormattedSql(root.resolve("empty.sql")));
    }

    /**
     * A SQL file that is not valid UTF-8 is decoded leniently and classified as
     * not formatted, instead of aborting the graph with a decode error.
     */
    @Test
    void decodesNonUtf8SqlLeniently() throws IOException {
        Path latin1 = root.resolve("latin1.sql");
        Files.write(latin1, new byte[]{'S', 'E', 'L', 'E', 'C', 'T', ' ', (byte) 0xE9, '\n'});

        assertFalse(ChangelogIncludes.isFormattedSql(latin1));
    }
}
