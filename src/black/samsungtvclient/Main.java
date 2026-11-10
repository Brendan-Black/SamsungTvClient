package black.samsungtvclient;

import black.samsungtvclient.api.App;
import black.samsungtvclient.api.AppId;
import black.samsungtvclient.api.AppStatus;
import black.samsungtvclient.api.Apps;
import black.samsungtvclient.api.Developer;
import black.samsungtvclient.api.DialAppName;
import black.samsungtvclient.api.DialApps;
import black.samsungtvclient.api.Duid;
import black.samsungtvclient.api.Generation;
import black.samsungtvclient.api.Key;
import black.samsungtvclient.api.KeyCode;
import black.samsungtvclient.api.LaunchType;
import black.samsungtvclient.api.Media;
import black.samsungtvclient.api.PackageId;
import black.samsungtvclient.api.Pairing;
import black.samsungtvclient.api.RemoteControl;
import black.samsungtvclient.api.SamsungTv;
import black.samsungtvclient.api.Support;
import black.samsungtvclient.api.TvInfo;
import black.samsungtvclient.api.TvStore;
import black.samsungtvclient.detect.GenerationDetector;
import black.samsungtvclient.detect.PortProbe;
import black.samsungtvclient.model.PlaybackStatus;
import black.samsungtvclient.model.PositionInfo;
import black.samsungtvclient.model.TransportState;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.Http;
import black.samsungtvclient.net.MacAddress;
import black.samsungtvclient.net.SoapFault;
import black.samsungtvclient.net.Ssdp;
import black.samsungtvclient.net.WakeOnLan;
import black.samsungtvclient.samsung.RemoteChannel;
import black.samsungtvclient.samsung.SmartApi;
import black.samsungtvclient.tizen.AppInstaller;
import black.samsungtvclient.tizen.CertificateStore;
import black.samsungtvclient.tizen.SamsungCertificates;
import black.samsungtvclient.tizen.TizenDistributor;
import black.samsungtvclient.tizen.WidgetSigner;
import black.samsungtvclient.upnp.Didl;
import black.samsungtvclient.upnp.Renderer;
import black.samsungtvclient.util.AtomicFiles;
import black.samsungtvclient.util.HttpUrl;
import black.samsungtvclient.util.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** The {@code tv} command: everything the TV's network interfaces offer, one command at a time. */
public final class Main {
	static final String DEFAULT_NAME = "SamsungTvClient";
	static final Duration DISCOVERY_WINDOW = Duration.ofSeconds(3);
	private static final long CAST_WAIT_MS = 15_000;
	private static final long KEY_GAP_MS = 150;

	private static final String USAGE = """
		Usage: tv [options] <command> [args]

		The TV:
		  discover                      find Samsung TVs on the LAN, with their API generation
		  generations                   the API generations Samsung TVs have had, and their support here
		  info                          what the TV says about itself
		  wake [mac]                    turn it on (Wake-on-LAN; needs Power On with Mobile)
		  power                         the power key (off, or on from standby)

		Remote control (pairs on first use: choose Allow on the TV):
		  pair | unpair                 pair now / forget the pairing
		  key <key>... [--hold <ms>]    press keys in turn, e.g. key home right right ok
		  keys                          list key names
		  text <text>                   type into the TV's on-screen keyboard
		  pointer <dx> <dy> | click     move the browser's pointer / click with it
		  events                        print what the TV reports, until Ctrl+C

		Apps:
		  apps                          list installed apps
		  launch <id or name> [--url <link>] [--native | --deep-link]
		  browse <url>                  open a web page in the TV's browser
		  app <id>                      is it running / on screen?
		  close <id>                    close it
		  dial <app> [status | launch [payload] | stop]
		                                DIAL apps: e.g. dial YouTube launch v=<video id>

		Your own apps (Developer Mode on, with this PC as Host PC IP):
		  duid                          the TV's id, which the distributor certificate names
		  certificate [--author <name>] [--renew]
		                                sign in to Samsung and get signing certificates for this TV
		  package <dir> [--out <file>] [--tizen-distributor]
		                                sign the web app in <dir> into a .wgt (--tizen-distributor:
		                                for TVs on Tizen 4.0 and older, which install does by itself)
		  install <dir | file.wgt>      sign if needed, install, and open it
		  uninstall <package id>        remove it

		Media (UPnP: the TV plays a URL; its own remote can't pause or skip it):
		  cast <url> [--title <t>] [--mime <type>] [--subtitles <url>]
		                                --subtitles: an .srt, .smi, .sub, .ssa, .ass, .vtt or .ttml
		                                file the TV can fetch (Samsung TVs show it)
		  play | pause | stop | seek <hh:mm:ss | mm:ss | seconds>
		  status | volume [0-100] | mute [on|off]

		Options (before the command):
		  --ip <addr>       the TV            (env SAMSUNGTV_IP; default: the one discovery finds)
		  --name <s>        this client's name on the TV (env SAMSUNGTV_NAME; default %s)
		  --store <file>    pairings          (env SAMSUNGTV_STORE; default ~/.samsungtv/tvs.properties)
		  --upnp-port <n>   the TV's UPnP port (default %d)
		  --generation <g>  the TV's API generation, e.g. tizen-token (default: detected; see `tv generations`)
		""".formatted(DEFAULT_NAME, Renderer.DEFAULT_PORT);

