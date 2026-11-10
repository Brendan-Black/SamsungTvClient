package black.samsungtvclient.tizen;

import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.api.Duid;
import black.samsungtvclient.net.Http;
import black.samsungtvclient.util.Json;
import black.samsungtvclient.util.Nullable;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.security.auth.x500.X500Principal;

/**
 * Samsung's certificate service for TV ("VD") developers, as Tizen Studio's Certificate Manager uses it (read out of
 * {@code org.tizen.common.cert}, Samsung Certificate Extension 2.0.75): sign in with a Samsung account in the browser,
 * then send a certificate request for the author certificate (who wrote the app; any TV) and one for the distributor
 * certificate (which TVs, by DUID, may install it). Samsung signs both; a retail TV installs nothing else.
 */
public final class SamsungCertificates {
	/** The CA certificates that issue VD author and (public-level) distributor certificates; the chains end with them. */
	public static final String AUTHOR_CA = "vd_tizen_dev_author_ca.cer";

	public static final String DISTRIBUTOR_CA = "vd_tizen_dev_public2.crt";

	private static final int CALLBACK_PORT = 4794; // Samsung redirects only here
	private static final String CALLBACK_PATH = "/signin/callback";

	// The field names of Samsung's sign-in replies and certificate forms.
	private static final String ACCESS_TOKEN = "access_token";
	private static final String USER_ID = "userId";
	private static final String USER_ID_FORM = "user_id";
	private static final String EMAIL = "inputEmailID";
	private static final String CODE = "code";
	private static final String PLATFORM = "platform";
	private static final String TV_PLATFORM = "VD";

