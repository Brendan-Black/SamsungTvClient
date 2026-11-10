package black.samsungtvclient.tizen;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;
import static black.samsungtvclient.Check.ok;
import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.Check;
import black.samsungtvclient.PseudoHttp;
import black.samsungtvclient.api.Duid;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Samsung's certificate service and Tizen's download site, against canned replies, including their refusals. */
public final class CertificateServiceTest {
	private static final Consumer<String> QUIET = _ -> {};
	private static final SamsungCertificates.Account ACCOUNT = new SamsungCertificates.Account("secret-token", "U1", "me@example.com");

	private CertificateServiceTest() {}

	public static void run() throws Exception {
		redaction();
		certificates();
		signIn();
		caDownload();
		distributorDownload();
	}

	private static SamsungCertificates service(PseudoHttp samsung, Consumer<URI> browser) {
		return new SamsungCertificates(new SamsungCertificates.Endpoints(
			port -> URI.create(samsung.uri("/login") + "?redirect_uri=http://localhost:" + port + "/signin/callback"),
			samsung.uri("/exchange") + "?authToken=", samsung.uri("/authors"), samsung.uri("/distributors"), samsung.uri("/extensions"), 0), browser);
	}

	private static void redaction() throws GeneralSecurityException {
		String account = ACCOUNT.toString();
		ok(!account.contains("secret-token") && !account.contains("me@example.com"), "an account's toString gives nothing away: " + account);
		eq("me@example.com", ACCOUNT.displayName(), "what's shown to the person signing in is asked for by name");
		eq("user U1", new SamsungCertificates.Account("t", "U1", null).displayName(), "...and works without an email");
		WidgetSigner.Signer signer = TestCerts.signer("CN=Author", List.of());
		String key = signer.key().getEncoded().length + " " + signer.key();
		ok(!signer.toString().contains(key), "a signer's toString doesn't print its key");
		ok(signer.toString().contains("CN=Author"), "...it says whose it is: " + signer);
	}

	private static void certificates() throws Exception {
		KeyPair keys = TestCerts.keys();
		WidgetSigner.Signer issued = TestCerts.signer("CN=Issued", List.of());
		String pem = Certs.pem("CERTIFICATE", issued.chain().getFirst().getEncoded());
		try (PseudoHttp samsung = new PseudoHttp()) {
			SamsungCertificates service = service(samsung, _ -> {});
			samsung.on("POST", "/authors", 200, "reply\n" + pem);
			samsung.on("POST", "/distributors", 200, pem);

			eq(issued.chain().getFirst(), service.author(ACCOUNT, keys, "Me, Inc"), "the author certificate in the reply");
			String form = samsung.received().getLast().body();
			ok(form.contains("name=access_token") && form.contains("secret-token") && form.contains("name=user_id") && form.contains("U1"),
				"signed in as this account");
			ok(form.contains("filename=author.csr") && form.contains("BEGIN CERTIFICATE REQUEST"), "with a certificate request");

			eq(issued.chain().getFirst(), service.distributor(ACCOUNT, keys, List.of(new Duid("TESTDUID00000"))), "the distributor certificate");
			ok(samsung.received().getLast().body().contains("name=privilege_level"), "public privilege level");
			fails(IllegalArgumentException.class, () -> service.distributor(ACCOUNT, keys, List.of()), "a distributor certificate needs a TV");

			samsung.on("POST", "/authors", 403, "{\"error\":\"not allowed\"}");
			IOException refused = fails(IOException.class, () -> service.author(ACCOUNT, keys, "Me"), "Samsung refuses");
			ok(refused.getMessage().contains("403") && refused.getMessage().contains("not allowed"), "says what Samsung said: " + refused.getMessage());
			ok(!refused.getMessage().contains("secret-token"), "...and not the token");

			samsung.on("POST", "/authors", 200, "{\"ok\":true}");
			fails(IOException.class, () -> service.author(ACCOUNT, keys, "Me"), "a 200 with no certificate in it isn't success");
		}
	}