	private Main() {}

	/**
	 * Parsed global options plus the command and its arguments.
	 *
	 * @param ip         null to discover the TV
	 * @param store      null for the default file
	 * @param generation null to detect it
	 */
	record Options(@Nullable Host ip, String name, @Nullable Path store, int upnpPort, @Nullable Generation generation, List<String> args) {
		static final List<String> FLAGS = List.of("--ip", "--name", "--store", "--upnp-port", "--generation");

		/** @throws IllegalArgumentException for a bad or unknown option */
		static Options parse(String[] argv) {
			String ip = env("SAMSUNGTV_IP");
			String name = Optional.ofNullable(env("SAMSUNGTV_NAME")).orElse(DEFAULT_NAME);
			String store = env("SAMSUNGTV_STORE");
			int upnpPort = Renderer.DEFAULT_PORT;
			Generation generation = null;
			int i = 0;
			for (; i < argv.length && argv[i].startsWith("-"); i++) {
				String flag = argv[i];
				switch (flag) {
					case "--ip" -> ip = value(argv, ++i, flag);
					case "--name" -> name = value(argv, ++i, flag).strip();
					case "--store" -> store = value(argv, ++i, flag);
					case "--upnp-port" -> upnpPort = port(value(argv, ++i, flag));
					case "--generation" -> {
						String g = value(argv, ++i, flag);
						generation = Generation.named(g).orElseThrow(() -> new IllegalArgumentException(
							"--generation: no generation " + g + " (see `tv generations`)"));
					}
					case "-h", "--help" -> {
						return new Options(null, name, null, upnpPort, generation, List.of("help"));
					}
					default -> throw new IllegalArgumentException("unknown option: " + flag);
				}
			}
			if (name.isEmpty()) { throw new IllegalArgumentException("--name can't be empty"); }
			return new Options(ip == null ? null : new Host(ip), name, store == null ? null : Path.of(store), upnpPort, generation,
				List.of(argv).subList(i, argv.length));
		}

		Optional<Host> knownIp() {
			return Optional.ofNullable(ip);
		}

		Optional<Generation> knownGeneration() {
			return Optional.ofNullable(generation);
		}

		TvStore tvStore() {
			return store == null ? TvStore.defaultStore() : new TvStore(store);
		}

		/** Signing keys and certificates: a {@code samsung} folder beside the pairings. */
		CertificateStore certificates() {
			return new CertificateStore(tvStore().file().resolveSibling("samsung"));
		}
	}

	public static void main(String[] argv) {
		int code;
		try {
			code = run(Options.parse(argv));
		} catch (IllegalArgumentException e) {
			System.err.println("error: " + e.getMessage());
			System.err.println();
			System.err.print(USAGE);
			code = 2;
		} catch (SoapFault e) {
			System.err.println("TV refused: " + e.getMessage());
			code = 1;
		} catch (UnsupportedOperationException e) { // a generation, or a capability of one, this library lacks
			System.err.println("error: " + e.getMessage());
			code = 1;
		} catch (IOException e) {
			System.err.println("error: " + Http.describe(e));
			code = 1;
		} catch (InterruptedException e) {
			code = 130;
		}
		System.exit(code);
	}

	static int run(Options o) throws IOException, InterruptedException {
		List<String> args = o.args();
		if (args.isEmpty() || List.of("help", "--help", "-h").contains(args.get(0))) {
			System.out.print(USAGE);
			return 0;
		}
		String command = args.get(0);
		List<String> rest = args.subList(1, args.size());
		switch (command) {
			case "discover" -> {
				none(rest);
				discover();
			}
			case "generations" -> {
				none(rest);
				generations();
			}
			case "keys" -> {
				none(rest);
				for (Key k : Key.values()) { System.out.printf("  %-14s %-18s %s%n", k.name().toLowerCase(Locale.ROOT), k.code(), k.description()); }
				System.out.println("  0-9, hdmi1-4, and any other KEY_... code the TV knows; aliases: ok, back, vol+, vol-, ch+, ch-");
			}
			case "wake" -> wake(o, rest);
			case "package" -> pack(o, rest);
			case "duid", "certificate", "install" -> developer(o, command, rest);
			case "uninstall" -> uninstall(o, rest);
			case "cast", "play", "pause", "stop", "seek", "status", "volume", "mute" -> media(o, command, rest);
			default -> {
				try (SamsungTv tv = tv(o)) {
					samsung(o, tv, command, rest);
				}
			}
		}
		return 0;
	}

