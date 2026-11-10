package black.samsungtvclient;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;
import static black.samsungtvclient.Check.ok;

import black.samsungtvclient.api.Capability;
import black.samsungtvclient.api.Generation;
import black.samsungtvclient.api.Key;
import black.samsungtvclient.api.KeyCode;
import black.samsungtvclient.api.RemoteControl;
import black.samsungtvclient.api.SamsungTv;
import black.samsungtvclient.api.Support;
import black.samsungtvclient.api.TvConnector;
import black.samsungtvclient.api.TvStore;
import black.samsungtvclient.api.UnsupportedCapabilityException;
import black.samsungtvclient.api.UnsupportedGenerationException;
import black.samsungtvclient.detect.PortProbe;
import black.samsungtvclient.net.Host;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The generation model, the connector registry, and telling generations apart by their ports. */
public final class ApiTest {
	private static final Host TV = new Host("tv");
	private static final Host LOCAL = new Host("127.0.0.1");
	private static final String API = "/api/v2/";

	private ApiTest() {}

	public static void run() throws Exception {
		generations();
		connectors();
		detection();
	}

	private static void generations() {
		eq(Optional.of(Generation.TIZEN_TOKEN), Generation.named("tizen-token"), "by name, with a dash");
		eq(Optional.of(Generation.ORSAY), Generation.named(" Orsay "), "any case, trimmed");
		eq(Optional.empty(), Generation.named("webos"), "not a Samsung generation");
		eq(Support.VERIFIED, Generation.TIZEN_TOKEN.support(), "2017 on: tried on a TV");
		eq(Support.NOT_IMPLEMENTED, Generation.ENCRYPTED.support(), "2014-2015: described, not implemented");
		eq("Encrypted (2014-2015, H and J series)", Generation.ENCRYPTED.label(), "label");
		ok(!Generation.ORSAY.offers().contains(Capability.APPS), "Orsay's remote can't list apps");
		ok(Generation.ORSAY.offers().contains(Capability.MEDIA), "every generation has the UPnP MediaRenderer");
	}

	private static void connectors() throws IOException {
		Connectors builtIn = Connectors.builtIn();
		ok(builtIn.forGeneration(Generation.TIZEN_TOKEN).isPresent() && builtIn.forGeneration(Generation.TIZEN_OPEN).isPresent(),
			"Tizen is built in");
		ok(builtIn.forGeneration(Generation.ORSAY).isEmpty(), "Orsay isn't");
		TvStore store = new TvStore(Check.tempDir("api").resolve("tvs.properties"));
		UnsupportedGenerationException none = fails(UnsupportedGenerationException.class,
			() -> builtIn.connect(TV, Generation.ORSAY, "c", store), "no connector, no TV");
		ok(none.getMessage().contains("2010-2013") && none.getMessage().contains("55000"),
			"the error names the generation and its protocol: " + none.getMessage());

		// Another generation's implementation plugs in beside the built-in ones, and needs only what it has.
		PseudoOrsay orsay = new PseudoOrsay();
		Connectors more = builtIn.with(orsay);
		try (SamsungTv tv = more.connect(TV, Generation.ORSAY, "c", store)) {
			tv.remote().key(Key.HOME.code());
			eq(List.of(Key.HOME.code()), orsay.pressed(), "the plugged-in connector's TV does the work");
			ok(tv.supports(Capability.REMOTE_KEYS) && !tv.supports(Capability.APPS), "it says what it can do");
			UnsupportedCapabilityException apps = fails(UnsupportedCapabilityException.class, tv::apps,
				"what it doesn't implement throws");
			ok(apps.getMessage().contains("don't offer"), "Orsay doesn't offer apps: " + apps.getMessage());
			UnsupportedCapabilityException media = fails(UnsupportedCapabilityException.class, tv::media, "media, not implemented");
			ok(media.getMessage().contains("doesn't implement"),
				"Orsay offers media, this implementation doesn't: " + media.getMessage());
		}
		ok(builtIn.forGeneration(Generation.ORSAY).isEmpty(), "with() leaves the original alone");
		fails(IllegalArgumentException.class, () -> Connectors.of(List.of(orsay, orsay)), "one connector per generation");
	}

	private static void detection() throws IOException {
		Duration quick = Duration.ofMillis(500);
		try (ServerSocket encrypted = listener();
			ServerSocket orsay = listener();
			PseudoHttp token = new PseudoHttp();
			PseudoHttp open = new PseudoHttp();
			PseudoHttp garbled = new PseudoHttp()) {
			token.on("GET", API, 200, "{\"device\":{\"TokenAuthSupport\":\"true\",\"modelName\":\"QN65Q80T\"}}");
			open.on("GET", API, 200, "{\"device\":{\"modelName\":\"UN55KS8000\"}}");
			garbled.on("GET", API, 200, "this is not JSON");
			int closed = closedPort();
			eq(Optional.of(Generation.TIZEN_TOKEN), probe(token.uri("/").getPort(), closed, closed, quick),
				"REST with TokenAuthSupport: 2017 on");
			eq(Optional.of(Generation.TIZEN_OPEN), probe(open.uri("/").getPort(), closed, closed, quick),
				"REST without it: 2016");
			eq(Optional.of(Generation.ENCRYPTED), probe(closed, encrypted.getLocalPort(), orsay.getLocalPort(), quick),
				"no REST, port 8000 open: 2014-2015");
			eq(Optional.of(Generation.ORSAY), probe(closed, closed, orsay.getLocalPort(), quick),
				"only port 55000 open: 2010-2013");
			eq(Optional.empty(), probe(closed, closed, closed, quick), "nothing answering: not a TV, or off");
			eq(Optional.empty(), probe(garbled.uri("/").getPort(), closed, closed, quick), "something else on 8001 isn't Tizen");
		}
	}

	private static Optional<Generation> probe(int rest, int encrypted, int orsay, Duration timeout) {
		return new PortProbe(new PortProbe.Ports(rest, encrypted, orsay), timeout).detect(LOCAL);
	}

	private static ServerSocket listener() throws IOException {
		return new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
	}

	private static int closedPort() throws IOException {
		try (ServerSocket s = listener()) {
			return s.getLocalPort();
		}
	}

	/** An Orsay implementation that only has the remote, recording the keys it's given. */
	private static final class PseudoOrsay implements TvConnector, SamsungTv, RemoteControl {
		private final List<KeyCode> pressed = new ArrayList<>();

		List<KeyCode> pressed() {
			return List.copyOf(pressed);
		}

		@Override
		public Generation generation() {
			return Generation.ORSAY;
		}

		@Override
		public SamsungTv connect(Host host, String clientName, TvStore store) {
			return this;
		}

		@Override
		public Host host() {
			return TV;
		}

		@Override
		public Set<Capability> capabilities() {
			return Set.of(Capability.REMOTE_KEYS);
		}

		@Override
		public RemoteControl remote() {
			return this;
		}

		@Override
		public void key(KeyCode code) {
			pressed.add(code);
		}

		@Override
		public void hold(KeyCode code, Duration duration) {
			throw new IllegalStateException("not expected in this test");
		}

		@Override
		public void close() {}
	}
}