	/** The whole sign-in, with a pseudo browser that goes where Samsung would send it. */
	private static void signIn() throws Exception {
		try (PseudoHttp samsung = new PseudoHttp()) {
			String good = URLEncoder.encode("{\"access_token\":\"T\",\"userId\":\"U\",\"inputEmailID\":\"me@x.com\"}", UTF_8);
			SamsungCertificates.Account a = service(samsung, callingBack("code=" + good)).signIn(Duration.ofSeconds(10), QUIET);
			eq("T", a.accessToken(), "the account comes back through the browser");

			IOException garbled = fails(IOException.class,
				() -> service(samsung, callingBack("code=" + URLEncoder.encode("{not json", UTF_8))).signIn(Duration.ofSeconds(10), QUIET),
				"a callback that isn't readable fails at once, rather than waiting out the sign-in");
			ok(garbled.getMessage().contains("sign-in failed"), garbled.getMessage());

			List<Integer> strayAnswer = new ArrayList<>();
			SamsungCertificates.Account afterStray = service(samsung, login -> Thread.ofVirtual().start(() -> {
				strayAnswer.add(post(login, "code=%zz")); // java.net.URI would refuse this in a query, but a body can say anything
				get(login, "code=" + good);
			})).signIn(Duration.ofSeconds(10), QUIET);
			eq(List.of(200), strayAnswer, "a request that isn't even form encoding still gets an answer");
			eq("T", afterStray.accessToken(), "...and doesn't end the sign-in");

			samsung.on("GET", "/exchange", 200, "<html><body>access_token=T2&userId=U2</body></html>");
			SamsungCertificates.Account exchanged = service(samsung, callingBack("code=abc")).signIn(Duration.ofSeconds(10), QUIET);
			eq("T2", exchanged.accessToken(), "an older-form code is exchanged for the token");
			eq("authToken=abc", samsung.received().getLast().query(), "...sending the code");

			IOException timedOut = fails(IOException.class, () -> service(samsung, _ -> {}).signIn(Duration.ZERO, QUIET), "nobody signs in");
			ok(timedOut.getMessage().contains("no sign-in"), timedOut.getMessage());
		}
	}

	/** A browser that follows the login page's redirect to the callback, with {@code query}. */
	private static Consumer<URI> callingBack(String query) {
		return login -> Thread.ofVirtual().start(() -> get(login, query));
	}


	/** Calls the callback that {@code login} names; returns the HTTP status. */
	private static int get(URI login, String query) {
		return send(HttpRequest.newBuilder(URI.create(callback(login) + "?" + query)).build());
	}

	/** Posts {@code body} to the callback that {@code login} names; returns the HTTP status. */
	private static int post(URI login, String body) {
		return send(HttpRequest.newBuilder(URI.create(callback(login))).POST(HttpRequest.BodyPublishers.ofString(body)).build());
	}

	private static String callback(URI login) {
		return login.getQuery().substring("redirect_uri=".length());
	}