	// ---- Samsung's own interfaces --------------------------------------------------------------------

	private static void samsung(Options o, SamsungTv tv, String command, List<String> rest) throws IOException, InterruptedException {
		switch (command) {
			case "info" -> {
				none(rest);
				printInfo(tv.generation(), tv.about().info());
			}
			case "power" -> {
				none(rest);
				tv.remote().key(Key.POWER.code());
				System.out.println("Power key sent.");
			}
			case "pair" -> {
				none(rest);
				Pairing pairing = tv.pairing();
				boolean known = pairing.paired();
				if (!known) { System.out.println("Connecting; choose Allow on the TV..."); }
				pairing.pair();
				System.out.println(known ? "Already paired with " + tv.host() + "." : "Paired with " + tv.host() + "; saved in " + o.tvStore().file());
			}
			case "unpair" -> {
				none(rest);
				tv.pairing().unpair();
				System.out.println("Forgot " + tv.host() + ". (The TV keeps its own list under Settings > General > "
					+ "External Device Manager > Device Connect Manager.)");
			}
			case "key" -> keys(tv.remote(), rest);
			case "text" -> {
				if (rest.isEmpty()) { throw new IllegalArgumentException("text needs something to type"); }
				tv.textInput().text(String.join(" ", rest));
				System.out.println("Typed. (It only lands while the TV's on-screen keyboard is open.)");
			}
			case "pointer" -> {
				if (rest.size() != 2) { throw new IllegalArgumentException("pointer needs dx and dy, e.g. pointer 100 -50"); }
				tv.pointer().move(integer(rest.get(0), "dx"), integer(rest.get(1), "dy"));
			}
			case "click" -> {
				none(rest);
				tv.pointer().click();
			}
			case "events" -> {
				none(rest);
				System.out.println("Listening to " + tv.host() + "; Ctrl+C to stop.");
				tv.events().listen(System.out::println);
				Thread.currentThread().join();
			}
			case "apps" -> {
				none(rest);
				List<App> apps = tv.apps().installed();
				for (App a : apps) { System.out.printf("  %-28s %-32s %s%n", a.id(), a.name(), a.launchType() == LaunchType.DEEP_LINK ? "web app" : "native"); }
				System.out.println(apps.size() + " apps. The browser (" + RemoteChannel.BROWSER + ") isn't listed but launches all the same.");
			}
			case "launch" -> launch(tv.apps(), rest);
			case "browse" -> {
				HttpUrl url = new HttpUrl(one(rest, "browse needs a URL"));
				tv.apps().browse(url);
				System.out.println("Opened " + url + " in the TV's browser.");
			}
			case "app" -> {
				AppId id = new AppId(one(rest, "app needs an app id (see `tv apps`)"));
				Optional<AppStatus> s = tv.apps().status(id);
				if (s.isEmpty()) {
					System.out.println("No app " + id + " on this TV.");
					return;
				}
				AppStatus a = s.get();
				System.out.println(a.name() + " (" + a.id() + a.knownVersion().map(v -> ", version " + v).orElse("") + "): "
					+ (a.visible() ? "on screen" : a.running() ? "running in the background" : "not running"));
			}
			case "close" -> {
				AppId id = new AppId(one(rest, "close needs an app id (see `tv apps`)"));
				tv.apps().close(id);
				System.out.println("Closed " + id + ".");
			}
			case "dial" -> dial(tv.dial(), rest);
			default -> throw new IllegalArgumentException("unknown command: " + command);
		}
	}

	private static void keys(RemoteControl remote, List<String> rest) throws IOException, InterruptedException {
		Duration hold = null;
		List<KeyCode> codes = new ArrayList<>();
		for (int i = 0; i < rest.size(); i++) {
			if (rest.get(i).equals("--hold")) {
				hold = Duration.ofMillis(integer(flagValue(rest, ++i, "--hold"), "--hold"));
			} else {
				codes.add(Key.code(rest.get(i)));
			}
		}
		if (codes.isEmpty()) { throw new IllegalArgumentException("key needs at least one key, e.g. key home (`tv keys` lists them)"); }
		for (int i = 0; i < codes.size(); i++) {
			if (i > 0) { Thread.sleep(KEY_GAP_MS); }
			if (hold != null) {
				remote.hold(codes.get(i), hold);
			} else {
				remote.key(codes.get(i));
			}
		}
		System.out.println("Sent " + String.join(" ", codes.stream().map(KeyCode::value).toList()) + ".");
	}

