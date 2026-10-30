package io.github.eyupmiduck.changelogvalidator;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Path;

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
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("failed to configure the XML parser", e);
        }
    }
}