	private static int send(HttpRequest request) {
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		} catch (IOException | InterruptedException e) {
			throw new IllegalStateException("the pseudo browser couldn't call back", e);
		}
	}

	private static void caDownload() throws Exception {
		WidgetSigner.Signer a = TestCerts.signer("CN=Author CA", List.of());
		WidgetSigner.Signer d = TestCerts.signer("CN=Distributor CA", List.of());
		byte[] jar = zip(Map.of("res/ca/" + SamsungCertificates.AUTHOR_CA, a.chain().getFirst().getEncoded(),
			"res/ca/" + SamsungCertificates.DISTRIBUTOR_CA, d.chain().getFirst().getEncoded(), "res/other.txt", new byte[] {1}));
		byte[] addOn = zip(Map.of("plugins/org.tizen.common.cert_2.0.75.jar", jar));
		byte[] outer = zip(Map.of("binary/cert-add-on_2.0.75.zip", addOn));
		try (PseudoHttp site = new PseudoHttp()) {
			SamsungCertificates service = service(site, _ -> {});
			String list = "<extension><name>Samsung Certificate Extension</name><repository>" + site.uri("/ext.zip") + "</repository></extension>";
			site.on("GET", "/extensions", 200, list).on("GET", "/ext.zip", 200, outer);
			Map<String, X509Certificate> cas = service.caCertificates(QUIET);
			eq(a.chain().getFirst(), cas.get(SamsungCertificates.AUTHOR_CA), "the author CA, three zips down");
			eq(d.chain().getFirst(), cas.get(SamsungCertificates.DISTRIBUTOR_CA), "the distributor CA");

			site.on("GET", "/ext.zip", 200,
				zip(Map.of("binary/cert-add-on_2.0.75.zip", zip(Map.of("plugins/org.tizen.common.cert_2.0.75.jar", zip(Map.of("x", new byte[] {1})))))));
			IOException moved = fails(IOException.class, () -> service.caCertificates(QUIET), "the certificates are gone from the extension");
			ok(moved.getMessage().contains("no longer holds"), moved.getMessage());
			site.on("GET", "/extensions", 200, "<extensions/>");
			fails(IOException.class, () -> service.caCertificates(QUIET), "the extension is gone from the list");
			site.on("GET", "/extensions", 503, "");
			fails(IOException.class, () -> service.caCertificates(QUIET), "the site is down");
		}
	}

	private static void distributorDownload() throws Exception {
		WidgetSigner.Signer tizen = TestCerts.signer("CN=Tizen Public Distributor", List.of());
		KeyStore ks = Certs.newKeyStore();
		ks.load(null, null);
		ks.setKeyEntry("signer", tizen.key(), TizenDistributor.PASSWORD, tizen.chain().toArray(Certificate[]::new));
		ByteArrayOutputStream p12 = new ByteArrayOutputStream();
		ks.store(p12, TizenDistributor.PASSWORD);
		byte[] pack = zip(Map.of("data/tools/certificate-generator/certificates/distributor/" + TizenDistributor.FILE, p12.toByteArray()));
		String list = "Package : other\nPath : /binary/other.zip\n\nPackage : certificate-generator\nVersion : 1\nPath : /binary/cert-gen.zip\n";

		Path dir = Check.tempDir("distributor");
		try (PseudoHttp site = new PseudoHttp()) {
			TizenDistributor source = new TizenDistributor(new TizenDistributor.Source(site.uri("/pkg_list"), site.uri("").toString()));
			site.on("GET", "/pkg_list", 200, "Package : other\nPath : /binary/other.zip\n");
			fails(IOException.class, () -> source.load(dir, QUIET), "the package is gone from the list");
			ok(!Files.exists(dir.resolve(TizenDistributor.FILE)), "...and nothing half-downloaded is kept");

			site.on("GET", "/pkg_list", 200, list).on("GET", "/binary/cert-gen.zip", 200, pack);
			List<String> said = new ArrayList<>();
			eq(tizen.chain(), source.load(dir, said::add).chain(), "the signer, from inside the package");
			eq(1, said.size(), "it says what it's fetching");
			int requests = site.received().size();
			eq(tizen.chain(), source.load(dir, QUIET).chain(), "the second time, from the folder");
			eq(requests, site.received().size(), "...without asking again");
		}
	}

	private static byte[] zip(Map<String, byte[]> files) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		try (ZipOutputStream z = new ZipOutputStream(b)) {
			for (Map.Entry<String, byte[]> e : files.entrySet()) {
				z.putNextEntry(new ZipEntry(e.getKey()));
				z.write(e.getValue());
				z.closeEntry();
			}
		}
		return b.toByteArray();
	}
}
