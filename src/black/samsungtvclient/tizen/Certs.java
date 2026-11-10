package black.samsungtvclient.tizen;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** The formats certificates travel in: PEM text and PKCS#12 keystores. */
final class Certs {
	static final String BEGIN_CERTIFICATE = "-----BEGIN CERTIFICATE-----";
	static final String END_CERTIFICATE = "-----END CERTIFICATE-----";

	private static final String X509 = "X.509";
	private static final String PKCS12 = "PKCS12";

	private Certs() {}

	/** {@code der} in PEM, under {@code label}, e.g. {@code CERTIFICATE}. */
	static String pem(String label, byte[] der) {
		return "-----BEGIN " + label + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(US_ASCII)).encodeToString(der)
			+ "\n-----END " + label + "-----\n";
	}

	/** A certificate from its PEM or DER encoding. */
	static X509Certificate x509(byte[] encoded) throws GeneralSecurityException {
		return (X509Certificate) CertificateFactory.getInstance(X509).generateCertificate(new ByteArrayInputStream(encoded));
	}

	static KeyStore newKeyStore() throws GeneralSecurityException {
		return KeyStore.getInstance(PKCS12);
	}

	/** The key and chain under {@code alias} in the keystore held in {@code p12}. */
	static WidgetSigner.Signer signer(byte[] p12, char[] password, String alias) throws IOException, GeneralSecurityException {
		KeyStore ks = load(p12, password);
		return signer(ks, password, ks.isKeyEntry(alias) ? alias : ks.aliases().nextElement());
	}

	/** The first key and its chain in the keystore held in {@code p12}. */
	static WidgetSigner.Signer signer(byte[] p12, char[] password) throws IOException, GeneralSecurityException {
		KeyStore ks = load(p12, password);
		return signer(ks, password, ks.aliases().nextElement());
	}

	private static KeyStore load(byte[] p12, char[] password) throws IOException, GeneralSecurityException {
		KeyStore ks = newKeyStore();
		try (InputStream in = new ByteArrayInputStream(p12)) {
			ks.load(in, password);
		}
		return ks;
	}

	private static WidgetSigner.Signer signer(KeyStore ks, char[] password, String alias) throws GeneralSecurityException {
		List<X509Certificate> chain = Arrays.stream(ks.getCertificateChain(alias)).map(c -> (X509Certificate) c).toList();
		return new WidgetSigner.Signer((PrivateKey) ks.getKey(alias, password), chain);
	}
}
