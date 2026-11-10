package black.samsungtvclient;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;
import static black.samsungtvclient.Check.ok;
import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.api.AppId;
import black.samsungtvclient.api.DialAppName;
import black.samsungtvclient.api.DialApps;
import black.samsungtvclient.api.Duid;
import black.samsungtvclient.api.KeyCode;
import black.samsungtvclient.api.Media;
import black.samsungtvclient.api.PackageId;
import black.samsungtvclient.api.PairingToken;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.MacAddress;
import black.samsungtvclient.util.AtomicFiles;
import black.samsungtvclient.util.HttpUrl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Every shaped value: what it accepts and what it refuses. A bad value someone reports becomes one more entry here. */
public final class TypesTest {
	private TypesTest() {}

	public static void run() throws Exception {
		shapes();
		media();
		atomicFiles();
	}

	private static void shapes() {
		accepts(AppId::new, "111299001912", "org.tizen.browser", "AbCdE12345.Cards");
		refuses(AppId::new, "", "../x", "a b", "a;reboot", "a/b", "$(id)");
		accepts(PackageId::new, "TvClient01", "AbCdE12345");
		refuses(PackageId::new, "", "short", "TvClient012", "TvClient 1", "TvClient;1");
		accepts(Duid::new, "TESTDUID00000", "AAA11111");
		refuses(Duid::new, "", "short", "has space in it", "bad;characters");
		accepts(DialAppName::new, "YouTube", "Netflix", "com.example.App");
		refuses(DialAppName::new, "", "../x", "You Tube", "a/b");
		accepts(KeyCode::new, "KEY_HOME", "KEY_0", "KEY_CH_LIST");
		refuses(KeyCode::new, "", "KEY_", "HOME", "key_home", "KEY_HOME;");
		accepts(MacAddress::new, "02:00:00:5F:82:78", "02-00-00-5f-82-78", "0200005f8278", " 02:00:00:5F:82:78 ");
		refuses(MacAddress::new, "", "02:00:00", "not a mac", "02:00:00:5F:82:7G", "02:00:00:5F:82:78:90");
		accepts(Host::new, "10.0.0.5", "tv.local", "fe80::1", "[::1]", " 10.0.0.5 ");
		refuses(Host::new, "", "1.2.3/4", "a b", "tv:local:", "[]", "x;y");
		accepts(HttpUrl::new, "http://h/a.mp4", "https://h/My Clip.mp4", "HTTP://h/");
		refuses(HttpUrl::new, "", "ftp://h/a.mp4", "file:///a.srt", "/just/a/path", "http:///nohost");
		accepts(PairingToken::new, "61460560");
		refuses(PairingToken::new, "", "   ");

		eq("fe80::1", new Host("[fe80::1]").value(), "brackets come off");
		eq("[fe80::1]", new Host("fe80::1").forUrl(), "and go back on for a URL");
		eq("tv.local", new Host(" tv.local ").forUrl(), "a name needs none");
		eq("http://h/My%20Clip.mp4", new HttpUrl("http://h/My Clip.mp4").value(), "URLs are encoded as browsers encode them");
		ok(!new PairingToken("61460560").toString().contains("61460560"), "a token can't reach a log through toString");
		eq(DialApps.AppState.INSTALLABLE, DialApps.AppState.parse("installable=http://h/x"), "DIAL's installable carries a URL");
		eq(DialApps.AppState.RUNNING, DialApps.AppState.parse(" Running "), "any case, trimmed");
		eq(DialApps.AppState.UNKNOWN, DialApps.AppState.parse("sideways"), "a state DIAL doesn't define");
	}

	private static void media() {
		HttpUrl url = new HttpUrl("http://h/a.mp4");
		HttpUrl subtitles = new HttpUrl("http://h/a.srt");
		Media plain = Media.of(url);
		eq(Optional.empty(), plain.knownTitle(), "nothing given");
		eq(Optional.empty(), plain.knownMime(), "nothing given");
		eq(Optional.empty(), plain.knownSubtitles(), "nothing given");
		Media full = plain.titled(" A Film ").typed("video/mp4").subtitled(subtitles);
		eq(Optional.of("A Film"), full.knownTitle(), "title, trimmed");
		eq(Optional.of("video/mp4"), full.knownMime(), "mime");
		eq(Optional.of(subtitles), full.knownSubtitles(), "subtitles");
		eq(Optional.empty(), plain.titled("  ").knownTitle(), "a blank title is no title");
		eq(url, full.url(), "the URL is kept through every step");
	}

	private static void atomicFiles() throws Exception {
		Path dir = Check.tempDir("atomic");
		Path file = dir.resolve("made/on/demand.txt");
		AtomicFiles.write(file, "one".getBytes(UTF_8));
		eq("one", Files.readString(file), "creates the file, and its folders");
		AtomicFiles.write(file, "two".getBytes(UTF_8));
		eq("two", Files.readString(file), "replaces it");
		try (var left = Files.list(file.getParent())) {
			eq(List.of(file), left.toList(), "no temporary file is left behind");
		}
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			eq("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), "only its owner can read it");
		}
		Path taken = dir.resolve("a-folder");
		Files.createDirectory(taken);
		Files.writeString(taken.resolve("occupied"), "x");
		fails(java.io.IOException.class, () -> AtomicFiles.write(taken, "y".getBytes(UTF_8)), "a target that can't be replaced");
		eq("x", Files.readString(taken.resolve("occupied")), "...leaves what was there");
		try (var left = Files.list(dir)) {
			ok(left.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "...and no temporary file");
		}
	}

	private static void accepts(Consumer<String> type, String... values) {
		for (String v : values) {
			try {
				type.accept(v);
				ok(true, "accepts " + v);
			} catch (IllegalArgumentException e) {
				ok(false, "should accept <" + v + ">: " + e.getMessage());
			}
		}
	}

	private static void refuses(Consumer<String> type, String... values) {
		for (String v : values) { fails(IllegalArgumentException.class, () -> type.accept(v), "refuses <" + v + ">"); }
	}
}
