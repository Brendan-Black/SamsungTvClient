package black.samsungtvclient.samsung;

import black.samsungtvclient.api.AppId;
import black.samsungtvclient.api.AppStatus;
import black.samsungtvclient.api.TvInfo;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.Http;
import black.samsungtvclient.util.Json;
import black.samsungtvclient.util.Nullable;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * Samsung's REST API on port 8001: device information, and the status, launch and close of one app by its id.
 * It needs no pairing; the app list itself only comes over {@link RemoteChannel}.
 */
public final class SmartApi {
	public static final int PORT = 8001;

	private final URI base;

	public SmartApi(Host host) {
		this(baseUri(host, PORT));
	}

	/** The API at {@code base} (ending in {@code /api/v2/}); tests point it at a pseudo. */
	public SmartApi(URI base) {
		this.base = base;
	}

	/** Where the API is on the TV at {@code host}, if it answers on {@code port}. */
	public static URI baseUri(Host host, int port) {
		return URI.create("http://" + host.forUrl() + ":" + port + "/api/v2/");
	}

	public TvInfo info() throws IOException {
		try {
			return TvInfo.parse(Http.get(base));
		} catch (IllegalArgumentException e) {
			throw new IOException(base + ": " + e.getMessage(), e);
		}
	}

	/** The app's state, or empty if the TV has no app with this id. */
	public Optional<AppStatus> appStatus(AppId appId) throws IOException {
		Http.Response r = Http.request(Http.Method.GET, app(appId));
		if (r.statusCode() == 404) { return Optional.empty(); }
		check(r, "status of " + appId);
		if (!(parse(r.body()) instanceof Map<?, ?> m)) { throw new IOException("unexpected reply for app " + appId + ": " + r.body()); }
		return Optional.of(new AppStatus(appId, Json.string(m, "name").orElse(appId.value()), Boolean.TRUE.equals(m.get("running")),
			Boolean.TRUE.equals(m.get("visible")), Json.string(m, "version").orElse(null)));
	}

	/** Starts the app (or brings it to the front). */
	public void launch(AppId appId) throws IOException {
		check(Http.request(Http.Method.POST, app(appId)), "launch " + appId);
	}

	/** Closes the app. */
	public void close(AppId appId) throws IOException {
		check(Http.request(Http.Method.DELETE, app(appId)), "close " + appId);
	}

	private URI app(AppId appId) {
		return base.resolve("applications/" + appId.value());
	}

	private static void check(Http.Response r, String what) throws IOException {
		if (r.ok()) { return; }
		String hint = switch (r.statusCode()) {
			case 404 -> " (no such app on this TV; `tv apps` lists them)";
			case 401, 403 -> " (the TV only allows this over the paired connection)";
			default -> "";
		};
		throw new IOException(what + " -> HTTP " + r.statusCode() + hint);
	}

	private static @Nullable Object parse(String body) throws IOException {
		try {
			return Json.parse(body);
		} catch (IllegalArgumentException e) {
			throw new IOException("unreadable reply: " + e.getMessage(), e);
		}
	}
}
