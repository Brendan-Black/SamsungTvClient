package black.samsungtvclient.detect;

import black.samsungtvclient.api.Generation;
import black.samsungtvclient.api.TvInfo;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.samsung.SmartApi;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;

/**
 * Tells the generations apart by what answers, newest first:
 * <ol>
 * <li>Tizen's REST API on port 8001: its device info says {@code TokenAuthSupport} since 2017
 * ({@link Generation#TIZEN_TOKEN}); without it, it's a 2016 TV ({@link Generation#TIZEN_OPEN}).</li>
 * <li>Port 8000 open: the encrypted remote of 2014-2015 ({@link Generation#ENCRYPTED}).</li>
 * <li>Port 55000 open: the Orsay remote of 2010-2013 ({@link Generation#ORSAY}).</li>
 * </ol>
 * The last two only check that something listens, so another device on those ports would pass for a TV; ask only
 * hosts that are Samsung TVs (e.g. found by discovery).
 */
public final class PortProbe implements GenerationDetector {
	/** The ports probed; tests point them at pseudos. */
	public record Ports(int rest, int encrypted, int orsay) {
		public static final Ports SAMSUNG = new Ports(SmartApi.PORT, 8000, 55000);
	}

	private final Ports ports;
	private final Duration timeout;

	public PortProbe() {
		this(Ports.SAMSUNG, Duration.ofMillis(1500));
	}

	public PortProbe(Ports ports, Duration timeout) {
		this.ports = ports;
		this.timeout = timeout;
	}

	@Override
	public Optional<Generation> detect(Host host) {
		try {
			TvInfo info = new SmartApi(SmartApi.baseUri(host, ports.rest())).info();
			return Optional.of(info.tokenAuth() ? Generation.TIZEN_TOKEN : Generation.TIZEN_OPEN);
		} catch (IOException e) {
			// not Tizen, or not answering: on to the older generations
		}
		if (listening(host, ports.encrypted())) { return Optional.of(Generation.ENCRYPTED); }
		if (listening(host, ports.orsay())) { return Optional.of(Generation.ORSAY); }
		return Optional.empty();
	}

	private boolean listening(Host host, int port) {
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(host.value(), port), (int) timeout.toMillis());
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
