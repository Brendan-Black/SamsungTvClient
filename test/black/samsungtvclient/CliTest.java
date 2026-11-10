package black.samsungtvclient;

import static black.samsungtvclient.Check.eq;
import static black.samsungtvclient.Check.fails;

import black.samsungtvclient.api.Generation;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.upnp.Renderer;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class CliTest {
	private CliTest() {}

	public static void run() {
		Main.Options o = parse("--ip", "10.0.0.5", "--name", " Den ", "--store", "s.properties", "--upnp-port", "7676", "key", "home", "--hold", "500");
		eq(new Host("10.0.0.5"), o.ip(), "--ip");
		eq("Den", o.name(), "--name is trimmed");
		eq(Path.of("s.properties"), o.store(), "--store");
		eq(7676, o.upnpPort(), "--upnp-port");
		eq(List.of("key", "home", "--hold", "500"), o.args(), "everything after the command is left alone");
		eq(new Host("fe80::1"), parse("--ip", "[fe80::1]", "info").ip(), "bracketed IPv6 is accepted");
		eq(List.of("help"), parse("--help").args(), "--help");
		eq(Optional.of(Generation.TIZEN_OPEN), parse("--generation", "tizen-open", "info").knownGeneration(), "--generation by name");
		eq(Optional.empty(), o.knownGeneration(), "without --generation it is detected");

		eq(URI.create("http://[fe80::1]:9197/dmr"), Renderer.descriptionUrl(new Host("fe80::1"), 9197), "renderer URL, IPv6");
		eq(URI.create("http://10.0.0.5:9197/dmr"), Renderer.descriptionUrl(new Host("10.0.0.5"), Renderer.DEFAULT_PORT), "renderer URL");

		fails(IllegalArgumentException.class, () -> parse("--ip", "1.2.3/4", "info"), "bad --ip");
		fails(IllegalArgumentException.class, () -> parse("--name", " ", "info"), "empty --name");
		fails(IllegalArgumentException.class, () -> parse("--upnp-port", "0", "status"), "bad port");
		fails(IllegalArgumentException.class, () -> parse("--ip"), "flag without value");
		fails(IllegalArgumentException.class, () -> parse("--bogus", "info"), "unknown option");
		fails(IllegalArgumentException.class, () -> parse("--generation", "webos", "info"), "unknown generation");
	}

	private static Main.Options parse(String... argv) {
		return Main.Options.parse(argv);
	}
}
