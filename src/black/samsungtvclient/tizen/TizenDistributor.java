package black.samsungtvclient.tizen;

import black.samsungtvclient.util.AtomicFiles;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Tizen's public distributor certificate (2012, "Tizen Public Distributor Signer"), which Tizen Studio ships with a
 * published password for anyone to sign with. TVs on Tizen 4.0 and older trust it and not the distributor certificates
 * Samsung has issued since its 2025 CA change (tested on a 2018 UN75NU8000: Samsung's fail with 118012, "invalid
 * certificate chain", even after "permit to install"; this one installs). Those TVs don't check that it expired in 2022.
 * It isn't tied to a DUID.
 */
public final class TizenDistributor {
	static final String FILE = "tizen-distributor-signer.p12";
	static final char[] PASSWORD = "tizenpkcs12passfordsigner".toCharArray(); // Tizen Studio's, published

	private static final String PLATFORM_VERSION = "platform_version";
	private static final String TIZEN_STUDIO = "https://download.tizen.org/sdk/tizenstudio/official";
	private static final Pattern PACKAGE_PATH = Pattern.compile("(?m)^Package : certificate-generator\\s*$(?:\\R(?!Package :).*)*?\\R\\s*Path : (\\S+)");
	private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)");
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL).build();

	/** Where the certificate is published; tests point it at a pseudo. */
	record Source(URI packages, String base) {}

	private final Source source;

	TizenDistributor(Source source) {
		this.source = source;
	}

	/** The one Tizen publishes, from download.tizen.org. */
	public static TizenDistributor live() {
		return new TizenDistributor(new Source(URI.create(TIZEN_STUDIO + "/pkg_list_windows-64"), TIZEN_STUDIO));
	}

	/** The signer, from {@code dir} if it was fetched before, else from Tizen's certificate-generator package. */
	public WidgetSigner.Signer load(Path dir, Consumer<String> say) throws IOException, GeneralSecurityException {
		Path file = dir.resolve(FILE);
		if (!Files.exists(file)) { AtomicFiles.write(file, download(say)); }
		return Certs.signer(Files.readAllBytes(file), PASSWORD);
	}

	/** The keystore, out of the certificate-generator package Tizen's package list names (about 1 MB). */
	private byte[] download(Consumer<String> say) throws IOException {
		String list = send(source.packages(), HttpResponse.BodyHandlers.ofString());
		Matcher m = PACKAGE_PATH.matcher(list);
		if (!m.find()) { throw new IOException("Tizen's package list (" + source.packages() + ") no longer names certificate-generator"); }
		URI zip = URI.create(source.base() + m.group(1));
		say.accept("Fetching Tizen's public distributor certificate from " + zip + " ...");
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(send(zip, HttpResponse.BodyHandlers.ofByteArray())))) {
			for (ZipEntry e; (e = in.getNextEntry()) != null;) {
				if (e.getName().endsWith("certificates/distributor/" + FILE)) { return in.readAllBytes(); }
			}
		}
		throw new IOException(zip + " no longer holds certificates/distributor/" + FILE);
	}

	private static <T> T send(URI uri, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			HttpResponse<T> r = CLIENT.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(2)).build(), handler);
			if (r.statusCode() != 200) { throw new IOException("GET " + uri + " -> HTTP " + r.statusCode()); }
			return r.body();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted fetching " + uri, e);
		}
	}

	/** Whether a TV with these capabilities needs this distributor rather than Samsung's: Tizen 4.0 and older. */
	public static boolean neededBy(Map<String, String> capabilities) {
		Matcher v = VERSION.matcher(capabilities.getOrDefault(PLATFORM_VERSION, ""));
		return v.find() && Integer.parseInt(v.group(1)) <= 4;
	}
}
