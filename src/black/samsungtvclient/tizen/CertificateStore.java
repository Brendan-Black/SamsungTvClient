package black.samsungtvclient.tizen;

import static java.nio.charset.StandardCharsets.US_ASCII;

import black.samsungtvclient.util.AtomicFiles;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Optional;

/**
 * The signing keys and Samsung's certificates for them, in a directory ({@code ~/.samsungtv/samsung} by default):
 * {@code author.p12} and {@code distributor.p12} (each a key and its chain, leaf first), the CA certificates they
 * chain to, and the random {@code password} protecting the two keystores.
 *
 * <p>Keep {@code author.p12}: the TV only updates an installed app when the update is signed by the same author.
 */
public final class CertificateStore {
	private static final String ALIAS = "usercertificate";
	private static final String AUTHOR = "author.p12";
	private static final String DISTRIBUTOR = "distributor.p12";
	private static final String CA_DIR = "ca";

	private final Path dir;

	public CertificateStore(Path dir) {
		this.dir = dir.toAbsolutePath();
	}

	public Path dir() {
		return dir;
	}

	public Optional<WidgetSigner.Signer> author() throws IOException, GeneralSecurityException {
		return load(AUTHOR);
	}

	public Optional<WidgetSigner.Signer> distributor() throws IOException, GeneralSecurityException {
		return load(DISTRIBUTOR);
	}

	public void saveAuthor(WidgetSigner.Signer s) throws IOException, GeneralSecurityException {
		save(AUTHOR, s);
	}

	public void saveDistributor(WidgetSigner.Signer s) throws IOException, GeneralSecurityException {
		save(DISTRIBUTOR, s);
	}

	/** A CA certificate saved by {@link #saveCa}, if any. */
	public Optional<X509Certificate> ca(String name) throws IOException, GeneralSecurityException {
		Path p = dir.resolve(CA_DIR).resolve(name);
		return Files.exists(p) ? Optional.of(Certs.x509(Files.readAllBytes(p))) : Optional.empty();
	}

	public void saveCa(String name, X509Certificate cert) throws IOException, GeneralSecurityException {
		AtomicFiles.write(dir.resolve(CA_DIR).resolve(name), Certs.pem("CERTIFICATE", cert.getEncoded()).getBytes(US_ASCII));
	}

	private Optional<WidgetSigner.Signer> load(String file) throws IOException, GeneralSecurityException {
		Path p = dir.resolve(file);
		return Files.exists(p) ? Optional.of(Certs.signer(Files.readAllBytes(p), password(), ALIAS)) : Optional.empty();
	}

	private void save(String file, WidgetSigner.Signer s) throws IOException, GeneralSecurityException {
		char[] password = password();
		KeyStore ks = Certs.newKeyStore();
		ks.load(null, null);
		ks.setKeyEntry(ALIAS, s.key(), password, s.chain().toArray(Certificate[]::new));
		ByteArrayOutputStream p12 = new ByteArrayOutputStream();
		ks.store(p12, password);
		AtomicFiles.write(dir.resolve(file), p12.toByteArray());
	}

	/** The keystores' password, made up the first time it's needed. */
	private char[] password() throws IOException {
		Path p = dir.resolve("password");
		if (!Files.exists(p)) {
			byte[] random = new byte[18];
			new SecureRandom().nextBytes(random);
			AtomicFiles.write(p, (Base64.getUrlEncoder().withoutPadding().encodeToString(random) + "\n").getBytes(US_ASCII));
		}
		return Files.readString(p, US_ASCII).strip().toCharArray();
	}
}
