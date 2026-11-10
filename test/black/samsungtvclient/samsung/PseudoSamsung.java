package black.samsungtvclient.samsung;

import static java.nio.charset.StandardCharsets.UTF_8;

import black.samsungtvclient.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A stand-in Samsung TV, in process: the REST API ({@code /api/v2/}), DIAL ({@code /ws/apps/}), and the
 * remote channel as a plain (non-TLS) WebSocket that pairs like the real one. It records what it receives.
 */
final class PseudoSamsung implements AutoCloseable {
	static final String TOKEN = "61460560";
	static final String YOUTUBE = "111299001912";

	/** Whether a client without a token gets "Allow" (true) or "Deny" (false). */
	volatile boolean allow = true;
	private final List<String> received = Collections.synchronizedList(new ArrayList<>());
	private final List<String> connections = Collections.synchronizedList(new ArrayList<>());
	private final List<String> rest = Collections.synchronizedList(new ArrayList<>());

	private final List<String> unexpected = Collections.synchronizedList(new ArrayList<>());
	private final HttpServer http;
	private final ServerSocket ws;

	PseudoSamsung() throws IOException {
		http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		http.createContext("/api/v2/", this::api);
		http.createContext("/ws/apps/", this::dial);
		http.start();
		ws = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		Thread.ofVirtual().start(this::acceptLoop);
	}

	/** Every remote-channel message received, as JSON text, in order. */
	List<String> received() {
		return List.copyOf(received);
	}

	/** The query string of every remote-channel connection, in order. */
	List<String> connections() {
		return List.copyOf(connections);
	}

	/** Every REST and DIAL request received, in order. */
	List<String> rest() {
		return List.copyOf(rest);
	}

	URI api() {
		return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/api/v2/");
	}

	URI dial() {
		return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/ws/apps/");
	}

	URI remote() {
		return URI.create("ws://127.0.0.1:" + ws.getLocalPort() + "/api/v2/channels/samsung.remote.control");
	}

	@Override
	public void close() throws IOException {
		http.stop(0);
		ws.close();
		if (!unexpected.isEmpty()) { throw new IllegalStateException("no TV would have been sent: " + unexpected); }
	}

	// ---- REST and DIAL -------------------------------------------------------------------------------

	private void api(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		rest.add(ex.getRequestMethod() + " " + path);
		if (path.equals("/api/v2/")) {
			reply(ex, 200, """
				{"device":{"OS":"Tizen","TokenAuthSupport":"true","developerMode":"0","id":"uuid:fake","ip":"127.0.0.1",
				"modelName":"UN75NU8000","model":"18_KANTM2_UHD","name":"[TV] Fake","networkType":"wireless",
				"resolution":"3840x2160","wifiMac":"02:00:00:5F:82:78"},
				"isSupport":"{\\"remote_available\\":\\"true\\",\\"DMP_DRM_WIDEVINE\\":\\"false\\"}","name":"[TV] Fake"}""");
		} else if (path.equals("/api/v2/applications/" + YOUTUBE)) {
			switch (ex.getRequestMethod()) {
				case "GET" -> reply(ex, 200, "{\"id\":\"" + YOUTUBE + "\",\"name\":\"YouTube\",\"running\":true,\"version\":\"2.1\",\"visible\":false}");
				case "POST", "DELETE" -> reply(ex, 200, "true");
				default -> reply(ex, 405, "");
			}
		} else {
			notFound(ex, path);
		}
	}

	private void dial(HttpExchange ex) throws IOException {
		String path = ex.getRequestURI().getPath();
		String body = new String(ex.getRequestBody().readAllBytes(), UTF_8);
		rest.add(ex.getRequestMethod() + " " + path + (body.isEmpty() ? "" : " " + body));
		if (path.equals("/ws/apps/YouTube") && ex.getRequestMethod().equals("GET")) {
			reply(ex, 200, "<?xml version=\"1.0\"?><service xmlns=\"urn:dial-multiscreen-org:schemas:dial\" dialVer=\"1.7\">"
				+ "<name>YouTube</name><options allowStop=\"true\"/><state>stopped</state></service>");
		} else if (path.equals("/ws/apps/YouTube") && ex.getRequestMethod().equals("POST")) {
			reply(ex, 201, "");
		} else if (path.equals("/ws/apps/YouTube/run") && ex.getRequestMethod().equals("DELETE")) {
			reply(ex, 200, "");
		} else {
			notFound(ex, path);
		}
	}

	/** A 404 for an app that doesn't exist, as the TV gives; any other path is a request no TV would understand. */
	private void notFound(HttpExchange ex, String path) throws IOException {
		if (!path.startsWith("/api/v2/applications/") && !path.startsWith("/ws/apps/")) { unexpected.add(path); }
		reply(ex, 404, "");
	}