	private static final String DEVICE_ID_URN = "URN:tizen:deviceid=";
	private static final Pattern BODY = Pattern.compile("(?is)<body>(.*?)</body>");
	private static final Pattern EXTENSION =
		Pattern.compile("(?s)<name>\\s*Samsung Certificate Extension\\s*</name>\\s*<repository>\\s*(\\S+?)\\s*</repository>");

	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL).build();

	/**
	 * Where Samsung's service is; tests point it at a pseudo.
	 *
	 * @param login         the sign-in page, given the port the callback is on
	 * @param tokenExchange the URL an older-form sign-in code is appended to
	 * @param callbackPort  the port to serve the callback on
	 */
	record Endpoints(IntFunction<URI> login, String tokenExchange, URI authors, URI distributors, URI extensions, int callbackPort) {}

	/**
	 * A signed-in Samsung account.
	 *
	 * @param email null if Samsung didn't say
	 */
	public record Account(String accessToken, String userId, @Nullable String email) {
		public Optional<String> knownEmail() {
			return Optional.ofNullable(email).filter(e -> !e.isBlank());
		}

		/** Who this is, to tell the person signing in. */
		public String displayName() {
			return knownEmail().orElse("user " + userId);
		}

		/** Debug output: an access token and an email address must not reach a log by way of a generated one. */
		@Override
		public String toString() {
			return "Account[redacted]";
		}
	}

	private final Endpoints endpoints;
	private final Consumer<URI> browser;

	SamsungCertificates(Endpoints endpoints, Consumer<URI> browser) {
		this.endpoints = endpoints;
		this.browser = browser;
	}

	/** Samsung's real service, signing in through this PC's browser. */
	public static SamsungCertificates live() {
		String vd = "https://svdca.samsungqbe.com/apis/v3/";
		return new SamsungCertificates(new Endpoints(
			port -> URI.create("https://account.samsung.com/mobile/account/check.do?serviceID=v285zxnl3h"
				+ "&actionID=StartOAuth2&accessToken=Y&redirect_uri=http://localhost:" + port + CALLBACK_PATH),
			"https://api.samsungosp.com/v2/license/security/authorizeToken?authToken=",
			URI.create(vd + "authors"),
			URI.create(vd + "distributors"),
			URI.create("https://download.tizen.org/sdk/tizenstudio/official/extension_info.xml"),
			CALLBACK_PORT), SamsungCertificates::openBrowser);
	}

	/**
	 * Signs in: serves Samsung's callback on localhost, opens the sign-in page in the browser (or asks for it to be
	 * opened), and waits for the account to come back.
	 *
	 * @param say what to tell the person signing in
	 */
	public Account signIn(Duration wait, Consumer<String> say) throws IOException, InterruptedException {
		CompletableFuture<Account> account = new CompletableFuture<>();
		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), endpoints.callbackPort()), 0);
		} catch (BindException e) {
			throw new IOException("port " + endpoints.callbackPort() + " is busy, and Samsung only signs in back to that port; close "
				+ "whatever holds it (Tizen Studio's Certificate Manager?) and try again", e);
		}
		server.createContext(CALLBACK_PATH, exchange -> callback(exchange, account));
		server.start();
		try {
			URI login = endpoints.login().apply(server.getAddress().getPort());
			say.accept("Sign in with your Samsung account in the browser. If it didn't open, open this:\n  " + login);
			browser.accept(login);
			return account.get(wait.toSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new IOException("no sign-in after " + wait.toMinutes() + " minutes", e);
		} catch (ExecutionException e) {
			Throwable cause = Objects.requireNonNullElse(e.getCause(), e);
			throw new IOException("sign-in failed: " + Http.describe(cause), cause);
		} finally {
			server.stop(1);
		}
	}

	private void callback(HttpExchange exchange, CompletableFuture<Account> account) throws IOException {
		String page;
		boolean fromSamsung = false; // anything else reaching this port is no reason to give up on the sign-in
		try {
			Map<String, String> params = callbackParams(exchange);
			fromSamsung = params.containsKey(CODE) || params.containsKey(ACCESS_TOKEN);
			Account a = account(params);
			account.complete(a);
			page = "<h2>&#10003; Signed in as " + escape(a.displayName()) + "</h2><p>You can close this tab; SamsungTvClient takes it from here.</p>";
		} catch (IOException | IllegalArgumentException e) {
			page = "<h2>Sign-in didn't come back right</h2><p>" + escape(Http.describe(e)) + "</p>";
			if (fromSamsung) { account.completeExceptionally(e); }
		}
		byte[] body = ("<!doctype html><meta charset=utf-8><body style='font:20px sans-serif;padding:60px'>" + page).getBytes(UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
		exchange.sendResponseHeaders(200, body.length);
		try (var out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	/** @throws IllegalArgumentException if they aren't valid form encoding */
	private static Map<String, String> callbackParams(HttpExchange exchange) throws IOException {
		Map<String, String> params = form(exchange.getRequestURI().getRawQuery());
		if ("POST".equals(exchange.getRequestMethod())) {
			params.putAll(form(new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
		}
		return params;
	}


	/**
	 * The account in Samsung's callback. Despite its name, {@code code} carries the whole token payload as JSON; an
	 * older form sends a real code, which is exchanged for the token.
	 */
	Account account(Map<String, String> params) throws IOException {
		String code = params.get(CODE);
		if (code != null && code.strip().startsWith("{")) {
			Map<?, ?> j = jsonObject(code);
			return account(Json.string(j, ACCESS_TOKEN).orElse(null), Json.string(j, USER_ID).orElse(null), Json.string(j, EMAIL).orElse(null));
		}
		if (params.containsKey(ACCESS_TOKEN)) {
			return account(params.get(ACCESS_TOKEN), params.getOrDefault(USER_ID, params.get(USER_ID_FORM)), params.get(EMAIL));
		}
		if (code == null || code.isBlank()) { throw new IOException("Samsung sent no code"); }
		String reply = get(URI.create(endpoints.tokenExchange() + URLEncoder.encode(code, UTF_8)));
		Matcher body = BODY.matcher(reply);
		Map<String, String> fields = form(body.find() ? body.group(1).strip() : reply.strip());
		return account(fields.get(ACCESS_TOKEN), fields.get(USER_ID), fields.get(EMAIL));
	}

	private static Map<?, ?> jsonObject(String text) throws IOException {
		try {
			if (Json.parse(text) instanceof Map<?, ?> m) { return m; }
		} catch (IllegalArgumentException e) {
			throw new IOException("Samsung's sign-in callback isn't readable: " + e.getMessage(), e);
		}
		throw new IOException("Samsung's sign-in callback isn't a JSON object");
	}

	private static Account account(@Nullable String token, @Nullable String userId, @Nullable String email) throws IOException {
		if (token == null || token.isBlank() || userId == null || userId.isBlank()) {
			throw new IOException("Samsung's reply had no access token or user id");
		}
		return new Account(token, userId, email);
	}

	// ---- certificates --------------------------------------------------------------------------------

	/** The author certificate: who made the app. Updates must be signed by the same one. */
	public X509Certificate author(Account account, KeyPair keys, String name) throws IOException, GeneralSecurityException {
		String csr = Csr.pem(new X500Principal("CN=" + rdnValue(name)), List.of(), keys);
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put(ACCESS_TOKEN, account.accessToken());
		fields.put(USER_ID_FORM, account.userId());
		fields.put(PLATFORM, TV_PLATFORM);
		return certificate(post(endpoints.authors(), fields, csr, "author.csr"), "author");
	}

	/**
	 * The distributor certificate: which TVs (by DUID) may install apps it signs. Public privilege level. TVs on Tizen
	 * 4.0 and older don't trust these since Samsung's 2025 CA change; see {@link TizenDistributor}.
	 */
	public X509Certificate distributor(Account account, KeyPair keys, List<Duid> duids) throws IOException, GeneralSecurityException {
		if (duids.isEmpty()) { throw new IllegalArgumentException("a distributor certificate needs at least one TV (DUID)"); }
		List<String> san = new ArrayList<>();
		san.add("URN:tizen:packageid=");
		for (Duid duid : duids) { san.add(DEVICE_ID_URN + duid); }
		String email = account.knownEmail().orElse("tizen@example.com");
		// RFC 2253 lists the most specific name first, so this encodes as CN, then emailAddress.
		String csr = Csr.pem(new X500Principal("EMAILADDRESS=" + rdnValue(email) + ",CN=TizenSDK"), san, keys);
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put(ACCESS_TOKEN, account.accessToken());
		fields.put(USER_ID_FORM, account.userId());
		fields.put("privilege_level", "Public");
		fields.put("developer_type", "Individual");
		fields.put(PLATFORM, TV_PLATFORM);
		return certificate(post(endpoints.distributors(), fields, csr, "distributor.csr"), "distributor");
	}

	/** The DUIDs a distributor certificate covers, from its subjectAltName URIs. */
	public static List<Duid> duids(X509Certificate distributor) throws GeneralSecurityException {
		var names = distributor.getSubjectAlternativeNames();
		if (names == null) { return List.of(); }
		try {
			return names.stream().filter(n -> (Integer) n.get(0) == 6).map(n -> String.valueOf(n.get(1)))
				.filter(u -> u.startsWith(DEVICE_ID_URN)).map(u -> new Duid(u.substring(DEVICE_ID_URN.length()))).toList();
		} catch (IllegalArgumentException e) {
			throw new GeneralSecurityException("a distributor certificate names something that isn't a TV: " + e.getMessage(), e);
		}
	}

	/**
	 * Samsung's form, byte for byte as Certificate Manager sends it: unquoted field names and a boundary of five
	 * asterisks.
	 */
	static byte[] multipart(Map<String, String> fields, String csr, String csrName) {
		String b = "*****";
		StringBuilder body = new StringBuilder();
		fields.forEach((k, v) -> body.append("--").append(b).append("\r\nContent-Disposition: form-data; name=").append(k)
			.append("\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n").append(v).append("\r\n"));
		body.append("--").append(b).append("\r\nContent-Disposition: form-data; name=csr; filename=").append(csrName)
			.append("\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n").append(csr).append("\r\n--").append(b).append("--\r\n");
		return body.toString().getBytes(UTF_8);
	}

	/** Posts the form; the reply must be 200 and hold a certificate. */
	private static String post(URI uri, Map<String, String> fields, String csr, String csrName) throws IOException {
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
			.header("Content-Type", "multipart/form-data; boundary=\"*****\"")
			.POST(HttpRequest.BodyPublishers.ofByteArray(multipart(fields, csr, csrName))).build();
		HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
		String body = response.body();
		if (response.statusCode() != 200 || !body.contains(Certs.BEGIN_CERTIFICATE)) {
			throw new IOException("Samsung refused the request to " + uri.getPath() + " (HTTP " + response.statusCode()
				+ "): " + body.strip().substring(0, Math.min(400, body.strip().length())));
		}
		return body;
	}

	static X509Certificate certificate(String pem, String what) throws GeneralSecurityException {
		int start = pem.indexOf(Certs.BEGIN_CERTIFICATE);
		int end = pem.indexOf(Certs.END_CERTIFICATE);
		if (start < 0 || end < 0) { throw new GeneralSecurityException("no " + what + " certificate in Samsung's reply"); }
		return Certs.x509(pem.substring(start, end + Certs.END_CERTIFICATE.length()).getBytes(UTF_8));
	}

	// ---- CA certificates -----------------------------------------------------------------------------

	/**
	 * The two VD CA certificates, from Samsung's Certificate Extension, which ships them inside a jar inside a zip
	 * inside the zip that Tizen's extension list names. Nothing is unpacked to disk: the download (about 45 MB) is
	 * read as it arrives.
	 *
	 * @return file name to certificate, for {@link #AUTHOR_CA} and {@link #DISTRIBUTOR_CA}
	 */
	public Map<String, X509Certificate> caCertificates(Consumer<String> say) throws IOException, GeneralSecurityException {
		String list = get(endpoints.extensions());
		Matcher m = EXTENSION.matcher(list);
		if (!m.find()) { throw new IOException("Tizen's extension list (" + endpoints.extensions() + ") no longer names the Samsung Certificate Extension"); }
		URI zip = URI.create(m.group(1));
		say.accept("Fetching Samsung's CA certificates from " + zip + " ...");
		HttpResponse<InputStream> response = send(HttpRequest.newBuilder(zip).timeout(Duration.ofMinutes(10)).build(),
			HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) { throw new IOException(zip + " -> HTTP " + response.statusCode()); }
		Map<String, X509Certificate> found = new HashMap<>();
		try (ZipInputStream outer = new ZipInputStream(response.body())) {
			ZipInputStream addOn = enter(outer, n -> n.matches("(.*/)?cert-add-on_.*\\.zip"));
			ZipInputStream jar = enter(addOn, n -> n.matches("(.*/)?org\\.tizen\\.common\\.cert_.*\\.jar"));
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			for (ZipEntry e; found.size() < 2 && (e = jar.getNextEntry()) != null;) {
				String name = e.getName().substring(e.getName().lastIndexOf('/') + 1);
				if (e.getName().startsWith("res/ca/") && (name.equals(AUTHOR_CA) || name.equals(DISTRIBUTOR_CA))) {
					found.put(name, (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(jar.readAllBytes())));
				}
			}
		}
		if (found.size() < 2) { throw new IOException("the Certificate Extension at " + zip + " no longer holds " + AUTHOR_CA + " and " + DISTRIBUTOR_CA); }
		return Map.copyOf(found);
	}

	/** Moves {@code zip} to the first entry whose name matches, and reads that entry as a zip. */
	private static ZipInputStream enter(ZipInputStream zip, Predicate<String> name) throws IOException {
		for (ZipEntry e; (e = zip.getNextEntry()) != null;) {
			if (name.test(e.getName())) { return new ZipInputStream(zip); }
		}
		throw new IOException("Samsung's Certificate Extension isn't laid out as expected");
	}

	// ---- helpers -------------------------------------------------------------------------------------

	private static String get(URI uri) throws IOException {
		HttpResponse<String> r = send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() != 200) { throw new IOException("GET " + uri + " -> HTTP " + r.statusCode()); }
		return r.body();
	}

	private static <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			return CLIENT.send(request, handler);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted talking to " + request.uri().getHost(), e);
		}
	}

	static Map<String, String> form(@Nullable String encoded) {
		Map<String, String> m = new LinkedHashMap<>();
		if (encoded == null || encoded.isBlank()) { return m; }
		for (String pair : encoded.split("&")) {
			int eq = pair.indexOf('=');
			String k = eq < 0 ? pair : pair.substring(0, eq);
			String v = eq < 0 ? "" : pair.substring(eq + 1);
			m.put(URLDecoder.decode(k, UTF_8), URLDecoder.decode(v, UTF_8));
		}
		return m;
	}

	/** Escapes a value for an RFC 2253 name. */
	private static String rdnValue(String s) {
		return s.replaceAll("([,+\"\\\\<>;=#])", "\\\\$1");
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static void openBrowser(URI uri) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(uri);
				return;
			}
		} catch (IOException | UnsupportedOperationException e) { // HeadlessException is one too
			// fall through to the platform's opener
		}
		String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
		List<String> cmd = os.contains("win") ? List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString())
			: os.contains("mac") ? List.of("open", uri.toString()) : List.of("xdg-open", uri.toString());
		try {
			new ProcessBuilder(cmd).start();
		} catch (IOException e) {
			// the URL was printed; it can be opened by hand
		}
	}
}
