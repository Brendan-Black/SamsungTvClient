package black.samsungtvclient.samsung;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;
import static black.samsungtvclient.Check.ok;
import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.Check;
import black.samsungtvclient.PseudoHttp;
import black.samsungtvclient.api.App;
import black.samsungtvclient.api.AppId;
import black.samsungtvclient.api.AppStatus;
import black.samsungtvclient.api.Capability;
import black.samsungtvclient.api.Developer;
import black.samsungtvclient.api.DialAppName;
import black.samsungtvclient.api.DialApps;
import black.samsungtvclient.api.Duid;
import black.samsungtvclient.api.Generation;
import black.samsungtvclient.api.Key;
import black.samsungtvclient.api.KeyCode;
import black.samsungtvclient.api.LaunchType;
import black.samsungtvclient.api.PairingToken;
import black.samsungtvclient.api.SamsungTv;
import black.samsungtvclient.api.TvInfo;
import black.samsungtvclient.api.TvStore;
import black.samsungtvclient.dial.Dial;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.MacAddress;
import black.samsungtvclient.samsung.RemoteChannel.Event;
import black.samsungtvclient.sdb.PseudoSdb;
import black.samsungtvclient.util.HttpUrl;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public final class SamsungTest {
	private static final Host TV1 = new Host("tv1");
	private static final Host TV2 = new Host("tv2");
	private static final AppId YOUTUBE = new AppId(PseudoSamsung.YOUTUBE);
	private static final DialAppName YOUTUBE_DIAL = new DialAppName("YouTube");
	private static final MacAddress MAC = new MacAddress("02:00:00:5F:82:78");

	/** What a UN75NU8000 answered at /api/v2/, with its addresses and ids replaced. */
	static final String REAL_INFO = """
		{"device":{"FrameTVSupport":"false","GamePadSupport":"true","ImeSyncedSupport":"true","OS":"Tizen",\
		"TokenAuthSupport":"true","VoiceSupport":"true","countryCode":"US","description":"Samsung DTV RCR",\
		"developerIP":"0.0.0.0","developerMode":"0","duid":"uuid:00000000-0000-4000-8000-000000000001",\
		"firmwareVersion":"Unknown","id":"uuid:00000000-0000-4000-8000-000000000001","ip":"192.168.1.52",\
		"model":"18_KANTM2_UHD","modelName":"UN75NU8000","name":"[TV] Living Room","networkType":"wireless",\
		"resolution":"3840x2160","smartHubAgreement":"true","ssid":"02:00:00:6e:f6:46","type":"Samsung SmartTV",\
		"udn":"uuid:00000000-0000-4000-8000-000000000001","wifiMac":"02:00:00:5F:82:78"},\
		"id":"uuid:00000000-0000-4000-8000-000000000001","isSupport":"{\\"DMP_DRM_PLAYREADY\\":\\"false\\",\
		\\"DMP_available\\":\\"true\\",\\"remote_available\\":\\"true\\",\\"remote_fourDirections\\":\\"true\\"}\\n",\
		"name":"[TV] Living Room","remote":"1.0","type":"Samsung SmartTV","uri":"http://192.168.1.52:8001/api/v2/",\
		"version":"2.0.25"}""";

	private SamsungTest() {}

	public static void run() throws Exception {
		keys();
		info();
		store();
		try (PseudoSamsung tv = new PseudoSamsung()) {
			rest(tv);
			dial(tv);
			remote(tv);
		}
		tizenTv();
		badReplies();
	}

	/** TizenTv: the generation-neutral interfaces, over the pseudo's REST, DIAL, remote channel and developer connection. */
	private static void tizenTv() throws Exception {
		eq(URI.create("wss://h:8002/api/v2/channels/samsung.remote.control"),
			TizenTv.Endpoints.of(new Host("h"), Generation.TIZEN_TOKEN).channel(), "since 2017: TLS on 8002");
		eq(URI.create("ws://h:8001/api/v2/channels/samsung.remote.control"),
			TizenTv.Endpoints.of(new Host("h"), Generation.TIZEN_OPEN).channel(), "2016: plain WebSocket on 8001");
		fails(IllegalArgumentException.class, () -> new TizenConnector(Generation.ORSAY), "a Tizen connector is for Tizen");

		TvStore store = new TvStore(Check.tempDir("tizentv").resolve("tvs.properties"));
		List<String> events = new CopyOnWriteArrayList<>();
		try (PseudoSamsung pseudo = new PseudoSamsung(); PseudoSdb sdb = new PseudoSdb()) {
			TizenTv.Endpoints at = new TizenTv.Endpoints(pseudo.api(), pseudo.remote(), pseudo.dial(), URI.create("http://127.0.0.1:9/dmr"), sdb.port());
			try (SamsungTv tv = new TizenTv(new Host("127.0.0.1"), Generation.TIZEN_TOKEN, "Test Client", store, at)) {
				Host host = tv.host();
				eq(Set.copyOf(EnumSet.allOf(Capability.class)), tv.capabilities(), "a Tizen TV can do everything listed");
				eq("UN75NU8000", tv.about().info().modelName(), "about: the REST device info");
				eq(Optional.of(MAC), store.mac(host), "about remembers the MAC, for wake");
				ok(!tv.pairing().paired(), "not paired yet");
				tv.events().listen(events::add);
				ok(tv.pairing().paired(), "listening connected, and paired");
				tv.remote().key(Key.HOME.code());
				eq(2, tv.apps().installed().size(), "apps over the remote channel");
				tv.apps().launch(YOUTUBE, LaunchType.DEEP_LINK, "v=x");
				ok(tv.apps().status(YOUTUBE).isPresent(), "app status over REST");
				eq("YouTube", tv.dial().status(YOUTUBE_DIAL).name(), "DIAL");
				ok(events.stream().anyMatch(e -> e.contains("ms.remote.touchEnable")), "events reach the listener as JSON");
				try (Developer.Session session = tv.developer().open()) {
					eq(new Duid(PseudoSdb.DUID), session.duid(), "the developer connection gives the TV's id");
				}
				tv.pairing().unpair();
				ok(!tv.pairing().paired(), "unpair forgets the token");
			}
		}
	}

	private static void keys() {
		key("KEY_HOME", "home", "short name");
		key("KEY_ENTER", "OK", "alias, any case");
		key("KEY_RETURN", "back", "back");
		key("KEY_VOLUP", "vol+", "vol+");
		key("KEY_VOLDOWN", "vol-", "vol-");
		key("KEY_CYAN", "blue", "blue is KEY_CYAN");
		key("KEY_7", "7", "digit");
		key("KEY_HDMI2", "hdmi2", "a numbered input");
		key("KEY_AMBIENT", "key_ambient", "raw codes pass through");
		key("KEY_CH_LIST", "ch-list", "dashes for underscores");
		fails(IllegalArgumentException.class, () -> Key.code("jump"), "unknown key");
		fails(IllegalArgumentException.class, () -> Key.code("KEY_"), "an empty raw code");
	}

	private static void key(String code, String name, String what) {
		eq(new KeyCode(code), Key.code(name), what);
	}

	private static void info() {
		TvInfo i = TvInfo.parse(REAL_INFO);
		eq("UN75NU8000", i.modelName(), "model name");
		eq(Optional.of(MAC), i.knownMac(), "MAC");
		eq(true, i.tokenAuth(), "pairing required");
		eq(false, i.developerMode(), "developer mode off");
		eq(Optional.empty(), i.knownPowerState(), "2018 TVs don't report power state");
		ok(i.supports("remote_available") && !i.supports("DMP_DRM_PLAYREADY"), "isSupport flags (a JSON string) parsed");
		fails(IllegalArgumentException.class, () -> TvInfo.parse("{\"name\":\"x\"}"), "not a device reply");

		TvInfo sparse = TvInfo.parse("{\"device\":{\"wifiMac\":\"not a mac\"}}");
		eq(TvInfo.UNKNOWN, sparse.name(), "text a TV leaves out reads unknown");
		eq(Optional.empty(), sparse.knownMac(), "a MAC that isn't one is no MAC");
		eq(Optional.empty(), sparse.knownModel(), "no model");
	}

	private static void store() throws IOException {
		Path file = Check.tempDir("store").resolve("tvs.properties");
		Host one = new Host("10.0.0.5");
		Host two = new Host("10.0.0.6");
		TvStore s = new TvStore(file);
		eq(Optional.empty(), s.token(one), "nothing stored yet");
		s.setToken(one, new PairingToken("123"));
		s.setMac(one, MAC);
		s.setToken(two, new PairingToken("456"));
		TvStore again = new TvStore(file);
		eq(Optional.of(new PairingToken("123")), again.token(one), "token persists");
		eq(Optional.of(MAC), again.mac(one), "MAC persists (colons survive)");
		again.forget(one);
		eq(Optional.empty(), again.token(one), "forget clears the TV");
		eq(Optional.of(new PairingToken("456")), again.token(two), "...and only that TV");

		// Reported from reading the code: forgetting "tv" also cleared "tv.local", whose keys start with "tv.".
		Host shortName = new Host("tv");
		Host longName = new Host("tv.local");
		s.setToken(shortName, new PairingToken("a"));
		s.setToken(longName, new PairingToken("b"));
		s.forget(shortName);
		eq(Optional.of(new PairingToken("b")), s.token(longName), "forgetting a name leaves names that start with it");

		Files.writeString(file, "10.0.0.5.mac=not a mac\n");
		IOException bad = fails(IOException.class, () -> s.mac(one), "a hand-edited file with a bad MAC");
		ok(bad.getMessage().contains(file.toString()), "says which file: " + bad.getMessage());
	}

	private static void rest(PseudoSamsung tv) throws IOException {
		SmartApi api = new SmartApi(tv.api());
		eq("[TV] Fake", api.info().name(), "REST device info");
		AppStatus yt = api.appStatus(YOUTUBE).orElseThrow();
		ok(yt.running() && !yt.visible() && "YouTube".equals(yt.name()), "app status");
		eq(Optional.of("2.1"), yt.knownVersion(), "app version");
		AppId nope = new AppId("nope");
		eq(Optional.empty(), api.appStatus(nope), "unknown app");
		api.launch(YOUTUBE);
		api.close(YOUTUBE);
		ok(tv.rest().contains("POST /api/v2/applications/" + YOUTUBE)
			&& tv.rest().contains("DELETE /api/v2/applications/" + YOUTUBE), "launch is POST, close is DELETE");
		IOException missing = fails(IOException.class, () -> api.launch(nope), "launching an unknown app");
		ok(missing.getMessage().contains("tv apps"), "the error says how to list apps");
	}

	private static void dial(PseudoSamsung tv) throws IOException {
		Dial dial = new Dial(tv.dial());
		DialApps.Status s = dial.status(YOUTUBE_DIAL);
		ok("YouTube".equals(s.name()) && !s.running(), "DIAL status (namespaced XML)");
		eq(DialApps.AppState.STOPPED, s.state(), "DIAL state");
		dial.launch(YOUTUBE_DIAL, "v=abc123");
		dial.stop(YOUTUBE_DIAL);
		ok(tv.rest().contains("POST /ws/apps/YouTube v=abc123"), "DIAL launch posts the payload");
		ok(tv.rest().contains("DELETE /ws/apps/YouTube/run"), "DIAL stop deletes the running instance");
		fails(IOException.class, () -> dial.status(new DialAppName("Nope")), "no such DIAL app");
	}

	private static void remote(PseudoSamsung tv) throws Exception {
		TvStore store = new TvStore(Check.tempDir("pair").resolve("tvs.properties"));
		List<Event> events = new CopyOnWriteArrayList<>();
		try (RemoteChannel r = RemoteChannel.connect(tv.remote(), TV1, "Test Client", store, events::add)) {
			eq(Optional.of(new PairingToken(PseudoSamsung.TOKEN)), store.token(TV1), "pairing saves the TV's token");
			ok(tv.connections().getLast().contains("name=" + Base64.getEncoder().encodeToString("Test Client".getBytes(UTF_8))),
				"the client's name goes base64-encoded");

			r.key(Key.HOME.code());
			r.text("你好 hello");
			r.move(30, -20);
			r.click();
			List<App> apps = r.apps(); // the reply arrives in two WebSocket frames
			eq(2, apps.size(), "installed apps");
			eq(LaunchType.DEEP_LINK, apps.get(0).launchType(), "app_type 2 is a web app: deep link");
			eq(LaunchType.NATIVE_LAUNCH, apps.get(1).launchType(), "app_type 4: native launch");
			r.browse(new HttpUrl("http://192.168.1.10:8300/"));
			fails(IOException.class, () -> r.launch(new AppId("nope"), LaunchType.DEEP_LINK), "the TV refuses an unknown app");

			awaitMessages(tv, 7);
			Map<?, ?> key = tv.control(0);
			ok("Click".equals(key.get("Cmd")) && "KEY_HOME".equals(key.get("DataOfCmd"))
				&& "SendRemoteKey".equals(key.get("TypeOfRemote")), "a key press: " + key);
			Map<?, ?> text = tv.control(1);
			eq("你好 hello", new String(Base64.getDecoder().decode(String.valueOf(text.get("Cmd"))), UTF_8),
				"text goes base64, UTF-8");
			eq("SendInputEnd", tv.control(2).get("TypeOfRemote"), "then the keyboard is closed");
			Map<?, ?> move = tv.control(3);
			ok("Move".equals(move.get("Cmd")) && move.get("Position") instanceof Map<?, ?> p
				&& Long.valueOf(30).equals(p.get("x")) && Long.valueOf(-20).equals(p.get("y")), "pointer move: " + move);
			eq("LeftClick", tv.control(4).get("Cmd"), "click");
			ok(tv.received().stream().anyMatch(m -> m.contains("\"org.tizen.browser\"") && m.contains("NATIVE_LAUNCH")
				&& m.contains("\"metaTag\":\"http://192.168.1.10:8300/\"")), "browse launches the browser natively with the URL");
			ok(events.stream().anyMatch(e -> e.name().equals("ms.remote.touchEnable")), "TV events reach the listener");
			ok(events.stream().noneMatch(e -> e.json().contains(PseudoSamsung.TOKEN)), "the pairing token never reaches a listener: `tv events` prints them");
		}

		RemoteChannel.connect(tv.remote(), TV1, "Test Client", store, _ -> {}).close();
		ok(tv.connections().getLast().contains("token=" + PseudoSamsung.TOKEN), "a paired client sends its token");

		tv.allow = false;
		TvStore fresh = new TvStore(Check.tempDir("deny").resolve("tvs.properties"));
		IOException denied = fails(IOException.class,
			() -> RemoteChannel.connect(tv.remote(), TV2, "Test Client", fresh, _ -> {}).close(), "Deny on the TV");
		ok(denied.getMessage().contains("refused"), "Deny is explained: " + denied);
		ok(!denied.getMessage().contains(PseudoSamsung.TOKEN), "...without giving away a token");
		eq(Optional.empty(), fresh.token(TV2), "no token after Deny");
		tv.allow = true;
	}

	/** REST and DIAL answers that aren't what they should be. */
	private static void badReplies() throws IOException {
		AppId app = new AppId("app");
		try (PseudoHttp tv = new PseudoHttp()) {
			SmartApi api = new SmartApi(tv.uri("/api/v2/"));
			tv.on("GET", "/api/v2/", 200, "not JSON");
			fails(IOException.class, api::info, "device info that isn't JSON is an IOException, not an unchecked one");
			tv.on("GET", "/api/v2/applications/app", 200, "[1,2]");
			fails(IOException.class, () -> api.appStatus(app), "an app status that isn't an object");
			tv.on("POST", "/api/v2/applications/app", 403, "");
			IOException forbidden = fails(IOException.class, () -> api.launch(app), "REST refusing");
			ok(forbidden.getMessage().contains("paired connection"), "explains it: " + forbidden.getMessage());
			tv.on("GET", "/api/v2/applications/app", 500, "");
			fails(IOException.class, () -> api.appStatus(app), "a TV having a bad day");

			Dial dial = new Dial(tv.uri("/ws/apps/"));
			tv.on("GET", "/ws/apps/YouTube", 200, "<service><name>YouTube</name></service>");
			IOException noState = fails(IOException.class, () -> dial.status(YOUTUBE_DIAL), "a DIAL reply without a state");
			ok(noState.getMessage().contains("<state>"), noState.getMessage());
			tv.on("GET", "/ws/apps/YouTube", 200, "not xml");
			fails(IOException.class, () -> dial.status(YOUTUBE_DIAL), "a DIAL reply that isn't XML");
			tv.on("GET", "/ws/apps/YouTube", 200, "<service><state>running</state></service>");
			eq(new DialApps.Status("YouTube", DialApps.AppState.RUNNING), dial.status(YOUTUBE_DIAL), "no name: the one asked for");
			tv.on("POST", "/ws/apps/YouTube", 503, "");
			fails(IOException.class, () -> dial.launch(YOUTUBE_DIAL), "DIAL refusing a launch");
			tv.on("DELETE", "/ws/apps/YouTube/run", 500, "");
			fails(IOException.class, () -> dial.stop(YOUTUBE_DIAL), "DIAL refusing a stop");
		}
	}

	private static void awaitMessages(PseudoSamsung tv, int n) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (tv.received().size() < n && System.nanoTime() < deadline) { Thread.sleep(20); }
	}
}
