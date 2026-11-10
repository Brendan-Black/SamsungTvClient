package black.samsungtvclient.api;

import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.MacAddress;
import black.samsungtvclient.util.AtomicFiles;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * What the client remembers about each TV, by host, in a properties file: the pairing token (so the TV
 * asks "Allow?" only once), the fingerprint of its TLS certificate (so a different device at that address
 * isn't trusted), and its MAC address (so it can be woken while it's off and can't be asked).
 */
public final class TvStore {
	private enum Field {
		TOKEN("token"), CERTIFICATE("certificate"), MAC("mac");

		private final String suffix;

		Field(String suffix) {
			this.suffix = suffix;
		}

		String key(Host host) {
			return host.value() + "." + suffix;
		}
	}

	private final Path file;

	public TvStore(Path file) {
		this.file = file.toAbsolutePath();
	}

	/** {@code ~/.samsungtv/tvs.properties} */
	public static TvStore defaultStore() {
		return new TvStore(Path.of(System.getProperty("user.home"), ".samsungtv", "tvs.properties"));
	}

	public Path file() {
		return file;
	}

	public Optional<PairingToken> token(Host host) throws IOException {
		return get(host, Field.TOKEN).map(PairingToken::new);
	}

	public void setToken(Host host, PairingToken token) throws IOException {
		set(host, Field.TOKEN, token.value());
	}

	/** The SHA-256 fingerprint of the certificate the TV presented when it was paired. */
	public Optional<String> certificate(Host host) throws IOException {
		return get(host, Field.CERTIFICATE);
	}

	public void setCertificate(Host host, String sha256) throws IOException {
		set(host, Field.CERTIFICATE, sha256);
	}

	/** @throws IOException also if the file has something other than a MAC address there */
	public Optional<MacAddress> mac(Host host) throws IOException {
		try {
			return get(host, Field.MAC).map(MacAddress::new);
		} catch (IllegalArgumentException e) {
			throw new IOException(file + ": bad entry for " + host + ": " + e.getMessage(), e);
		}
	}

	public void setMac(Host host, MacAddress mac) throws IOException {
		set(host, Field.MAC, mac.value());
	}

	/** Forgets everything about {@code host}, so the next connection pairs afresh. */
	public synchronized void forget(Host host) throws IOException {
		Properties p = load();
		for (Field field : Field.values()) { p.remove(field.key(host)); }
		save(p);
	}

	private synchronized Optional<String> get(Host host, Field field) throws IOException {
		return Optional.ofNullable(load().getProperty(field.key(host))).filter(v -> !v.isBlank());
	}

	private synchronized void set(Host host, Field field, String value) throws IOException {
		Properties p = load();
		p.setProperty(field.key(host), value);
		save(p);
	}

	private Properties load() throws IOException {
		Properties p = new Properties();
		if (Files.exists(file)) {
			try (Reader r = Files.newBufferedReader(file, UTF_8)) {
				p.load(r);
			}
		}
		return p;
	}

	private void save(Properties p) throws IOException {
		StringWriter text = new StringWriter();
		p.store(text, "TVs this client has paired with (SamsungTvClient)");
		AtomicFiles.write(file, text.toString().getBytes(UTF_8));
	}
}
