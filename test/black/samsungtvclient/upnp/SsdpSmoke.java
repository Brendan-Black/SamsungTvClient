package black.samsungtvclient.upnp;

import black.samsungtvclient.model.RendererInfo;
import black.samsungtvclient.net.Ssdp;
import java.time.Duration;
import java.util.List;

/**
 * A smoke test against the real network, kept out of {@code check} ({@code ./gradlew smoke}): whether SSDP finds
 * PseudoTv over actual multicast, which depends on the machine and what its firewall allows.
 */
public final class SsdpSmoke {
	private SsdpSmoke() {}

	public static void main(String[] args) throws Exception {
		try (PseudoTvProcess _ = new PseudoTvProcess()) {
			List<RendererInfo> found = Renderer.discover(Duration.ofSeconds(3), Ssdp::search);
			long pseudos = found.stream().filter(r -> r.name().equals(EndToEndTest.NAME)).count();
			System.out.println("SSDP found " + found + "; " + pseudos + " PseudoTv");
			if (pseudos != 1) { System.exit(1); }
		}
	}
}
