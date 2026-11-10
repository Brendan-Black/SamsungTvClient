package black.samsungtvclient.net;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

/** The shared HTTP client, plus a GET helper for device descriptions. */
public final class Http {
	public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
	public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

	/** Descriptions and SOAP replies are a few KB; anything far bigger isn't a TV talking. */
	static final int MAX_RESPONSE_BYTES = 1024 * 1024;

	// HTTP/1.1 only: the default would send an h2c Upgrade header, which embedded TV servers can trip on.
	private static final HttpClient CLIENT = HttpClient.newBuilder()
		.version(HttpClient.Version.HTTP_1_1)
		.connectTimeout(CONNECT_TIMEOUT)
		.build();

	/** The HTTP methods TVs are spoken to with. */
	public enum Method {
		GET, POST, DELETE
	}

	/** A response whose body has been read in full. */
	public record Response(int statusCode, String body) {
		public boolean ok() {
			return statusCode / 100 == 2;
		}
	}

	private Http() {}

	/** Sends a bodiless request; any status is returned, not thrown. */
	public static Response request(Method method, URI uri) throws IOException {
		return send(HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).method(method.name(), HttpRequest.BodyPublishers.noBody()).build());
	}

	/** Sends {@code body} as {@code contentType}; any status is returned, not thrown. */
	public static Response request(Method method, URI uri, String body, String contentType) throws IOException {
		return send(HttpRequest.newBuilder(uri)
			.timeout(REQUEST_TIMEOUT)
			.header("Content-Type", contentType)
			.method(method.name(), HttpRequest.BodyPublishers.ofString(body))
			.build());
	}

	/** GETs {@code uri} and returns the body, failing on any non-2xx status. */
	public static String get(URI uri) throws IOException {
		Response response = request(Method.GET, uri);
		if (!response.ok()) { throw new IOException("GET " + uri + " -> HTTP " + response.statusCode()); }
		return response.body();
	}

	/** Sends {@code request} and reads at most {@link #MAX_RESPONSE_BYTES} of the reply. */
	static Response send(HttpRequest request) throws IOException {
		HttpResponse<InputStream> response;
		try {
			response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted waiting for " + request.uri(), e);
		}
		try (InputStream in = response.body()) {
			byte[] bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
			if (bytes.length > MAX_RESPONSE_BYTES) {
				throw new IOException(request.uri() + ": response larger than " + MAX_RESPONSE_BYTES / 1024 + " KB");
			}
			return new Response(response.statusCode(), new String(bytes, UTF_8));
		}
	}

	/** A one-line, human-readable reason for a network failure (JDK messages are often null). */
	public static String describe(Throwable e) {
		if (e instanceof HttpConnectTimeoutException) { return "connection timed out"; }
		if (e instanceof HttpTimeoutException) { return "no response (timed out)"; }
		if (e instanceof ConnectException) { return "connection refused or host unreachable"; }
		String msg = e.getMessage();
		return msg == null || msg.isBlank() ? e.getClass().getSimpleName() : msg;
	}
}
