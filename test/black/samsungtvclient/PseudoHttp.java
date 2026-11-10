package black.samsungtvclient;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * A strict HTTP server for canned replies, including the bad ones a TV or a web service can give. A request nothing was
 * canned for is an error, reported when it closes; {@link #received()} is what was actually asked for.
 */
public final class PseudoHttp implements AutoCloseable {
	/** One request, as received. */
	public record Received(String method, String path, String query, String body) {}

	private record Canned(int status, byte[] body) {}

	private final Map<String, Canned> canned = new ConcurrentHashMap<>();
	private final List<Received> received = Collections.synchronizedList(new ArrayList<>());
	private final List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
	private final HttpServer server;

	public PseudoHttp() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		server.createContext("/", this::handle);
		server.start();
	}

	/** Answers {@code method path} with {@code status} and {@code body}, replacing any earlier answer. */
	public PseudoHttp on(String method, String path, int status, byte[] body) {
		canned.put(method + " " + path, new Canned(status, body));
		return this;
	}

	public PseudoHttp on(String method, String path, int status, String body) {
		return on(method, path, status, body.getBytes(UTF_8));
	}

	public URI uri(String path) {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
	}

	/** Every request answered, in order. */
	public List<Received> received() {
		return List.copyOf(received);
	}

	@Override
	public void close() {
		server.stop(0);
		if (!unexpected.isEmpty()) { throw new IllegalStateException("requests nothing was canned for: " + unexpected); }
	}

	private void handle(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		String key = ex.getRequestMethod() + " " + path;
		String body = new String(ex.getRequestBody().readAllBytes(), UTF_8);
		Canned reply = canned.get(key);
		if (reply == null) {
			unexpected.add(key);
			ex.sendResponseHeaders(500, -1);
			ex.close();
			return;
		}
		received.add(new Received(ex.getRequestMethod(), path, String.valueOf(ex.getRequestURI().getRawQuery()), body));
		ex.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
		if (reply.body().length > 0) { ex.getResponseBody().write(reply.body()); }
		ex.close();
	}
}
