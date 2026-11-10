package black.samsungtvclient.samsung;

import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.api.App;
import black.samsungtvclient.api.AppId;
import black.samsungtvclient.api.KeyCode;
import black.samsungtvclient.api.LaunchType;
import black.samsungtvclient.api.PairingToken;
import black.samsungtvclient.api.Pointer;
import black.samsungtvclient.api.RemoteControl;
import black.samsungtvclient.api.TextInput;
import black.samsungtvclient.api.TvStore;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.Http;
import black.samsungtvclient.util.HttpUrl;
import black.samsungtvclient.util.Json;
import black.samsungtvclient.util.Nullable;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * The TV's remote-control channel: a WebSocket on port 8002 (TLS) that the TV only opens to clients it
 * has paired with. The first connection makes the TV ask "Allow?"; it then hands out a token, which
 * {@link TvStore} keeps so later connections go straight through.
 *
 * <p>Over it go remote keys, text for an on-screen keyboard, pointer moves and clicks, the installed-app
 * list, and app launches (with a URL or deep link). The TV also sends events back, e.g. when its
 * keyboard opens; {@link #connect} takes a listener for them.
 */
public final class RemoteChannel implements RemoteControl, TextInput, Pointer, AutoCloseable {
	public static final int PORT = 8002;

	/** The remote channel's path, on {@link #PORT} (TLS, 2017 on) or the REST API's port (plain, 2016). */
	public static final String PATH = "/api/v2/channels/samsung.remote.control";

	public static final AppId BROWSER = new AppId("org.tizen.browser");

	/** How long the TV's "Allow?" prompt stays up; it then refuses on its own. */
	public static final Duration ALLOW_WAIT = Duration.ofSeconds(35);

	private static final Duration REPLY_WAIT = Duration.ofSeconds(10);

	// The protocol's own field names, in every control message.
	private static final String CMD = "Cmd";
	private static final String TYPE_OF_REMOTE = "TypeOfRemote";

	/** Something the TV sent: {@code name} is the event (e.g. {@code ms.remote.imeStart}), {@code data} its payload, if any. */
	public record Event(String name, @Nullable Object data, String json) {}

	private final WebSocket socket;
	private final Map<String, CompletableFuture<Event>> waiting = new ConcurrentHashMap<>();

	private RemoteChannel(WebSocket socket) {
		this.socket = socket;
	}

	/**
	 * Connects to the TV at {@code host}, pairing on first use: the TV shows "Allow?" and this waits up to
	 * {@link #ALLOW_WAIT} for an answer.
	 *
	 * @param clientName how the TV lists this client under its connected devices
	 * @param events     receives what the TV reports, except the pairing handshake, which carries the token
	 * @throws IOException if the TV is unreachable, refuses (Deny, or a changed certificate), or nobody answers
	 */
	public static RemoteChannel connect(Host host, String clientName, TvStore store, Consumer<Event> events) throws IOException {
		URI endpoint = URI.create("wss://" + host.forUrl() + ":" + PORT + PATH);
		return connect(endpoint, host, clientName, store, events);
	}

	/** As {@link #connect(Host, String, TvStore, Consumer)}, ignoring what the TV reports. */
	public static RemoteChannel connect(Host host, String clientName, TvStore store) throws IOException {
		return connect(host, clientName, store, _ -> {});
	}

	/** At {@code endpoint}, which may be a plain {@code ws://} URL: the 2016 TVs' form, and the tests'. */
	static RemoteChannel connect(URI endpoint, Host host, String clientName, TvStore store, Consumer<Event> events) throws IOException {
		Optional<PairingToken> token = store.token(host);
		URI uri = URI.create(endpoint + "?name=" + URLEncoder.encode(Base64.getEncoder().encodeToString(clientName.getBytes(UTF_8)), UTF_8)
			+ token.map(t -> "&token=" + URLEncoder.encode(t.value(), UTF_8)).orElse(""));
		PinnedTrust trust = new PinnedTrust(store.certificate(host).orElse(null));
		HttpClient client;
		try {
			SSLContext ssl = SSLContext.getInstance("TLS");
			ssl.init(null, new TrustManager[] {trust}, null);
			client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Http.CONNECT_TIMEOUT).build();
		} catch (GeneralSecurityException e) {
			throw new IOException("TLS unavailable: " + e.getMessage(), e);
		}

		CompletableFuture<Event> paired = new CompletableFuture<>();
		Listener listener = new Listener(paired, events);
		WebSocket ws;
		try {
			ws = client.newWebSocketBuilder().connectTimeout(Http.CONNECT_TIMEOUT).buildAsync(uri, listener).get();
		} catch (ExecutionException e) {
			Throwable cause = Objects.requireNonNullElse(e.getCause(), e);
			if (trust.mismatch) {
				throw new IOException("the TV at " + host + " presented a different certificate from the one it paired "
					+ "with; if it's the same TV (e.g. after a reset), run `tv unpair` and pair again", cause);
			}
			throw new IOException("can't reach the TV's remote channel at " + endpoint.getAuthority() + ": " + Http.describe(cause), cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted connecting to the TV's remote channel", e);
		}
		RemoteChannel channel = new RemoteChannel(ws);
		listener.channel = channel;

		Event connected;
		try {
			connected = paired.get(ALLOW_WAIT.toSeconds(), TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			channel.close();
			throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
		} catch (TimeoutException e) {
			channel.close();
			throw new IOException("the TV didn't answer; if it shows an \"Allow\" prompt, choose Allow and try again", e);
		} catch (InterruptedException e) {
			channel.close();
			Thread.currentThread().interrupt();
			throw new IOException("interrupted waiting for the TV to pair", e);
		}
		if (connected.data() instanceof Map<?, ?> d && d.get("token") instanceof Object t && !String.valueOf(t).isBlank()) {
			store.setToken(host, new PairingToken(String.valueOf(t)));
		}
		String seen = trust.seen;
		if (seen != null && trust.expected == null) { store.setCertificate(host, seen); }
		return channel;
	}

	// ---- remote keys ---------------------------------------------------------------------------------

	/** Presses and releases a key. */
	@Override
	public void key(KeyCode code) throws IOException {
		keyCommand("Click", code);
	}

	/** Holds a key down for {@code duration}, e.g. to power off or to scroll fast. */
	@Override
	public void hold(KeyCode code, Duration duration) throws IOException {
		keyCommand("Press", code);
		try {
			Thread.sleep(duration);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			keyCommand("Release", code);
		}
	}

	private void keyCommand(String command, KeyCode code) throws IOException {
		control(Map.of(CMD, command, "DataOfCmd", code.value(), "Option", "false", TYPE_OF_REMOTE, "SendRemoteKey"));
	}

	// ---- text and pointer ----------------------------------------------------------------------------

	/**
	 * Types {@code text} into the TV's on-screen keyboard, replacing what's there, and closes the keyboard.
	 * It only lands while a text field has the keyboard open (the TV sends {@code ms.remote.imeStart}).
	 */
	@Override
	public void text(String text) throws IOException {
		control(Map.of(CMD, Base64.getEncoder().encodeToString(text.getBytes(UTF_8)), "DataOfCmd", "base64",
			TYPE_OF_REMOTE, "SendInputString"));
		control(Map.of(TYPE_OF_REMOTE, "SendInputEnd"));
	}

	/** Moves the browser's pointer by ({@code dx}, {@code dy}) pixels. */
	@Override
	public void move(int dx, int dy) throws IOException {
		Map<String, Object> position = new LinkedHashMap<>();
		position.put("x", dx);
		position.put("y", dy);
		position.put("Time", String.valueOf(System.currentTimeMillis()));
		control(Map.of(CMD, "Move", "Position", position, TYPE_OF_REMOTE, "ProcessMouseDevice"));
	}

	/** Clicks where the pointer is. */
	@Override
	public void click() throws IOException {
		control(Map.of(CMD, "LeftClick", TYPE_OF_REMOTE, "ProcessMouseDevice"));
	}

	// ---- apps ----------------------------------------------------------------------------------------

	/** The apps installed on the TV. (Built-in ones such as the browser aren't listed but launch all the same.) */
	public List<App> apps() throws IOException {
		Event reply = request("ed.installedApp.get", null);
		List<App> apps = new ArrayList<>();
		if (reply.data() instanceof Map<?, ?> d && d.get("data") instanceof List<?> list) {
			for (Object o : list) {
				if (o instanceof Map<?, ?> a && Json.string(a, "appId").filter(RemoteChannel::usable).isPresent()) {
					AppId id = new AppId(String.valueOf(a.get("appId")));
					int type = a.get("app_type") instanceof Number n ? n.intValue() : 0;
					apps.add(new App(id, Json.string(a, "name").orElse(id.value()), type));
				}
			}
		}
		return List.copyOf(apps);
	}

	/** An entry whose id can't be an {@link AppId} couldn't be launched or asked about, so it isn't listed. */
	private static boolean usable(String id) {
		try {
			new AppId(id);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/**
	 * Starts an app.
	 *
	 * @throws IOException if the TV says it couldn't (no such app, or the wrong launch type for it)
	 */
	public void launch(AppId appId, LaunchType type) throws IOException {
		launch(appId, type, Map.of());
	}

	/**
	 * Starts an app, passing {@code metaTag}: a URL or deep link the app understands.
	 *
	 * @throws IOException if the TV says it couldn't (no such app, or the wrong launch type for it)
	 */
	public void launch(AppId appId, LaunchType type, String metaTag) throws IOException {
		launch(appId, type, Map.of("metaTag", metaTag));
	}

	private void launch(AppId appId, LaunchType type, Map<String, String> extra) throws IOException {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("appId", appId.value());
		data.put("action_type", type.name());
		data.putAll(extra);
		Event reply = request("ed.apps.launch", data);
		if (!(reply.data() instanceof Number n && n.intValue() == 200)) {
			throw new IOException("the TV couldn't launch " + appId + " (" + reply.data() + ")"
				+ (type == LaunchType.DEEP_LINK ? "; try --native" : "; try --deep-link"));
		}
	}

	/** Opens {@code url} in the TV's web browser. */
	public void browse(HttpUrl url) throws IOException {
		launch(BROWSER, LaunchType.NATIVE_LAUNCH, url.value());
	}

	@Override
	public void close() {
		try {
			socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (ExecutionException | TimeoutException e) {
			// closing anyway
		}
		socket.abort();
	}

	// ---- plumbing ------------------------------------------------------------------------------------

	private void control(Map<String, Object> params) throws IOException {
		send(Map.of("method", "ms.remote.control", "params", params));
	}

	/** Emits {@code event} to the TV and waits for its reply, which carries the same event name. */
	private Event request(String event, @Nullable Object data) throws IOException {
		CompletableFuture<Event> reply = new CompletableFuture<>();
		if (waiting.putIfAbsent(event, reply) != null) { throw new IllegalStateException("already waiting for " + event); }
		try {
			Map<String, Object> params = new LinkedHashMap<>();
			params.put("event", event);
			params.put("to", "host");
			if (data != null) { params.put("data", data); }
			send(Map.of("method", "ms.channel.emit", "params", params));
			return reply.get(REPLY_WAIT.toSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			throw new IOException("no reply from the TV to " + event, e);
		} catch (ExecutionException e) {
			throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted waiting for the TV's reply to " + event, e);
		} finally {
			waiting.remove(event, reply);
		}
	}

	private void send(Map<String, Object> message) throws IOException {
		try {
			socket.sendText(Json.write(message), true).get(REPLY_WAIT.toSeconds(), TimeUnit.SECONDS);
		} catch (ExecutionException e) {
			Throwable cause = Objects.requireNonNullElse(e.getCause(), e);
			throw new IOException("sending to the TV failed, the remote channel is gone: " + Http.describe(cause), cause);
		} catch (TimeoutException e) {
			throw new IOException("the TV isn't taking messages", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted sending to the TV", e);
		}
	}

	private void closedByTv(String why) {
		IOException gone = new IOException("the TV closed the remote channel" + (why.isBlank() ? "" : ": " + why));
		waiting.values().forEach(f -> f.completeExceptionally(gone));
	}

	/** Assembles messages, completes the pairing handshake, and routes replies and events. */
	private static final class Listener implements WebSocket.Listener {
		private final CompletableFuture<Event> paired;
		private final Consumer<Event> events;
		private final StringBuilder partial = new StringBuilder();
		volatile @Nullable RemoteChannel channel;

		Listener(CompletableFuture<Event> paired, Consumer<Event> events) {
			this.paired = paired;
			this.events = events;
		}

		@Override
		public @Nullable CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				String json = partial.toString();
				partial.setLength(0);
				handle(json);
			}
			ws.request(1);
			return null;
		}

		private void handle(String json) {
			Event e;
			try {
				if (!(Json.parse(json) instanceof Map<?, ?> m) || !(m.get("event") instanceof String name)) { return; }
				e = new Event(name, m.get("data"), json);
			} catch (IllegalArgumentException bad) {
				return; // not JSON; nothing to route
			}
			switch (e.name()) {
				case "ms.channel.connect" -> {
					paired.complete(e);
					return; // it carries the token, which listeners (and `tv events`, which prints them) have no use for
				}
				case "ms.channel.unauthorized" -> paired.completeExceptionally(new IOException(
					"the TV refused this client. If you chose Deny, allow it again on the TV under Settings > General > "
						+ "External Device Manager > Device Connect Manager > Device List"));
				case "ms.channel.timeOut" -> paired.completeExceptionally(
					new IOException("nobody answered the \"Allow\" prompt on the TV in time"));
				default -> {
					RemoteChannel c = channel;
					if (c != null) {
						CompletableFuture<Event> waiter = c.waiting.get(e.name());
						if (waiter != null) { waiter.complete(e); }
					}
				}
			}
			events.accept(e);
		}

		@Override
		public @Nullable CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
			paired.completeExceptionally(new IOException("the TV closed the connection" + (reason.isBlank() ? "" : ": " + reason)));
			RemoteChannel c = channel;
			if (c != null) { c.closedByTv(reason); }
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			paired.completeExceptionally(new IOException(Http.describe(error), error));
			RemoteChannel c = channel;
			if (c != null) { c.closedByTv(Http.describe(error)); }
		}
	}

	/**
	 * Trusts the TV's self-signed certificate the first time (pairing) and only that certificate afterwards.
	 * The TV's certificate names no host, so there's no host name to check; the pin takes its place.
	 */
	static final class PinnedTrust extends X509ExtendedTrustManager {
		final @Nullable String expected;
		volatile @Nullable String seen;
		volatile boolean mismatch;

		PinnedTrust(@Nullable String expected) {
			this.expected = expected;
		}

		static String fingerprint(X509Certificate cert) throws CertificateException {
			try {
				return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
			} catch (GeneralSecurityException e) {
				throw new CertificateException(e);
			}
		}

		private void check(X509Certificate[] chain) throws CertificateException {
			if (chain == null || chain.length == 0) { throw new CertificateException("no certificate"); }
			String fp = fingerprint(chain[0]);
			seen = fp;
			if (expected != null && !expected.equalsIgnoreCase(fp)) {
				mismatch = true;
				throw new CertificateException("certificate changed since pairing");
			}
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			check(chain);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
			check(chain);
		}

		@Override
		public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
			check(chain);
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
			throw new CertificateException("not a server");
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
			throw new CertificateException("not a server");
		}

		@Override
		public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
			throw new CertificateException("not a server");
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}
	}
}
