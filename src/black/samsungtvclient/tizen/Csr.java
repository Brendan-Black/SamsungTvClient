package black.samsungtvclient.tizen;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.util.List;
import javax.security.auth.x500.X500Principal;

/**
 * A PKCS#10 certificate signing request, which the JDK can't make on its own: just enough DER for Samsung's
 * certificate server (a subject, an RSA key, and optionally subjectAltName URIs, signed with SHA-256).
 */
final class Csr {
	private static final byte[] SHA256_WITH_RSA = oid(1, 2, 840, 113549, 1, 1, 11);
	private static final byte[] EXTENSION_REQUEST = oid(1, 2, 840, 113549, 1, 9, 14);
	private static final byte[] SUBJECT_ALT_NAME = oid(2, 5, 29, 17);

	private Csr() {}

	/**
	 * @param subject e.g. {@code CN=TizenSDK}; RFC 2253 syntax, as {@link X500Principal} takes it
	 * @param uris    subjectAltName URIs, or empty for none
	 * @return the request, PEM-encoded
	 */
	static String pem(X500Principal subject, List<String> uris, KeyPair keys) throws GeneralSecurityException {
		ByteArrayOutputStream attributes = new ByteArrayOutputStream();
		if (!uris.isEmpty()) {
			ByteArrayOutputStream names = new ByteArrayOutputStream();
			for (String uri : uris) { names.writeBytes(tlv(0x86, uri.getBytes(US_ASCII))); } // [6] IMPLICIT IA5String
			byte[] san = seq(SUBJECT_ALT_NAME, tlv(0x04, seq(names.toByteArray())));
			attributes.writeBytes(seq(EXTENSION_REQUEST, tlv(0x31, seq(san))));
		}
		byte[] info = seq(new byte[] {0x02, 0x01, 0x00}, subject.getEncoded(), keys.getPublic().getEncoded(),
			tlv(0xA0, attributes.toByteArray()));

		Signature signer = Signature.getInstance("SHA256withRSA");
		signer.initSign(keys.getPrivate());
		signer.update(info);
		byte[] signature = signer.sign();
		byte[] bits = new byte[signature.length + 1]; // a BIT STRING starts with its count of unused bits
		System.arraycopy(signature, 0, bits, 1, signature.length);
		byte[] der = seq(info, seq(SHA256_WITH_RSA, new byte[] {0x05, 0x00}), tlv(0x03, bits));

		return Certs.pem("CERTIFICATE REQUEST", der);
	}

	// ---- DER -----------------------------------------------------------------------------------------

	static byte[] seq(byte[]... parts) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		for (byte[] p : parts) { b.writeBytes(p); }
		return tlv(0x30, b.toByteArray());
	}

	static byte[] tlv(int tag, byte[] value) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.write(tag);
		int n = value.length;
		if (n < 0x80) {
			b.write(n);
		} else {
			int bytes = n > 0xFFFFFF ? 4 : n > 0xFFFF ? 3 : n > 0xFF ? 2 : 1;
			b.write(0x80 | bytes);
			for (int i = bytes - 1; i >= 0; i--) { b.write(n >>> (8 * i)); }
		}
		b.writeBytes(value);
		return b.toByteArray();
	}

	static byte[] oid(int... arcs) {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		b.write(arcs[0] * 40 + arcs[1]);
		for (int i = 2; i < arcs.length; i++) {
			int v = arcs[i];
			int groups = 1;
			while (v >>> (7 * groups) != 0) { groups++; }
			for (int g = groups - 1; g >= 0; g--) { b.write(((v >>> (7 * g)) & 0x7F) | (g > 0 ? 0x80 : 0)); }
		}
		return tlv(0x06, b.toByteArray());
	}
}
