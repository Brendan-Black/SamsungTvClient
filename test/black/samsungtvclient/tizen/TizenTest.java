package black.samsungtvclient.tizen;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;
import static black.samsungtvclient.Check.ok;
import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.Check;
import black.samsungtvclient.api.AppId;
import black.samsungtvclient.api.Duid;
import black.samsungtvclient.api.PackageId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.security.auth.x500.X500Principal;

/** Certificate requests, Samsung's certificate service's formats, signing and the certificate store. */
public final class TizenTest {
	/** For reading callbacks, which needs no network unless a code has to be exchanged. */
	private static final SamsungCertificates CALLBACKS = new SamsungCertificates(new SamsungCertificates.Endpoints(
		_ -> URI.create("http://127.0.0.1:1/login"), "http://127.0.0.1:1/exchange?t=", URI.create("http://127.0.0.1:1/a"),
		URI.create("http://127.0.0.1:1/d"), URI.create("http://127.0.0.1:1/e"), 0), _ -> {});

	private TizenTest() {}

	static final String CONFIG = """
		<?xml version="1.0" encoding="UTF-8"?>
		<widget xmlns:tizen="http://tizen.org/ns/widgets" xmlns="http://www.w3.org/ns/widgets" id="http://x/Cards" version="0.1.0">
		    <tizen:application id="TvClient01.Cards" package="TvClient01" required_version="3.0"/>
		    <content src="index.html"/>
		</widget>
		""";

	public static void run() throws Exception {
		csr();
		samsungFormats();
		signing();
		store();
		ids();
		eq(true, TizenDistributor.neededBy(Map.of("platform_version", "4.0")), "Tizen 4.0 needs Tizen's distributor");
		eq(false, TizenDistributor.neededBy(Map.of("platform_version", "5.5")), "...5.5 doesn't");
		eq(false, TizenDistributor.neededBy(Map.of()), "...nor a TV that doesn't say");
	}

	private static void csr() throws GeneralSecurityException {
		KeyPair keys = TestCerts.keys();
		String pem = Csr.pem(new X500Principal("EMAILADDRESS=a@b.c,CN=TizenSDK"),
			List.of("URN:tizen:packageid=", "URN:tizen:deviceid=TESTDUID00000"), keys);
		ok(pem.startsWith("-----BEGIN CERTIFICATE REQUEST-----\n") && pem.endsWith("-----END CERTIFICATE REQUEST-----\n"), "PEM framing");
		byte[] der = Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
		List<byte[]> parts = children(der);
		eq(3, parts.size(), "request = info, algorithm, signature");
		byte[] info = parts.get(0);
		Signature v = Signature.getInstance("SHA256withRSA");
		v.initVerify(keys.getPublic());
		v.update(info);
		byte[] bits = content(parts.get(2));
		ok(v.verify(Arrays.copyOfRange(bits, 1, bits.length)), "self-signed with the request's own key (as openssl checks)");
		String text = new String(info, StandardCharsets.ISO_8859_1);
		ok(text.indexOf("TizenSDK") < text.indexOf("a@b.c"), "subject: CN first, then emailAddress (as Certificate Manager's)");
		ok(text.contains("URN:tizen:deviceid=TESTDUID00000") && text.contains("URN:tizen:packageid="), "DUID in subjectAltName");
		ok(contains(info, keys.getPublic().getEncoded()), "carries the public key");
	}