	private static void launch(Apps apps, List<String> rest) throws IOException {
		String url = null;
		LaunchType type = null;
		List<String> positional = new ArrayList<>();
		for (int i = 0; i < rest.size(); i++) {
			switch (rest.get(i)) {
				case "--url" -> url = flagValue(rest, ++i, "--url");
				case "--native" -> type = LaunchType.NATIVE_LAUNCH;
				case "--deep-link" -> type = LaunchType.DEEP_LINK;
				default -> positional.add(rest.get(i));
			}
		}
		String wanted = String.join(" ", positional);
		if (wanted.isBlank()) { throw new IllegalArgumentException("launch needs an app id or name (see `tv apps`)"); }
		Optional<App> app = apps.installed().stream()
			.filter(a -> a.id().value().equals(wanted) || a.name().equalsIgnoreCase(wanted)).findFirst();
		AppId id = app.map(App::id).orElseGet(() -> {
			try {
				return new AppId(wanted);
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("no installed app called " + wanted + " (see `tv apps`)", e);
			}
		});
		// Unlisted ids are built-in apps such as the browser, which launch natively.
		LaunchType how = type != null ? type : app.map(App::launchType).orElse(LaunchType.NATIVE_LAUNCH);
		if (url == null) {
			apps.launch(id, how);
		} else {
			apps.launch(id, how, url);
		}
		System.out.println("Launched " + app.map(App::name).orElse(id.value()) + (url == null ? "." : " with " + url + "."));
	}

	private static void dial(DialApps dial, List<String> rest) throws IOException {
		if (rest.isEmpty()) { throw new IllegalArgumentException("dial needs an app name, e.g. dial YouTube"); }
		DialAppName app = new DialAppName(rest.get(0));
		String action = rest.size() > 1 ? rest.get(1) : "status";
		switch (action) {
			case "status" -> {
				if (rest.size() > 2) { throw unexpected(rest.get(2)); }
				DialApps.Status s = dial.status(app);
				System.out.println(s.name() + ": " + s.state().name().toLowerCase(Locale.ROOT));
			}
			case "launch" -> {
				if (rest.size() > 2) {
					String payload = String.join(" ", rest.subList(2, rest.size()));
					dial.launch(app, payload);
					System.out.println("Launched " + app + " with " + payload + ".");
				} else {
					dial.launch(app);
					System.out.println("Launched " + app + ".");
				}
			}
			case "stop" -> {
				if (rest.size() > 2) { throw unexpected(rest.get(2)); }
				dial.stop(app);
				System.out.println("Stopped " + app + ".");
			}
			default -> throw new IllegalArgumentException("dial takes status, launch [payload] or stop, not " + action);
		}
	}

	private static void wake(Options o, List<String> rest) throws IOException, InterruptedException {
		if (rest.size() > 1) { throw unexpected(rest.get(1)); }
		Optional<Host> host = o.knownIp();
		MacAddress mac;
		if (!rest.isEmpty()) {
			mac = new MacAddress(rest.get(0));
		} else {
			Host known = host.orElseThrow(() -> new IllegalArgumentException("wake needs --ip (a TV that's off can't be discovered) or a MAC address"));
			mac = o.tvStore().mac(known).orElseThrow(() -> new IOException("the TV's MAC address isn't known yet; run "
				+ "`tv --ip " + known + " info` once while it's on, or give it: `tv wake <mac>`"));
		}
		WakeOnLan.wake(mac);
		System.out.println("Wake-on-LAN sent to " + mac + ".");
		if (host.isEmpty()) { return; }
		System.out.println(awaitOn(host.get(), Duration.ofSeconds(20)) ? "The TV is on."
			: "No answer from " + host.get() + " after 20s. "
				+ "Is Settings > General > Network > Expert Settings > Power On with Mobile on?");
	}

