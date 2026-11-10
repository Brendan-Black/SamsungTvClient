package black.samsungtvclient.util;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Small DOM helpers. Lookups are by local name so callers needn't care about namespace prefixes. */
public final class Xml {
	private Xml() {}

	/**
	 * Parses a document with DTDs and external entities disabled (the input comes off the network).
	 *
	 * @throws IllegalArgumentException if the text is not well-formed XML
	 */
	public static Document parse(String xml) {
		try {
			DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
			f.setNamespaceAware(true);
			f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
			f.setXIncludeAware(false);
			f.setExpandEntityReferences(false);
			DocumentBuilder builder = f.newDocumentBuilder();
			builder.setErrorHandler(new DefaultHandler()); // throw on errors without printing them to stderr
			return builder.parse(new InputSource(new StringReader(xml)));
		} catch (ParserConfigurationException | SAXException | IOException e) {
			throw new IllegalArgumentException("malformed XML: " + e.getMessage(), e);
		}
	}

	/** The first descendant element of {@code scope} with this local name, if there is one. */
	public static Optional<Element> first(Node scope, String localName) {
		NodeList list = descendants(scope, localName);
		return list.getLength() == 0 ? Optional.empty() : Optional.of((Element) list.item(0));
	}

	/** All descendant elements of {@code scope} with this local name, in document order. */
	public static List<Element> all(Node scope, String localName) {
		NodeList list = descendants(scope, localName);
		List<Element> out = new ArrayList<>(list.getLength());
		for (int i = 0; i < list.getLength(); i++) { out.add((Element) list.item(i)); }
		return List.copyOf(out);
	}

	/** Trimmed text of the first descendant element with this local name, if there is one. */
	public static Optional<String> text(Node scope, String localName) {
		return first(scope, localName).map(e -> e.getTextContent().trim());
	}

	/** The direct element children of {@code parent}. */
	public static List<Element> children(Element parent) {
		List<Element> out = new ArrayList<>();
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e) { out.add(e); }
		}
		return List.copyOf(out);
	}

	/** Escapes text for use in XML element content or a quoted attribute value. */
	public static String escape(String s) {
		StringBuilder sb = new StringBuilder(s.length() + 16);
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '&' -> sb.append("&amp;");
				case '<' -> sb.append("&lt;");
				case '>' -> sb.append("&gt;");
				case '"' -> sb.append("&quot;");
				case '\'' -> sb.append("&apos;");
				default -> sb.append(c);
			}
		}
		return sb.toString();
	}

	private static NodeList descendants(Node scope, String localName) {
		return scope instanceof Document d
			? d.getElementsByTagNameNS("*", localName)
			: ((Element) scope).getElementsByTagNameNS("*", localName);
	}
}