	private static void samsungFormats() throws Exception {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("access_token", "tok");
		fields.put("platform", "VD");
		eq("--*****\r\nContent-Disposition: form-data; name=access_token\r\nContent-Type: text/plain; charset=utf-8\r\n\r\ntok\r\n"
			+ "--*****\r\nContent-Disposition: form-data; name=platform\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nVD\r\n"
			+ "--*****\r\nContent-Disposition: form-data; name=csr; filename=author.csr\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n"
			+ "CSR\r\n--*****--\r\n", new String(SamsungCertificates.multipart(fields, "CSR", "author.csr"), UTF_8),
			"the form, byte for byte as Certificate Manager sends it");

		SamsungCertificates.Account a = CALLBACKS.account(Map.of("code",
			"{\"access_token\":\"T\",\"userId\":\"U\",\"inputEmailID\":\"me@x.com\",\"access_token_expires_in\":\"3600\"}"));
		ok("T".equals(a.accessToken()) && "U".equals(a.userId()) && "me@x.com".equals(a.email()), "the callback's code is the token payload, as JSON");
		eq("U", CALLBACKS.account(Map.of("access_token", "T", "user_id", "U")).userId(), "...or plain parameters");
		fails(IOException.class, () -> CALLBACKS.account(Map.of("code", "{\"userId\":\"U\"}")), "no token");
		eq(Map.of("a", "1 2", "b", ""), SamsungCertificates.form("a=1+2&b="), "form decoding");

		WidgetSigner.Signer d = TestCerts.signer("CN=TizenSDK", List.of("URN:tizen:packageid=", "URN:tizen:deviceid=AAA11111", "URN:tizen:deviceid=BBB22222"));
		eq(List.of(new Duid("AAA11111"), new Duid("BBB22222")), SamsungCertificates.duids(d.chain().getFirst()), "DUIDs from a distributor certificate");
		String pem = "junk\n-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder().encodeToString(d.chain().getFirst().getEncoded())
			+ "\n-----END CERTIFICATE-----\nmore";
		eq(d.chain().getFirst(), SamsungCertificates.certificate(pem, "test"), "the certificate in Samsung's reply");
		fails(GeneralSecurityException.class, () -> SamsungCertificates.certificate("{\"error\":1}", "test"), "a reply without one");
	}

	private static void signing() throws Exception {
		WidgetSigner.Signer author = TestCerts.signer("CN=Author", List.of());
		WidgetSigner.Signer distributor = TestCerts.signer("CN=TizenSDK", List.of("URN:tizen:deviceid=AAA11111"));
		Path dir = Check.tempDir("app");
		Files.writeString(dir.resolve("config.xml"), CONFIG);
		Files.writeString(dir.resolve("index.html"), "<p>你好</p>");
		Files.createDirectories(dir.resolve("audio files"));
		Files.write(dir.resolve("audio files").resolve("stop sign.wav"), new byte[] {1, 2, 3});
		Files.writeString(dir.resolve(".project"), "IDE file");
		Files.writeString(dir.resolve("signature1.xml"), "an old signature");

		byte[] wgt = WidgetSigner.sign(dir, author, distributor);
		WidgetSigner.verify(wgt);
		ok(true, "a signed package verifies");
		Map<String, byte[]> files = WidgetSigner.unzip(wgt);
		eq(List.of("audio files/stop sign.wav", "author-signature.xml", "config.xml", "index.html", "signature1.xml"),
			new ArrayList<>(files.keySet()), "dot files and the old signature are left out");
		String a = new String(files.get("author-signature.xml"), UTF_8);
		String s = new String(files.get("signature1.xml"), UTF_8);
		ok(a.contains("Reference URI=\"audio%20files%2Fstop%20sign.wav\""), "file URIs are percent-encoded, '/' too");
		ok(a.contains("Id=\"AuthorSignature\"") && a.contains("widgets-digsig#role-author") && a.contains("rsa-sha512"),
			"author signature: id, role, RSA-SHA512");
		ok(s.contains("Id=\"DistributorSignature\"") && s.contains("widgets-digsig#role-distributor"), "distributor signature: id and role");
		ok(s.contains("Reference URI=\"author-signature.xml\"") && !a.contains("Reference URI=\"signature1.xml\""),
			"the distributor signs the author signature");
		ok(!a.contains("&#13;") && !a.contains("\r"), "no carriage returns (Windows line breaks in base64 broke it)");
		ok(count(a, "<X509Certificate>") == 2, "the chain, without the root, in KeyInfo");

		Map<String, byte[]> tampered = new TreeMap<>(files);
		tampered.put("index.html", "<p>changed</p>".getBytes(UTF_8));
		fails(GeneralSecurityException.class, () -> WidgetSigner.verify(zip(tampered)), "a changed file");
		Map<String, byte[]> added = new TreeMap<>(files);
		added.put("extra.js", "alert(1)".getBytes(UTF_8));
		fails(GeneralSecurityException.class, () -> WidgetSigner.verify(zip(added)), "a file neither signature covers");
		Map<String, byte[]> unsigned = new TreeMap<>(files);
		unsigned.remove("signature1.xml");
		fails(GeneralSecurityException.class, () -> WidgetSigner.verify(zip(unsigned)), "no distributor signature");

		Path empty = Check.tempDir("notapp");
		fails(IOException.class, () -> WidgetSigner.sign(empty, author, distributor), "a folder without config.xml");
		eq("a%2Fb%20c%E4%BD%A0.js", WidgetSigner.encode("a/b c你.js"), "encoding");
		eq("a/b c你.js", WidgetSigner.decode("a%2Fb%20c%E4%BD%A0.js"), "...and back");
		eq("a+b", WidgetSigner.decode("a+b"), "'+' is a plus, not a space");
	}

