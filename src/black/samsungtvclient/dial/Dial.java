package black.samsungtvclient.dial;

import black.samsungtvclient.api.DialAppName;
import black.samsungtvclient.api.DialApps;
import black.samsungtvclient.net.Host;
import black.samsungtvclient.net.Http;
import black.samsungtvclient.util.Xml;
import java.io.IOException;
import java.net.URI;
import org.w3c.dom.Document;

/**
 * DIAL, the "cast to an app" protocol phones use for YouTube and Netflix: ask whether an app is running,
 * start it with a payload it understands (YouTube takes {@code v=<video id>}), or stop it. Samsung TVs serve
 * it on port 8080 under {@code /ws/apps/}.
 */
public final class Dial implements DialApps {
	public static final int PORT = 8080;

	private final URI apps;

	public Dial(Host host) {
		this(baseUri(host));
	}

	/** The DIAL service at {@code apps} ({@code .../apps/}); tests point it at a pseudo. */
	public Dial(URI apps) {
		this.apps = apps;
	}

	/** Where a Samsung TV at {@code host} serves DIAL. */
	public static URI baseUri(Host host) {
		return URI.create("http://" + host.forUrl() + ":" + PORT + "/ws/apps/");
	}

	/** @throws IOException if the TV has no such DIAL app */
	@Override
	public Status status(DialAppName app) throws IOException {
		Http.Response r = Http.request(Http.Method.GET, app(app));
		if (r.statusCode() == 404) { throw new IOException("the TV has no DIAL app called " + app + " (names are case-sensitive, e.g. YouTube, Netflix)"); }
		if (!r.ok()) { throw new IOException("DIAL status of " + app + " -> HTTP " + r.statusCode()); }
		return parse(r.body(), app);
	}

	@Override
	public void launch(DialAppName app) throws IOException {
		launch(app, "");
	}

	/** Starts {@code app}, handing it {@code payload}; for YouTube, {@code v=<id>} plays that video. */
	@Override
	public void launch(DialAppName app, String payload) throws IOException {
		Http.Response r = Http.request(Http.Method.POST, app(app), payload, "text/plain; charset=utf-8");
		if (!r.ok()) { throw new IOException("DIAL launch of " + app + " -> HTTP " + r.statusCode()); }
	}

	@Override
	public void stop(DialAppName app) throws IOException {
		Http.Response r = Http.request(Http.Method.DELETE, app(app).resolve(app.value() + "/run"));
		if (!r.ok()) { throw new IOException("DIAL stop of " + app + " -> HTTP " + r.statusCode()); }
	}

	static Status parse(String xml, DialAppName asked) throws IOException {
		try {
			Document doc = Xml.parse(xml);
			String state = Xml.text(doc, "state").orElseThrow(() -> new IOException("DIAL reply about " + asked + " without a <state>"));
			return new Status(Xml.text(doc, "name").orElse(asked.value()), AppState.parse(state));
		} catch (IllegalArgumentException e) {
			throw new IOException("unreadable DIAL reply: " + e.getMessage(), e);
		}
	}

	private URI app(DialAppName app) {
		return apps.resolve(app.value());
	}
}