	private static void reply(HttpExchange ex, int code, String body) throws IOException {
		byte[] bytes = body.getBytes(UTF_8);
		ex.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) { ex.getResponseBody().write(bytes); }
		ex.close();
	}

	// ---- the remote channel (a minimal WebSocket server) ---------------------------------------------

	private void acceptLoop() {
		while (!ws.isClosed()) {
			try {
				Socket s = ws.accept();
				Thread.ofVirtual().start(() -> session(s));
			} catch (IOException e) {
				return;
			}
		}
	}

	private void session(Socket s) {
		try (s; InputStream in = s.getInputStream(); OutputStream out = s.getOutputStream()) {
			String request = headers(in);
			String target = request.lines().findFirst().orElse("").split(" ")[1];
			String query = target.contains("?") ? target.substring(target.indexOf('?') + 1) : "";
			connections.add(URLDecoder.decode(query, UTF_8));
			String key = request.lines().filter(l -> l.toLowerCase().startsWith("sec-websocket-key:"))
				.map(l -> l.substring(l.indexOf(':') + 1).strip()).findFirst().orElseThrow();
			String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
				.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(UTF_8)));
			out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
				+ "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(UTF_8));

			boolean hasToken = query.contains("token=" + TOKEN);
			if (!hasToken && !allow) {
				text(out, "{\"event\":\"ms.channel.unauthorized\"}");
				// Close as a WebSocket server does: a close frame, then read until the client's. Dropping the socket
				// with the client's frames unread makes Windows send a reset, which can discard the message above
				// before the client reads it (seen on CI).
				frame(out, 0x8, true, "");
				while (read(in, out) != null) {} // read() answers the client's close and returns null
				return;
			}
			text(out, "{\"data\":{\"clients\":[],\"id\":\"c1\"" + (hasToken ? "" : ",\"token\":\"" + TOKEN + "\"")
				+ "},\"event\":\"ms.channel.connect\"}");
			text(out, "{\"event\":\"ms.remote.touchEnable\"}");
			while (true) {
				String message = read(in, out);
				if (message == null) { return; }
				received.add(message);
				respond(out, message);
			}
		} catch (Exception e) {
			// the client went away
		}
	}

	private void respond(OutputStream out, String message) throws IOException {
		if (!(Json.parse(message) instanceof Map<?, ?> m) || !"ms.channel.emit".equals(m.get("method"))
			|| !(m.get("params") instanceof Map<?, ?> p)) {
			return;
		}
		switch (String.valueOf(p.get("event"))) {
			case "ed.installedApp.get" -> {
				// Sent in two frames, as a long app list would be.
				String json = "{\"data\":{\"data\":[{\"appId\":\"" + YOUTUBE + "\",\"app_type\":2,\"name\":\"YouTube\"},"
					+ "{\"appId\":\"3201907018807\",\"app_type\":4,\"name\":\"Netflix\"}]},"
					+ "\"event\":\"ed.installedApp.get\",\"from\":\"host\"}";
				int half = json.length() / 2;
				frame(out, 0x01, false, json.substring(0, half));
				frame(out, 0x00, true, json.substring(half));
			}
			case "ed.apps.launch" -> {
				Map<?, ?> d = (Map<?, ?>) p.get("data");
				Object id = d.get("appId");
				boolean known = YOUTUBE.equals(id) || "org.tizen.browser".equals(id);
				text(out, "{\"data\":" + (known ? 200 : 404) + ",\"event\":\"ed.apps.launch\",\"from\":\"host\"}");
			}
			default -> unexpected.add(message);
		}
	}

	private static String headers(InputStream in) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		int c;
		while ((c = in.read()) != -1) {
			b.write(c);
			String s = b.toString(UTF_8);
			if (s.endsWith("\r\n\r\n")) { return s; }
		}
		throw new IOException("connection closed during the handshake");
	}

	/** Reads one message (reassembling fragments); null once the client closes. */
	private static String read(InputStream in, OutputStream out) throws IOException {
		ByteArrayOutputStream message = new ByteArrayOutputStream();
		while (true) {
			int b0 = in.read();
			int b1 = in.read();
			if (b0 < 0 || b1 < 0) { return null; }
			long len = b1 & 0x7F;
			if (len == 126) {
				len = (in.read() << 8) | in.read();
			} else if (len == 127) {
				len = 0;
				for (int i = 0; i < 8; i++) { len = (len << 8) | in.read(); }
			}
			byte[] mask = (b1 & 0x80) != 0 ? in.readNBytes(4) : null;
			byte[] payload = in.readNBytes((int) len);
			if (mask != null) { for (int i = 0; i < payload.length; i++) { payload[i] ^= mask[i % 4]; } }
			int opcode = b0 & 0x0F;
			if (opcode == 0x8) {
				frame(out, 0x8, true, "");
				return null;
			}
			if (opcode == 0x9) {
				out.write(0x8A);
				out.write(payload.length);
				out.write(payload);
				continue;
			}
			message.write(payload);
			if ((b0 & 0x80) != 0) { return message.toString(UTF_8); }
		}
	}

	private static void text(OutputStream out, String s) throws IOException {
		frame(out, 0x1, true, s);
	}

	private static synchronized void frame(OutputStream out, int opcode, boolean fin, String s) throws IOException {
		byte[] p = s.getBytes(UTF_8);
		out.write((fin ? 0x80 : 0) | opcode);
		if (p.length < 126) {
			out.write(p.length);
		} else if (p.length < 65536) {
			out.write(126);
			out.write(p.length >> 8);
			out.write(p.length & 0xFF);
		} else {
			out.write(127);
			for (int i = 7; i >= 0; i--) { out.write((int) ((long) p.length >> (8 * i)) & 0xFF); }
		}
		out.write(p);
		out.flush();
	}

	/** The parameters of the n-th received {@code ms.remote.control} message. */
	Map<?, ?> control(int n) {
		List<Map<?, ?>> controls = new ArrayList<>();
		synchronized (received) {
			for (String r : received) {
				if (Json.parse(r) instanceof Map<?, ?> m && "ms.remote.control".equals(m.get("method"))) { controls.add((Map<?, ?>) m.get("params")); }
			}
		}
		return n < controls.size() ? controls.get(n) : new LinkedHashMap<>();
	}
}
