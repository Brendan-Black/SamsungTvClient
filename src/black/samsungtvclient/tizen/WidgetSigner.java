package black.samsungtvclient.tizen;

import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.util.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.crypto.Data;
import javax.xml.crypto.MarshalException;
import javax.xml.crypto.OctetStreamData;
import javax.xml.crypto.URIDereferencer;
import javax.xml.crypto.URIReference;
import javax.xml.crypto.URIReferenceException;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.XMLStructure;
import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignatureProperty;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLObject;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Packages a Tizen web app as a signed {@code .wgt}: a zip of the app's files plus two XML Digital Signatures for
 * Widgets, laid out as Samsung's own packager writes them. {@code author-signature.xml} signs every file with the
 * author's key; {@code signature1.xml} signs every file and the author signature with the distributor's key. Each
 * names its role in a {@code #prop} object, and carries its certificate chain (without the root) in KeyInfo.
 */
public final class WidgetSigner {
	static final String AUTHOR_SIGNATURE = "author-signature.xml";
	static final String DISTRIBUTOR_SIGNATURE = "signature1.xml";
	private static final String DSIG_PROPERTIES = "http://www.w3.org/2009/xmldsig-properties";
	private static final String WIDGETS_DIGSIG = "http://www.w3.org/ns/widgets-digsig#";

	static {
		// The JDK wraps base64 with the platform's line ending; on Windows that's a CR, written out as &#13;, inside
		// digests, signature values and certificates. Unwrapped base64 is valid everywhere. Read once, so set early.
		System.setProperty("com.sun.org.apache.xml.internal.security.ignoreLineBreaks", "true");
	}

	/** A signing key and its certificate chain, leaf first. */
	public record Signer(PrivateKey key, List<X509Certificate> chain) {
		public Signer {
			chain = List.copyOf(chain);
			if (chain.isEmpty()) { throw new IllegalArgumentException("a signer needs its certificate"); }
		}

		/** Debug output: the generated one would print the private key. */
		@Override
		public String toString() {
			return "Signer[" + chain.getFirst().getSubjectX500Principal().getName() + "]";
		}
	}

	private WidgetSigner() {}

	/** The app in {@code dir} (which must hold a config.xml), signed. Dot files and old signatures are left out. */
	public static byte[] sign(Path dir, Signer author, Signer distributor) throws IOException, GeneralSecurityException {
		return sign(read(dir), author, distributor);
	}

	/** {@code files} (zip path to contents), signed and zipped. */
	static byte[] sign(Map<String, byte[]> files, Signer author, Signer distributor) throws IOException, GeneralSecurityException {
		if (!files.containsKey("config.xml")) { throw new IOException("not a Tizen web app: no config.xml at its top level"); }
		TreeMap<String, byte[]> signed = new TreeMap<>(files);
		signed.keySet().removeIf(WidgetSigner::isSignature);
		signed.put(AUTHOR_SIGNATURE, signature(signed, author, "AuthorSignature", "role-author"));
		byte[] distributorSignature = signature(signed, distributor, "DistributorSignature", "role-distributor");
		signed.put(DISTRIBUTOR_SIGNATURE, distributorSignature);

		ByteArrayOutputStream wgt = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(wgt)) {
			for (Map.Entry<String, byte[]> e : signed.entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey()));
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return wgt.toByteArray();
	}

	/** The files in a {@code .wgt}, by zip path. */
	public static Map<String, byte[]> unzip(byte[] wgt) throws IOException {
		Map<String, byte[]> files = new TreeMap<>();
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(wgt))) {
			for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
				if (!e.isDirectory()) { files.put(e.getName(), zip.readAllBytes()); }
			}
		}
		return files;
	}

	/**
	 * Checks a signed package the way the TV starts to: both signatures verify, each covers every other file (the
	 * distributor's covers the author's too), and each names its role. Doesn't check whom the certificates chain to.
	 *
	 * @throws GeneralSecurityException saying what's wrong
	 */
	public static void verify(byte[] wgt) throws IOException, GeneralSecurityException {
		Map<String, byte[]> files = unzip(wgt);
		verifyOne(files, AUTHOR_SIGNATURE, "role-author");
		verifyOne(files, DISTRIBUTOR_SIGNATURE, "role-distributor");
	}

	// ---- signing -------------------------------------------------------------------------------------

	private static byte[] signature(Map<String, byte[]> files, Signer signer, String id, String role)
		throws IOException, GeneralSecurityException {
		XMLSignatureFactory f = XMLSignatureFactory.getInstance("DOM");
		DigestMethod sha512 = f.newDigestMethod(DigestMethod.SHA512, null);
		List<Reference> refs = new ArrayList<>();
		for (String name : files.keySet()) { refs.add(f.newReference(encode(name), sha512)); }
		Transform c14n11 = f.newTransform(CanonicalizationMethod.INCLUSIVE_11, (TransformParameterSpec) null);
		refs.add(f.newReference("#prop", sha512, List.of(c14n11), null, null));
		SignedInfo info = f.newSignedInfo(
			f.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE, (C14NMethodParameterSpec) null),
			f.newSignatureMethod(SignatureMethod.RSA_SHA512, null), refs);

		Document doc = newDocument();
		List<SignatureProperty> props = List.of(
			property(f, doc, id, "profile", "Profile", WIDGETS_DIGSIG + "profile"),
			property(f, doc, id, "role", "Role", WIDGETS_DIGSIG + role),
			property(f, doc, id, "identifier", "Identifier", null));
		XMLObject prop = f.newXMLObject(List.of(f.newSignatureProperties(props, null)), "prop", null, null);

		KeyInfoFactory kf = f.getKeyInfoFactory();
		X509Data chain = kf.newX509Data(signer.chain());
		XMLSignature signature = f.newXMLSignature(info, kf.newKeyInfo(List.of(chain)), List.of(prop), id, null);

		DOMSignContext ctx = new DOMSignContext(signer.key(), doc);
		ctx.setURIDereferencer(new FileDereferencer(files, f.getURIDereferencer()));
		try {
			signature.sign(ctx);
		} catch (MarshalException | XMLSignatureException e) {
			throw new GeneralSecurityException("couldn't sign: " + e.getMessage(), e);
		}
		return serialize(doc);
	}

	private static SignatureProperty property(XMLSignatureFactory f, Document doc, String signatureId, String id,
		String element, @Nullable String uri) {
		Element e = doc.createElementNS(DSIG_PROPERTIES, "dsp:" + element);
		// Declared outright: canonicalization sees only declared namespaces, and the serializer would add this one
		// after the digest was taken.
		e.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:dsp", DSIG_PROPERTIES);
		if (uri != null) { e.setAttribute("URI", uri); }
		return f.newSignatureProperty(List.<XMLStructure>of(new DOMStructure(e)), "#" + signatureId, id);
	}

	/** Hands the signature engine a file's bytes for its (percent-encoded) URI. */
	private record FileDereferencer(Map<String, byte[]> files, URIDereferencer sameDocument) implements URIDereferencer {
		@Override
		public Data dereference(URIReference ref, XMLCryptoContext ctx) throws URIReferenceException {
			String uri = ref.getURI();
			if (uri.startsWith("#")) { return sameDocument.dereference(ref, ctx); }
			byte[] bytes = files.get(decode(uri));
			if (bytes == null) { throw new URIReferenceException("no file " + uri + " in the package"); }
			return new OctetStreamData(new ByteArrayInputStream(bytes), uri, null);
		}
	}

	// ---- verifying -----------------------------------------------------------------------------------

	private static void verifyOne(Map<String, byte[]> files, String name, String role) throws IOException, GeneralSecurityException {
		byte[] xml = files.get(name);
		if (xml == null) { throw new GeneralSecurityException("no " + name); }
		Document doc = parse(xml);
		Element sig = doc.getDocumentElement();
		NodeList objects = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Object");
		for (int i = 0; i < objects.getLength(); i++) {
			Element o = (Element) objects.item(i);
			if (o.hasAttribute("Id")) { o.setIdAttribute("Id", true); }
		}
		X509Certificate leaf = leafCertificate(doc);
		XMLSignatureFactory f = XMLSignatureFactory.getInstance("DOM");
		DOMValidateContext ctx = new DOMValidateContext(leaf.getPublicKey(), sig);
		ctx.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE); // apps have more than its 30 references
		ctx.setURIDereferencer(new FileDereferencer(files, f.getURIDereferencer()));
		boolean valid;
		XMLSignature s;
		try {
			s = f.unmarshalXMLSignature(ctx);
			valid = s.validate(ctx);
		} catch (MarshalException | XMLSignatureException e) {
			throw new GeneralSecurityException(name + ": " + e.getMessage(), e);
		}
		if (!valid) { throw new GeneralSecurityException(name + " doesn't verify"); }

		List<String> covered = new ArrayList<>();
		for (Object r : s.getSignedInfo().getReferences()) { covered.add(((Reference) r).getURI()); }
		for (String file : files.keySet()) {
			boolean exempt = file.equals(name) || file.equals(DISTRIBUTOR_SIGNATURE) || (name.equals(AUTHOR_SIGNATURE) && isSignature(file));
			if (!exempt && !covered.contains(encode(file))) { throw new GeneralSecurityException(name + " doesn't cover " + file); }
		}
		if (!covered.contains("#prop")) { throw new GeneralSecurityException(name + " doesn't sign its properties"); }
		if (!new String(xml, UTF_8).contains(WIDGETS_DIGSIG + role)) { throw new GeneralSecurityException(name + " doesn't name its role"); }
	}

	private static X509Certificate leafCertificate(Document doc) throws GeneralSecurityException {
		NodeList certs = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "X509Certificate");
		if (certs.getLength() == 0) { throw new GeneralSecurityException("no certificate in the signature"); }
		byte[] der = Base64.getMimeDecoder().decode(certs.item(0).getTextContent());
		return Certs.x509(der);
	}

	// ---- files and XML -------------------------------------------------------------------------------

	private static Map<String, byte[]> read(Path dir) throws IOException {
		if (!Files.isDirectory(dir)) { throw new IOException(dir + " isn't a directory"); }
		Map<String, byte[]> files = new TreeMap<>();
		try (Stream<Path> walk = Files.walk(dir)) {
			for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
				String name = dir.relativize(p).toString().replace('\\', '/');
				boolean hidden = Stream.of(name.split("/")).anyMatch(part -> part.startsWith("."));
				if (!hidden && !isSignature(name)) { files.put(name, Files.readAllBytes(p)); }
			}
		}
		return files;
	}

	private static boolean isSignature(String name) {
		return name.equals(AUTHOR_SIGNATURE) || name.matches("signature[0-9]+\\.xml");
	}

	/** Percent-encodes everything but unreserved characters, '/' included, as Samsung's packager does. */
	static String encode(String path) {
		StringBuilder b = new StringBuilder();
		for (byte c : path.getBytes(UTF_8)) {
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
				b.append((char) c);
			} else {
				b.append('%').append(String.format("%02X", c & 0xff));
			}
		}
		return b.toString();
	}

	static String decode(String uri) {
		return URLDecoder.decode(uri.replace("+", "%2B"), UTF_8);
	}

	private static Document newDocument() throws IOException {
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
			return dbf.newDocumentBuilder().newDocument();
		} catch (ParserConfigurationException e) {
			throw new IOException(e);
		}
	}

	private static Document parse(byte[] xml) throws IOException {
		try (InputStream in = new ByteArrayInputStream(xml)) {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
			dbf.setNamespaceAware(true);
			dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			return dbf.newDocumentBuilder().parse(in);
		} catch (ParserConfigurationException | SAXException e) {
			throw new IOException("not a signature: " + e.getMessage(), e);
		}
	}

	private static byte[] serialize(Document doc) throws IOException {
		try {
			var t = TransformerFactory.newInstance().newTransformer();
			t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			t.transform(new DOMSource(doc), new StreamResult(out));
			return out.toByteArray();
		} catch (TransformerException e) {
			throw new IOException(e);
		}
	}
}
