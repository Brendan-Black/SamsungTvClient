package black.samsungtvclient.api;

import static black.samsungtvclient.api.Capability.ABOUT;
import static black.samsungtvclient.api.Capability.APPS;
import static black.samsungtvclient.api.Capability.DEVELOPER;
import static black.samsungtvclient.api.Capability.DIAL;
import static black.samsungtvclient.api.Capability.EVENTS;
import static black.samsungtvclient.api.Capability.MEDIA;
import static black.samsungtvclient.api.Capability.PAIRING;
import static black.samsungtvclient.api.Capability.POINTER;
import static black.samsungtvclient.api.Capability.REMOTE_KEYS;
import static black.samsungtvclient.api.Capability.TEXT;
import static black.samsungtvclient.api.Capability.VOLUME;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The network control APIs Samsung has shipped on its TVs, oldest first. Each is a different protocol for the same
 * job, so each needs its own {@link SamsungTv} implementation (made by a {@link TvConnector}). The UPnP MediaRenderer
 * ({@link Capability#MEDIA}, {@link Capability#VOLUME}) is the one interface every generation shares.
 *
 * <p>Model years by series letter: C 2010, D 2011, E 2012, F 2013, H 2014, J 2015, K 2016, M 2017, N 2018, R 2019,
 * T 2020, A 2021, B 2022, C 2023 (again), D 2024. QLEDs use Q plus a number with the same year letters.
 */
public enum Generation {
	/**
	 * Orsay, the pre-Tizen Smart TV. The remote is a TCP connection to port 55000 carrying length-prefixed packets
	 * whose fields are base64 strings (an authentication packet naming the client, then one packet per {@code KEY_}
	 * code); the TV asks "Allow?" once per client. No app control beyond Samsung's own UPnP {@code MainTVAgent2}
	 * service (sources and channels, on port 7676).
	 */
	ORSAY("Orsay", "2010-2013", "C, D, E, F", "Orsay", "remote over TCP 55000 (base64 packets); Allow on the TV",
		Set.of(PAIRING, REMOTE_KEYS, MEDIA, VOLUME), Support.NOT_IMPLEMENTED),

	/**
	 * Samsung's "encrypted" pairing (Smart View 1.0). Pairing is a PIN the TV shows, exchanged over HTTP on port 8080
	 * ({@code /ws/pairing}, steps 0 to 2) for a session key; the remote is then a Socket.IO WebSocket on port 8000
	 * whose key commands are AES-encrypted with that key. Some 2015 J models already run Tizen 2.3 underneath but
	 * keep this protocol.
	 */
	ENCRYPTED("Encrypted", "2014-2015", "H, J", "Orsay, Tizen 2.3",
		"PIN pairing over HTTP 8080, then an AES-encrypted Socket.IO remote on 8000",
		Set.of(PAIRING, REMOTE_KEYS, DIAL, MEDIA, VOLUME), Support.NOT_IMPLEMENTED),

	/**
	 * The first Tizen API: the REST API on port 8001 and the remote channel as a plain WebSocket on the same port,
	 * with no token (the TV asks "Allow?" for each new client name).
	 */
	TIZEN_OPEN("Tizen without a token", "2016", "K", "Tizen 2.4", "REST and a plain WebSocket remote channel, both on 8001; no token",
		Set.of(ABOUT, PAIRING, REMOTE_KEYS, TEXT, POINTER, EVENTS, APPS, DIAL, MEDIA, VOLUME, DEVELOPER), Support.UNTESTED),

	/**
	 * The current Tizen API: the REST API on port 8001 (its device info says {@code TokenAuthSupport}), and the
	 * remote channel as a TLS WebSocket on port 8002 that hands out a token on the first Allow.
	 */
	TIZEN_TOKEN("Tizen with a token", "2017 on", "M, N, R, T, A, B, C, D, Q", "Tizen 3.0 on",
		"REST on 8001, a TLS WebSocket remote channel on 8002 with a token",
		Set.of(ABOUT, PAIRING, REMOTE_KEYS, TEXT, POINTER, EVENTS, APPS, DIAL, MEDIA, VOLUME, DEVELOPER), Support.VERIFIED);

	private final String title;
	private final String years;
	private final String series;
	private final String os;
	private final String protocol;
	private final Set<Capability> offers;
	private final Support support;

	Generation(String title, String years, String series, String os, String protocol, Set<Capability> offers, Support support) {
		this.title = title;
		this.years = years;
		this.series = series;
		this.os = os;
		this.protocol = protocol;
		this.offers = offers;
		this.support = support;
	}

	/** Model years, e.g. {@code 2014-2015}. */
	public String years() {
		return years;
	}

	/** Model series letters. */
	public String series() {
		return series;
	}

	public String os() {
		return os;
	}

	/** How a client talks to it, in a line. */
	public String protocol() {
		return protocol;
	}

	/** What its protocol can do, whether or not this library implements it. */
	public Set<Capability> offers() {
		return offers;
	}

	public Support support() {
		return support;
	}

	/** A short name for people, e.g. {@code Encrypted}. */
	public String title() {
		return title;
	}

	/** The title with years and series, e.g. {@code Encrypted (2014-2015, H and J series)}. */
	public String label() {
		int last = series.lastIndexOf(", ");
		String s = last < 0 ? series : series.substring(0, last) + " and " + series.substring(last + 2);
		return title + " (" + years + ", " + s + " series)";
	}

	/** By name, ignoring case and with {@code -} for {@code _}: e.g. {@code tizen-token}. */
	public static Optional<Generation> named(String name) {
		String n = name.strip().toUpperCase(Locale.ROOT).replace('-', '_');
		for (Generation g : values()) { if (g.name().equals(n)) { return Optional.of(g); } }
		return Optional.empty();
	}
}
