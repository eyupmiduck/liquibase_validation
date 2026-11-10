package io.github.eyupmiduck.changelogvalidator;

import org.w3c.dom.Document;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Parses changelog XML defensively.
 *
 * <p>A changelog is untrusted input: it can come from a released jar or from a
 * repository under review. The parser therefore rejects any {@code DOCTYPE}
 * declaration and never resolves external entities, so a crafted changelog
 * cannot read a local file, reach the network, or exhaust the process with
 * entity expansion. The validator and the linter share this parser, so the
 * policy cannot drift between them.
 */
public final class XmlDocuments {

    private XmlDocuments() {
    }

    /**
     * Parses a namespace-aware XML document with DTDs and external entities
     * disabled.
     *
     * @param documentFile the XML file
     * @return the parsed document
     * @throws IOException if the file cannot be read or parsed, including when
     *                     it declares a {@code DOCTYPE}
     */
    public static Document parse(Path documentFile) throws IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            // Reject a DOCTYPE outright; the external-entity flags are defence in
            // depth for a parser that accepts one despite the feature.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(documentFile.toFile());
        } catch (SAXException e) {
            throw new IOException("failed to parse XML " + documentFile, e);
        } catch (IllegalArgumentException e) {
            throw new IOException("failed to configure the XML parser", e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("failed to configure the XML parser", e);
        }
    }

    /**
     * Returns the one-based start-tag line of each element name, in document
     * order. A namespace-prefixed name is keyed by its qualified name, matching
     * {@link org.w3c.dom.Element#getTagName()}.
     *
     * @param documentFile the XML file
     * @return the line numbers by element name, in document order
     * @throws IOException if the file cannot be read or parsed
     */
    public static Map<String, Deque<Integer>> startTagLines(Path documentFile) throws IOException {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            // Supply the qualified name for prefixed elements, matching DOM's
            // Element#getTagName().
            factory.setFeature("http://xml.org/sax/features/namespace-prefixes", true);
            TagLineHandler handler = new TagLineHandler();
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            reader.setContentHandler(handler);
            try (InputStream input = Files.newInputStream(documentFile)) {
                reader.parse(new InputSource(input));
            }
            return handler.lines();
        } catch (SAXException e) {
            throw new IOException("failed to parse XML " + documentFile, e);
        } catch (IllegalArgumentException e) {
            throw new IOException("failed to configure the XML parser", e);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("failed to configure the XML parser", e);
        }
    }

    /**
     * Collects start-tag lines from a SAX parse.
     */
    private static final class TagLineHandler extends DefaultHandler {

        private final Map<String, Deque<Integer>> lines = new HashMap<>();
        private Locator locator;

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            if (locator == null) {
                throw new SAXException("the SAX parser did not supply a locator");
            }
            String name = qName == null || qName.isEmpty() ? localName : qName;
            int line = locator.getLineNumber();
            lines.computeIfAbsent(name, ignored -> new ArrayDeque<>()).add(line < 1 ? 1 : line);
        }

        private Map<String, Deque<Integer>> lines() {
            return lines;
        }
    }
}