	/** Waits up to {@code timeout} for the TV at {@code host} to answer as any generation. */
	private static boolean awaitOn(Host host, Duration timeout) throws InterruptedException {
		GenerationDetector detector = new PortProbe();
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (detector.detect(host).isPresent()) { return true; }
			Thread.sleep(1000);
		}
		return false;
	}

	private static void printInfo(Generation g, TvInfo i) {
		System.out.println("Name:        " + i.name());
		System.out.println("Model:       " + i.modelName() + i.knownModel().map(m -> " (" + m + ")").orElse(""));
		System.out.println("OS:          " + i.os());
		System.out.println("API:         " + g.label());
		System.out.println("Address:     " + i.ip() + " (" + i.networkType() + ")" + i.knownMac().map(m -> ", MAC " + m).orElse(""));
		System.out.println("Resolution:  " + i.resolution());
		i.knownPowerState().ifPresent(p -> System.out.println("Power:       " + p));
		System.out.println("Remote:      " + (i.tokenAuth() ? "paired, port " + RemoteChannel.PORT : "open, port " + SmartApi.PORT));
		System.out.println("Developer:   " + (i.developerMode() ? "on" : "off"));
		List<String> on = i.features().entrySet().stream().filter(e -> "true".equals(e.getValue())).map(Map.Entry::getKey).sorted().toList();
		if (!on.isEmpty()) { System.out.println("Supports:    " + String.join(", ", on)); }
	}

	private static void discover() throws IOException {
		System.out.println("Searching for " + DISCOVERY_WINDOW.toSeconds() + "s...");
		List<Discovery.Found> found = Discovery.discover(DISCOVERY_WINDOW, Ssdp::search, new PortProbe());
		if (found.isEmpty()) {
			System.out.println("No Samsung TV answered. Is it on, and is this PC on the same network?");
			return;
		}
		for (Discovery.Found f : found) {
			System.out.printf("%-32s %-14s %-16s %s%s%n", f.name(), f.model(), f.host(), f.generation().label(),
				f.generation().support() == Support.NOT_IMPLEMENTED ? " (not supported yet)" : "");
		}
	}

	private static void generations() {
		for (Generation g : Generation.values()) {
			System.out.printf("%-12s %-10s %-20s %s%n", g.name().toLowerCase(Locale.ROOT).replace('_', '-'), g.years(),
				switch (g.support()) {
					case VERIFIED -> "supported";
					case UNTESTED -> "supported, untested";
					case NOT_IMPLEMENTED -> "not implemented";
				}, g.os() + " (" + g.series() + ")");
			System.out.println("             " + g.protocol());
		}
	}

	/** The TV to talk to: --ip, else the only Samsung TV discovery finds; through its generation's connector. */
	private static SamsungTv tv(Options o) throws IOException {
		GenerationDetector detector = new PortProbe();
		Optional<Generation> generation = o.knownGeneration();
		Host host;
		Optional<Host> given = o.knownIp();
		if (given.isPresent()) {
			host = given.get();
		} else {
			Discovery.Found only = onlyTv(detector);
			host = only.host();
			if (generation.isEmpty()) { generation = Optional.of(only.generation()); }
		}
		Generation g = generation.isPresent() ? generation.get()
			: detector.detect(host).orElseThrow(() -> new IOException("nothing at " + host
				+ " answers like a Samsung TV; is it on? (--generation skips this check)"));
		return Connectors.builtIn().connect(host, g, o.name(), o.tvStore());
	}

	private static Discovery.Found onlyTv(GenerationDetector detector) throws IOException {
		System.err.println("No --ip given; searching for a TV...");
		List<Discovery.Found> found = Discovery.discover(DISCOVERY_WINDOW, Ssdp::search, detector);
		if (found.isEmpty()) { throw new IOException("no Samsung TV answered discovery; is it on, and on this network? (or give --ip)"); }
		if (found.size() > 1) {
			throw new IOException("found " + found.size() + " TVs (" + String.join(", ",
				found.stream().map(f -> f.name() + " at " + f.host()).toList()) + "); choose one with --ip");
		}
		return found.getFirst();
	}

	// ---- your own apps: Samsung certificates, signing, and the developer connection -----------------

	private static void developer(Options o, String command, List<String> rest) throws IOException, InterruptedException {
		try (SamsungTv tv = tv(o)) {
			Developer developer = tv.developer();
			switch (command) {
				case "duid" -> {
					none(rest);
					try (Developer.Session s = developer.open()) {
						System.out.println(s.duid());
					}
				}
				case "certificate" -> certificate(o, developer, rest);
				case "install" -> {
					Path app = Path.of(one(rest, "install needs a web app folder or a .wgt file"));
					AppId appId;
					try (Developer.Session s = developer.open()) {
						Map<String, String> caps = s.capabilities();
						byte[] wgt = Files.isDirectory(app) ? sign(o, app, TizenDistributor.neededBy(caps)) : Files.readAllBytes(app);
						System.out.println("Installing " + AppInstaller.ids(wgt).appId() + " (" + wgt.length / 1024 + " KB) on " + tv.host() + "...");
						appId = s.install(wgt);
						s.open(appId);
					}
					System.out.println("Installed and opened. It's in the TV's Apps too; `tv launch " + appId + "` opens it again.");
				}
				default -> throw new IllegalArgumentException("unknown command: " + command);
			}
		}
	}

	private static void uninstall(Options o, List<String> rest) throws IOException {
		String pkg = one(rest, "uninstall needs the package id (the 10 characters before the dot in the app id)");
		PackageId id = new PackageId(pkg.contains(".") ? pkg.substring(0, pkg.indexOf('.')) : pkg); // a bad id is a usage error, before looking for the TV
		try (SamsungTv tv = tv(o); Developer.Session s = tv.developer().open()) {
			s.uninstall(id);
		}
		System.out.println("Removed " + pkg + ".");
	}

	/** Gets (or extends to this TV) the author and distributor certificates; keeps an existing author certificate. */
	private static void certificate(Options o, Developer developer, List<String> rest) throws IOException, InterruptedException {
		String authorName = o.name();
		boolean renew = false;
		for (int i = 0; i < rest.size(); i++) {
			switch (rest.get(i)) {
				case "--author" -> authorName = flagValue(rest, ++i, "--author").strip();
				case "--renew" -> renew = true;
				default -> throw unexpected(rest.get(i));
			}
		}
		if (authorName.isEmpty()) { throw new IllegalArgumentException("--author can't be empty"); }
		CertificateStore certs = o.certificates();
		SamsungCertificates samsung = SamsungCertificates.live();
		try {
			Duid duid;
			try (Developer.Session s = developer.open()) {
				duid = s.duid();
			}
			Optional<WidgetSigner.Signer> author = certs.author();
			Optional<WidgetSigner.Signer> distributor = certs.distributor();
			List<Duid> covered = new ArrayList<>(distributor.isPresent()
				? SamsungCertificates.duids(distributor.get().chain().getFirst())
				: List.of());
			if (!renew && author.isPresent() && covered.contains(duid)) {
				System.out.println("The certificates in " + certs.dir() + " already cover this TV (" + duid + "). --renew gets new ones.");
				return;
			}
			keepCaCertificates(certs, samsung);

			SamsungCertificates.Account account = samsung.signIn(Duration.ofMinutes(10), System.out::println);
			System.out.println("Signed in as " + account.displayName() + ".");
			if (author.isEmpty()) {
				X509Certificate authorCa = certs.ca(SamsungCertificates.AUTHOR_CA).orElseThrow();
				KeyPair keys = rsa();
				X509Certificate leaf = samsung.author(account, keys, authorName);
				issuedBy(leaf, authorCa, "author");
				certs.saveAuthor(new WidgetSigner.Signer(keys.getPrivate(), List.of(leaf, authorCa)));
				System.out.println("Author certificate: " + leaf.getSubjectX500Principal().getName() + ", until " + leaf.getNotAfter() + ".");
			} else {
				System.out.println("Keeping the author certificate (updates to installed apps need the same one).");
			}
			if (!covered.contains(duid)) { covered.add(duid); }
			X509Certificate distributorCa = certs.ca(SamsungCertificates.DISTRIBUTOR_CA).orElseThrow();
			KeyPair keys = rsa();
			X509Certificate leaf = samsung.distributor(account, keys, covered);
			issuedBy(leaf, distributorCa, "distributor");
			certs.saveDistributor(new WidgetSigner.Signer(keys.getPrivate(), List.of(leaf, distributorCa)));
			System.out.println("Distributor certificate for " + String.join(", ", SamsungCertificates.duids(leaf).stream().map(Duid::value).toList())
				+ ", until " + leaf.getNotAfter() + ".");
			System.out.println("Saved in " + certs.dir() + ". Back up author.p12 and password: they sign every update.");
		} catch (GeneralSecurityException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	/** Makes sure {@code certs} has Samsung's two CA certificates, fetching them if not. */
	private static void keepCaCertificates(CertificateStore certs, SamsungCertificates samsung) throws IOException, GeneralSecurityException {
		if (certs.ca(SamsungCertificates.AUTHOR_CA).isPresent() && certs.ca(SamsungCertificates.DISTRIBUTOR_CA).isPresent()) { return; }
		for (Map.Entry<String, X509Certificate> ca : samsung.caCertificates(System.out::println).entrySet()) { certs.saveCa(ca.getKey(), ca.getValue()); }
	}

	private static void pack(Options o, List<String> rest) throws IOException {
		Path out = null;
		boolean tizenDistributor = false;
		List<String> positional = new ArrayList<>();
		for (int i = 0; i < rest.size(); i++) {
			switch (rest.get(i)) {
				case "--out" -> out = Path.of(flagValue(rest, ++i, "--out"));
				case "--tizen-distributor" -> tizenDistributor = true;
				default -> positional.add(rest.get(i));
			}
		}
		Path dir = Path.of(one(positional, "package needs the web app's folder"));
		byte[] wgt = sign(o, dir, tizenDistributor);
		Path target = out != null ? out : Path.of(dir.toAbsolutePath().normalize().getFileName() + ".wgt");
		AtomicFiles.write(target, wgt);
		System.out.println("Signed " + AppInstaller.ids(wgt).appId() + ": " + target.toAbsolutePath() + " (" + wgt.length / 1024 + " KB).");
	}

	/**
	 * The web app in {@code dir}, signed with the stored author certificate and, for TVs on Tizen 4.0 and older,
	 * Tizen's public distributor certificate (else Samsung's for this TV), and checked.
	 */
	private static byte[] sign(Options o, Path dir, boolean tizenDistributor) throws IOException {
		CertificateStore certs = o.certificates();
		try {
			Optional<WidgetSigner.Signer> author = certs.author();
			Optional<WidgetSigner.Signer> distributor = tizenDistributor
				? Optional.of(TizenDistributor.live().load(certs.dir(), System.out::println))
				: certs.distributor();
			if (author.isEmpty() || distributor.isEmpty()) {
				throw new IOException("no signing certificates in " + certs.dir() + " yet; run `tv certificate` with the TV in Developer Mode");
			}
			byte[] wgt = WidgetSigner.sign(dir, author.get(), distributor.get());
			WidgetSigner.verify(wgt);
			return wgt;
		} catch (GeneralSecurityException e) {
			throw new IOException("couldn't sign " + dir + ": " + e.getMessage(), e);
		}
	}

	private static KeyPair rsa() throws GeneralSecurityException {
		KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
		g.initialize(2048);
		return g.generateKeyPair();
	}

	private static void issuedBy(X509Certificate leaf, X509Certificate ca, String what) throws GeneralSecurityException {
		try {
			leaf.verify(ca.getPublicKey());
		} catch (GeneralSecurityException e) {
			throw new GeneralSecurityException("Samsung's " + what + " certificate isn't signed by " + ca.getSubjectX500Principal().getName()
				+ "; the TV wouldn't trust it", e);
		}
	}

	// ---- UPnP media ----------------------------------------------------------------------------------

	private static void media(Options o, String command, List<String> rest) throws IOException, InterruptedException {
		switch (command) {
			case "cast" -> cast(o, rest);
			case "play" -> {
				none(rest);
				renderer(o).play();
				System.out.println("Playing.");
			}
			case "pause" -> {
				none(rest);
				renderer(o).pause();
				System.out.println("Paused.");
			}
			case "stop" -> {
				none(rest);
				renderer(o).stop();
				System.out.println("Stopped.");
			}
			case "seek" -> {
				String time = one(rest, "seek needs a time, e.g. seek 0:01:30");
				Duration position = PositionInfo.parseTime(time)
					.orElseThrow(() -> new IllegalArgumentException("not a time (use hh:mm:ss, mm:ss or seconds): " + time));
				renderer(o).seek(position);
				System.out.println("Seeked to " + PositionInfo.toHms(position.toSeconds()) + ".");
			}
			case "status" -> {
				none(rest);
				printStatus(renderer(o));
			}
			case "volume" -> volume(o, rest);
			case "mute" -> mute(o, rest);
			default -> throw new IllegalArgumentException("unknown command: " + command);
		}
	}

	private static void cast(Options o, List<String> rest) throws IOException, InterruptedException {
		String title = null;
		String mime = null;
		HttpUrl subtitles = null;
		List<String> positional = new ArrayList<>();
		for (int i = 0; i < rest.size(); i++) {
			switch (rest.get(i)) {
				case "--title" -> title = flagValue(rest, ++i, "--title");
				case "--mime" -> mime = flagValue(rest, ++i, "--mime");
				case "--subtitles" -> subtitles = new HttpUrl(flagValue(rest, ++i, "--subtitles")); // a bad URL is a usage error, before looking for the TV
				default -> positional.add(rest.get(i));
			}
		}
		HttpUrl url = new HttpUrl(one(positional, "cast needs a media URL"));
		if (subtitles != null && !Didl.isSubtitle(subtitles.value())) {
			System.err.println("Note: " + subtitles + " has no subtitle extension (" + Didl.SUBTITLE_FORMATS + "); sending it as srt.");
		}
		Renderer tv = renderer(o);
		System.out.println("Casting to " + tv.info().name() + ": " + url + (subtitles == null ? "" : " (subtitles: " + subtitles + ")"));
		tv.cast(new Media(url, title, mime, subtitles));

		// Report what the TV actually does with it: it buffers (TRANSITIONING) before PLAYING.
		long deadline = System.currentTimeMillis() + CAST_WAIT_MS;
		TransportState state = tv.transportState();
		while (state != TransportState.PLAYING && System.currentTimeMillis() < deadline) {
			Thread.sleep(500);
			state = tv.transportState();
		}
		System.out.println(state == TransportState.PLAYING
			? "Now PLAYING."
			: "TV is " + state + " after " + CAST_WAIT_MS / 1000 + "s; check `tv status`.");
	}

	private static void volume(Options o, List<String> rest) throws IOException {
		if (rest.isEmpty()) {
			System.out.println("Volume: " + renderer(o).volume());
			return;
		}
		int level = integer(one(rest, "volume takes one number, 0-100"), "volume");
		renderer(o).setVolume(level);
		System.out.println("Volume set to " + level + ".");
	}

	private static void mute(Options o, List<String> rest) throws IOException {
		if (rest.isEmpty()) {
			System.out.println("Mute: " + (renderer(o).muted() ? "on" : "off"));
			return;
		}
		boolean on = switch (one(rest, "mute takes on or off")) {
			case "on" -> true;
			case "off" -> false;
			default -> throw new IllegalArgumentException("mute takes on or off");
		};
		renderer(o).setMuted(on);
		System.out.println("Mute " + (on ? "on." : "off."));
	}

	private static void printStatus(Renderer tv) throws IOException {
		PlaybackStatus s = tv.status();
		PositionInfo p = s.position();
		System.out.println("TV:       " + tv.info().name() + " (" + tv.info().ip() + ")");
		System.out.println("State:    " + s.state());
		long position = p.knownPosition().orElse(Duration.ZERO).toSeconds();
		long duration = p.knownDuration().orElse(Duration.ZERO).toSeconds();
		if (duration > 0 || position > 0) { System.out.println("Position: " + PositionInfo.toHms(position) + " / " + PositionInfo.toHms(duration)); }
		if (!p.trackUri().isBlank()) { System.out.println("Track:    " + p.trackUri()); }
		System.out.println("Volume:   " + s.volume() + (s.muted() ? " (muted)" : ""));
	}

	/** UPnP works with any MediaRenderer, so without --ip any one renderer on the LAN will do. */
	private static Renderer renderer(Options o) throws IOException {
		Optional<Host> ip = o.knownIp();
		if (ip.isPresent()) { return Renderer.connect(Renderer.descriptionUrl(ip.get(), o.upnpPort())); }
		System.err.println("No --ip given; searching for a TV...");
		return Renderer.find(DISCOVERY_WINDOW, Ssdp::search);
	}

	// ---- arguments -----------------------------------------------------------------------------------

	private static void none(List<String> rest) {
		if (!rest.isEmpty()) { throw unexpected(rest.get(0)); }
	}

	private static String one(List<String> rest, String message) {
		for (String arg : rest) { if (Options.FLAGS.contains(arg)) { throw unexpected(arg); } }
		if (rest.size() != 1) { throw new IllegalArgumentException(message); }
		return rest.get(0);
	}

	private static IllegalArgumentException unexpected(String arg) {
		return new IllegalArgumentException(Options.FLAGS.contains(arg)
			? arg + " goes before the command, e.g. tv " + arg + " <value> <command>"
			: "unexpected argument: " + arg);
	}

	private static String flagValue(List<String> rest, int i, String flag) {
		if (i >= rest.size()) { throw new IllegalArgumentException(flag + " needs a value"); }
		return rest.get(i);
	}

	private static int integer(String s, String what) {
		try {
			return Integer.parseInt(s.strip());
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException(what + " must be a whole number, got " + s, e);
		}
	}

	private static @Nullable String env(String name) {
		String v = System.getenv(name);
		return v == null || v.isBlank() ? null : v.strip();
	}

	private static String value(String[] argv, int i, String flag) {
		if (i >= argv.length) { throw new IllegalArgumentException(flag + " needs a value"); }
		return argv[i];
	}

	private static int port(String s) {
		int p = integer(s, "--upnp-port");
		if (p < 1 || p > 65535) { throw new IllegalArgumentException("--upnp-port: not a valid port: " + s); }
		return p;
	}
}