	private static void store() throws Exception {
		CertificateStore store = new CertificateStore(Check.tempDir("certs"));
		eq(false, store.author().isPresent(), "nothing stored yet");
		WidgetSigner.Signer author = TestCerts.signer("CN=Author", List.of());
		store.saveAuthor(author);
		store.saveCa("ca.cer", author.chain().get(1));
		CertificateStore again = new CertificateStore(store.dir());
		WidgetSigner.Signer back = again.author().orElseThrow();
		eq(author.chain(), back.chain(), "the chain survives the keystore");
		eq(author.key(), back.key(), "...and the key");
		eq(author.chain().get(1), again.ca("ca.cer").orElseThrow(), "CA certificates are kept");
		ok(Files.readString(store.dir().resolve("password")).strip().length() >= 20, "a generated password");
	}

	private static void ids() throws IOException {
		AppInstaller.AppIds ids = AppInstaller.ids(CONFIG);
		eq(new AppInstaller.AppIds(new PackageId("TvClient01"), new AppId("TvClient01.Cards")), ids, "ids from config.xml");
		fails(IOException.class, () -> AppInstaller.ids(CONFIG.replace("package=\"TvClient01\"", "package=\"short\"")),
			"a package id that isn't 10 characters");
		fails(IOException.class, () -> AppInstaller.ids(CONFIG.replace("TvClient01.Cards", "Other12345.Cards")), "an app id outside its package");
		fails(IOException.class, () -> AppInstaller.ids("<widget/>"), "no <tizen:application>");
	}

	// ---- helpers -------------------------------------------------------------------------------------

	static byte[] zip(Map<String, byte[]> files) throws IOException {
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

	/** The TLVs inside a DER SEQUENCE. */
	private static List<byte[]> children(byte[] seq) {
		byte[] body = content(seq);
		List<byte[]> out = new ArrayList<>();
		for (int i = 0; i < body.length;) {
			int[] len = length(body, i + 1);
			int end = i + 1 + len[1] + len[0];
			out.add(Arrays.copyOfRange(body, i, end));
			i = end;
		}
		return out;
	}

	private static byte[] content(byte[] tlv) {
		int[] len = length(tlv, 1);
		return Arrays.copyOfRange(tlv, 1 + len[1], 1 + len[1] + len[0]);
	}

	/** {length, bytes the length took} at {@code at}. */
	private static int[] length(byte[] b, int at) {
		int first = b[at] & 0xff;
		if (first < 0x80) { return new int[] {first, 1}; }
		int n = first & 0x7f, len = 0;
		for (int i = 1; i <= n; i++) { len = (len << 8) | (b[at + i] & 0xff); }
		return new int[] {len, 1 + n};
	}

	private static boolean contains(byte[] hay, byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= hay.length; i++) {
			for (int j = 0; j < needle.length; j++) { if (hay[i + j] != needle[j]) { continue outer; } }
			return true;
		}
		return false;
	}

	private static int count(String s, String what) {
		int n = 0;
		for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + 1)) { n++; }
		return n;
	}
}
