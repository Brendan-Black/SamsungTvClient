package black.samsungtvclient.tizen;

import static black.samsungtvclient.tizen.Csr.oid;
import static black.samsungtvclient.tizen.Csr.seq;
import static black.samsungtvclient.tizen.Csr.tlv;
import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.security.auth.x500.X500Principal;

/** X.509 certificates for tests, built with {@link Csr}'s DER helpers (the JDK has no public API to make them). */
final class TestCerts {
	private static final byte[] SHA256_WITH_RSA = seq(oid(1, 2, 840, 113549, 1, 1, 11), new byte[] {0x05, 0x00});

	private TestCerts() {}

	static KeyPair keys() throws GeneralSecurityException {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		return g.generateKeyPair();
	}

	/** A certificate for {@code subject}'s key, signed by {@code issuerKey}, with optional subjectAltName URIs. */
	static X509Certificate issue(String subject, KeyPair keys, String issuer, PrivateKey issuerKey, List<String> uris)
		throws GeneralSecurityException {
		ByteArrayOutputStream ext = new ByteArrayOutputStream();
		if (!uris.isEmpty()) {
			ByteArrayOutputStream names = new ByteArrayOutputStream();
			for (String u : uris) { names.writeBytes(tlv(0x86, u.getBytes(US_ASCII))); }
			ext.writeBytes(seq(oid(2, 5, 29, 17), tlv(0x04, seq(names.toByteArray()))));
		}
		byte[] validity = seq(tlv(0x17, "250101000000Z".getBytes(US_ASCII)), tlv(0x17, "491231235959Z".getBytes(US_ASCII)));
		byte[] tbs = seq(tlv(0xA0, new byte[] {0x02, 0x01, 0x02}), tlv(0x02, BigInteger.valueOf(System.nanoTime()).toByteArray()),
			SHA256_WITH_RSA, new X500Principal(issuer).getEncoded(), validity, new X500Principal(subject).getEncoded(),
			keys.getPublic().getEncoded(), ext.size() == 0 ? new byte[0] : tlv(0xA3, seq(ext.toByteArray())));
		Signature s = Signature.getInstance("SHA256withRSA");
		s.initSign(issuerKey);
		s.update(tbs);
		byte[] sig = s.sign();
		byte[] bits = new byte[sig.length + 1];
		System.arraycopy(sig, 0, bits, 1, sig.length);
		byte[] der = seq(tbs, SHA256_WITH_RSA, tlv(0x03, bits));
		return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
	}

	/** A signer whose chain is a leaf and the CA that issued it, as Samsung's are. */
	static WidgetSigner.Signer signer(String leaf, List<String> uris) throws GeneralSecurityException {
		KeyPair ca = keys();
		KeyPair me = keys();
		X509Certificate caCert = issue("CN=Test CA", ca, "CN=Test Root", keys().getPrivate(), List.of());
		return new WidgetSigner.Signer(me.getPrivate(), List.of(issue(leaf, me, "CN=Test CA", ca.getPrivate(), uris), caCert));
	}
}
